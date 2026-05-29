package com.elainashield.obfuscator.core;

import com.elainashield.obfuscator.transformers.ControlFlowTransformer;
import com.elainashield.obfuscator.transformers.InvokeDynamicTransformer;
import com.elainashield.obfuscator.transformers.JunkCodeTransformer;
import com.elainashield.obfuscator.transformers.NameManglingTransformer;
import com.elainashield.obfuscator.transformers.NumberEncryptionTransformer;
import com.elainashield.obfuscator.transformers.OutliningTransformer;
import com.elainashield.obfuscator.transformers.StringEncryptionTransformer;
import com.elainashield.obfuscator.utils.NameGenerator;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.ZipEntry;

/**
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║              JAR Processor - Obfuscation Pipeline                ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║ Orchestrates the complete obfuscation workflow:                  ║
 * ║   1. Read input JAR and parse all .class files into ClassNodes   ║
 * ║   2. Build the obfuscation context (scope, main class, etc.)     ║
 * ║   3. Apply transformers in sequence:                         ║
 * ║      a. String Encryption (encrypt literals, cache them)          ║
 * ║      b. Number Encryption (encrypt numeric constants)             ║
 * ║      c. Junk Code Injection (add fake code before renaming)       ║
 * ║      c. Control Flow Flattening (restructure methods)             ║
 * ║      d. Name Mangling (rename everything)                         ║
 * ║      e. Invoke Dynamic (hide method calls)                        ║
 * ║   4. Write the obfuscated classes into output JAR                ║
 * ║   5. Copy non-class resources unchanged                          ║
 * ╚══════════════════════════════════════════════════════════════════╝
 */
public class JarProcessor {

    private final ObfuscationConfig config;

    public JarProcessor(ObfuscationConfig config) {
        this.config = config;
    }

    /**
     * Process the input JAR and write the obfuscated output JAR.
     */
    public void process(File inputJar, File outputJar) throws IOException {
        System.out.println("  [Pipeline] Starting obfuscation pipeline...");
        System.out.println();

        // --- Step 1: Read JAR ---
        System.out.println("  ═══════════════════════════════════════════");
        System.out.println("  STEP 1: Reading input JAR");
        System.out.println("  ═══════════════════════════════════════════");

        List<ClassNode> classes = new ArrayList<>();
        Map<String, byte[]> resources = new LinkedHashMap<>(); // Non-class entries
        Manifest manifest = null;
        String mainClassName = null;

        try (JarInputStream jis = new JarInputStream(new FileInputStream(inputJar))) {
            manifest = jis.getManifest();

            // Extract main class from manifest
            if (manifest != null) {
                Attributes attrs = manifest.getMainAttributes();
                if (attrs != null) {
                    String mainClass = attrs.getValue(Attributes.Name.MAIN_CLASS);
                    if (mainClass != null) {
                        mainClassName = mainClass.replace('.', '/');
                        System.out.println("  [*] Main class detected: " + mainClass);
                    }
                }
            }

            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                String name = entry.getName();
                byte[] data = readAllBytes(jis);

                if (name.endsWith(".class")) {
                    try {
                        ClassReader cr = new ClassReader(data);
                        ClassNode cn = new ClassNode(Opcodes.ASM9);
                        cr.accept(cn, ClassReader.EXPAND_FRAMES);
                        classes.add(cn);
                    } catch (Exception e) {
                        // If we can't parse a class, keep it as a resource
                        System.out.println("    [WARN] Could not parse class: " + name +
                                " (" + e.getMessage() + ")");
                        resources.put(name, data);
                    }
                } else if (!name.equals("META-INF/MANIFEST.MF")) {
                    resources.put(name, data);
                }
            }
        }

        System.out.println("  [*] Read " + classes.size() + " classes, " +
                resources.size() + " resources");

        if (classes.isEmpty()) {
            System.err.println("  [ERROR] No classes found in JAR!");
            return;
        }

        // --- Step 2: Build context ---
        System.out.println();
        System.out.println("  ═══════════════════════════════════════════");
        System.out.println("  STEP 2: Building obfuscation context");
        System.out.println("  ═══════════════════════════════════════════");

        ObfuscationContext context = new ObfuscationContext();

        // Register all classes in JAR scope
        for (ClassNode cn : classes) {
            context.addJarClassName(cn.name);
        }

        // Set main class
        if (mainClassName != null) {
            context.setMainClassInternalName(mainClassName);
            if (config.isKeepMainClass()) {
                context.excludeClass(mainClassName);
                System.out.println("  [*] Main class excluded from renaming: " + mainClassName);
            }
        }

        // Initialize name generator with seed
        long seed = config.getSeed();
        if (seed < 0) {
            seed = System.nanoTime() ^ System.currentTimeMillis();
        }
        NameGenerator nameGen = new NameGenerator(config.getNameStyle(), seed);
        System.out.println("  [*] Name generator initialized (seed=" + seed + ")");

        // --- Step 3: Apply transformers ---
        System.out.println();
        System.out.println("  ═══════════════════════════════════════════");
        System.out.println("  STEP 3: Applying obfuscation transformers");
        System.out.println("  ═══════════════════════════════════════════");

        // 3a. String Encryption (must be done FIRST)
        if (config.isStringEncryptionEnabled()) {
            System.out.println();
            new StringEncryptionTransformer(config, context, nameGen).transform(classes);
        } else {
            System.out.println("  [StringEncryption] DISABLED");
        }

        // 3b. Number Encryption
        if (config.isNumberEncryptionEnabled()) {
            System.out.println();
            new NumberEncryptionTransformer(config, context).transform(classes);
        } else {
            System.out.println("  [NumberEncryption] DISABLED");
        }

