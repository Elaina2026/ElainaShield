package com.elainashield.obfuscator.transformers;

import com.elainashield.obfuscator.core.ObfuscationConfig;
import com.elainashield.obfuscator.core.ObfuscationContext;
import com.elainashield.obfuscator.utils.NameGenerator;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

public class InvokeDynamicTransformer {

    @SuppressWarnings("unused")
    private final ObfuscationConfig config;
    @SuppressWarnings("unused")
    private final ObfuscationContext context;
    private final NameGenerator nameGen;

    private static final int XOR_KEY = 105;

    public InvokeDynamicTransformer(ObfuscationConfig config, ObfuscationContext context, NameGenerator nameGen) {
        this.config = config;
        this.context = context;
        this.nameGen = nameGen;
    }

    public void transform(List<ClassNode> classes) {
        System.out.println("  [InvokeDynamic] Applying InvokeDynamic Obfuscation...");

        int totalInvocationsObfuscated = 0;
        int totalFieldsObfuscated = 0;

        for (ClassNode cn : classes) {
            if ((cn.access & Opcodes.ACC_INTERFACE) != 0 || (cn.access & Opcodes.ACC_ANNOTATION) != 0) {
                continue;
            }

            boolean modified = false;
            String bsmName = nameGen.nextMethodName();

            Handle bsmHandle = new Handle(
                    Opcodes.H_INVOKESTATIC,
                    cn.name,
                    bsmName,
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
                    false
            );

            for (MethodNode mn : cn.methods) {
                AbstractInsnNode[] insns = mn.instructions.toArray();
                
                for (AbstractInsnNode insn : insns) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode min = (MethodInsnNode) insn;

                        if (min.getOpcode() == Opcodes.INVOKESPECIAL || min.name.startsWith("<")) {
                            continue;
                        }

                        if (min.getOpcode() == Opcodes.INVOKEVIRTUAL ||
                            min.getOpcode() == Opcodes.INVOKESTATIC ||
                            min.getOpcode() == Opcodes.INVOKEINTERFACE) {

                            String payload = min.getOpcode() + "\0" + min.owner + "\0" + min.name + "\0" + min.desc;
                            String encryptedPayload = encrypt(payload);

                            String indyDesc = min.desc;
                            if (min.getOpcode() != Opcodes.INVOKESTATIC) {
                                Type[] args = Type.getArgumentTypes(min.desc);
                                Type returnType = Type.getReturnType(min.desc);
                                Type[] newArgs = new Type[args.length + 1];
                                newArgs[0] = Type.getObjectType(min.owner);
                                System.arraycopy(args, 0, newArgs, 1, args.length);
                                indyDesc = Type.getMethodDescriptor(returnType, newArgs);
                            }

                            InvokeDynamicInsnNode indyNode = new InvokeDynamicInsnNode("invoke", indyDesc, bsmHandle, encryptedPayload);

                            mn.instructions.insertBefore(min, indyNode);
                            mn.instructions.remove(min);

                            modified = true;
                            if ((cn.version & 0xFFFF) < Opcodes.V1_7) {
                                cn.version = (cn.version & 0xFFFF0000) | Opcodes.V1_7;
                            }
                            totalInvocationsObfuscated++;
                        }
                    } else if (insn instanceof FieldInsnNode) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        int opcode = fin.getOpcode();

                        if (opcode == Opcodes.PUTFIELD && mn.name.equals("<init>")) {
                            continue;
                        }
                        if (opcode == Opcodes.PUTSTATIC && mn.name.equals("<clinit>")) {
                            continue;
                        }
                        
                        String payload = opcode + "\0" + fin.owner + "\0" + fin.name + "\0" + fin.desc;
                        String encryptedPayload = encrypt(payload);
                        
                        String indyDesc = "";
                        if (opcode == Opcodes.GETSTATIC) {
                            indyDesc = "()" + fin.desc;
                        } else if (opcode == Opcodes.PUTSTATIC) {
                            indyDesc = "(" + fin.desc + ")V";
                        } else if (opcode == Opcodes.GETFIELD) {
                            indyDesc = "(L" + fin.owner + ";)" + fin.desc;
                        } else if (opcode == Opcodes.PUTFIELD) {
                            indyDesc = "(L" + fin.owner + ";" + fin.desc + ")V";
                        }

                        InvokeDynamicInsnNode indyNode = new InvokeDynamicInsnNode("invoke", indyDesc, bsmHandle, encryptedPayload);
                        mn.instructions.insertBefore(fin, indyNode);
                        mn.instructions.remove(fin);
                        
                        modified = true;
                        if ((cn.version & 0xFFFF) < Opcodes.V1_7) {
                            cn.version = (cn.version & 0xFFFF0000) | Opcodes.V1_7;
                        }
                        totalFieldsObfuscated++;
                    }
                }
            }

            if (modified) {
                injectBootstrapMethod(cn, bsmName);
            }
        }

        System.out.println("  [InvokeDynamic] Converted " + totalInvocationsObfuscated + " method calls to invokedynamic.");
        System.out.println("  [InvokeDynamic] Converted " + totalFieldsObfuscated + " field accesses to invokedynamic.");
    }

    private String encrypt(String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (bytes[i] ^ XOR_KEY);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void injectBootstrapMethod(ClassNode cn, String bsmName) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                bsmName,
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
                null,
                new String[] { "java/lang/Throwable" }
        );

        InsnList il = mn.instructions;

        // Decode Payload -> parts
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Base64", "getDecoder", "()Ljava/util/Base64$Decoder;", false));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 4));

        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ISTORE, 5));

        LabelNode loopCond = new LabelNode();
        LabelNode loopBody = new LabelNode();
        il.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
        
        il.add(loopBody);
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new VarInsnNode(Opcodes.ILOAD, 5));
        il.add(new InsnNode(Opcodes.DUP2));
        il.add(new InsnNode(Opcodes.BALOAD));
        il.add(new IntInsnNode(Opcodes.BIPUSH, XOR_KEY));
        il.add(new InsnNode(Opcodes.IXOR));
        il.add(new InsnNode(Opcodes.I2B));
        il.add(new InsnNode(Opcodes.BASTORE));
        
        il.add(new IincInsnNode(5, 1));
        
        il.add(loopCond);
        il.add(new VarInsnNode(Opcodes.ILOAD, 5));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new InsnNode(Opcodes.ARRAYLENGTH));
        il.add(new JumpInsnNode(Opcodes.IF_ICMPLT, loopBody));

        // parts
        il.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;"));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V", false));
        il.add(new LdcInsnNode("\0"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "split", "(Ljava/lang/String;)[Ljava/lang/String;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 6));

        // opcode
        il.add(new VarInsnNode(Opcodes.ALOAD, 6));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new InsnNode(Opcodes.AALOAD));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false));
        il.add(new VarInsnNode(Opcodes.ISTORE, 7));

        // ownerClass
        il.add(new VarInsnNode(Opcodes.ALOAD, 6));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new InsnNode(Opcodes.AALOAD));
        il.add(new IntInsnNode(Opcodes.BIPUSH, '/'));
        il.add(new IntInsnNode(Opcodes.BIPUSH, '.'));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replace", "(CC)Ljava/lang/String;", false));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "lookupClass", "()Ljava/lang/Class;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 8));

        LabelNode lblMethod = new LabelNode();
        LabelNode lblGetStatic = new LabelNode();
        LabelNode lblPutStatic = new LabelNode();
        LabelNode lblGetField = new LabelNode();
        LabelNode lblPutField = new LabelNode();
        LabelNode finishMh = new LabelNode();

        // if opcode >= INVOKEVIRTUAL -> Method Logic
        il.add(new VarInsnNode(Opcodes.ILOAD, 7));
        il.add(new IntInsnNode(Opcodes.SIPUSH, Opcodes.INVOKEVIRTUAL));
        il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, lblMethod));

        // --- FIELD LOGIC ---
        // Class<?> fieldTypeClass = MethodType.fromMethodDescriptorString("()" + parts[3], caller.lookupClass().getClassLoader()).returnType();
        il.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new LdcInsnNode("()"));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false));
        il.add(new VarInsnNode(Opcodes.ALOAD, 6));
        il.add(new InsnNode(Opcodes.ICONST_3));
        il.add(new InsnNode(Opcodes.AALOAD));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "lookupClass", "()Ljava/lang/Class;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodType", "fromMethodDescriptorString", "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodType", "returnType", "()Ljava/lang/Class;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 9)); // fieldTypeClass

        il.add(new VarInsnNode(Opcodes.ILOAD, 7));
        il.add(new IntInsnNode(Opcodes.SIPUSH, Opcodes.GETSTATIC));
        il.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, lblGetStatic));

        il.add(new VarInsnNode(Opcodes.ILOAD, 7));
        il.add(new IntInsnNode(Opcodes.SIPUSH, Opcodes.PUTSTATIC));
        il.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, lblPutStatic));

        il.add(new VarInsnNode(Opcodes.ILOAD, 7));
        il.add(new IntInsnNode(Opcodes.SIPUSH, Opcodes.GETFIELD));
        il.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, lblGetField));

        il.add(new JumpInsnNode(Opcodes.GOTO, lblPutField));

        // GETSTATIC
        il.add(lblGetStatic);
        il.add(new VarInsnNode(Opcodes.ALOAD, 0)); // caller
        il.add(new VarInsnNode(Opcodes.ALOAD, 8)); // ownerClass
        il.add(new VarInsnNode(Opcodes.ALOAD, 6)); // parts
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.AALOAD));
        il.add(new VarInsnNode(Opcodes.ALOAD, 9)); // fieldTypeClass
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findStaticGetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", false));
        il.add(new JumpInsnNode(Opcodes.GOTO, finishMh));

        // PUTSTATIC
        il.add(lblPutStatic);
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 8));
        il.add(new VarInsnNode(Opcodes.ALOAD, 6));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.AALOAD));
        il.add(new VarInsnNode(Opcodes.ALOAD, 9));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findStaticSetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", false));
        il.add(new JumpInsnNode(Opcodes.GOTO, finishMh));

        // GETFIELD
        il.add(lblGetField);
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 8));
        il.add(new VarInsnNode(Opcodes.ALOAD, 6));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.AALOAD));
        il.add(new VarInsnNode(Opcodes.ALOAD, 9));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findGetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", false));
        il.add(new JumpInsnNode(Opcodes.GOTO, finishMh));

        // PUTFIELD
        il.add(lblPutField);
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 8));
        il.add(new VarInsnNode(Opcodes.ALOAD, 6));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.AALOAD));
        il.add(new VarInsnNode(Opcodes.ALOAD, 9));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findSetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", false));
        il.add(new JumpInsnNode(Opcodes.GOTO, finishMh));

        // --- METHOD LOGIC ---
        il.add(lblMethod);
        il.add(new VarInsnNode(Opcodes.ALOAD, 6));
        il.add(new InsnNode(Opcodes.ICONST_3));
        il.add(new InsnNode(Opcodes.AALOAD));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "lookupClass", "()Ljava/lang/Class;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodType", "fromMethodDescriptorString", "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 9)); // targetType

        LabelNode lblStaticMethod = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ILOAD, 7));
        il.add(new IntInsnNode(Opcodes.SIPUSH, Opcodes.INVOKESTATIC));
        il.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, lblStaticMethod));

        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 8));
        il.add(new VarInsnNode(Opcodes.ALOAD, 6));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.AALOAD));
        il.add(new VarInsnNode(Opcodes.ALOAD, 9));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        il.add(new JumpInsnNode(Opcodes.GOTO, finishMh));

        il.add(lblStaticMethod);
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 8));
        il.add(new VarInsnNode(Opcodes.ALOAD, 6));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.AALOAD));
        il.add(new VarInsnNode(Opcodes.ALOAD, 9));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));

        il.add(finishMh);
        il.add(new VarInsnNode(Opcodes.ASTORE, 10)); // mh

        // ConstantCallSite
        il.add(new TypeInsnNode(Opcodes.NEW, "java/lang/invoke/ConstantCallSite"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new VarInsnNode(Opcodes.ALOAD, 10));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "asType", "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/invoke/ConstantCallSite", "<init>", "(Ljava/lang/invoke/MethodHandle;)V", false));
        il.add(new InsnNode(Opcodes.ARETURN));

        mn.maxStack = 6;
        mn.maxLocals = 11;

        cn.methods.add(mn);
    }
}
