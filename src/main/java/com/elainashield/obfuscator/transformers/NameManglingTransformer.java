package com.elainashield.obfuscator.transformers;

import com.elainashield.obfuscator.core.ObfuscationConfig;
import com.elainashield.obfuscator.core.ObfuscationContext;
import com.elainashield.obfuscator.utils.NameGenerator;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * ╔════════════════════════════════════════════════════════════════╗
 * ║        Name Mangling Transformer - "Super-Grade"              ║
 * ╠════════════════════════════════════════════════════════════════╣
 * ║ Renames ALL classes, methods, and fields to unreadable names. ║
 * ║ Uses invisible Unicode and confusing lookalike characters.    ║
 * ║ Every build produces completely different names.              ║
 * ╚════════════════════════════════════════════════════════════════╝
 *
 * Phase 1: Scan all classes and build the complete renaming map.
 * Phase 2: Apply the renaming map using ASM's Remapper framework.
 *
 * Protected identifiers (never renamed):
 *   - main(String[]) method
 *   - <init>, <clinit> (constructors/static initializers)
 *   - Methods annotated with common framework annotations
 *   - Native methods
 *   - Enum special methods (values, valueOf)
 */
public class NameManglingTransformer {

    private final ObfuscationConfig config;
    private final ObfuscationContext context;
    private final NameGenerator nameGen;

    /** Methods that must never be renamed */
    private static final Set<String> PROTECTED_METHODS = new HashSet<>(Arrays.asList(
            "<init>", "<clinit>", "main", "values", "valueOf",
            "toString", "hashCode", "equals", "clone", "finalize",
            "compareTo", "iterator", "hasNext", "next",
            "run", "call", "get", "apply", "accept", "test",
            // Bukkit / JavaPlugin Lifecycle
            "onEnable", "onDisable", "onLoad",
            // Bukkit Command API
            "onCommand", "onTabComplete"
    ));



    public NameManglingTransformer(ObfuscationConfig config, ObfuscationContext context, NameGenerator nameGen) {
        this.config = config;
        this.context = context;
        this.nameGen = nameGen;
    }

    /**
     * Phase 1: Analyze all classes and build the complete renaming map.
     * Must be called BEFORE applying the transformation.
     */
    public void buildRenamingMap(List<ClassNode> classes) {
        System.out.println("  [NameMangling] Phase 1: Building renaming map...");

        // 1. Generate obfuscated package root (randomized depth 1-3)
        int pkgDepth = 1 + nameGen.getRandom().nextInt(3);
        String obfuscatedPackage = nameGen.generateObfuscatedPackage(pkgDepth);

        // 2. Map class names
        for (ClassNode cn : classes) {
            if (shouldSkipClass(cn)) {
                System.out.println("    [SKIP] " + cn.name + " (protected)");
                continue;
            }

            String newClassName = obfuscatedPackage + "/" + nameGen.nextClassName();
            context.putClassName(cn.name, newClassName);
            context.incrementClassesRenamed();
        }

        // 3. Map inner class relationships
        for (ClassNode cn : classes) {
            if (cn.innerClasses != null) {
                for (InnerClassNode icn : cn.innerClasses) {
                    if (context.isInJarScope(icn.name) && !context.hasClassMapping(icn.name)
                            && !shouldSkipClassByName(icn.name)) {
                        String newName = obfuscatedPackage + "/" + nameGen.nextClassName();
                        context.putClassName(icn.name, newName);
                        context.incrementClassesRenamed();
                    }
                }
            }
        }

        // 4. Map method names globally by signature to preserve inheritance
        Set<String> globallySkippedMethods = new HashSet<>();
        for (ClassNode cn : classes) {
            for (MethodNode mn : cn.methods) {
                if (shouldSkipMethod(cn, mn)) {
                    globallySkippedMethods.add(mn.name + mn.desc);
                }
            }
        }

        Map<String, String> globalMethodNames = new HashMap<>();
        for (ClassNode cn : classes) {
            for (MethodNode mn : cn.methods) {
                String signature = mn.name + mn.desc;
                if (globallySkippedMethods.contains(signature)) {
                    continue;
                }

                String newName = globalMethodNames.computeIfAbsent(signature, k -> nameGen.nextMethodName());
                context.putMethodName(cn.name, mn.name, mn.desc, newName);
                context.incrementMethodsRenamed();
            }
        }

        // 5. Map field names globally
        Set<String> globallySkippedFields = new HashSet<>();
        for (ClassNode cn : classes) {
            for (FieldNode fn : cn.fields) {
                if (shouldSkipField(cn, fn)) {
                    globallySkippedFields.add(fn.name + fn.desc);
                }
            }
        }

        Map<String, String> globalFieldNames = new HashMap<>();
        for (ClassNode cn : classes) {
            for (FieldNode fn : cn.fields) {
                String signature = fn.name + fn.desc;
                if (globallySkippedFields.contains(signature)) {
                    continue;
                }

                String newName = globalFieldNames.computeIfAbsent(signature, k -> nameGen.nextFieldName());
                context.putFieldName(cn.name, fn.name, fn.desc, newName);
                context.incrementFieldsRenamed();
            }
        }

        System.out.println("  [NameMangling] Renaming map built: " +
                context.getClassNameMap().size() + " classes mapped");
    }

