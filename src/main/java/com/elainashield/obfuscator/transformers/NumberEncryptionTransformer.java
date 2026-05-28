package com.elainashield.obfuscator.transformers;

import com.elainashield.obfuscator.core.ObfuscationConfig;
import com.elainashield.obfuscator.core.ObfuscationContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.List;
import java.util.Random;

/**
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║                 Number Encryption Transformer                    ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║ Hides hardcoded numbers (int, long, float, double) by replacing  ║
 * ║ them with runtime mathematical calculations.                     ║
 * ║ Employs IEEE 754 bit-level conversion for Floats & Doubles to    ║
 * ║ guarantee 100% precision without floating-point errors.          ║
 * ╚══════════════════════════════════════════════════════════════════╝
 */
public class NumberEncryptionTransformer {

    @SuppressWarnings("unused")
    private final ObfuscationConfig config;
    @SuppressWarnings("unused")
    private final ObfuscationContext context;
    private final Random random;

    public NumberEncryptionTransformer(ObfuscationConfig config, ObfuscationContext context) {
        this.config = config;
        this.context = context;
        this.random = new Random(config.getSeed() >= 0 ? config.getSeed() : System.currentTimeMillis());
    }

    public void transform(List<ClassNode> classes) {
        System.out.println("  [NumberEncryption] Encrypting numeric constants...");

        int encryptedCount = 0;

        for (ClassNode cn : classes) {
            if ((cn.access & Opcodes.ACC_INTERFACE) != 0 || (cn.access & Opcodes.ACC_ANNOTATION) != 0) {
                continue;
            }

            for (MethodNode mn : cn.methods) {
                AbstractInsnNode[] insns = mn.instructions.toArray();
                for (AbstractInsnNode insn : insns) {
                    Integer intValue = null;
                    Long longValue = null;
                    Float floatValue = null;
                    Double doubleValue = null;

                    // Extract constant value
                    int opcode = insn.getOpcode();
                    if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
                        intValue = opcode - Opcodes.ICONST_0;
                    } else if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                        intValue = ((IntInsnNode) insn).operand;
                    } else if (opcode == Opcodes.LDC) {
                        Object cst = ((LdcInsnNode) insn).cst;
                        if (cst instanceof Integer) {
                            intValue = (Integer) cst;
                        } else if (cst instanceof Long) {
                            longValue = (Long) cst;
                        } else if (cst instanceof Float) {
                            floatValue = (Float) cst;
                        } else if (cst instanceof Double) {
                            doubleValue = (Double) cst;
                        }
                    }

                    // Process and replace
                    if (intValue != null) {
                        mn.instructions.insertBefore(insn, obfuscateInt(intValue));
                        mn.instructions.remove(insn);
                        encryptedCount++;
                    } else if (longValue != null) {
                        mn.instructions.insertBefore(insn, obfuscateLong(longValue));
                        mn.instructions.remove(insn);
                        encryptedCount++;
                    } else if (floatValue != null) {
                        mn.instructions.insertBefore(insn, obfuscateFloat(floatValue));
                        mn.instructions.remove(insn);
                        encryptedCount++;
                    } else if (doubleValue != null) {
                        mn.instructions.insertBefore(insn, obfuscateDouble(doubleValue));
                        mn.instructions.remove(insn);
                        encryptedCount++;
                    }
                }
            }
        }

        System.out.println("  [NumberEncryption] Encrypted " + encryptedCount + " numbers.");
    }

    private InsnList obfuscateInt(int target) {
        InsnList list = new InsnList();
        int a = random.nextInt();
        int b = target ^ a;
        pushInt(list, a);
        pushInt(list, b);
        list.add(new InsnNode(Opcodes.IXOR));
        return list;
    }

    private InsnList obfuscateLong(long target) {
        InsnList list = new InsnList();
        long a = random.nextLong();
        long b = target ^ a;
        pushLong(list, a);
        pushLong(list, b);
        list.add(new InsnNode(Opcodes.LXOR));
        return list;
    }

    private InsnList obfuscateFloat(float target) {
        InsnList list = new InsnList();
        int bits = Float.floatToIntBits(target);
        list.add(obfuscateInt(bits));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false));
        return list;
    }

    private InsnList obfuscateDouble(double target) {
        InsnList list = new InsnList();
        long bits = Double.doubleToLongBits(target);
        list.add(obfuscateLong(bits));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false));
        return list;
    }

    private void pushInt(InsnList list, int value) {
        if (value >= -1 && value <= 5) {
            list.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            list.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            list.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            list.add(new LdcInsnNode(value));
        }
    }

    private void pushLong(InsnList list, long value) {
        if (value == 0 || value == 1) {
            list.add(new InsnNode(Opcodes.LCONST_0 + (int) value));
        } else {
            list.add(new LdcInsnNode(value));
        }
    }
}
