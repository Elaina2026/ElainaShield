package com.elainashield.obfuscator.core;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A custom ClassWriter that resolves the TypeNotPresentException during COMPUTE_FRAMES.
 * 
 * ASM's default ClassWriter uses Class.forName() to calculate common super classes.
 * This fails for two reasons during obfuscation:
 * 1. External dependencies (e.g. SLF4J, Bukkit) are not on the obfuscator's classpath.
 * 2. Classes renamed by Name Mangling no longer exist with their original names.
 * 
 * This custom writer falls back to checking the obfuscator's internal map of ClassNodes
 * to manually resolve the class hierarchy, and safely defaults to java/lang/Object 
 * if resolution fails, avoiding crashes.
 */
public class CustomClassWriter extends ClassWriter {
    
    private final Map<String, ClassNode> classMap;
    private final ClassLoader customClassLoader;

    public CustomClassWriter(int flags, Map<String, ClassNode> classMap, ClassLoader customClassLoader) {
        super(flags);
        this.classMap = classMap;
        this.customClassLoader = customClassLoader;
    }

    @Override
    protected ClassLoader getClassLoader() {
        return customClassLoader != null ? customClassLoader : super.getClassLoader();
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        try {
            if (type1 == null || type2 == null) {
                return "java/lang/Object";
            }
            if (type1.equals(type2)) {
                return type1;
            }
            if ("java/lang/Object".equals(type1) || "java/lang/Object".equals(type2)) {
                return "java/lang/Object";
            }

            // Attempt reflection with the custom ClassLoader (supports external libraries)
            try {
                ClassLoader loader = getClassLoader();
                Class<?> c1 = Class.forName(type1.replace('/', '.'), false, loader);
                Class<?> c2 = Class.forName(type2.replace('/', '.'), false, loader);
                if (c1.isAssignableFrom(c2)) {
                    return type1;
                }
                if (c2.isAssignableFrom(c1)) {
                    return type2;
                }
                if (c1.isInterface() || c2.isInterface()) {
                    return "java/lang/Object";
                }
                do {
                    c1 = c1.getSuperclass();
                } while (!c1.isAssignableFrom(c2));
                return c1.getName().replace('.', '/');
            } catch (Exception | NoClassDefFoundError e) {
                // If reflection fails (due to name mangling or missing dependencies),
                // fallback to our custom hierarchy resolver based on ClassNodes.
                return resolveCommonSuperClass(type1, type2);
            }
        } catch (Throwable t) {
            // BULLETPROOF BLOCK: If absolutely anything goes wrong, return Object
            // to guarantee COMPUTE_FRAMES never crashes the pipeline.
            return "java/lang/Object";
        }
    }

    /**
     * Resolves the closest common superclass using the obfuscator's internal ClassNode map.
     */
    private String resolveCommonSuperClass(String type1, String type2) {
        Set<String> superTypes1 = getSuperTypes(type1);
        Set<String> superTypes2 = getSuperTypes(type2);

        // Find the first intersecting superclass (closest to the leaf)
        for (String t1 : superTypes1) {
            if (superTypes2.contains(t1)) {
                return t1;
            }
        }
        
        // Ultimate fallback
        return "java/lang/Object";
    }

    /**
     * Traverses up the class hierarchy to build an ordered set of superclasses.
     */
    private Set<String> getSuperTypes(String type) {
        Set<String> supers = new LinkedHashSet<>();
        supers.add(type);
        
        String current = type;
        while (current != null && !"java/lang/Object".equals(current)) {
            ClassNode cn = classMap.get(current);
            if (cn != null) {
                // If we found the class in our obfuscation context, walk up its superName
                if (cn.superName != null) {
                    current = cn.superName;
                    supers.add(current);
                } else {
                    break;
                }
            } else {
                // If the class is missing (e.g. external library), we can't trace its hierarchy further.
                // We just assume its parent is Object.
                supers.add("java/lang/Object");
                break;
            }
        }
        return supers;
    }
}
