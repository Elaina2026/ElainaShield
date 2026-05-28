package com.elainashield.obfuscator.utils;

import com.elainashield.obfuscator.core.ObfuscationConfig;

import java.util.*;

/**
 * Generates obfuscated names using various Unicode tricks.
 *
 * Strategies:
 * 1. INVISIBLE_UNICODE: Uses zero-width characters that are invisible
 *    but form valid Java identifiers. Extremely hard to read/copy.
 * 2. CONFUSING_UNICODE: Uses Cyrillic/Greek lookalike characters that
 *    look identical to Latin but are different codepoints.
 * 3. MIXED: Randomly combines both strategies.
 *
 * Every invocation with the same seed produces different results because
 * we use a combination of counter + random suffix.
 */
public class NameGenerator {

    private final Random random;
    private final ObfuscationConfig.NameStyle style;

    // Counter to ensure uniqueness
    private int classCounter = 0;
    private int methodCounter = 0;
    private int fieldCounter = 0;

    /**
     * Invisible Unicode characters valid in Java identifiers.
     * These are zero-width or non-printing characters that the JVM accepts.
     */
    private static final char[] INVISIBLE_CHARS = {
            '\u200B', // Zero Width Space (used after first char)
            '\u200C', // Zero Width Non-Joiner
            '\u200D', // Zero Width Joiner
            '\u2060', // Word Joiner
            '\u180E', // Mongolian Vowel Separator
            '\uFEFF', // Zero Width No-Break Space (BOM)
    };

    /**
     * Starter characters for invisible mode - must be valid Java identifier start.
     * We use rare but valid Unicode letters.
     */
    private static final char[] INVISIBLE_STARTERS = {
            '\u01A2', // Ƣ - Latin Letter OI
            '\u0250', // ɐ - Latin Small Letter Turned A
            '\u0251', // ɑ - Latin Small Letter Alpha
            '\u0252', // ɒ - Latin Small Letter Turned Alpha
            '\u0253', // ɓ - Latin Small Letter B with Hook
            '\u0254', // ɔ - Latin Small Letter Open O
            '\u0256', // ɖ - Latin Small Letter D with Tail
            '\u0257', // ɗ - Latin Small Letter D with Hook
    };

    /**
     * Cyrillic characters that look identical to Latin letters.
     * е=e, а=a, о=o, р=p, с=c, у=y, х=x, etc.
     */
    private static final char[] CYRILLIC_LOOKALIKES = {
            '\u0430', // а (looks like 'a')
            '\u0435', // е (looks like 'e')
            '\u043E', // о (looks like 'o')
            '\u0440', // р (looks like 'p')
            '\u0441', // с (looks like 'c')
            '\u0443', // у (looks like 'y')
            '\u0445', // х (looks like 'x')
            '\u0456', // і (looks like 'i')
            '\u0458', // ј (looks like 'j')
            '\u04BB', // һ (looks like 'h')
            '\u049B', // қ (looks like 'k')
            '\u04E1', // ӡ (looks like 'z')
    };

    /**
     * Greek characters for additional confusion.
     */
    private static final char[] GREEK_CHARS = {
            '\u03B1', // α (alpha)
            '\u03B2', // β (beta)
            '\u03B3', // γ (gamma)
            '\u03B4', // δ (delta)
            '\u03B5', // ε (epsilon)
            '\u03B6', // ζ (zeta)
            '\u03B7', // η (eta)
            '\u03B8', // θ (theta)
            '\u03B9', // ι (iota)
            '\u03BA', // κ (kappa)
            '\u03BB', // λ (lambda)
            '\u03BC', // μ (mu)
    };