    /**
     * Phase 2: Apply the renaming using ASM's ClassRemapper.
     * Returns a new list of ClassNodes with all names remapped.
     */
    public List<ClassNode> applyRenaming(List<ClassNode> classes) {
        System.out.println("  [NameMangling] Phase 2: Applying renaming...");

        ElainaRemapper remapper = new ElainaRemapper();
        List<ClassNode> remappedClasses = new ArrayList<>();

        for (ClassNode cn : classes) {
            ClassNode remappedNode = new ClassNode(Opcodes.ASM9);
            ClassRemapper classRemapper = new ClassRemapper(remappedNode, remapper);
            cn.accept(classRemapper);
            remappedClasses.add(remappedNode);
        }

        System.out.println("  [NameMangling] Renaming applied to " + remappedClasses.size() + " classes");
        return remappedClasses;
    }

    // ------------------------------------------------------------------
    // Skip Checks
    // ------------------------------------------------------------------

    private boolean shouldSkipClass(ClassNode cn) {
        // Skip if explicitly excluded
        if (context.isClassExcluded(cn.name)) return true;
        
        // Skip common shaded libraries to prevent AbstractMethodError and ServiceLoader issues
        if (isLibraryPackage(cn.name)) {
            return true;
        }

        // Keep main class if configured
        if (config.isKeepMainClass() && cn.name.equals(context.getMainClassInternalName())) {
            return true;
        }

        // Keep Bukkit / BungeeCord / Velocity plugin main classes automatically
        if (cn.superName != null && (
                cn.superName.equals("org/bukkit/plugin/java/JavaPlugin") ||
                cn.superName.equals("org/bukkit/plugin/PluginBase") ||
                cn.superName.equals("net/md_5/bungee/api/plugin/Plugin") ||
                cn.superName.equals("com/velocitypowered/api/plugin/Plugin")
        )) {
            System.out.println("    [SKIP] " + cn.name + " (Plugin Main Class)");
            return true;
        }

        // Skip enum classes (renaming can break them)
        if ((cn.access & Opcodes.ACC_ENUM) != 0) return true;

        // Skip annotation types
        if ((cn.access & Opcodes.ACC_ANNOTATION) != 0) return true;

        // Skip classes not in JAR scope
        if (!context.isInJarScope(cn.name)) return true;

        return false;
    }

    private boolean shouldSkipClassByName(String name) {
        if (context.isClassExcluded(name)) return true;
        if (isLibraryPackage(name)) return true;
        if (config.isKeepMainClass() && name.equals(context.getMainClassInternalName())) return true;
        return false;
    }

