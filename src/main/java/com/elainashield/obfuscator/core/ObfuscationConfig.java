package com.elainashield.obfuscator.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration object holding all obfuscation settings.
 * Parsed from command-line arguments.
 */
public class ObfuscationConfig {

    public enum NameStyle {
        /** Uses zero-width and invisible Unicode characters */
        INVISIBLE_UNICODE,
        /** Uses visually confusing Unicode characters (Cyrillic lookalikes, etc.) */
        CONFUSING_UNICODE,
        /** Mix of both styles randomly */
        MIXED
    }

    private boolean nameManglingEnabled = true;
    private boolean controlFlowEnabled = true;
    private boolean junkCodeEnabled = true;
    private boolean stringEncryptionEnabled = true;
    private boolean invokeDynamicEnabled = true;
    private boolean numberEncryptionEnabled = true;
    private boolean outliningEnabled = true;
    private boolean aggressiveMode = false;
    private boolean keepMainClass = false;
    private NameStyle nameStyle = NameStyle.MIXED;
    private long seed = -1; // -1 means use random seed
    private String librariesPath = null;
    private final List<String> keepApiPackages = new ArrayList<>();

    public boolean isNameManglingEnabled() {
        return nameManglingEnabled;
    }

    public void setNameManglingEnabled(boolean nameManglingEnabled) {
        this.nameManglingEnabled = nameManglingEnabled;
    }

    public boolean isControlFlowEnabled() {
        return controlFlowEnabled;
    }

    public void setControlFlowEnabled(boolean controlFlowEnabled) {
        this.controlFlowEnabled = controlFlowEnabled;
    }

    public boolean isJunkCodeEnabled() {
        return junkCodeEnabled;
    }

    public void setJunkCodeEnabled(boolean junkCodeEnabled) {
        this.junkCodeEnabled = junkCodeEnabled;
    }

    public boolean isStringEncryptionEnabled() {
        return stringEncryptionEnabled;
    }

    public void setStringEncryptionEnabled(boolean stringEncryptionEnabled) {
        this.stringEncryptionEnabled = stringEncryptionEnabled;
    }

    public boolean isInvokeDynamicEnabled() {
        return invokeDynamicEnabled;
    }

    public void setInvokeDynamicEnabled(boolean invokeDynamicEnabled) {
        this.invokeDynamicEnabled = invokeDynamicEnabled;
    }

    public boolean isNumberEncryptionEnabled() {
        return numberEncryptionEnabled;
    }

    public void setNumberEncryptionEnabled(boolean numberEncryptionEnabled) {
        this.numberEncryptionEnabled = numberEncryptionEnabled;
    }

    public boolean isOutliningEnabled() {
        return outliningEnabled;
    }

    public void setOutliningEnabled(boolean outliningEnabled) {
        this.outliningEnabled = outliningEnabled;
    }

    public boolean isAggressiveMode() {
        return aggressiveMode;
    }

    public void setAggressiveMode(boolean aggressiveMode) {
        this.aggressiveMode = aggressiveMode;
    }

    public boolean isKeepMainClass() {
        return keepMainClass;
    }

    public void setKeepMainClass(boolean keepMainClass) {
        this.keepMainClass = keepMainClass;
    }

    public NameStyle getNameStyle() {
        return nameStyle;
    }

    public void setNameStyle(NameStyle nameStyle) {
        this.nameStyle = nameStyle;
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public String getLibrariesPath() {
        return librariesPath;
    }

    public void setLibrariesPath(String librariesPath) {
        this.librariesPath = librariesPath;
    }

    public void addKeepApiPackage(String pkg) {
        String normalized = pkg.replace('.', '/').replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        this.keepApiPackages.add(normalized);
    }

    public List<String> getKeepApiPackages() {
        return keepApiPackages;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("NameMangling=").append(nameManglingEnabled ? "ON" : "OFF");
        sb.append(", ControlFlow=").append(controlFlowEnabled ? "ON" : "OFF");
        sb.append(", JunkCode=").append(junkCodeEnabled ? "ON" : "OFF");
        sb.append(", StringEncrypt=").append(stringEncryptionEnabled ? "ON" : "OFF");
        sb.append(", InvokeDynamic=").append(invokeDynamicEnabled ? "ON" : "OFF");
        sb.append(", NumberEncrypt=").append(numberEncryptionEnabled ? "ON" : "OFF");
        sb.append(", Mode=").append(aggressiveMode ? "AGGRESSIVE" : "NORMAL");
        sb.append(", NameStyle=").append(nameStyle);
        if (keepMainClass) sb.append(", KeepMain=YES");
        if (seed >= 0) sb.append(", Seed=").append(seed);
        if (librariesPath != null) sb.append(", Libs=").append(librariesPath);
        if (!keepApiPackages.isEmpty()) sb.append(", KeepAPI=").append(keepApiPackages);
        return sb.toString();
    }
}
