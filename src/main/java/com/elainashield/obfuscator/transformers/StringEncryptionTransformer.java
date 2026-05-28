package com.elainashield.obfuscator.transformers;

import com.elainashield.obfuscator.core.ObfuscationConfig;
import com.elainashield.obfuscator.core.ObfuscationContext;
import com.elainashield.obfuscator.utils.NameGenerator;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Random;

/**
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║                 String Encryption Transformer                    ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║ Encrypts String literals at compile-time using XOR + Base64.     ║
 * ║ Injects a decryption method with a static String array CACHE.    ║
 * ║ Decrypted strings are cached to prevent GC overhead.             ║
 * ╚══════════════════════════════════════════════════════════════════╝
 */
public class StringEncryptionTransformer {

    @SuppressWarnings("unused")
    private final ObfuscationConfig config;
    @SuppressWarnings("unused")
    private final ObfuscationContext context;
    private final NameGenerator nameGen;
    private final Random random;

    public StringEncryptionTransformer(ObfuscationConfig config, ObfuscationContext context, NameGenerator nameGen) {
        this.config = config;
        this.context = context;
        this.nameGen = nameGen;
        this.random = new Random(config.getSeed() >= 0 ? config.getSeed() : System.currentTimeMillis());
    }

    public void transform(List<ClassNode> classes) {
        System.out.println("  [StringEncryption] Applying String Encryption...");

        int totalStringsEncrypted = 0;

        for (ClassNode cn : classes) {
            // Bỏ qua các class là Interface hoặc Annotation
            if ((cn.access & Opcodes.ACC_INTERFACE) != 0 || (cn.access & Opcodes.ACC_ANNOTATION) != 0) {
                continue;
            }

            // Tên hàm giải mã và tên mảng cache tĩnh
            String decryptMethodName = nameGen.nextMethodName();
            String cacheFieldName = nameGen.nextFieldName();
            int classKey = random.nextInt(Integer.MAX_VALUE - 1) + 1;

            int stringIdCounter = 0;
            boolean modified = false;

            for (MethodNode mn : cn.methods) {
                AbstractInsnNode[] insns = mn.instructions.toArray();
                
                for (AbstractInsnNode insn : insns) {
                    if (insn.getOpcode() == Opcodes.LDC) {
                        LdcInsnNode ldc = (LdcInsnNode) insn;
                        
                        if (ldc.cst instanceof String) {
                            String originalStr = (String) ldc.cst;

                            // Bỏ qua chuỗi rỗng
                            if (originalStr.isEmpty()) {
                                continue;
                            }

                            String encryptedStr = encrypt(originalStr, classKey);
                            int currentId = stringIdCounter++;

                            // LDC -> decrypt(id, encrypted, key)
                            InsnList newInstructions = new InsnList();
                            
                            // Load ID (int)
                            newInstructions.add(new LdcInsnNode(currentId));
                            // Load Encrypted String
                            newInstructions.add(new LdcInsnNode(encryptedStr));
                            // Load Key (int)
                            newInstructions.add(new LdcInsnNode(classKey));
                            // Invoke Static
                            newInstructions.add(new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    cn.name,
                                    decryptMethodName,
                                    "(ILjava/lang/String;I)Ljava/lang/String;",
                                    false
                            ));

                            mn.instructions.insertBefore(insn, newInstructions);
                            mn.instructions.remove(insn);

                            modified = true;
                            totalStringsEncrypted++;
                        }
                    }
                }
            }

            if (modified) {
                // Tiêm Cache Field
                FieldNode cacheField = new FieldNode(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        cacheFieldName,
                        "[Ljava/lang/String;",
                        null,
                        null
                );
                cn.fields.add(cacheField);

                // Tiêm Decrypt Method
                injectDecryptMethod(cn, decryptMethodName, cacheFieldName, stringIdCounter);
            }
        }

