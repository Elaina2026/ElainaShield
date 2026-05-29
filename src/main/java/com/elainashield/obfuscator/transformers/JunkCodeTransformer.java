package com.elainashield.obfuscator.transformers;

import com.elainashield.obfuscator.core.ObfuscationConfig;
import com.elainashield.obfuscator.core.ObfuscationContext;
import com.elainashield.obfuscator.utils.NameGenerator;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║            Junk Code Injection Transformer                       ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║ Injects various types of dead/junk code to bloat the binary     ║
 * ║ and confuse decompilers:                                        ║
 * ║                                                                  ║
 * ║ 1. JUNK METHODS: Entire fake methods with complex-looking but   ║
 * ║    meaningless logic (loops, math, string manipulation)          ║
 * ║                                                                  ║
 * ║ 2. JUNK FIELDS: Static fields initialized with garbage values   ║
 * ║                                                                  ║
 * ║ 3. OPAQUE PREDICATES: Conditions that always evaluate to the    ║
 * ║    same value but look complex (e.g., x*x >= 0 is always true)  ║
 * ║                                                                  ║
 * ║ 4. INLINE DEAD CODE: Unreachable code blocks inserted inline    ║
 * ║    within existing methods behind opaque predicates              ║
 * ╚══════════════════════════════════════════════════════════════════╝
 */
public class JunkCodeTransformer {

    private final ObfuscationConfig config;
    private final ObfuscationContext context;
    private final NameGenerator nameGen;
    private final Random random;

    /** Number of junk methods to add per class */
    private static final int JUNK_METHODS_PER_CLASS_MIN = 15;
    private static final int JUNK_METHODS_PER_CLASS_MAX = 30;

    /** Number of junk fields to add per class */
    private static final int JUNK_FIELDS_PER_CLASS_MIN = 10;
    private static final int JUNK_FIELDS_PER_CLASS_MAX = 20;

    /** Aggressive mode multiplier */
    private static final int AGGRESSIVE_MULTIPLIER = 5;

    public JunkCodeTransformer(ObfuscationConfig config, ObfuscationContext context, NameGenerator nameGen) {
        this.config = config;
        this.context = context;
        this.nameGen = nameGen;
        this.random = nameGen.getRandom();
    }

    /**
     * Apply junk code injection to all classes.
     */
    public void transform(List<ClassNode> classes) {
        System.out.println("  [JunkCode] Injecting junk code...");

        int totalMethods = 0;
        int totalFields = 0;
        int totalInlineJunk = 0;

        for (ClassNode cn : classes) {
            // Skip interfaces and annotations
            if ((cn.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) != 0) {
                continue;
            }

            // 1. Add junk fields
            int fieldCount = randomRange(JUNK_FIELDS_PER_CLASS_MIN, JUNK_FIELDS_PER_CLASS_MAX);
            if (config.isAggressiveMode()) fieldCount *= AGGRESSIVE_MULTIPLIER;
            for (int i = 0; i < fieldCount; i++) {
                cn.fields.add(generateJunkField());
            }
            totalFields += fieldCount;

            // 2. Add junk methods
            int methodCount = randomRange(JUNK_METHODS_PER_CLASS_MIN, JUNK_METHODS_PER_CLASS_MAX);
            if (config.isAggressiveMode()) methodCount *= AGGRESSIVE_MULTIPLIER;
            for (int i = 0; i < methodCount; i++) {
                cn.methods.add(generateJunkMethod(cn));
            }
            totalMethods += methodCount;

            // 3. Insert inline dead code into existing methods
            for (MethodNode mn : cn.methods) {
                if (canInjectInline(mn)) {
                    int injected = injectInlineDeadCode(mn);
                    totalInlineJunk += injected;
                }
            }
        }

        context.addJunkMethods(totalMethods);
        context.addJunkFields(totalFields);

        System.out.println("  [JunkCode] Injected: " + totalMethods + " methods, " +
                totalFields + " fields, " + totalInlineJunk + " inline blocks");
    }

    // ==================================================================
    // JUNK FIELD GENERATION
    // ==================================================================

    private FieldNode generateJunkField() {
        String name = nameGen.nextFieldName();
        int choice = random.nextInt(5);
        String desc;
        Object value = null;

        switch (choice) {
            case 0:
                desc = "I"; // int
                value = random.nextInt();
                break;
            case 1:
                desc = "J"; // long
                value = random.nextLong();
                break;
            case 2:
                desc = "Ljava/lang/String;"; // String
                value = generateGarbageString();
                break;
            case 3:
                desc = "D"; // double
                value = random.nextDouble() * 1000;
                break;
            case 4:
                desc = "Z"; // boolean
                value = random.nextBoolean() ? 1 : 0;
                break;
            default:
                desc = "I";
                value = 0;
        }

        int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC;
        if (random.nextBoolean()) access |= Opcodes.ACC_FINAL;

        return new FieldNode(access, name, desc, null, value);
    }

