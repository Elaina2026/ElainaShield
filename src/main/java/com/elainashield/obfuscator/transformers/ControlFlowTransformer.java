package com.elainashield.obfuscator.transformers;

import com.elainashield.obfuscator.core.ObfuscationConfig;
import com.elainashield.obfuscator.core.ObfuscationContext;
import com.elainashield.obfuscator.utils.NameGenerator;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.*;

/**
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║         Control Flow Flattening Transformer                      ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║ Destroys the original control flow graph by splitting methods    ║
 * ║ into basic blocks and re-dispatching them through a giant        ║
 * ║ switch-case dispatcher with randomized state variables.          ║
 * ║                                                                  ║
 * ║ Original:                                                        ║
 * ║   if (x > 0) { doA(); } else { doB(); }                         ║
 * ║   doC();                                                         ║
 * ║                                                                  ║
 * ║ Flattened:                                                       ║
 * ║   int state = RANDOM_START;                                      ║
 * ║   while (true) {                                                 ║
 * ║     switch (state) {                                             ║
 * ║       case 0x7A3F: if (x > 0) state=0xB1C2; else state=0xD4E5;  ║
 * ║                    break;                                        ║
 * ║       case 0xB1C2: doA(); state=0xF6A7; break;                  ║
 * ║       case 0xD4E5: doB(); state=0xF6A7; break;                  ║
 * ║       case 0xF6A7: doC(); state=0x0000; break;                  ║
 * ║       case 0x0000: return;                                       ║
 * ║       default: // bogus dead code                                ║
 * ║     }                                                            ║
 * ║   }                                                              ║
 * ╚══════════════════════════════════════════════════════════════════╝
 */
public class ControlFlowTransformer {

    private final ObfuscationConfig config;
    private final ObfuscationContext context;
    private final Random random;

    /** Maximum method size (in instructions) we'll attempt to flatten */
    private static final int MAX_METHOD_SIZE = 300;

    /** Minimum method size worth flattening */
    private static final int MIN_METHOD_SIZE = 8;

    /** Maximum instructions per basic block */
    @SuppressWarnings("unused")
    private static final int MAX_BLOCK_SIZE = 15;

    public ControlFlowTransformer(ObfuscationConfig config, ObfuscationContext context, NameGenerator nameGen) {
        this.config = config;
        this.context = context;
        this.random = nameGen.getRandom();
    }

    /**
     * Apply control flow flattening to all eligible methods in all classes.
     */
    public void transform(List<ClassNode> classes) {
        System.out.println("  [ControlFlow] Flattening control flow...");

        int totalFlattened = 0;
        for (ClassNode cn : classes) {
            for (MethodNode mn : cn.methods) {
                Frame<?>[] frames = analyzeMethod(cn, mn);
                if (frames != null && shouldFlatten(mn)) {
                    try {
                        flattenMethod(cn, mn, frames);
                        totalFlattened++;
                        context.incrementMethodsFlattened();
                    } catch (Exception e) {
                        // If flattening fails, skip this method silently
                        System.out.println("    [WARN] Skipped flattening: " +
                                cn.name + "." + mn.name + " (" + e.getMessage() + ")");
                    }
                }
            }
        }

        System.out.println("  [ControlFlow] Flattened " + totalFlattened + " methods");
    }

    private Frame<?>[] analyzeMethod(ClassNode cn, MethodNode mn) {
        try {
            Analyzer<?> analyzer = new Analyzer<>(new BasicVerifier());
            return analyzer.analyze(cn.name, mn);
        } catch (AnalyzerException e) {
            return null; // Analysis failed, do not flatten
        }
    }

    private boolean shouldFlatten(MethodNode mn) {
        // Skip abstract/native methods
        if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;

        // Skip constructors and static initializers
        if (mn.name.startsWith("<")) return false;

        // Skip too small methods
        if (mn.instructions.size() < MIN_METHOD_SIZE) return false;

        // Skip too large methods (might exceed bytecode limits)
        if (mn.instructions.size() > MAX_METHOD_SIZE) return false;

        // Skip methods with try-catch blocks (complex to flatten correctly)
        if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) return false;

