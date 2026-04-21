/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.gradle.internal.dependencies.patches.hadoop;

import org.gradle.api.artifacts.transform.CacheableTransform;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;

/**
 * Strips the shaded {@code org.eclipse.jetty:jetty-http} classes, SPI descriptor and Maven metadata from
 * {@code hadoop-client-runtime-*.jar}. The Hadoop uber-jar bundles Jetty so Hadoop's embedded HTTP server can run; ORC
 * file I/O never loads that server, so the shaded jetty-http bytecode is unreachable here and produces spurious
 * "use of vulnerable org.eclipse.jetty:jetty-http" hits in coordinate-agnostic scanners. Other shaded Jetty modules
 * (jetty-util, jetty-io, etc.) are left intact because removing them is unnecessary for the coordinate the scanner
 * flags and because they are depended on by Jetty modules we are not stripping.
 *
 * Other jars pass through unchanged. The class is a Gradle {@link TransformAction} so stripping happens once per
 * resolved artifact and is cached by Gradle.
 */
@CacheableTransform
public abstract class HadoopJettyHttpStripper implements TransformAction<TransformParameters.None> {

    private static final Pattern TARGET_JAR = Pattern.compile("^hadoop-client-runtime-.*\\.jar$");

    private static final String SHADED_JETTY_HTTP_PACKAGE = "org/apache/hadoop/shaded/org/eclipse/jetty/http/";
    private static final String JETTY_HTTP_MAVEN_METADATA = "META-INF/maven/org.eclipse.jetty/jetty-http/";
    private static final String JETTY_HTTP_SPI_DESCRIPTOR =
        "META-INF/services/org.apache.hadoop.shaded.org.eclipse.jetty.http.HttpFieldPreEncoder";

    @Classpath
    @InputArtifact
    public abstract Provider<FileSystemLocation> getInputArtifact();

    @Override
    public void transform(@NotNull TransformOutputs outputs) {
        File inputFile = getInputArtifact().get().getAsFile();
        if (TARGET_JAR.matcher(inputFile.getName()).find() == false) {
            outputs.file(getInputArtifact());
            return;
        }
        System.out.println("Stripping shaded org.eclipse.jetty:jetty-http from " + inputFile.getName());
        File outputFile = outputs.file(inputFile.getName().replace(".jar", "-jetty-http-stripped.jar"));
        stripJar(inputFile, outputFile);
    }

    private static void stripJar(File inputFile, File outputFile) {
        try (JarFile src = new JarFile(inputFile); JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputFile))) {
            Enumeration<JarEntry> entries = src.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (shouldStrip(name)) {
                    continue;
                }
                jos.putNextEntry(new JarEntry(name));
                try (InputStream is = src.getInputStream(entry)) {
                    is.transferTo(jos);
                }
                jos.closeEntry();
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static boolean shouldStrip(String entryName) {
        return entryName.startsWith(SHADED_JETTY_HTTP_PACKAGE)
            || entryName.startsWith(JETTY_HTTP_MAVEN_METADATA)
            || entryName.equals(JETTY_HTTP_SPI_DESCRIPTOR);
    }
}
