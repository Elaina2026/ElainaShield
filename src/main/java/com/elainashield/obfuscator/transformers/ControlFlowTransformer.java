package com.elainashield.obfuscator.transformers;

import com.elainashield.obfuscator.core.ObfuscationConfig;
import com.elainashield.obfuscator.core.ObfuscationContext;
import com.elainashield.obfuscator.utils.NameGenerator;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.*;

/**
 * True Control Flow Flattening -- State-Machine Switch Dispatcher.
 *
 * &lt;p&gt;Destroys the original control flow graph by splitting methods into
 * basic blocks and re-dispatching them through a giant LOOKUPSWITCH
 * inside an infinite {@code while(true)} loop with randomised state IDs.&lt;/p&gt;
 *
 * &lt;p&gt;Supports methods with try-catch blocks via handler trampolines and
 * localised exception-scope reconstruction.&lt;/p&gt;
 *
 * &lt;pre&gt;
 * Original:
 *   try {
 *     if (x &gt; 0) { doA(); } else { doB(); }
 *   } catch (Exception e) { handle(e); }
 *   doC();
 *
 * Flattened:
 *   int state = 0x7A3F;
 *   while (true) {
 *     switch (state) {
 *       case 0x7A3F: if(x&gt;0) state=0xB1C2; else state=0xD4E5; break;
 *       case 0xB1C2: doA();  state=0xF6A7; break;
 *       case 0xD4E5: doB();  state=0xF6A7; break;
 *       case 0xF6A7: doC();  return;
 *       case 0xA91B: state=0x7A3F; break; // bogus dead case
 *       default: throw null;
 *     }
 *   }
 *   // Handler trampolines live outside the switch
 * &lt;/pre&gt;
 */
public class ControlFlowTransformer {

    private final ObfuscationConfig config;
    private final ObfuscationContext context;
    private final Random random;

    /** Maximum method size we will attempt to flatten (in instructions). */
    private static final int MAX_METHOD_SIZE_NORMAL     = 300;
    private static final int MAX_METHOD_SIZE_AGGRESSIVE = 500;

    /** Minimum method size worth flattening. */
    private static final int MIN_METHOD_SIZE = 8;

    public ControlFlowTransformer(ObfuscationConfig config,
                                  ObfuscationContext context,
                                  NameGenerator nameGen) {
        this.config  = config;
        this.context = context;
        this.random  = nameGen.getRandom();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════════════

    /**
     * Apply true control-flow flattening to all eligible methods.
     */
    public void transform(List<ClassNode> classes) {
        System.out.println("  [ControlFlow] Flattening control flow (State-Machine Dispatcher)...");
        int totalFlattened = 0;

        for (ClassNode cn : classes) {
            for (MethodNode mn : cn.methods) {
                if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) {
                    continue; // Skip toàn bộ hàm này, không làm phẳng để bảo toàn Frame
                }
                
                if (!shouldFlatten(mn)) continue;

                Frame<?>[] frames = analyzeMethod(cn, mn);
                if (frames == null) continue;

                try {
                    if (flattenMethod(mn, frames)) {
                        totalFlattened++;
                        context.incrementMethodsFlattened();
                    }
                } catch (Exception e) {
                    // Flattening failed for this method — skip silently
                    System.out.println("    [WARN] Skipped: " + cn.name + "." +
                            mn.name + " (" + e.getClass().getSimpleName() + ": " +
                            e.getMessage() + ")");
                }
            }
        }

        System.out.println("  [ControlFlow] Flattened " + totalFlattened +
                " methods with switch dispatcher");
    }

    // ══════════════════════════════════════════════════════════════════
    //  Method eligibility
    // ══════════════════════════════════════════════════════════════════