        // ──────────────────────────────────────────────────────────────
        // [FIX #3] Skip methods containing TABLESWITCH/LOOKUPSWITCH
        // from the original code. These instructions contain complex
        // label arrays that cannot be safely cloned or converted to
        // state transitions without a full data-flow analysis.
        // ──────────────────────────────────────────────────────────────
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH) {
                return false;
            }
        }

        if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) {
            return false; // Skip methods with try-catch blocks to prevent handler stack issues
        }

        // Skip methods with JSR/RET (deprecated but might exist in old bytecode)
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.JSR || insn.getOpcode() == Opcodes.RET) {
                return false;
            }
        }

        // In aggressive mode, also flatten smaller methods
        if (config.isAggressiveMode() && mn.instructions.size() >= MIN_METHOD_SIZE / 2) return true;

        return true;
    }

    /**
     * Flatten a single method's control flow.
     */
    private void flattenMethod(ClassNode owner, MethodNode method, Frame<?>[] frames) {
        // ──────────────────────────────────────────────────────────────
        // [FIX #1] Build a complete label map BEFORE splitting.
        // This map translates every LabelNode in the original method
        // to a fresh LabelNode, so that clone() never returns null.
        // ──────────────────────────────────────────────────────────────
        Map<LabelNode, LabelNode> labelMap = buildLabelMap(method);

        // Step 1: Split into basic blocks safely using Frame data
        List<BasicBlock> blocks = splitIntoBlocks(method, frames);

        if (blocks.isEmpty() || blocks.size() < 2) return;

        // Step 2: (Deleted, no state ID needed)

        // --- Shuffle blocks to destroy structural layout ---
        // We must remember the original layout to fix fallthroughs
        List<BasicBlock> originalBlocks = new ArrayList<>(blocks);
        Collections.shuffle(blocks, random);

        // Step 3: Shuffle blocks to destroy order
        List<BasicBlock> shuffled = new ArrayList<>(blocks);
        Collections.shuffle(shuffled, random);

        // Step 4: Rebuild the method with GOTO spaghetti
        rebuildMethodWithSpaghetti(method, originalBlocks, shuffled, labelMap);
    }

    /**
     * [FIX #1] Build a mapping from every LabelNode in the original method
     * to a fresh LabelNode. This ensures that insn.clone(labelMap) never
     * produces null label references.
     */
    private Map<LabelNode, LabelNode> buildLabelMap(MethodNode method) {
        Map<LabelNode, LabelNode> map = new HashMap<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getType() == AbstractInsnNode.LABEL) {
                LabelNode original = (LabelNode) insn;
                map.put(original, new LabelNode());
            }
        }
        return map;
    }

    /**
     * Split method instructions into basic blocks safely.
     * A basic block is split ONLY when the stack is empty to avoid
     * incompatible stack heights in the central dispatcher loop.
     */
    private List<BasicBlock> splitIntoBlocks(MethodNode method, Frame<?>[] frames) {
        List<BasicBlock> blocks = new ArrayList<>();
        BasicBlock currentBlock = new BasicBlock();
        blocks.add(currentBlock);

        for (int insnIndex = 0; insnIndex < method.instructions.size(); insnIndex++) {
            AbstractInsnNode insn = method.instructions.get(insnIndex);
            
            // ──────────────────────────────────────────────────────────
            // DEAD CODE STRIPPING: If frame is null, it's unreachable!
            // We just skip it entirely to prevent ASM crashes later.
            // ──────────────────────────────────────────────────────────
            Frame<?> currentFrame = frames[insnIndex];
            if (currentFrame == null) {
                continue;
            }

            boolean isStackEmpty = (currentFrame.getStackSize() == 0);

            // Labels start new blocks IF stack is empty
            if (insn.getType() == AbstractInsnNode.LABEL) {
                if (!currentBlock.instructions.isEmpty() && isStackEmpty) {
                    currentBlock = new BasicBlock();
                    blocks.add(currentBlock);
                }
                currentBlock.instructions.add(insn);
                continue;
            }

            currentBlock.instructions.add(insn);

            // End block at jumps, returns, throws
            boolean isBlockEnd = (insn.getType() != AbstractInsnNode.LABEL && insn.getType() != AbstractInsnNode.LINE && insn.getType() != AbstractInsnNode.FRAME);
            switch (insn.getOpcode()) {
                case Opcodes.GOTO:
                case Opcodes.IF_ICMPEQ:
                case Opcodes.IF_ICMPNE:
                case Opcodes.IF_ICMPLT:
                case Opcodes.IF_ICMPGE:
                case Opcodes.IF_ICMPGT:
                case Opcodes.IF_ICMPLE:
                case Opcodes.IFEQ:
                case Opcodes.IFNE:
                case Opcodes.IFLT:
                case Opcodes.IFGE:
                case Opcodes.IFGT:
                case Opcodes.IFLE:
                case Opcodes.IFNULL:
                case Opcodes.IFNONNULL:
                case Opcodes.IF_ACMPEQ:
                case Opcodes.IF_ACMPNE:
                case Opcodes.IRETURN:
                case Opcodes.LRETURN:
                case Opcodes.FRETURN:
                case Opcodes.DRETURN:
                case Opcodes.ARETURN:
                case Opcodes.RETURN:
                case Opcodes.ATHROW:
                    isBlockEnd = true;
                    break;
                case Opcodes.TABLESWITCH:
                case Opcodes.LOOKUPSWITCH:
                    isBlockEnd = true;
                    break;
            }

            // Find the NEXT reachable frame
            Frame<?> nextFrame = null;
            for (int j = insnIndex + 1; j < frames.length; j++) {
                if (frames[j] != null) {
                    nextFrame = frames[j];
                    break;
                }
            }
            
            boolean isNextStackEmpty = (nextFrame != null && nextFrame.getStackSize() == 0);
            
            // Terminal instructions never fall through, so we MUST split after them
            boolean isTerminal = (insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN)
                    || insn.getOpcode() == Opcodes.ATHROW
                    || insn.getOpcode() == Opcodes.GOTO
                    || insn.getOpcode() == Opcodes.TABLESWITCH
                    || insn.getOpcode() == Opcodes.LOOKUPSWITCH;

            // Split block if this is a block end AND (next stack is empty OR instruction is terminal)
            if (isBlockEnd && (isNextStackEmpty || isTerminal || nextFrame == null)) {
                currentBlock = new BasicBlock();
                blocks.add(currentBlock);
            }
        }

        // Remove empty blocks
        blocks.removeIf(b -> b.instructions.isEmpty());

        return blocks;
    }

    /**
     * Rebuild the method by shuffling blocks and connecting them with GOTO.
     * This avoids a central dispatcher which merges unrelated local variable types
     * and causes VerifyErrors.
     */
    private void rebuildMethodWithSpaghetti(MethodNode method,
                                            List<BasicBlock> originalBlocks,
                                            List<BasicBlock> blocks,
                                            Map<LabelNode, LabelNode> labelMap) {
        InsnList newInsns = new InsnList();

        // Assign a unique label to the start of each block
        for (BasicBlock block : blocks) {
            block.label = new LabelNode();
        }

        // --- Start method by jumping to the first block ---
        newInsns.add(new JumpInsnNode(Opcodes.GOTO, originalBlocks.get(0).label));

        // --- Emit blocks in shuffled order ---
        for (int i = 0; i < blocks.size(); i++) {
            BasicBlock block = blocks.get(i);
            newInsns.add(block.label);

            boolean hasTerminator = false;

            if (!block.instructions.isEmpty()) {
                AbstractInsnNode lastInsn = block.instructions.get(block.instructions.size() - 1);
                int opcode = lastInsn.getOpcode();

                // Check if block naturally terminates
                if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) ||
                    opcode == Opcodes.ATHROW ||
                    opcode == Opcodes.GOTO ||
                    opcode == Opcodes.TABLESWITCH ||
                    opcode == Opcodes.LOOKUPSWITCH) {
                    
                    for (AbstractInsnNode insn : block.instructions) {
                        newInsns.add(safeClone(insn, labelMap));
                    }
                    hasTerminator = true;
                } else {
                    // It falls through (either after conditional jump or normal instructions)
                    for (AbstractInsnNode insn : block.instructions) {
                        newInsns.add(safeClone(insn, labelMap));
                    }
                    
                    int origIndex = originalBlocks.indexOf(block);
                    BasicBlock nextBlock = (origIndex >= 0 && origIndex + 1 < originalBlocks.size()) ? originalBlocks.get(origIndex + 1) : null;
                    
                    if (nextBlock != null) {
                        newInsns.add(new JumpInsnNode(Opcodes.GOTO, nextBlock.label));
                    } else {
                        // Should not happen for valid bytecode, but just in case it falls off the end
                        newInsns.add(new InsnNode(Opcodes.ACONST_NULL));
                        newInsns.add(new InsnNode(Opcodes.ATHROW));
                    }
                    hasTerminator = true;
                }
            }
            
            // If block was completely empty, we still need to connect it
            if (!hasTerminator) {
                int origIndex = originalBlocks.indexOf(block);
                BasicBlock nextBlock = (origIndex >= 0 && origIndex + 1 < originalBlocks.size()) ? originalBlocks.get(origIndex + 1) : null;
                if (nextBlock != null) {
                    newInsns.add(new JumpInsnNode(Opcodes.GOTO, nextBlock.label));
                } else {
                    newInsns.add(new InsnNode(Opcodes.ACONST_NULL));
                    newInsns.add(new InsnNode(Opcodes.ATHROW));
                }
            }
        }

        // Replace method instructions
        method.instructions.clear();
        method.instructions.add(newInsns);

        // Clear old try-catch blocks and local variables (they reference old labels)
        if (method.tryCatchBlocks != null) method.tryCatchBlocks.clear();
        if (method.localVariables != null) method.localVariables.clear();

        // Increase max stack to be safe
        method.maxStack = Math.max(method.maxStack + 4, 8);
    }

    // ══════════════════════════════════════════════════════════════════
    // [FIX #1] Safe clone with complete label map
    // ══════════════════════════════════════════════════════════════════

    /**
     * Safely clone an instruction with a proper label map.
     *
     * <p>The critical fix: ASM's {@code AbstractInsnNode.clone(Map)} calls
     * {@code map.get(label)} for every label reference. If the map doesn't
     * contain that label, it returns {@code null}, which later causes an NPE
     * when the ClassWriter tries to resolve the label offset.</p>
     *
     * <p>This method uses a {@link SafeLabelMap} that auto-creates new
     * LabelNodes for any unknown labels, guaranteeing non-null results.</p>
     *
     * @param insn     the instruction to clone
     * @param baseMap  the pre-built label map from the original method
     * @return a cloned instruction with all labels safely resolved
     */
    private AbstractInsnNode safeClone(AbstractInsnNode insn, Map<LabelNode, LabelNode> baseMap) {
        return insn.clone(new SafeLabelMap(baseMap));
    }

    /**
     * A Map wrapper that auto-creates new LabelNodes for any key not found
     * in the underlying map. This prevents null returns from clone().
     *
     * <p>This is critical because instructions may reference labels that
     * were stripped during block splitting (e.g., labels in the middle of
     * a basic block that we didn't track).</p>
     */
    private static class SafeLabelMap extends AbstractMap<LabelNode, LabelNode> {
        private final Map<LabelNode, LabelNode> delegate;

        SafeLabelMap(Map<LabelNode, LabelNode> delegate) {
            this.delegate = new HashMap<>(delegate);
        }

        @Override
        public LabelNode get(Object key) {
            if (key instanceof LabelNode) {
                // computeIfAbsent: create a fresh LabelNode if missing
                return delegate.computeIfAbsent((LabelNode) key, k -> new LabelNode());
            }
            return null;
        }

        @Override
        public boolean containsKey(Object key) {
            return true; // Always claim we have the key
        }

        @Override
        public Set<Entry<LabelNode, LabelNode>> entrySet() {
            return delegate.entrySet();
        }
    }

    // ------------------------------------------------------------------
    // BasicBlock inner class
    // ------------------------------------------------------------------

    private static class BasicBlock {
        List<AbstractInsnNode> instructions = new ArrayList<>();
        LabelNode label;
    }
}