    private String generateGarbageString() {
        int len = 8 + random.nextInt(24);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append((char) (0x20 + random.nextInt(95))); // printable ASCII
        }
        return sb.toString();
    }

    // ==================================================================
    // JUNK METHOD GENERATION
    // ==================================================================

    /**
     * Generate a complete junk method with complex-looking but meaningless logic.
     * Multiple templates are used for variety.
     */
    private MethodNode generateJunkMethod(ClassNode owner) {
        int template = random.nextInt(6);
        switch (template) {
            case 0: return generateMathJunkMethod();
            case 1: return generateLoopJunkMethod();
            case 2: return generateStringJunkMethod();
            case 3: return generateArrayJunkMethod();
            case 4: return generateBitManipJunkMethod();
            case 5: return generateNestedConditionJunkMethod();
            default: return generateMathJunkMethod();
        }
    }

    /**
     * Template 1: Mathematical computation junk
     * Generates: int method(int a, int b) { return ((a * MAGIC) ^ b) + CONST; }
     */
    private MethodNode generateMathJunkMethod() {
        String name = nameGen.nextJunkMethodName();
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                name, "(II)I", null, null);

        InsnList insns = mn.instructions;

        // Complex math that looks important but is never called
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        pushInt(insns, random.nextInt(1000) + 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new InsnNode(Opcodes.IXOR));
        pushInt(insns, random.nextInt(0xFFFF));
        insns.add(new InsnNode(Opcodes.IADD));

        // More operations for complexity
        insns.add(new InsnNode(Opcodes.DUP));
        pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.ISHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        pushInt(insns, 0x45D9F3B);
        insns.add(new InsnNode(Opcodes.IMUL));

        insns.add(new InsnNode(Opcodes.IRETURN));

        mn.maxStack = 4;
        mn.maxLocals = 2;
        return mn;
    }

    /**
     * Template 2: Loop-based junk
     * Generates a method with a for loop that computes something useless
     */
    private MethodNode generateLoopJunkMethod() {
        String name = nameGen.nextJunkMethodName();
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                name, "(I)J", null, null);

        InsnList insns = mn.instructions;

        // long result = 0;
        insns.add(new InsnNode(Opcodes.LCONST_0));
        insns.add(new VarInsnNode(Opcodes.LSTORE, 1)); // result at slot 1-2

        // int i = 0;
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 3)); // i at slot 3

        // Loop start
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        insns.add(loopStart);

        // if (i >= param) goto end
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

        // result += (i * MAGIC) ^ (result >>> 16)
        insns.add(new VarInsnNode(Opcodes.LLOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        pushInt(insns, random.nextInt(10000) + 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 1));
        pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.LSTORE, 1));

        // i++
        insns.add(new IincInsnNode(3, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));

        insns.add(loopEnd);
        insns.add(new VarInsnNode(Opcodes.LLOAD, 1));
        insns.add(new InsnNode(Opcodes.LRETURN));

        mn.maxStack = 6;
        mn.maxLocals = 4;
        return mn;
    }

    /**
     * Template 3: String manipulation junk
     */
    private MethodNode generateStringJunkMethod() {
        String name = nameGen.nextJunkMethodName();
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                name, "(Ljava/lang/String;)Ljava/lang/String;", null, null);

        InsnList insns = mn.instructions;

        // StringBuilder sb = new StringBuilder();
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/StringBuilder", "<init>", "()V", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 1));

        // sb.append(param).append("GARBAGE")
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        insns.add(new LdcInsnNode(generateGarbageString()));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        insns.add(new InsnNode(Opcodes.POP));

        // return sb.toString()
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false));
        insns.add(new InsnNode(Opcodes.ARETURN));

        mn.maxStack = 4;
        mn.maxLocals = 2;
        return mn;
    }

    /**
     * Template 4: Array manipulation junk
     */
    private MethodNode generateArrayJunkMethod() {
        String name = nameGen.nextJunkMethodName();
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                name, "(I)[I", null, null);

        InsnList insns = mn.instructions;

        int arraySize = 16 + random.nextInt(48);

        // int[] arr = new int[SIZE];
        pushInt(insns, arraySize);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 1));

        // Fill array with pseudo-random values
        for (int i = 0; i < Math.min(arraySize, 8); i++) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
            pushInt(insns, i);
            pushInt(insns, random.nextInt());
            insns.add(new InsnNode(Opcodes.IASTORE));
        }

        // Shuffle with XOR swap
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pushInt(insns, 0);
        insns.add(new InsnNode(Opcodes.IALOAD));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pushInt(insns, 1);
        insns.add(new InsnNode(Opcodes.IALOAD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.POP));

        // return arr
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new InsnNode(Opcodes.ARETURN));

        mn.maxStack = 4;
        mn.maxLocals = 2;
        return mn;
    }

    /**
     * Template 5: Bit manipulation junk
     */
    private MethodNode generateBitManipJunkMethod() {
        String name = nameGen.nextJunkMethodName();
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                name, "(JI)J", null, null);

        InsnList insns = mn.instructions;

        // Complex bit manipulation
        insns.add(new VarInsnNode(Opcodes.LLOAD, 0)); // param1 (long, slots 0-1)
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2)); // param2 (int, slot 2)
        insns.add(new InsnNode(Opcodes.LSHL));

        insns.add(new VarInsnNode(Opcodes.LLOAD, 0));
        pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));

        insns.add(new LdcInsnNode(0x5DEECE66DL));
        insns.add(new InsnNode(Opcodes.LMUL));
        insns.add(new LdcInsnNode(0xBL));
        insns.add(new InsnNode(Opcodes.LADD));

        insns.add(new InsnNode(Opcodes.LRETURN));

        mn.maxStack = 4;
        mn.maxLocals = 3;
        return mn;
    }

    /**
     * Template 6: Nested conditions junk
     */
    private MethodNode generateNestedConditionJunkMethod() {
        String name = nameGen.nextJunkMethodName();
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                name, "(III)I", null, null);

        InsnList insns = mn.instructions;

        LabelNode l1 = new LabelNode();
        LabelNode l2 = new LabelNode();
        LabelNode l3 = new LabelNode();
        LabelNode end = new LabelNode();

        // if (a > b)
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, l1));

        // a * c + MAGIC
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new InsnNode(Opcodes.IMUL));
        pushInt(insns, random.nextInt(1000));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));

        // else if (b > c)
        insns.add(l1);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, l2));

        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        insns.add(new InsnNode(Opcodes.ISUB));
        pushInt(insns, random.nextInt(500));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));

        // else if (c > a)
        insns.add(l2);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, l3));

        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new InsnNode(Opcodes.IADD));
        pushInt(insns, random.nextInt(128));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));

        // else (fallback)
        insns.add(l3);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pushInt(insns, random.nextInt(256));
        insns.add(new InsnNode(Opcodes.IAND));

        insns.add(end);
        insns.add(new InsnNode(Opcodes.IRETURN));

        mn.maxStack = 4;
        mn.maxLocals = 3;
        return mn;
    }

    // ==================================================================
    // INLINE DEAD CODE INJECTION
    // ==================================================================

    private boolean canInjectInline(MethodNode mn) {
        if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        if (mn.name.startsWith("<")) return false;
        if (mn.instructions.size() < 4) return false;
        return true;
    }

    /**
     * Inject inline dead code using opaque predicates.
     *
     * Pattern:
     *   if ((CONST * CONST) < 0) {  // Always false: x*x >= 0 for any int... mostly
     *       ... junk code ...
     *   }
     *
     * Alternative patterns:
     *   if ((x | ~x) != -1) { ... }    // Always false
     *   if ((x & 0) != 0) { ... }      // Always false
     */
    private int injectInlineDeadCode(MethodNode method) {
        int injected = 0;
        int maxInjections = config.isAggressiveMode() ? 15 : 5;

        InsnList insns = method.instructions;
        List<AbstractInsnNode> insertionPoints = findInsertionPoints(insns);

        Collections.shuffle(insertionPoints, random);
        int count = Math.min(maxInjections, insertionPoints.size());

        for (int i = 0; i < count; i++) {
            AbstractInsnNode insertBefore = insertionPoints.get(i);
            InsnList deadCode = generateOpaquePredicateBlock();
            insns.insertBefore(insertBefore, deadCode);
            injected++;
        }

        // Increase max stack to accommodate injected code
        method.maxStack = Math.max(method.maxStack + 4, 8);
        method.maxLocals = Math.max(method.maxLocals + 2, 4);

        return injected;
    }

    /**
     * Find safe insertion points within a method.
     * We look for positions between independent statements (after xSTORE, INVOKE, POP, etc.)
     */
    private List<AbstractInsnNode> findInsertionPoints(InsnList insns) {
        List<AbstractInsnNode> points = new ArrayList<>();

        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getNext() == null) continue;

            int opcode = insn.getOpcode();
            // Safe to insert after stores, invocations (void), pops
            if ((opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) ||
                    opcode == Opcodes.POP || opcode == Opcodes.POP2) {

                // Don't insert right before a jump target or return
                AbstractInsnNode next = insn.getNext();
                if (next.getType() != AbstractInsnNode.LABEL &&
                        next.getOpcode() != Opcodes.RETURN &&
                        next.getType() != AbstractInsnNode.FRAME) {
                    points.add(next);
                }
            }
        }

        return points;
    }

    /**
     * Generate a dead code block guarded by an opaque predicate.
     * The predicate always evaluates to false, so the code never executes.
     */
    private InsnList generateOpaquePredicateBlock() {
        InsnList insns = new InsnList();
        LabelNode skipLabel = new LabelNode();

        int predicateType = random.nextInt(4);
        switch (predicateType) {
            case 0:
                // Opaque predicate: (CONST * CONST + 1) < 0
                // For non-overflow ints, x*x+1 > 0 always
                pushInt(insns, 2 + random.nextInt(100));
                insns.add(new InsnNode(Opcodes.DUP));
                insns.add(new InsnNode(Opcodes.IMUL));
                insns.add(new InsnNode(Opcodes.ICONST_1));
                insns.add(new InsnNode(Opcodes.IADD));
                insns.add(new JumpInsnNode(Opcodes.IFGE, skipLabel)); // Always taken
                break;

            case 1:
                // Opaque predicate: (0 & RANDOM) != 0 → always false
                insns.add(new InsnNode(Opcodes.ICONST_0));
                pushInt(insns, random.nextInt());
                insns.add(new InsnNode(Opcodes.IAND));
                insns.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel)); // Always taken
                break;

            case 2:
                // Opaque predicate: Integer.MAX_VALUE + 1 > 0 → false (overflow)
                // Actually: we use a simpler always-false
                pushInt(insns, 0);
                pushInt(insns, 1);
                insns.add(new JumpInsnNode(Opcodes.IF_ICMPLT, skipLabel)); // 0 < 1, always taken
                break;

            case 3:
                // Opaque predicate: (CONST | CONST) == 0 → always false for non-zero
                pushInt(insns, 1 + random.nextInt(1000));
                pushInt(insns, 1 + random.nextInt(1000));
                insns.add(new InsnNode(Opcodes.IOR));
                insns.add(new JumpInsnNode(Opcodes.IFNE, skipLabel)); // Always taken (non-zero OR non-zero)
                break;
        }

        // Dead code block (never executed)
        generateInlineJunkInstructions(insns);

        insns.add(skipLabel);
        return insns;
    }

    /**
     * Generate a few meaningless instructions for the dead code body.
     */
    private void generateInlineJunkInstructions(InsnList insns) {
        int count = 15 + random.nextInt(25);
        for (int i = 0; i < count; i++) {
            int type = random.nextInt(6);
            switch (type) {
                case 0:
                    pushInt(insns, random.nextInt());
                    insns.add(new InsnNode(Opcodes.POP));
                    break;
                case 1:
                    pushInt(insns, random.nextInt(100));
                    pushInt(insns, random.nextInt(100) + 1);
                    insns.add(new InsnNode(Opcodes.IMUL));
                    insns.add(new InsnNode(Opcodes.POP));
                    break;
                case 2:
                    insns.add(new LdcInsnNode(generateGarbageString()));
                    insns.add(new InsnNode(Opcodes.POP));
                    break;
                case 3:
                    pushInt(insns, random.nextInt());
                    pushInt(insns, 1 + random.nextInt(31));
                    insns.add(new InsnNode(Opcodes.ISHR));
                    insns.add(new InsnNode(Opcodes.POP));
                    break;
                case 4:
                    insns.add(new InsnNode(Opcodes.ICONST_0));
                    insns.add(new InsnNode(Opcodes.I2L));
                    insns.add(new InsnNode(Opcodes.POP2));
                    break;
                case 5:
                    pushInt(insns, random.nextInt());
                    pushInt(insns, random.nextInt());
                    insns.add(new InsnNode(Opcodes.IADD));
                    insns.add(new InsnNode(Opcodes.POP));
                    break;
            }
        }
    }

    // ==================================================================
    // UTILITY
    // ==================================================================

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

    private int randomRange(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}