    private boolean isLibraryPackage(String name) {
        String lower = name.toLowerCase();
        return lower.contains("/slf4j/") ||
               lower.contains("/hikari/") ||
               lower.contains("/bstats/") ||
               lower.contains("/logback/") ||
               lower.contains("/gson/") ||
               lower.contains("/apache/") ||
               lower.contains("/boostedyaml/") ||
               lower.contains("/yaml/") ||
               lower.contains("/ormlite/");
    }

    private boolean shouldSkipMethod(ClassNode owner, MethodNode mn) {
        // Skip entirely if this class belongs to a shaded library
        if (isLibraryPackage(owner.name)) return true;

        // Never rename constructors or static initializers
        if (mn.name.startsWith("<")) return true;

        // Never rename protected standard methods
        if (PROTECTED_METHODS.contains(mn.name)) return true;

        // Never rename native methods
        if ((mn.access & Opcodes.ACC_NATIVE) != 0) return true;

        // Never rename bridge or synthetic methods generated by compiler
        if ((mn.access & Opcodes.ACC_BRIDGE) != 0) return true;

        // Check for framework annotations (EventHandler, etc.)
        if (mn.visibleAnnotations != null) {
            for (AnnotationNode an : mn.visibleAnnotations) {
                if (an.desc.equals("Lorg/bukkit/event/EventHandler;") ||
                        an.desc.contains("Subscribe")) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean shouldSkipField(ClassNode owner, FieldNode fn) {
        // Skip entirely if this class belongs to a shaded library
        if (isLibraryPackage(owner.name)) return true;

        // Skip enum constants
        if ((owner.access & Opcodes.ACC_ENUM) != 0 &&
                (fn.access & Opcodes.ACC_ENUM) != 0) {
            return true;
        }

        // Skip serialVersionUID
        if ("serialVersionUID".equals(fn.name)) return true;

        return false;
    }

    /**
     * Check if a method is overriding a method from a class/interface
     * outside the JAR scope (e.g., JDK interfaces).
     */
    @SuppressWarnings("unused")
    private boolean isOverridingExternalMethod(ClassNode owner, MethodNode mn) {
        // Check superclass
        if (owner.superName != null && !context.isInJarScope(owner.superName)) {
            // Method might be overriding an external superclass method
            // Be conservative: if superclass is external, skip any non-private method
            if ((mn.access & Opcodes.ACC_PRIVATE) == 0 &&
                    !owner.superName.equals("java/lang/Object")) {
                return true;
            }
        }

        // Check interfaces
        if (owner.interfaces != null) {
            for (String iface : owner.interfaces) {
                if (!context.isInJarScope(iface)) {
                    // Conservative: skip non-private methods if implementing external interface
                    if ((mn.access & Opcodes.ACC_PRIVATE) == 0) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    // ------------------------------------------------------------------
    // Custom Remapper
    // ------------------------------------------------------------------

    /**
     * ASM Remapper implementation that uses our ObfuscationContext
     * to remap all references consistently.
     */
    private class ElainaRemapper extends Remapper {

        @Override
        public String map(String internalName) {
            // Remap class name if we have a mapping
            if (context.hasClassMapping(internalName)) {
                return context.getObfuscatedClassName(internalName);
            }
            return internalName;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            // Don't remap constructors
            if (name.startsWith("<")) return name;

            // Check if we have a mapping for this method
            if (context.hasMethodMapping(owner, name, descriptor)) {
                return context.getObfuscatedMethodName(owner, name, descriptor);
            }

            // Try to find mapping through class hierarchy
            String remappedOwner = map(owner);
            if (!remappedOwner.equals(owner) && context.hasMethodMapping(owner, name, descriptor)) {
                return context.getObfuscatedMethodName(owner, name, descriptor);
            }

            return name;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            if (context.hasFieldMapping(owner, name, descriptor)) {
                return context.getObfuscatedFieldName(owner, name, descriptor);
            }
            return name;
        }
    }
}