        System.out.println("  [StringEncryption] Encrypted " + totalStringsEncrypted + " strings with caching.");
    }

    private String encrypt(String input, int key) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = new byte[bytes.length];
        
        for (int i = 0; i < bytes.length; i++) {
            encrypted[i] = (byte) (bytes[i] ^ key);
        }
        
        return Base64.getEncoder().encodeToString(encrypted);
    }

    /**
     * Sinh mã ASM Tree cho hàm giải mã với logic cache.
     * 
     * private static String decrypt(int id, String encrypted, int key) {
     *     if (cache == null) cache = new String[MAX_STRINGS];
     *     if (cache[id] != null) return cache[id];
     *     
     *     byte[] decoded = Base64.getDecoder().decode(encrypted);
     *     byte[] result = new byte[decoded.length];
     *     for (int i = 0; i < decoded.length; i++) {
     *         result[i] = (byte) (decoded[i] ^ key);
     *     }
     *     String str = new String(result, StandardCharsets.UTF_8);
     *     cache[id] = str;
     *     return str;
     * }
     */
    private void injectDecryptMethod(ClassNode cn, String methodName, String cacheFieldName, int maxStrings) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                methodName,
                "(ILjava/lang/String;I)Ljava/lang/String;",
                null,
                null
        );

        InsnList il = mn.instructions;

        // Label cho logic check null
        LabelNode labelNotNull1 = new LabelNode();
        LabelNode labelNotNull2 = new LabelNode();
        
        // 1. if (cache == null)
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, cacheFieldName, "[Ljava/lang/String;"));
        il.add(new JumpInsnNode(Opcodes.IFNONNULL, labelNotNull1));
        
        // cache = new String[MAX_STRINGS];
        il.add(new LdcInsnNode(maxStrings));
        il.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
        il.add(new FieldInsnNode(Opcodes.PUTSTATIC, cn.name, cacheFieldName, "[Ljava/lang/String;"));
        
        il.add(labelNotNull1);

        // 2. if (cache[id] != null) return cache[id];
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, cacheFieldName, "[Ljava/lang/String;"));
        il.add(new VarInsnNode(Opcodes.ILOAD, 0)); // id
        il.add(new InsnNode(Opcodes.AALOAD));
        il.add(new JumpInsnNode(Opcodes.IFNULL, labelNotNull2));
        
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, cacheFieldName, "[Ljava/lang/String;"));
        il.add(new VarInsnNode(Opcodes.ILOAD, 0)); // id
        il.add(new InsnNode(Opcodes.AALOAD));
        il.add(new InsnNode(Opcodes.ARETURN));
        
        il.add(labelNotNull2);

        // 3. byte[] decoded = Base64.getDecoder().decode(encrypted);
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Base64", "getDecoder", "()Ljava/util/Base64$Decoder;", false));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1)); // encrypted (String)
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 3)); // decoded (byte[])

        // 4. byte[] result = new byte[decoded.length];
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new InsnNode(Opcodes.ARRAYLENGTH));
        il.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        il.add(new VarInsnNode(Opcodes.ASTORE, 4)); // result (byte[])

        // 5. int i = 0;
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ISTORE, 5)); // i (int)

        LabelNode loopCondition = new LabelNode();
        LabelNode loopBody = new LabelNode();
        
        il.add(new JumpInsnNode(Opcodes.GOTO, loopCondition));
        il.add(loopBody);

        // result[i] = (byte) (decoded[i] ^ key);
        il.add(new VarInsnNode(Opcodes.ALOAD, 4)); // result
        il.add(new VarInsnNode(Opcodes.ILOAD, 5)); // i
        
        il.add(new VarInsnNode(Opcodes.ALOAD, 3)); // decoded
        il.add(new VarInsnNode(Opcodes.ILOAD, 5)); // i
        il.add(new InsnNode(Opcodes.BALOAD));      // decoded[i]
        
        il.add(new VarInsnNode(Opcodes.ILOAD, 2)); // key
        il.add(new InsnNode(Opcodes.IXOR));        // decoded[i] ^ key
        il.add(new InsnNode(Opcodes.I2B));         // (byte)
        
        il.add(new InsnNode(Opcodes.BASTORE));     // result[i] = ...

        // i++;
        il.add(new IincInsnNode(5, 1));

        il.add(loopCondition);
        il.add(new VarInsnNode(Opcodes.ILOAD, 5)); // i
        il.add(new VarInsnNode(Opcodes.ALOAD, 3)); // decoded
        il.add(new InsnNode(Opcodes.ARRAYLENGTH)); // decoded.length
        il.add(new JumpInsnNode(Opcodes.IF_ICMPLT, loopBody));

        // 6. String str = new String(result, StandardCharsets.UTF_8);
        il.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4)); // result
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;"));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 6)); // str

        // 7. cache[id] = str;
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, cacheFieldName, "[Ljava/lang/String;"));
        il.add(new VarInsnNode(Opcodes.ILOAD, 0)); // id
        il.add(new VarInsnNode(Opcodes.ALOAD, 6)); // str
        il.add(new InsnNode(Opcodes.AASTORE));

        // 8. return str;
        il.add(new VarInsnNode(Opcodes.ALOAD, 6));
        il.add(new InsnNode(Opcodes.ARETURN));

        // Setup limits manually for safety, though COMPUTE_MAXS will recalculate
        mn.maxStack = 6;
        mn.maxLocals = 7;

        cn.methods.add(mn);
    }
}