    /**
     * Additional confusing Unicode characters from various scripts.
     */
    private static final char[] EXOTIC_CHARS = {
            '\u13A0', // Ꭰ Cherokee
            '\u13A1', // Ꭱ
            '\u13A2', // Ꭲ
            '\u13A3', // Ꭳ
            '\u13A4', // Ꭴ
            '\u2C60', // Ⱡ Latin Extended-C
            '\u2C61', // ⱡ
            '\u2C62', // Ɫ
            '\u2C63', // Ᵽ
            '\u2C64', // Ɽ
            '\uA722', // Ꜣ Latin Extended-D
            '\uA724', // Ꜥ
    };

    private final Set<String> usedNames = new HashSet<>();

    public NameGenerator(ObfuscationConfig.NameStyle style, long seed) {
        this.style = style;
        this.random = (seed >= 0) ? new Random(seed) : new Random();
    }

    /**
     * Generate a unique obfuscated class name (without package path).
     * The class name portion only - package will be handled separately.
     */
    public String nextClassName() {
        return generateUniqueName("C", classCounter++, 4, 12);
    }

    /**
     * Generate a unique obfuscated package name segment.
     */
    public String nextPackageName() {
        return generateUniqueName("P", classCounter++, 2, 6);
    }

    /**
     * Generate a unique obfuscated method name.
     */
    public String nextMethodName() {
        return generateUniqueName("M", methodCounter++, 3, 10);
    }

    /**
     * Generate a unique obfuscated field name.
     */
    public String nextFieldName() {
        return generateUniqueName("F", fieldCounter++, 3, 8);
    }

    /**
     * Generate a junk method name that looks realistic but is nonsensical.
     */
    public String nextJunkMethodName() {
        return generateUniqueName("J", methodCounter++, 5, 15);
    }

    private String generateUniqueName(String prefix, int counter, int minLen, int maxLen) {
        String name;
        int attempts = 0;
        do {
            ObfuscationConfig.NameStyle effectiveStyle = style;
            if (style == ObfuscationConfig.NameStyle.MIXED) {
                effectiveStyle = random.nextBoolean()
                        ? ObfuscationConfig.NameStyle.INVISIBLE_UNICODE
                        : ObfuscationConfig.NameStyle.CONFUSING_UNICODE;
            }

            switch (effectiveStyle) {
                case INVISIBLE_UNICODE:
                    name = generateInvisibleName(counter, minLen, maxLen);
                    break;
                case CONFUSING_UNICODE:
                    name = generateConfusingName(counter, minLen, maxLen);
                    break;
                default:
                    name = generateConfusingName(counter, minLen, maxLen);
            }
            attempts++;
            if (attempts > 1000) {
                // Fallback: use prefix + counter to guarantee uniqueness
                name = prefix + "_" + counter + "_" + random.nextInt(999999);
            }
        } while (usedNames.contains(name));

        usedNames.add(name);
        return name;
    }

    /**
     * Generate a name using invisible characters.
     * Structure: [visible starter char] + [invisible char sequence based on counter]
     * The counter is encoded in base-N using invisible chars.
     */
    private String generateInvisibleName(int counter, int minLen, int maxLen) {
        int len = minLen + random.nextInt(maxLen - minLen + 1);
        StringBuilder sb = new StringBuilder();

        // Start with a random visible (but rare) starter character
        sb.append(INVISIBLE_STARTERS[random.nextInt(INVISIBLE_STARTERS.length)]);

        // Encode counter into invisible chars for uniqueness
        int tempCounter = counter + random.nextInt(100);
        int base = INVISIBLE_CHARS.length;
        List<Character> encoded = new ArrayList<>();
        if (tempCounter == 0) {
            encoded.add(INVISIBLE_CHARS[0]);
        }
        while (tempCounter > 0) {
            encoded.add(INVISIBLE_CHARS[tempCounter % base]);
            tempCounter /= base;
        }
        Collections.reverse(encoded);
        for (char c : encoded) {
            sb.append(c);
        }

        // Pad with random invisible chars to desired length
        while (sb.length() < len) {
            sb.append(INVISIBLE_CHARS[random.nextInt(INVISIBLE_CHARS.length)]);
        }

        // Inject a couple more visible starters at random positions for extra confusion
        if (len > 3) {
            int insertPos = 1 + random.nextInt(sb.length() - 1);
            sb.insert(insertPos, INVISIBLE_STARTERS[random.nextInt(INVISIBLE_STARTERS.length)]);
        }

        return sb.toString();
    }