        // 3c. Safe Outlining (must be done before control flow flattening)
        if (config.isOutliningEnabled()) {
            System.out.println();
            new OutliningTransformer(config, context, nameGen).transform(classes);
        } else {
            System.out.println("  [Outlining] DISABLED");
        }

        // 3d. Junk Code Injection (must be done BEFORE renaming)
        if (config.isJunkCodeEnabled()) {
            System.out.println();
            JunkCodeTransformer junkTransformer = new JunkCodeTransformer(config, context, nameGen);
            junkTransformer.transform(classes);
        } else {
            System.out.println("  [JunkCode] DISABLED");
        }

        // 3d. Control Flow Flattening (must be done before renaming)
        if (config.isControlFlowEnabled()) {
            System.out.println();
            new ControlFlowTransformer(config, context, nameGen).transform(classes);
        } else {
            System.out.println("  [ControlFlow] DISABLED");
        }

        // 3e. Name Mangling (must be done LAST before Indy)
        if (config.isNameManglingEnabled()) {
            System.out.println();
            NameManglingTransformer nameTransformer = new NameManglingTransformer(config, context, nameGen);
            nameTransformer.buildRenamingMap(classes);
            classes = nameTransformer.applyRenaming(classes);
        } else {
            System.out.println("  [NameMangling] DISABLED");
        }

        // 3f. Invoke Dynamic (must be done after Name Mangling)
        if (config.isInvokeDynamicEnabled()) {
            System.out.println();
            InvokeDynamicTransformer indyTransformer = new InvokeDynamicTransformer(config, context, nameGen);
            indyTransformer.transform(classes);
        } else {
            System.out.println("  [InvokeDynamic] DISABLED");
        }

        // --- Step 4: Write output JAR ---
        System.out.println();
        System.out.println("  ═══════════════════════════════════════════");
        System.out.println("  STEP 4: Writing output JAR");
        System.out.println("  ═══════════════════════════════════════════");

        // Update manifest with remapped main class
        if (manifest == null) {
            manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        }

        if (mainClassName != null && config.isNameManglingEnabled()) {
            String remappedMainClass = context.getObfuscatedClassName(mainClassName);
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS,
                    remappedMainClass.replace('/', '.'));
            System.out.println("  [*] Main class remapped: " +
                    mainClassName.replace('/', '.') + " -> " +
                    remappedMainClass.replace('/', '.'));
        }

        // Ensure output directory exists
        File parentDir = outputJar.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputJar), manifest)) {
            // Build class map for CustomClassWriter superclass resolution
            Map<String, ClassNode> classMap = new HashMap<>();
            for (ClassNode cn : classes) {
                classMap.put(cn.name, cn);
            }
            
            // Load external libraries if specified
            DependenciesLoader depsLoader = new DependenciesLoader(config.getLibrariesPath());
            ClassLoader customClassLoader = depsLoader.getClassLoader();

            // Write obfuscated classes
            int classesWritten = 0;
            for (ClassNode cn : classes) {
                try {
                    ClassWriter cw = new CustomClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, classMap, customClassLoader);
                    cn.accept(cw);
                    byte[] bytecode = cw.toByteArray();

                    String entryName = cn.name + ".class";
                    jos.putNextEntry(new ZipEntry(entryName));
                    jos.write(bytecode);
                    jos.closeEntry();
                    classesWritten++;
                } catch (Exception e) {
                    System.err.println("    [ERROR] Crash in ClassNode.accept for class: " + cn.name);
                    for (MethodNode mn : cn.methods) {
                        try {
                            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
                            mn.accept(cw);
                        } catch (Exception ex) {
                            System.err.println("      [ERROR] Method causing crash: " + mn.name + mn.desc);
                            // Print instructions
                            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                                System.err.println("        " + insn.getClass().getSimpleName() + " : " + insn.getOpcode());
                            }
                        }
                    }
                    System.out.println("    [WARN] COMPUTE_FRAMES failed for " + cn.name +
                            " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "), retrying without frames...");
                    e.printStackTrace(System.out);
                    try {
                        ClassWriter cw = new CustomClassWriter(ClassWriter.COMPUTE_MAXS, classMap, customClassLoader);
                        cn.accept(cw);
                        byte[] bytecode = cw.toByteArray();

                        String entryName = cn.name + ".class";
                        jos.putNextEntry(new ZipEntry(entryName));
                        jos.write(bytecode);
                        jos.closeEntry();
                        classesWritten++;
                    } catch (Exception e2) {
                        System.err.println("    [ERROR] Failed to write class " + cn.name +
                                ": " + e2.getMessage());
                    }
                }
            }
            System.out.println("  [*] Wrote " + classesWritten + " obfuscated classes");

            // Write resources unchanged
            int resourcesWritten = 0;
            for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
                String resourceName = entry.getKey();

                // Remap resource paths for renamed classes (e.g., .properties files)
                // Keep resources as-is for now
                jos.putNextEntry(new ZipEntry(resourceName));
                jos.write(entry.getValue());
                jos.closeEntry();
                resourcesWritten++;
            }
            System.out.println("  [*] Copied " + resourcesWritten + " resources");
        }

        // --- Step 5: Print statistics ---
        System.out.println();
        System.out.println("  ═══════════════════════════════════════════");
        System.out.println("  STEP 5: Summary");
        System.out.println("  ═══════════════════════════════════════════");
        context.printStatistics();

        // Print file size comparison
        long inputSize = inputJar.length();
        long outputSize = outputJar.length();
        double ratio = (double) outputSize / inputSize;
        System.out.printf("  [*] Input size:  %,d bytes%n", inputSize);
        System.out.printf("  [*] Output size: %,d bytes (%.1fx)%n", outputSize, ratio);
    }

    /**
     * Read all bytes from an InputStream (Java 8 compatible).
     */
    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        return bos.toByteArray();
    }

}
