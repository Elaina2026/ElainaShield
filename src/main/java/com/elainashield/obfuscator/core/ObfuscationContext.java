package com.elainashield.obfuscator.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains the global mapping context for obfuscation.
 * Tracks all original → obfuscated name mappings to ensure consistency
 * across the entire JAR (e.g., a method call in class A to class B's
 * method must use B's obfuscated method name).
 *
 * Thread-safe for potential future parallel processing.
 */
public class ObfuscationContext {

    /** Maps original class internal names → obfuscated internal names */
    private final Map<String, String> classNameMap = new ConcurrentHashMap<>();

    /** Maps "owner.name.desc" → obfuscated method name */
    private final Map<String, String> methodNameMap = new ConcurrentHashMap<>();

    /** Maps "owner.name.desc" → obfuscated field name */
    private final Map<String, String> fieldNameMap = new ConcurrentHashMap<>();

    /** Set of classes that should NOT be renamed (e.g., main class) */
    private final Set<String> excludedClasses = ConcurrentHashMap.newKeySet();

    /** Set of methods that should NOT be renamed */
    private final Set<String> excludedMethods = ConcurrentHashMap.newKeySet();

    /** The main class internal name from MANIFEST.MF */
    private String mainClassInternalName;

    /** All known class internal names in the JAR (for scope checking) */
    private final Set<String> jarClassNames = ConcurrentHashMap.newKeySet();

    /** Statistics */
    private int classesRenamed = 0;
    private int methodsRenamed = 0;
    private int fieldsRenamed = 0;
    private int methodsFlattened = 0;
    private int junkMethodsInjected = 0;
    private int junkFieldsInjected = 0;

    // ------------------------------------------------------------------
    // Class Name Mapping
    // ------------------------------------------------------------------

    public void putClassName(String original, String obfuscated) {
        classNameMap.put(original, obfuscated);
    }

    public String getObfuscatedClassName(String original) {
        return classNameMap.getOrDefault(original, original);
    }

    public boolean hasClassMapping(String original) {
        return classNameMap.containsKey(original);
    }

    public Map<String, String> getClassNameMap() {
        return Collections.unmodifiableMap(classNameMap);
    }

    // ------------------------------------------------------------------
    // Method Name Mapping
    // ------------------------------------------------------------------

    public static String methodKey(String owner, String name, String desc) {
        return owner + "." + name + "." + desc;
    }

    public void putMethodName(String owner, String name, String desc, String obfuscated) {
        methodNameMap.put(methodKey(owner, name, desc), obfuscated);
    }

    public String getObfuscatedMethodName(String owner, String name, String desc) {
        String key = methodKey(owner, name, desc);
        return methodNameMap.getOrDefault(key, name);
    }

    public boolean hasMethodMapping(String owner, String name, String desc) {
        return methodNameMap.containsKey(methodKey(owner, name, desc));
    }

    // ------------------------------------------------------------------
    // Field Name Mapping
    // ------------------------------------------------------------------

    public static String fieldKey(String owner, String name, String desc) {
        return owner + "." + name + "." + desc;
    }

    public void putFieldName(String owner, String name, String desc, String obfuscated) {
        fieldNameMap.put(fieldKey(owner, name, desc), obfuscated);
    }

    public String getObfuscatedFieldName(String owner, String name, String desc) {
        String key = fieldKey(owner, name, desc);
        return fieldNameMap.getOrDefault(key, name);
    }

    public boolean hasFieldMapping(String owner, String name, String desc) {
        return fieldNameMap.containsKey(fieldKey(owner, name, desc));
    }

    // ------------------------------------------------------------------
    // Exclusions
    // ------------------------------------------------------------------

    public void excludeClass(String internalName) {
        excludedClasses.add(internalName);
    }

    public boolean isClassExcluded(String internalName) {
        return excludedClasses.contains(internalName);
    }

    public void excludeMethod(String key) {
        excludedMethods.add(key);
    }

    public boolean isMethodExcluded(String owner, String name, String desc) {
        return excludedMethods.contains(methodKey(owner, name, desc));
    }

    // ------------------------------------------------------------------
    // JAR Scope
    // ------------------------------------------------------------------

    public void addJarClassName(String internalName) {
        jarClassNames.add(internalName);
    }

    public boolean isInJarScope(String internalName) {
        return jarClassNames.contains(internalName);
    }

    public Set<String> getJarClassNames() {
        return Collections.unmodifiableSet(jarClassNames);
    }

    // ------------------------------------------------------------------
    // Main Class
    // ------------------------------------------------------------------

    public String getMainClassInternalName() {
        return mainClassInternalName;
    }

    public void setMainClassInternalName(String mainClassInternalName) {
        this.mainClassInternalName = mainClassInternalName;
    }

    // ------------------------------------------------------------------
    // Statistics
    // ------------------------------------------------------------------

    public synchronized void incrementClassesRenamed() { classesRenamed++; }
    public synchronized void incrementMethodsRenamed() { methodsRenamed++; }
    public synchronized void incrementFieldsRenamed() { fieldsRenamed++; }
    public synchronized void incrementMethodsFlattened() { methodsFlattened++; }
    public synchronized void addJunkMethods(int count) { junkMethodsInjected += count; }
    public synchronized void addJunkFields(int count) { junkFieldsInjected += count; }

    public void printStatistics() {
        System.out.println("  ┌────────────────────────────────────────────┐");
        System.out.println("  │           Obfuscation Statistics           │");
        System.out.println("  ├────────────────────────────────────────────┤");
        System.out.printf("  │  Classes renamed:        %-18d│%n", classesRenamed);
        System.out.printf("  │  Methods renamed:        %-18d│%n", methodsRenamed);
        System.out.printf("  │  Fields renamed:         %-18d│%n", fieldsRenamed);
        System.out.printf("  │  Methods flattened:      %-18d│%n", methodsFlattened);
        System.out.printf("  │  Junk methods injected:  %-18d│%n", junkMethodsInjected);
        System.out.printf("  │  Junk fields injected:   %-18d│%n", junkFieldsInjected);
        System.out.println("  └────────────────────────────────────────────┘");
    }

    /**
     * Remaps a type descriptor, replacing all class references with their
     * obfuscated names. E.g., "Lcom/example/Foo;" → "Lα/β/γ;"
     */
    public String remapDescriptor(String descriptor) {
        if (descriptor == null) return null;

        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < descriptor.length()) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int semicolonIdx = descriptor.indexOf(';', i);
                if (semicolonIdx > 0) {
                    String className = descriptor.substring(i + 1, semicolonIdx);
                    String remapped = getObfuscatedClassName(className);
                    result.append('L').append(remapped).append(';');
                    i = semicolonIdx + 1;
                } else {
                    result.append(c);
                    i++;
                }
            } else if (c == '[') {
                result.append('[');
                i++;
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }
}