    /**
     * Generate a name using confusing Unicode characters.
     * Mixes Cyrillic lookalikes, Greek letters, and exotic Unicode to create
     * names that look readable but are completely misleading.
     */
    private String generateConfusingName(int counter, int minLen, int maxLen) {
        int len = minLen + random.nextInt(maxLen - minLen + 1);
        StringBuilder sb = new StringBuilder();

        // Build the name from multiple confusing character sets
        char[][] charSets = {CYRILLIC_LOOKALIKES, GREEK_CHARS, EXOTIC_CHARS};

        for (int i = 0; i < len; i++) {
            // Pick a random character set
            char[] charSet = charSets[random.nextInt(charSets.length)];
            sb.append(charSet[random.nextInt(charSet.length)]);
        }

        // Embed counter bits for uniqueness: XOR some positions based on counter
        int hash = (counter * 31 + random.nextInt(256)) & 0x7FFFFFFF;
        for (int i = 0; i < Math.min(3, sb.length()); i++) {
            int pos = (hash >> (i * 4)) % sb.length();
            char[] charSet = charSets[(hash >> (i * 2)) % charSets.length];
            sb.setCharAt(pos, charSet[(hash + i) % charSet.length]);
        }

        return sb.toString();
    }

    /**
     * Generates an obfuscated package structure.
     * Returns something like "ɐ/ɒ/ε" (using Unicode chars as package segments).
     */
    public String generateObfuscatedPackage(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            if (i > 0) sb.append('/');
            sb.append(nextPackageName());
        }
        return sb.toString();
    }

    /**
     * Generates fake but realistic-looking Java identifiers for junk code.
     * These look like real method/field names to further confuse decompilers.
     */
    public String nextFakeRealisticName() {
        String[] prefixes = {"get", "set", "is", "has", "create", "init", "update",
                "process", "handle", "validate", "compute", "check", "parse",
                "build", "load", "save", "find", "resolve", "convert", "apply"};
        String[] suffixes = {"Value", "Data", "Result", "State", "Config", "Info",
                "Item", "Node", "Entry", "Cache", "Buffer", "Token",
                "Flag", "Count", "Index", "Size", "Key", "Type"};

        // Generate using confusing chars to make the "realistic" name subtly wrong
        String prefix = prefixes[random.nextInt(prefixes.length)];
        String suffix = suffixes[random.nextInt(suffixes.length)];
        String base = prefix + suffix;

        // Randomly replace some Latin chars with Cyrillic lookalikes
        StringBuilder sb = new StringBuilder();
        for (char c : base.toCharArray()) {
            if (random.nextInt(3) == 0) {
                char replacement = getCyrillicLookalike(c);
                sb.append(replacement != 0 ? replacement : c);
            } else {
                sb.append(c);
            }
        }

        String name = sb.toString();
        if (usedNames.contains(name)) {
            name = name + nextClassName();
        }
        usedNames.add(name);
        return name;
    }

    private char getCyrillicLookalike(char latin) {
        switch (Character.toLowerCase(latin)) {
            case 'a': return '\u0430';
            case 'e': return '\u0435';
            case 'o': return '\u043E';
            case 'p': return '\u0440';
            case 'c': return '\u0441';
            case 'y': return '\u0443';
            case 'x': return '\u0445';
            case 'i': return '\u0456';
            case 'j': return '\u0458';
            case 'h': return '\u04BB';
            default: return 0;
        }
    }

    public Random getRandom() {
        return random;
    }
}
