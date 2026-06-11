# ElainaShield 🧙‍♀️✨
**Advanced Java Bytecode Obfuscator for Spigot/Paper Plugins**

ElainaShield is a powerful, ASM-based Java obfuscator specifically designed to protect Minecraft plugins. It employs multiple layers of advanced obfuscation techniques to deter decompilation, reverse engineering, and tampering.

![ElainaShield](https://i.imgur.com/8Q5Z2jG.png)

## Features

- **Name Mangling**: Renames classes, methods, and fields to confusing names. Supports Invisible Unicode, Cyrillic/Greek mix, and standard ASCII dictionary.
- **String Encryption**: Encrypts string literals using XOR + Base64 with a local cache to minimize GC overhead.
- **Number Encryption**: Obfuscates integer literals using math expressions and XOR operations.
- **Control Flow Flattening**: Flattens `if/else`, `for`, `while`, and `switch` statements into a massive, unreadable `switch` dispatcher block.
- **Junk Code Injection (Dead Code)**: Injects opaque predicates and random JVM instructions to break decompilers. Includes a strict 60KB safety limit to prevent `Method code too large` errors.
- **InvokeDynamic**: Hides direct method calls using Java's `invokedynamic` instruction, forcing decompilers to fail at resolving method invocations.
- **Method Outlining**: Extracts local blocks of code into synthetic, private helper methods scattered around the class.
- **Spigot/Paper Smart Mode**: Automatically preserves plugin main classes (`JavaPlugin`), event handlers (`@EventHandler`), and Bukkit command structures.
- **API Preservation**: Exclude entire API packages from obfuscation so developers can still hook into your plugin.

## Usage

```bash
java -jar elaina-shield.jar <input.jar> [output.jar] [options]
```

### Options

| Option | Description |
|---|---|
| `--keep-main` | Preserve the plugin's Main Class name (e.g., prevents Paper 1.20.6+ loading issues). |
| `--keep-api <pkg>` | Preserve all classes under a specific API package (e.g. `vn.yourname.api`). Can be used multiple times. |
| `--unicode-invisible` | Use truly invisible Unicode characters (Zero-Width spaces, Hangul fillers) for names. |
| `--unicode-confuse` | Use confusing Cyrillic/Greek characters for names (e.g., `сθᎢ`). |
| `--seed <number>` | Set a fixed random seed for reproducible builds. |
| `--libraries <path>` | Path to a folder containing external libraries (e.g. Spigot API) for `COMPUTE_FRAMES` resolving. |
| `--no-rename` | Disable Name Mangling. |
| `--no-flow` | Disable Control Flow Flattening. |
| `--no-junk` | Disable Junk Code Injection. |
| `--no-string` | Disable String Encryption. |
| `--no-indy` | Disable InvokeDynamic obfuscation. |
| `--no-outline` | Disable Method Outlining. |
| `--no-num` | Disable Number Encryption. |
| `--aggressive` | Enable Aggressive Obfuscation Mode (injects 5x more junk, deeper flattening, thicker math expressions). |

## Examples

**1. Basic Protection (Spigot 1.8 - 1.21)**
```bash
java -jar elaina-shield.jar NexShop-2.2.jar NexShop-Shield.jar
```

**2. Invisible Identifiers & Keep Main Class (Paper 1.20.6+)**
```bash
java -jar elaina-shield.jar MyPlugin.jar MyPlugin-Protected.jar --unicode-invisible --keep-main
```

**3. Maximum Security (Requires Libraries)**
```bash
java -jar elaina-shield.jar MyPlugin.jar MyPlugin-Max.jar --aggressive --unicode-confuse --libraries ./target/libs
```

**4. Public API Preservation**
```bash
java -jar elaina-shield.jar MyCore.jar MyCore-Obf.jar --keep-api vn.myname.core.api
```

## How to build
Ensure you have Maven installed.
```bash
mvn clean package
```
The compiled fat-jar will be available in the `target/` directory.

## Known Limitations & Solutions
- **`VerifyError: Bad local variable type`**: Fixed using strict ASTORE reference slot validation.
- **`ClassFormatError: Method code too large`**: Fixed using the Advanced Size Tracker (methods > 60KB are bypassed).
- **`AbstractMethodError`**: Fixed by automatically scanning interface hierarchies to preserve overridden methods from external dependencies.

> *Elaina Best Waifu ❤ - Protected by the Ashen Witch*