    private boolean shouldFlatten(MethodNode mn) {
        // Skip abstract / native
        if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;

        // Skip constructors and static initialisers
        if (mn.name.startsWith("<")) return false;

        int size = mn.instructions.size();
        if (size < MIN_METHOD_SIZE) return false;

        int maxSize = config.isAggressiveMode()
                ? MAX_METHOD_SIZE_AGGRESSIVE
                : MAX_METHOD_SIZE_NORMAL;
        if (size > maxSize) return false;

        // Skip methods containing TABLESWITCH / LOOKUPSWITCH / JSR / RET
        for (AbstractInsnNode insn = mn.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op == Opcodes.TABLESWITCH || op == Opcodes.LOOKUPSWITCH ||
                op == Opcodes.JSR         || op == Opcodes.RET) {
                return false;
            }
        }

        // Skip methods with try-catch blocks to prevent ASM Frame verification issues
        if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) {
            return false;
        }

        return true;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Frame analysis
    // ══════════════════════════════════════════════════════════════════

    private Frame<?>[] analyzeMethod(ClassNode cn, MethodNode mn) {
        try {
            return new Analyzer<>(new BasicVerifier()).analyze(cn.name, mn);
        } catch (AnalyzerException e) {
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Main flattening orchestration
    // ══════════════════════════════════════════════════════════════════

    /**
     * @return {@code true} if the method was successfully flattened.
     */
    private boolean flattenMethod(MethodNode method, Frame<?>[] frames) {
        // Fast-Fail: Bỏ qua CFF cho các hàm có try-catch để tránh ASM Empty Try-Catch Bug
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) {
            return false;
        }

        // ── 0. Strict Stack Frame Verification ───────────────────────────
        // We cannot flatten methods if any basic block boundary has a non-empty stack!
        for (int i = 0; i < method.instructions.size(); i++) {
            AbstractInsnNode insn = method.instructions.get(i);
            Frame<?> frame = (i < frames.length) ? frames[i] : null;

            if (frame != null && frame.getStackSize() > 0) {
                if (insn instanceof LabelNode) {
                    // A jump target with non-empty stack
                    return false;
                }
                if (i > 0) {
                    AbstractInsnNode prev = method.instructions.get(i - 1);
                    int prevOp = prev.getOpcode();
                    if (isConditionalJump(prev) || prevOp == Opcodes.GOTO || 
                        (prevOp >= Opcodes.IRETURN && prevOp <= Opcodes.RETURN) || prevOp == Opcodes.ATHROW) {
                        // Instruction after a jump/terminator with non-empty stack
                        return false;
                    }
                }
            }
        }

        // ── 0.5. Local Variable Type Inference (Anti-Dispatcher Poisoning) ──
        int maxLocals = method.maxLocals;
        int[] localTypes = new int[maxLocals]; // 0=unknown, 1=int, 2=float, 3=long, 4=double, 5=ref
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode) {
                VarInsnNode vin = (VarInsnNode) insn;
                int type = 0;
                int op = vin.getOpcode();
                if (op == Opcodes.ILOAD || op == Opcodes.ISTORE || op == Opcodes.RET) type = 1;
                else if (op == Opcodes.FLOAD || op == Opcodes.FSTORE) type = 2;
                else if (op == Opcodes.LLOAD || op == Opcodes.LSTORE) type = 3;
                else if (op == Opcodes.DLOAD || op == Opcodes.DSTORE) type = 4;
                else if (op == Opcodes.ALOAD || op == Opcodes.ASTORE) type = 5;

                if (type != 0 && vin.var < maxLocals) {
                    if (localTypes[vin.var] != 0 && localTypes[vin.var] != type) {
                        return false; // Type conflict! Slot reused for different types. Skip CFF.
                    }
                    localTypes[vin.var] = type;
                }
            } else if (insn instanceof IincInsnNode) {
                int varIdx = ((IincInsnNode) insn).var;
                if (varIdx < maxLocals) {
                    if (localTypes[varIdx] != 0 && localTypes[varIdx] != 1) return false;
                    localTypes[varIdx] = 1;
                }
            }
        }

        // ── 0.6. Reference Slot Reuse Detection ──────────────────────────
        // If a local variable is reused for different reference types, the dispatcher will merge them into Object.
        // This causes VerifyError when a block expects a specific type like Color.
        Map<Integer, String> refSlotTypes = new HashMap<>();
        boolean hasLocals = method.localVariables != null && !method.localVariables.isEmpty();
        if (hasLocals) {
            for (LocalVariableNode lvn : method.localVariables) {
                if (lvn.desc.startsWith("L") || lvn.desc.startsWith("[")) {
                    if (refSlotTypes.containsKey(lvn.index) && !refSlotTypes.get(lvn.index).equals(lvn.desc)) {
                        return false; // Type conflict! Reference slot reused. Skip CFF.
                    }
                    refSlotTypes.put(lvn.index, lvn.desc);
                }
            }
        }
        
        Set<Integer> astoreSeen = new HashSet<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.ASTORE) {
                int varIdx = ((VarInsnNode) insn).var;
                if (!hasLocals || !refSlotTypes.containsKey(varIdx)) {
                    // If we don't have local variable info for this slot, enforce strict single-ASTORE rule
                    if (!astoreSeen.add(varIdx)) {
                        return false; // Multiple ASTOREs to unknown reference slot. Unsafe! Skip CFF.
                    }
                }
            }
        }

        // ── 1. Map instructions to their TryCatchBlockNodes ────────────────
        List<TryCatchBlockNode> originalTryCatch = new ArrayList<>();
        Map<AbstractInsnNode, List<TryCatchBlockNode>> insnToTcbs = new HashMap<>();
        if (method.tryCatchBlocks != null) {
            originalTryCatch.addAll(method.tryCatchBlocks);
            for (TryCatchBlockNode tcb : originalTryCatch) {
                boolean inTry = false;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn == tcb.start) inTry = true;
                    if (insn == tcb.end) inTry = false;
                    if (inTry && insn.getOpcode() >= 0) {
                        insnToTcbs.computeIfAbsent(insn, k -> new ArrayList<>()).add(tcb);
                    }
                }
            }
        }

        // ── 2. Split into basic blocks (safe points only) ──────────────────
        List<BasicBlock> blocks = splitIntoBlocks(method, frames, originalTryCatch);
        if (blocks.size() < 2) return false;

        // ── 3. Build label → block map ─────────────────────────────────────
        Map<LabelNode, BasicBlock> labelToBlock = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            BasicBlock block = blocks.get(i);
            block.originalIndex = i;
            for (AbstractInsnNode insn : block.instructions) {
                if (insn instanceof LabelNode) {
                    labelToBlock.put((LabelNode) insn, block);
                }
            }
        }

        // ── 4. Identify handler blocks ───────────────────────────────
        Set<BasicBlock> handlerBlocks = new LinkedHashSet<>();
        for (TryCatchBlockNode tcb : originalTryCatch) {
            BasicBlock hBlock = labelToBlock.get(tcb.handler);
            if (hBlock != null) {
                hBlock.isHandler = true;
                handlerBlocks.add(hBlock);
            }
        }

        // ── 5. Assign randomised state IDs ───────────────────────────
        Set<Integer> usedIds = new HashSet<>();
        for (BasicBlock block : blocks) {
            int id;
            do { id = random.nextInt(0xFFFE) + 1; } while (usedIds.contains(id));
            usedIds.add(id);
            block.stateId = id;
        }

        // ── 6. Build fall-through map (original order) ───────────────
        Map<BasicBlock, BasicBlock> fallthroughMap = new HashMap<>();
        for (int i = 0; i < blocks.size() - 1; i++) {
            fallthroughMap.put(blocks.get(i), blocks.get(i + 1));
        }

        // ── 7. Detach all instructions from the method ───────────────
        detachAllInstructions(method);

        // ── 8. Rebuild with the Switch Dispatcher ────────────────────
        rebuildWithSwitchDispatcher(
                method, blocks, labelToBlock, fallthroughMap,
                handlerBlocks, originalTryCatch, usedIds, insnToTcbs, localTypes);

        return true;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Block splitting (try-catch aware)
    // ══════════════════════════════════════════════════════════════════

    private List<BasicBlock> splitIntoBlocks(MethodNode method,
                                             Frame<?>[] frames,
                                             List<TryCatchBlockNode> originalTryCatch) {
        List<BasicBlock> blocks = new ArrayList<>();
        BasicBlock current = new BasicBlock();
        blocks.add(current);

        for (int i = 0; i < method.instructions.size(); i++) {
            AbstractInsnNode insn = method.instructions.get(i);
            Frame<?> frame = (i < frames.length) ? frames[i] : null;

            if (frame == null) {
                current.instructions.add(insn);
                continue;
            }

            boolean stackEmpty = frame.getStackSize() == 0;

            // Handlers always have 1 item on stack (the exception), but we must split at them
            boolean isHandler = false;
            for (TryCatchBlockNode tcb : originalTryCatch) {
                if (tcb.handler == insn) isHandler = true;
            }

            if (insn instanceof LabelNode && (stackEmpty || isHandler)) {
                if (!current.instructions.isEmpty()) {
                    current = new BasicBlock();
                    blocks.add(current);
                }
            }

            current.instructions.add(insn);

            // ── Split after terminators / conditional jumps ──
            int op = insn.getOpcode();
            boolean isTerminator = op == Opcodes.GOTO
                    || (op >= Opcodes.IRETURN && op <= Opcodes.RETURN)
                    || op == Opcodes.ATHROW;

            if (isTerminator || isConditionalJump(insn)) {
                current = new BasicBlock();
                blocks.add(current);
            }
        }

        // Remove truly empty blocks
        blocks.removeIf(b -> b.instructions.isEmpty());

        return blocks;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Switch Dispatcher construction
    // ══════════════════════════════════════════════════════════════════

    private void rebuildWithSwitchDispatcher(
            MethodNode method,
            List<BasicBlock> blocks,
            Map<LabelNode, BasicBlock> labelToBlock,
            Map<BasicBlock, BasicBlock> fallthroughMap,
            Set<BasicBlock> handlerBlocks,
            List<TryCatchBlockNode> originalTryCatch,
            Set<Integer> usedIds,
            Map<AbstractInsnNode, List<TryCatchBlockNode>> insnToTcbs,
            int[] localTypes) {

        // ── Allocate two new local variable slots ────────────────────
        int stateSlot     = method.maxLocals;
        int exceptionSlot = method.maxLocals + 1;

        // ── Create structural labels ─────────────────────────────────
        LabelNode loopLabel    = new LabelNode();
        LabelNode defaultLabel = new LabelNode();

        // ── Create per-block case labels ─────────────────────────────
        for (BasicBlock b : blocks) {
            b.caseLabel    = new LabelNode();
            b.caseEndLabel = new LabelNode();
        }

        // ── Shuffle block emission order ─────────────────────────────
        List<BasicBlock> shuffled = new ArrayList<>(blocks);
        Collections.shuffle(shuffled, random);

        // ── Generate bogus dead-code cases ───────────────────────────
        int numBogus = config.isAggressiveMode()
                ? random.nextInt(6) + 3   // 3-8 bogus cases
                : random.nextInt(3) + 1;  // 1-3 bogus cases
        List<int[]> bogusCases   = new ArrayList<>(); // [bogusId, targetId]
        List<LabelNode> bogusLabels = new ArrayList<>();
        for (int i = 0; i < numBogus; i++) {
            int bogusId;
            do { bogusId = random.nextInt(0xFFFE) + 1; } while (usedIds.contains(bogusId));
            usedIds.add(bogusId);
            int targetId = blocks.get(random.nextInt(blocks.size())).stateId;
            bogusCases.add(new int[]{ bogusId, targetId });
            bogusLabels.add(new LabelNode());
        }

        // ── Build sorted key / label arrays for LOOKUPSWITCH ─────────
        // (JVM spec requires LOOKUPSWITCH keys in ascending order)
        List<int[]> allEntries = new ArrayList<>();
        //  [key, index, type]  type: 0 = real,  1 = bogus
        for (int i = 0; i < shuffled.size(); i++) {
            allEntries.add(new int[]{ shuffled.get(i).stateId, i, 0 });
        }
        for (int i = 0; i < bogusCases.size(); i++) {
            allEntries.add(new int[]{ bogusCases.get(i)[0], i, 1 });
        }
        allEntries.sort(Comparator.comparingInt(a -> a[0]));

        int[]       keys       = new int[allEntries.size()];
        LabelNode[] caseLabels = new LabelNode[allEntries.size()];
        for (int i = 0; i < allEntries.size(); i++) {
            keys[i] = allEntries.get(i)[0];
            int idx  = allEntries.get(i)[1];
            caseLabels[i] = (allEntries.get(i)[2] == 0)
                    ? shuffled.get(idx).caseLabel
                    : bogusLabels.get(idx);
        }

        // ══════════════════════════════════════════════════════════════
        //  Build the new instruction list
        // ══════════════════════════════════════════════════════════════
        InsnList newInsns = new InsnList();

        // ── Initialise state variable ────────────────────────────────
        pushInt(newInsns, blocks.get(0).stateId);
        newInsns.add(new VarInsnNode(Opcodes.ISTORE, stateSlot));

        // ── Initialise exception variable ────────────────────────────
        // (Prevents ASM COMPUTE_FRAMES from failing due to uninitialized variable on normal paths)
        newInsns.add(new InsnNode(Opcodes.ACONST_NULL));
        newInsns.add(new VarInsnNode(Opcodes.ASTORE, exceptionSlot));

        // ── Initialise local variables to prevent Dispatcher Poisoning ──
        int argLocals = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            argLocals++;
        }
        for (org.objectweb.asm.Type t : org.objectweb.asm.Type.getArgumentTypes(method.desc)) {
            argLocals += t.getSize();
        }

        for (int i = argLocals; i < localTypes.length; i++) {
            int type = localTypes[i];
            if (type == 1) { // int
                newInsns.add(new InsnNode(Opcodes.ICONST_0));
                newInsns.add(new VarInsnNode(Opcodes.ISTORE, i));
            } else if (type == 2) { // float
                newInsns.add(new InsnNode(Opcodes.FCONST_0));
                newInsns.add(new VarInsnNode(Opcodes.FSTORE, i));
            } else if (type == 3) { // long
                newInsns.add(new InsnNode(Opcodes.LCONST_0));
                newInsns.add(new VarInsnNode(Opcodes.LSTORE, i));
            } else if (type == 4) { // double
                newInsns.add(new InsnNode(Opcodes.DCONST_0));
                newInsns.add(new VarInsnNode(Opcodes.DSTORE, i));
            } else if (type == 5) { // ref
                newInsns.add(new InsnNode(Opcodes.ACONST_NULL));
                newInsns.add(new VarInsnNode(Opcodes.ASTORE, i));
            }
        }

        // ── Loop entry (while-true) ──────────────────────────────────
        newInsns.add(loopLabel);
        newInsns.add(new VarInsnNode(Opcodes.ILOAD, stateSlot));

        // ── LOOKUPSWITCH dispatcher ──────────────────────────────────
        newInsns.add(new LookupSwitchInsnNode(defaultLabel, keys, caseLabels));

        // ── Emit real cases (shuffled order) ─────────────────────────
        for (BasicBlock block : shuffled) {
            newInsns.add(block.caseLabel);

            // Handler blocks: restore the exception that the trampoline
            // stored into exceptionSlot so the original handler code
            // finds it on top of the stack as the JVM normally provides.
            if (block.isHandler) {
                newInsns.add(new VarInsnNode(Opcodes.ALOAD, exceptionSlot));
            }

            emitBlockCode(newInsns, block, labelToBlock, fallthroughMap,
                    stateSlot, loopLabel);

            newInsns.add(block.caseEndLabel);
        }

        // ── Emit bogus dead-code cases ───────────────────────────────
        for (int i = 0; i < bogusCases.size(); i++) {
            newInsns.add(bogusLabels.get(i));
            emitBogusCase(newInsns, bogusCases.get(i)[1], stateSlot, loopLabel);
        }

        // ── Default case (unreachable) ───────────────────────────────
        newInsns.add(defaultLabel);
        newInsns.add(new InsnNode(Opcodes.ACONST_NULL));
        newInsns.add(new InsnNode(Opcodes.ATHROW));

        // ── Handler trampolines (outside the switch) ─────────────────
        // When an exception is thrown inside a covered case, the JVM
        // pushes the exception onto the stack and jumps here.
        // The trampoline stores the exception, sets the state variable
        // to the handler's case ID, and re-enters the dispatcher loop.
        Map<BasicBlock, LabelNode> trampolineLabels = new LinkedHashMap<>();
        for (BasicBlock hBlock : handlerBlocks) {
            LabelNode trampLabel = new LabelNode();
            trampolineLabels.put(hBlock, trampLabel);

            newInsns.add(trampLabel);
            // Stack: [exception] — save it
            newInsns.add(new VarInsnNode(Opcodes.ASTORE, exceptionSlot));
            // Set state to the handler's case
            pushInt(newInsns, hBlock.stateId);
            newInsns.add(new VarInsnNode(Opcodes.ISTORE, stateSlot));
            // Re-enter the dispatcher
            newInsns.add(new JumpInsnNode(Opcodes.GOTO, loopLabel));
        }

        // ══════════════════════════════════════════════════════════════
        //  Replace method body
        // ══════════════════════════════════════════════════════════════
        method.instructions.add(newInsns);

        // ══════════════════════════════════════════════════════════════
        //  Reconstruct try-catch blocks (localised per case)
        // ══════════════════════════════════════════════════════════════
        method.tryCatchBlocks.clear();
        
        List<TryCatchBlockNode> currentTcbs = null;
        LabelNode currentStart = null;
        
        // We must iterate over an array since we will insert nodes
        AbstractInsnNode[] newNodes = method.instructions.toArray();
        for (AbstractInsnNode insn : newNodes) {
            List<TryCatchBlockNode> tcbs = insnToTcbs.get(insn);
            
            if (!Objects.equals(tcbs, currentTcbs)) {
                if (currentTcbs != null && !currentTcbs.isEmpty()) {
                    LabelNode end = new LabelNode();
                    method.instructions.insertBefore(insn, end);
                    for (TryCatchBlockNode tcb : currentTcbs) {
                        LabelNode tramp = trampolineLabels.get(labelToBlock.get(tcb.handler));
                        if (tramp != null) {
                            method.tryCatchBlocks.add(new TryCatchBlockNode(currentStart, end, tramp, tcb.type));
                        }
                    }
                }
                if (tcbs != null && !tcbs.isEmpty()) {
                    currentStart = new LabelNode();
                    method.instructions.insertBefore(insn, currentStart);
                }
                currentTcbs = tcbs;
            }
        }
        if (currentTcbs != null && !currentTcbs.isEmpty()) {
            LabelNode end = new LabelNode();
            method.instructions.add(end);
            for (TryCatchBlockNode tcb : currentTcbs) {
                LabelNode tramp = trampolineLabels.get(labelToBlock.get(tcb.handler));
                if (tramp != null) {
                    method.tryCatchBlocks.add(new TryCatchBlockNode(currentStart, end, tramp, tcb.type));
                }
            }
        }

        // ══════════════════════════════════════════════════════════════
        //  Update metadata (COMPUTE_FRAMES will refine these)
        // ══════════════════════════════════════════════════════════════
        method.maxLocals = exceptionSlot + 1;
        method.maxStack  = Math.max(method.maxStack + 6, 10);
        if (method.localVariables != null) method.localVariables.clear();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Single-block code emission with jump → state-transition conversion
    // ══════════════════════════════════════════════════════════════════

    /**
     * Emits all instructions for one basic block, converting every jump
     * instruction into a state-variable assignment + GOTO LOOP_LABEL.
     */
    private void emitBlockCode(InsnList out,
                               BasicBlock block,
                               Map<LabelNode, BasicBlock> labelToBlock,
                               Map<BasicBlock, BasicBlock> fallthroughMap,
                               int stateSlot,
                               LabelNode loopLabel) {

        List<AbstractInsnNode> insns = block.instructions;

        // ── Find the last "real" instruction (opcode >= 0) ───────────
        int lastRealIdx = -1;
        for (int i = insns.size() - 1; i >= 0; i--) {
            if (insns.get(i).getOpcode() >= 0) {
                lastRealIdx = i;
                break;
            }
        }

        // ── Block with no real instructions: just fall through ───────
        if (lastRealIdx < 0) {
            BasicBlock next = fallthroughMap.get(block);
            if (next != null) {
                pushInt(out, next.stateId);
                out.add(new VarInsnNode(Opcodes.ISTORE, stateSlot));
                out.add(new JumpInsnNode(Opcodes.GOTO, loopLabel));
            }
            return;
        }

        AbstractInsnNode lastInsn = insns.get(lastRealIdx);

        // ── Emit all instructions BEFORE the terminator ──────────────
        for (int i = 0; i < lastRealIdx; i++) {
            AbstractInsnNode insn = insns.get(i);
            // Skip FrameNodes — COMPUTE_FRAMES will recalculate them
            if (insn.getType() == AbstractInsnNode.FRAME) continue;
            out.add(insn);
        }

        // ── Handle the terminator instruction ────────────────────────
        int op = lastInsn.getOpcode();

        if (op == Opcodes.GOTO) {
            // ── Unconditional jump → state transition ────────────────
            JumpInsnNode jump = (JumpInsnNode) lastInsn;
            BasicBlock target = labelToBlock.get(jump.label);
            if (target != null) {
                pushInt(out, target.stateId);
                out.add(new VarInsnNode(Opcodes.ISTORE, stateSlot));
                out.add(new JumpInsnNode(Opcodes.GOTO, loopLabel));
            } else {
                out.add(lastInsn); // target outside our scope — keep original
            }

        } else if (isConditionalJump(lastInsn)) {
            // ── Conditional jump → dual state transition ─────────────
            //
            //   IF_COND TRUE_LABEL
            //   [false branch] state = fallthroughId; goto LOOP
            //   TRUE_LABEL:
            //   [true branch]  state = targetId;      goto LOOP
            //
            JumpInsnNode condJump     = (JumpInsnNode) lastInsn;
            BasicBlock   trueTarget   = labelToBlock.get(condJump.label);
            BasicBlock   falseTarget  = fallthroughMap.get(block);

            if (trueTarget != null && falseTarget != null) {
                LabelNode trueBranch = new LabelNode();

                // Emit the conditional (pointing to our new true-branch label)
                out.add(new JumpInsnNode(condJump.getOpcode(), trueBranch));

                // False path: set state to fall-through block
                pushInt(out, falseTarget.stateId);
                out.add(new VarInsnNode(Opcodes.ISTORE, stateSlot));
                out.add(new JumpInsnNode(Opcodes.GOTO, loopLabel));

                // True path: set state to jump-target block
                out.add(trueBranch);
                pushInt(out, trueTarget.stateId);
                out.add(new VarInsnNode(Opcodes.ISTORE, stateSlot));
                out.add(new JumpInsnNode(Opcodes.GOTO, loopLabel));
            } else {
                // Safety fallback: emit original + fall-through
                out.add(lastInsn);
                BasicBlock next = fallthroughMap.get(block);
                if (next != null) {
                    pushInt(out, next.stateId);
                    out.add(new VarInsnNode(Opcodes.ISTORE, stateSlot));
                    out.add(new JumpInsnNode(Opcodes.GOTO, loopLabel));
                }
            }

        } else if ((op >= Opcodes.IRETURN && op <= Opcodes.RETURN)
                || op == Opcodes.ATHROW) {
            // ── Terminal: return / throw — no state transition needed ─
            out.add(lastInsn);

        } else {
            // ── Regular instruction: emit it + fall-through state ────
            out.add(lastInsn);
            BasicBlock next = fallthroughMap.get(block);
            if (next != null) {
                pushInt(out, next.stateId);
                out.add(new VarInsnNode(Opcodes.ISTORE, stateSlot));
                out.add(new JumpInsnNode(Opcodes.GOTO, loopLabel));
            }
        }

        // ── Emit trailing pseudo-instructions (labels, line numbers) ─
        for (int i = lastRealIdx + 1; i < insns.size(); i++) {
            AbstractInsnNode insn = insns.get(i);
            if (insn.getType() == AbstractInsnNode.FRAME) continue;
            out.add(insn);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Bogus dead-code case emission
    // ══════════════════════════════════════════════════════════════════

    /**
     * Emit a plausible-looking but unreachable switch case to mislead
     * static analysis and decompilers.
     */
    private void emitBogusCase(InsnList out, int targetStateId,
                               int stateSlot, LabelNode loopLabel) {
        // Sprinkle some harmless junk instructions
        int junkCount = random.nextInt(4) + 1;
        for (int j = 0; j < junkCount; j++) {
            switch (random.nextInt(5)) {
                case 0:
                    out.add(new InsnNode(Opcodes.ICONST_0));
                    out.add(new InsnNode(Opcodes.POP));
                    break;
                case 1:
                    out.add(new InsnNode(Opcodes.ACONST_NULL));
                    out.add(new InsnNode(Opcodes.POP));
                    break;
                case 2:
                    pushInt(out, random.nextInt(1000));
                    out.add(new InsnNode(Opcodes.POP));
                    break;
                case 3:
                    out.add(new VarInsnNode(Opcodes.ILOAD, stateSlot));
                    out.add(new InsnNode(Opcodes.POP));
                    break;
                case 4:
                    out.add(new InsnNode(Opcodes.ICONST_1));
                    pushInt(out, random.nextInt(100));
                    out.add(new InsnNode(Opcodes.IADD));
                    out.add(new InsnNode(Opcodes.POP));
                    break;
            }
        }

        // Transition to a real state (looks like genuine control flow)
        pushInt(out, targetStateId);
        out.add(new VarInsnNode(Opcodes.ISTORE, stateSlot));
        out.add(new JumpInsnNode(Opcodes.GOTO, loopLabel));
    }

    // ══════════════════════════════════════════════════════════════════
    //  Utility methods
    // ══════════════════════════════════════════════════════════════════

    /**
     * Properly detach every instruction from the method's InsnList so
     * they can be freely re-added to a new list.
     */
    private void detachAllInstructions(MethodNode method) {
        AbstractInsnNode insn = method.instructions.getFirst();
        while (insn != null) {
            AbstractInsnNode next = insn.getNext();
            method.instructions.remove(insn);
            insn = next;
        }
    }

    /** Check whether an instruction is a conditional jump (IF_*). */
    private boolean isConditionalJump(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return (op >= Opcodes.IFEQ && op <= Opcodes.IF_ACMPNE)
                || op == Opcodes.IFNULL
                || op == Opcodes.IFNONNULL;
    }

    /**
     * Efficiently push an integer constant onto the operand stack,
     * choosing the smallest encoding (ICONST_*, BIPUSH, SIPUSH, LDC).
     */
    private void pushInt(InsnList insns, int value) {
        if (value >= -1 && value <= 5) {
            insns.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            insns.add(new LdcInsnNode(value));
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  BasicBlock data class
    // ══════════════════════════════════════════════════════════════════

    private static class BasicBlock {
        /** Original bytecode instructions belonging to this block. */
        final List<AbstractInsnNode> instructions = new ArrayList<>();

        /** Randomised state ID used as the LOOKUPSWITCH case key. */
        int stateId;

        /** Position of this block in the original (pre-shuffle) order. */
        @SuppressWarnings("unused")
        int originalIndex;

        /** Whether this block is the entry point for an exception handler. */
        boolean isHandler;

        /** Label emitted at the START of this case (used as switch target). */
        LabelNode caseLabel;

        /** Label emitted at the END of this case (used for try-catch scope). */
        LabelNode caseEndLabel;
    }
}
