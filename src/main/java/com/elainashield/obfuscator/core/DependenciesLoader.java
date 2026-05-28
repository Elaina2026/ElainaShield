package com.elainashield.obfuscator.core;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads external library JARs from a directory into a URLClassLoader.
 * This helps the CustomClassWriter resolve common super classes for external dependencies
 * during the COMPUTE_FRAMES phase.
 */
public class DependenciesLoader {

    private final ClassLoader classLoader;

    public DependenciesLoader(String librariesPath) {
        if (librariesPath == null || librariesPath.isEmpty()) {
            this.classLoader = this.getClass().getClassLoader();
            return;
        }

        File libDir = new File(librariesPath);
        if (!libDir.exists() || !libDir.isDirectory()) {
            System.err.println("  [WARN] Libraries path does not exist or is not a directory: " + librariesPath);
            this.classLoader = this.getClass().getClassLoader();
            return;
        }

        File[] files = libDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
        if (files == null || files.length == 0) {
            this.classLoader = this.getClass().getClassLoader();
            return;
        }

        List<URL> urls = new ArrayList<>();
        for (File file : files) {
            try {
                urls.add(file.toURI().toURL());
            } catch (MalformedURLException e) {
                System.err.println("  [WARN] Failed to load library: " + file.getName());
            }
        }

        if (urls.isEmpty()) {
            this.classLoader = this.getClass().getClassLoader();
        } else {
            System.out.println("  [*] Loaded " + urls.size() + " external libraries for ClassWriter resolution");
            this.classLoader = new URLClassLoader(urls.toArray(new URL[0]), this.getClass().getClassLoader());
        }
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }
}
