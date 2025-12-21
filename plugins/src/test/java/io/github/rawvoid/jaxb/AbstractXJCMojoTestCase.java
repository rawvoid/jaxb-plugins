/*
 * Copyright 2025 Rawvoid(https://github.com/rawvoid)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.rawvoid.jaxb;

import com.sun.tools.xjc.Options;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.jvnet.jaxb.maven.AbstractXJCMojo;
import org.jvnet.jaxb.maven.XJCMojo;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author Rawvoid
 */
public abstract class AbstractXJCMojoTestCase {

    protected Path baseDirectory;
    protected Path schemaDirectory;
    protected Path generatedDirectory;
    protected Path classesDirectory;

    protected AbstractXJCMojoTestCase() {
        this.baseDirectory = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().getPath())
            .getParent().getParent();
        this.schemaDirectory = baseDirectory.resolve("src/test/resources/schema");
        this.generatedDirectory = baseDirectory.resolve("target/generated-sources-" + getClass().getSimpleName());
        this.classesDirectory = baseDirectory.resolve("target/generated-classes-" + getClass().getSimpleName());
    }

    @BeforeEach
    public void setUp() throws Exception {
        cleanDirectory(generatedDirectory);
        cleanDirectory(classesDirectory);
    }

    @SuppressWarnings("UnusedReturnValue")
    public List<Class<?>> testExecute(List<String> args, Predicate<Class<?>> classFilter, Consumer consumer) throws Exception {
        var mojo = createMojo();
        configureMojo(mojo, args);
        mojo.execute();
        compileGeneratedJavaFiles();
        var classes = loadGeneratedClasses();
        if (consumer != null) {
            for (var clazz : classes) {
                if (classFilter != null && !classFilter.test(clazz)) continue;
                var sourceFile = generatedDirectory.resolve(clazz.getName()
                    .replace('.', '/') + ".java");
                var source = String.join("\n", Files.readAllLines(sourceFile));
                consumer.accept(source, clazz);
            }
        }
        return classes;
    }


    protected List<Path> getGeneratedJavaFiles() throws IOException {
        return getFiles(generatedDirectory, file -> file.getFileName().toString().endsWith(".java"));
    }

    protected List<Path> getCompiledClassFiles() throws IOException {
        return getFiles(classesDirectory, file -> file.getFileName().toString().endsWith(".class"));
    }

    public String getGeneratePackage() {
        return null;
    }

    public boolean isWriteCode() {
        return true;
    }

    protected AbstractXJCMojo<Options> createMojo() {
        return new XJCMojo();
    }

    protected void configureMojo(final AbstractXJCMojo<Options> mojo, List<String> args) {
        mojo.setProject(new MavenProject());
        var request = new DefaultMavenExecutionRequest();
        @SuppressWarnings("deprecation")
        var mavenSession = new MavenSession(null, null, request, null);
        mojo.setMavenSession(mavenSession);
        mojo.setSchemaDirectory(schemaDirectory.toFile());
        mojo.setGenerateDirectory(generatedDirectory.toFile());
        mojo.setGeneratePackage(getGeneratePackage());
        mojo.setArgs(args);
        mojo.setVerbose(true);
        mojo.setDebug(true);
        mojo.setWriteCode(isWriteCode());
    }

    protected void compileGeneratedJavaFiles() throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();

        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        var fm = compiler.getStandardFileManager(diagnostics, Locale.getDefault(), null);

        var options = getCompileOptions();
        var javaFiles = fm.getJavaFileObjectsFromPaths(getGeneratedJavaFiles());

        var task = compiler.getTask(null, fm, diagnostics, options, null, javaFiles);
        var result = task.call();

        if (result == null || !result) {
            var errorReport = new StringBuilder("Java class compile failed\n");

            for (var diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    errorReport.append("Error on file: ")
                        .append(diagnostic.getSource().getName())
                        .append("\n  Line ")
                        .append(diagnostic.getLineNumber())
                        .append(": ")
                        .append(diagnostic.getMessage(Locale.getDefault()))
                        .append("\n");
                }
            }
            throw new RuntimeException(errorReport.toString());
        }
    }

    protected List<String> getCompileOptions() {
        return List.of(
            "-d", classesDirectory.toAbsolutePath().toString(),
            "-proc:full",
            "-classpath", System.getProperty("java.class.path")
        );
    }

    protected List<Class<?>> loadGeneratedClasses() throws Exception {
        try (var classLoader = new URLClassLoader(new URL[]{classesDirectory.toUri().toURL()}, getClass().getClassLoader())) {
            var classFiles = getCompiledClassFiles();
            return classFiles.stream()
                .map(classesDirectory::relativize)
                .map(path -> path.toString().replace(File.separatorChar, '.'))
                .map(name -> name.substring(0, name.length() - 6)) // 去掉末尾的 ".class"
                .map(className -> {
                    try {
                        return classLoader.loadClass(className);
                    } catch (Exception e) {
                        throw new RuntimeException("class load failed: " + className, e);
                    }
                })
                .collect(Collectors.toList());
        }
    }

    protected List<Path> getFiles(Path directory, Function<Path, Boolean> fileFilter) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return Collections.emptyList();
        }

        List<Path> files = new LinkedList<>();

        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                if (path == null) return FileVisitResult.CONTINUE;
                if (fileFilter == null || fileFilter.apply(path)) {
                    files.add(path);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return files;
    }

    protected void cleanDirectory(Path directory) throws IOException {
        if (Files.notExists(directory)) return;

        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null)
                    throw exc;
                delete(dir);
                return FileVisitResult.CONTINUE;
            }

            public void delete(Path path) throws IOException {
                Objects.requireNonNull(path);
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    if (!Files.isWritable(path)
                        && path.toFile().setWritable(true)) {
                        Files.delete(path);
                    } else {
                        throw e;
                    }
                }
            }
        });
    }

    public interface Consumer {
        void accept(String source, Class<?> clazz) throws Exception;
    }

}
