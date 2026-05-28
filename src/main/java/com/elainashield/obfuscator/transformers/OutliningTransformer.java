package com.elainashield.obfuscator.transformers;

import com.elainashield.obfuscator.core.ObfuscationConfig;
import com.elainashield.obfuscator.core.ObfuscationContext;
import com.elainashield.obfuscator.utils.NameGenerator;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.*;

public class OutliningTransformer {

    @SuppressWarnings("unused")
    private final ObfuscationConfig config;
    @SuppressWarnings("unused")
    private final ObfuscationContext context;
    private final NameGenerator nameGen;

    public OutliningTransformer(ObfuscationConfig config, ObfuscationContext context, NameGenerator nameGen) {
        this.config = config;
        this.context = context;
        this.nameGen = nameGen;
    }

    public void transform(List<ClassNode> classes) {
        System.out.println("  [Outlining] Applying Safe Outlining Obfuscation...");
        int totalOutlined = 0;

        for (ClassNode cn : classes) {
            if ((cn.access & Opcodes.ACC_INTERFACE) != 0 || (cn.access & Opcodes.ACC_ANNOTATION) != 0) {
                continue;
            }

            List<MethodNode> newMethods = new ArrayList<>();

            for (MethodNode mn : cn.methods) {
                if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) continue;
                if ((mn.access & Opcodes.ACC_ABSTRACT) != 0 || (mn.access & Opcodes.ACC_NATIVE) != 0) continue;

                boolean modified = true;
                while (modified) {
                    modified = false;
                    
                    if (mn.instructions.size() < 10) break;

                    Frame<BasicValue>[] frames;
                    try {
                        Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
                        frames = analyzer.analyze(cn.name, mn);
                    } catch (AnalyzerException e) {
                        break;
                    }

                    AbstractInsnNode[] insns = mn.instructions.toArray();
                    Map<LabelNode, Integer> labelIndices = new HashMap<>();
                    Map<AbstractInsnNode, Integer> insnIndices = new HashMap<>();
                    for (int i = 0; i < insns.length; i++) {
                        insnIndices.put(insns[i], i);
                        if (insns[i] instanceof LabelNode) {
                            labelIndices.put((LabelNode) insns[i], i);
                        }
                    }

                    Set<LabelNode> jumpTargets = collectJumpTargets(mn);

                    int i = 0;
                    while (i < insns.length) {
                        if (frames[i] == null || frames[i].getStackSize() != 0) {
                            i++;
                            continue;
                        }

                        int blockStart = i;
                        int blockEnd = -1;

                        for (int j = i; j < insns.length; j++) {
                            AbstractInsnNode insn = insns[j];
                            if (isForbidden(insn, jumpTargets)) break;

                            if (j > i && j < insns.length && frames[j] != null && frames[j].getStackSize() == 0) {
                                blockEnd = j;
                            }
                        }

                        if (blockEnd != -1) {
                            int actualEnd = blockEnd - 1;
                            List<AbstractInsnNode> toExtract = new ArrayList<>();
                            for (int k = blockStart; k <= actualEnd; k++) {
                                AbstractInsnNode insn = insns[k];
                                if (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode) {
                                    continue;
                                }
                                toExtract.add(insn);
                            }

                            if (toExtract.size() >= 4 && canOutline(cn, mn, toExtract, insnIndices, labelIndices)) {
                                MethodNode syntheticMethod = outlineBlock(cn, mn, toExtract, insnIndices, labelIndices);
                                if (syntheticMethod != null) {
                                    newMethods.add(syntheticMethod);
                                    totalOutlined++;
                                    modified = true;
                                    break;
                                }
                            }
                        }
                        i++;
                    }
                }
            }
            cn.methods.addAll(newMethods);
        }
        System.out.println("  [Outlining] Successfully extracted " + totalOutlined + " methods.");
    }

    private Set<LabelNode> collectJumpTargets(MethodNode mn) {
        Set<LabelNode> targets = new HashSet<>();
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof JumpInsnNode) {
                targets.add(((JumpInsnNode) insn).label);
            } else if (insn instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode tsin = (TableSwitchInsnNode) insn;
                targets.add(tsin.dflt);
                targets.addAll(tsin.labels);
            } else if (insn instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode lsin = (LookupSwitchInsnNode) insn;
                targets.add(lsin.dflt);
                targets.addAll(lsin.labels);
            }
        }
        if (mn.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                targets.add(tcb.start);
                targets.add(tcb.end);
                targets.add(tcb.handler);
            }
        }
        if (mn.localVariables != null) {
            for (LocalVariableNode lvn : mn.localVariables) {
                targets.add(lvn.start);
                targets.add(lvn.end);
            }
        }
        return targets;
    }

    private boolean isForbidden(AbstractInsnNode insn, Set<LabelNode> jumpTargets) {
        if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || 
            insn instanceof LookupSwitchInsnNode) {
            return true;
        }
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) return true;
        if (opcode == Opcodes.ATHROW || opcode == Opcodes.RET) return true;
        
        if (insn instanceof LabelNode && jumpTargets.contains((LabelNode) insn)) {
            return true;
        }
        
        if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) return true;
        if (opcode == Opcodes.IINC) return true;
        
        return false;
    }

    private boolean canOutline(ClassNode cn, MethodNode mn, List<AbstractInsnNode> toExtract, Map<AbstractInsnNode, Integer> insnIndices, Map<LabelNode, Integer> labelIndices) {
        for (AbstractInsnNode insn : toExtract) {
            if (insn.getOpcode() == Opcodes.ALOAD) {
                int insnIndex = insnIndices.get(insn);
                String desc = getLocalDesc(cn, mn, (VarInsnNode) insn, insnIndex, labelIndices);
                if (desc == null) return false;
            }
        }
        return true;
    }

    private String getLocalDesc(ClassNode cn, MethodNode mn, VarInsnNode vin, int insnIndex, Map<LabelNode, Integer> labelIndices) {
        int opcode = vin.getOpcode();
        if (opcode == Opcodes.ILOAD) return "I";
        if (opcode == Opcodes.LLOAD) return "J";
        if (opcode == Opcodes.FLOAD) return "F";
        if (opcode == Opcodes.DLOAD) return "D";
        
        if (vin.var == 0 && (mn.access & Opcodes.ACC_STATIC) == 0) {
            return "L" + cn.name + ";";
        }
        
        if (mn.localVariables != null) {
            for (LocalVariableNode lvn : mn.localVariables) {
                if (lvn.index == vin.var) {
                    int startIdx = labelIndices.getOrDefault(lvn.start, -1);
                    int endIdx = labelIndices.getOrDefault(lvn.end, -1);
                    if (startIdx != -1 && endIdx != -1) {
                        if (insnIndex >= startIdx && insnIndex < endIdx) {
                            return lvn.desc;
                        }
                    }
                }
            }
            LocalVariableNode match = null;
            int count = 0;
            for (LocalVariableNode lvn : mn.localVariables) {
                if (lvn.index == vin.var) {
                    match = lvn;
                    count++;
                }
            }
            if (count == 1) return match.desc;
        }
        return null;
    }

    private MethodNode outlineBlock(ClassNode cn, MethodNode mn, List<AbstractInsnNode> toExtract, Map<AbstractInsnNode, Integer> insnIndices, Map<LabelNode, Integer> labelIndices) {
        Map<Integer, String> readVars = new TreeMap<>();
        
        for (AbstractInsnNode insn : toExtract) {
            if (insn instanceof VarInsnNode) {
                VarInsnNode vin = (VarInsnNode) insn;
                if (!readVars.containsKey(vin.var)) {
                    String desc = getLocalDesc(cn, mn, vin, insnIndices.get(vin), labelIndices);
                    if (desc == null) return null;
                    readVars.put(vin.var, desc);
                }
            }
        }
        
        StringBuilder descBuilder = new StringBuilder("(");
        Map<Integer, Integer> varMap = new HashMap<>(); 
        int newVarIndex = 0;
        
        for (Map.Entry<Integer, String> entry : readVars.entrySet()) {
            int oldVar = entry.getKey();
            String typeDesc = entry.getValue();
            
            descBuilder.append(typeDesc);
            varMap.put(oldVar, newVarIndex);
            
            if (typeDesc.equals("J") || typeDesc.equals("D")) {
                newVarIndex += 2;
            } else {
                newVarIndex += 1;
            }
        }
        descBuilder.append(")V");
        
        String bsmName = nameGen.nextMethodName();
        MethodNode syntheticMethod = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                bsmName,
                descBuilder.toString(),
                null,
                new String[] { "java/lang/Throwable" }
        );
        
        Map<LabelNode, LabelNode> labelCloneMap = new HashMap<>();
        InsnList newInsns = new InsnList();
        for (AbstractInsnNode insn : toExtract) {
            AbstractInsnNode cloned = insn.clone(labelCloneMap);
            if (cloned instanceof VarInsnNode) {
                VarInsnNode vin = (VarInsnNode) cloned;
                vin.var = varMap.get(vin.var);
            }
            newInsns.add(cloned);
        }
        newInsns.add(new InsnNode(Opcodes.RETURN));
        syntheticMethod.instructions = newInsns;
        syntheticMethod.maxLocals = newVarIndex;
        syntheticMethod.maxStack = mn.maxStack;
        
        InsnList callInsns = new InsnList();
        for (Map.Entry<Integer, String> entry : readVars.entrySet()) {
            int oldVar = entry.getKey();
            String typeDesc = entry.getValue();
            int opcode;
            switch (typeDesc.charAt(0)) {
                case 'I': case 'Z': case 'B': case 'C': case 'S': opcode = Opcodes.ILOAD; break;
                case 'J': opcode = Opcodes.LLOAD; break;
                case 'F': opcode = Opcodes.FLOAD; break;
                case 'D': opcode = Opcodes.DLOAD; break;
                default: opcode = Opcodes.ALOAD; break;
            }
            callInsns.add(new VarInsnNode(opcode, oldVar));
        }
        callInsns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, bsmName, descBuilder.toString(), false));
        
        AbstractInsnNode insertPoint = toExtract.get(0);
        mn.instructions.insertBefore(insertPoint, callInsns);
        for (AbstractInsnNode insn : toExtract) {
            mn.instructions.remove(insn);
        }
        
        return syntheticMethod;
    }
}
