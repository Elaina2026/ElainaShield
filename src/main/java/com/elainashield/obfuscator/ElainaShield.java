package com.elainashield.obfuscator;

import com.elainashield.obfuscator.core.JarProcessor;
import com.elainashield.obfuscator.core.ObfuscationConfig;

import java.io.File;
import java.io.InputStream;
import java.util.Properties;

/**
 * ╔══════════════════════════════════════════════════════════════╗
 * ║               ElainaShield Java Obfuscator v1.0             ║
 * ║         Advanced Bytecode Obfuscation Engine                ║
 * ╚══════════════════════════════════════════════════════════════╝
 *
 * Main entry point for the obfuscation tool.
 * Usage: java -jar elaina-shield.jar <input.jar> [output.jar] [options]
 *
 * Features:
 *   - Super Name Mangling (invisible Unicode characters)
 *   - Control Flow Flattening (switch-case dispatcher)
 *   - Junk Code Injection (dead code, opaque predicates)
 */
public class ElainaShield {

    private static String VERSION = "1.0.0"; // fallback

    static {
        try (InputStream is = ElainaShield.class.getClassLoader().getResourceAsStream("version.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                VERSION = props.getProperty("version", "1.0.0");
            }
        } catch (Exception ignored) {
        }
    }

    private static final String BANNER_TEMPLATE = "\n" +
            "  ╔══════════════════════════════════════════════════════╗\n" +
            "  ║       ███████╗██╗      █████╗ ██╗███╗   ██╗ █████╗   ║\n" +
            "  ║       ██╔════╝██║     ██╔══██╗██║████╗  ██║██╔══██╗  ║\n" +
            "  ║       █████╗  ██║     ███████║██║██╔██╗ ██║███████║  ║\n" +
            "  ║       ██╔══╝  ██║     ██╔══██║██║██║╚██╗██║██╔══██║  ║\n" +
            "  ║       ███████╗███████╗██║  ██║██║██║ ╚████║██║  ██║  ║\n" +
            "  ║       ╚══════╝╚══════╝╚═╝  ╚═╝╚═╝╚═╝  ╚═══╝╚═╝  ╚═╝  ║\n" +
            "  ║              S H I E L D   v%-22s   ║\n" +
            "  ║         Advanced Java Bytecode Obfuscator            ║\n" +
            "  ╚══════════════════════════════════════════════════════╝\n";

    public static void main(String[] args) {
        System.out.printf(BANNER_TEMPLATE, VERSION);

        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String inputPath = args[0];
        String outputPath = args.length >= 2 ? args[1] : deriveOutputPath(inputPath);

        File inputFile = new File(inputPath);
        if (!inputFile.exists() || !inputFile.isFile()) {
            System.err.println("[ERROR] Input file not found: " + inputPath);
            System.exit(1);
        }

        if (!inputPath.endsWith(".jar")) {
            System.err.println("[ERROR] Input must be a .jar file");
            System.exit(1);
        }

        // Parse options
        ObfuscationConfig config = parseConfig(args);

        System.out.println("  [*] Input:  " + inputPath);
        System.out.println("  [*] Output: " + outputPath);
        System.out.println("  [*] Config: " + config);
        System.out.println();

        try {
            long startTime = System.currentTimeMillis();

            JarProcessor processor = new JarProcessor(config);
            processor.process(inputFile, new File(outputPath));

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println();
            System.out.println("  ╔══════════════════════════════════════════════╗");
            System.out.println("  ║  [OK] Obfuscation completed successfully!    ║");
            System.out.printf("  ║  [*] Time elapsed: %-25s ║%n", elapsed + "ms");
            System.out.printf("  ║  [*] Output: %-31s ║%n", truncate(outputPath, 31));
            System.out.println("  ╚══════════════════════════════════════════════╝");

        } catch (Exception e) {
            System.err.println();
            System.err.println("  [ERROR] Obfuscation failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static ObfuscationConfig parseConfig(String[] args) {
        ObfuscationConfig config = new ObfuscationConfig();

        for (int i = 2; i < args.length; i++) {
            switch (args[i].toLowerCase()) {
                case "--no-rename":
                    config.setNameManglingEnabled(false);
                    break;
                case "--no-flow":
                    config.setControlFlowEnabled(false);
                    break;
                case "--no-junk":
                    config.setJunkCodeEnabled(false);
                    break;
                case "--no-string":
                    config.setStringEncryptionEnabled(false);
                    break;
                case "--no-outline":
                    config.setOutliningEnabled(false);
                    break;
                case "--no-num":
                    config.setNumberEncryptionEnabled(false);
                    break;
                case "--no-indy":
                    config.setInvokeDynamicEnabled(false);
                    break;
                case "--aggressive":
                    config.setAggressiveMode(true);
                    break;
                case "--keep-main":
                    config.setKeepMainClass(true);
                    break;
                case "--unicode-invisible":
                    config.setNameStyle(ObfuscationConfig.NameStyle.INVISIBLE_UNICODE);
                    break;
                case "--unicode-confuse":
                    config.setNameStyle(ObfuscationConfig.NameStyle.CONFUSING_UNICODE);
                    break;
                case "--seed":
                    if (i + 1 < args.length) {
                        config.setSeed(Long.parseLong(args[++i]));
                    }
                    break;
                case "--libraries":
                    if (i + 1 < args.length) {
                        config.setLibrariesPath(args[++i]);
                    } else {
                        System.err.println("  [ERROR] Missing path for --libraries option.");
                    }
                    break;
                case "--keep-api":
                    if (i + 1 < args.length) {
                        config.addKeepApiPackage(args[++i]);
                    } else {
                        System.err.println("  [ERROR] Missing package for --keep-api option.");
                    }
                    break;
                default:
                    System.err.println("  [WARN] Unknown option: " + args[i]);
            }
        }

        return config;
    }

    private static String deriveOutputPath(String inputPath) {
        int dotIndex = inputPath.lastIndexOf('.');
        if (dotIndex > 0) {
            return inputPath.substring(0, dotIndex) + "-Shield.jar";
        }
        return inputPath + "-Shield";
    }

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return "..." + s.substring(s.length() - maxLen + 3);
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar elaina-shield.jar <input.jar> [output.jar] [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("    --keep-main         Preserve the main class name");
        System.out.println("    --keep-api <pkg>    Preserve classes under a specific API package (can be used multiple times)");
        System.out.println("    --unicode-invisible Use invisible characters for renaming");
        System.out.println("    --unicode-confuse   Use confusing Cyrillic/Greek characters for renaming");
        System.out.println("    --seed <number>     Set random seed for reproducibility");
        System.out.println("    --libraries <path>  Path to external libraries folder for frame computation");
        System.out.println("    --no-rename         Disable name mangling");
        System.out.println("    --no-flow           Disable control flow flattening");
        System.out.println("    --no-junk           Disable junk code injection");
        System.out.println("    --no-string         Disable string encryption");
        System.out.println("    --no-indy           Disable invokedynamic obfuscation");
        System.out.println("    --no-outline        Disable method outlining");
        System.out.println("    --no-num            Disable number encryption");
        System.out.println("    --aggressive        Enable aggressive obfuscation mode");
        System.out.println("    java -jar elaina-shield.jar app.jar");
        System.out.println("    java -jar elaina-shield.jar app.jar secured.jar --aggressive");
        System.out.println("    java -jar elaina-shield.jar app.jar out.jar --keep-main --no-junk");
    }
}
