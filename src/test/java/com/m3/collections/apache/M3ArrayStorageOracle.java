/*
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.m3.collections.apache;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Independently compiles the immutable preimages used by this bounded receiver proof. */
public final class M3ArrayStorageOracle implements AutoCloseable {
    private static final String PREFIX = "com.m3.collections.apache.";
    private static final Map<String, String> SOURCE_HASHES = Map.of(
            "iterators.IteratorChain", "f96aa53b71ef74098658fd8ea1de9afc28394c95283d2d1c2f0924086599782d",
            "iterators.UnmodifiableIterator", "d823ac0aa7d3ab645c4b0fdba5cae4349448216a11a672cbcaff9299a31589a4",
            "queue.CircularFifoQueue", "a93526672443ce20662aa44b528e90d1b739e30ed9b33078e1ff3473f64f1e97",
            "iterators.PermutationIterator", "0c91046426a9bcee2555c9d3586d50597178158a7a8a5da795d01b615798e079");

    private final URLClassLoader loader;

    /** Creates original classes from exact UTF-8 preimages; the live owner is never an oracle. */
    public M3ArrayStorageOracle(final Path work) throws Exception {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A JDK is required for the original-source proof");
        }
        final List<Path> sources = new ArrayList<>();
        for (final Map.Entry<String, String> entry : SOURCE_HASHES.entrySet()) {
            final String leaf = entry.getKey().substring(entry.getKey().lastIndexOf('.') + 1) + ".java";
            final byte[] bytes;
            try (InputStream input = M3ArrayStorageOracle.class.getResourceAsStream(
                    "/m3/array-storage-20261019/original/" + leaf + ".txt")) {
                if (input == null) {
                    throw new IllegalStateException("Missing immutable original " + leaf);
                }
                bytes = input.readAllBytes();
            }
            final String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!entry.getValue().equals(actualHash)) {
                throw new IllegalStateException("Original-source drift: " + leaf);
            }
            final Path source = work.resolve("source/" + entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.write(source, bytes);
            sources.add(source);
        }
        final Path classes = work.resolve("classes");
        Files.createDirectories(classes);
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            final List<String> options = Arrays.asList("--release", "21", "-proc:none", "-classpath",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    "-d", classes.toString());
            if (!Boolean.TRUE.equals(compiler.getTask(null, files, diagnostics, options, null,
                    files.getJavaFileObjectsFromPaths(sources)).call())) {
                throw new IllegalStateException("Original source compilation failed: " + diagnostics.getDiagnostics());
            }
        }
        loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, M3ArrayStorageOracle.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    final boolean originalOwner = SOURCE_HASHES.keySet().stream()
                            .anyMatch(owner -> name.equals(PREFIX + owner) || name.startsWith(PREFIX + owner + "$"));
                    if (!originalOwner) {
                        return super.loadClass(name, resolve);
                    }
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
        };
    }

    /** Returns the independently loaded class at the same public binary name. */
    public Class<?> originalClass(final String owner) throws ClassNotFoundException {
        return loader.loadClass(PREFIX + owner);
    }

    /** Creates the pinned original ring, sharing only the JDK Queue interface with the receiver. */
    @SuppressWarnings("unchecked")
    public <E> Queue<E> originalQueue(final int capacity) throws Exception {
        return (Queue<E>) originalClass("queue.CircularFifoQueue").getConstructor(int.class).newInstance(capacity);
    }

    /** Creates an empty pinned original iterator chain. */
    @SuppressWarnings("unchecked")
    public <E> Iterator<E> originalChain() throws Exception {
        return (Iterator<E>) originalClass("iterators.IteratorChain").getConstructor().newInstance();
    }

    /** Creates the pinned original permutation owner from the same collection contract. */
    @SuppressWarnings("unchecked")
    public <E> Iterator<List<E>> originalPermutations(final Collection<? extends E> values) throws Exception {
        try {
            return (Iterator<List<E>>) originalClass("iterators.PermutationIterator")
                    .getConstructor(Collection.class).newInstance(values);
        } catch (final InvocationTargetException exception) {
            final Throwable cause = exception.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw (Error) cause;
        }
    }

    /** Wraps an original chain with its same-loader unmodifiable owner. */
    @SuppressWarnings("unchecked")
    public <E> Iterator<E> originalUnmodifiable(final Iterator<E> iterator) throws Exception {
        return (Iterator<E>) originalClass("iterators.UnmodifiableIterator")
                .getMethod("unmodifiableIterator", Iterator.class).invoke(null, iterator);
    }

    /** Invokes an admission method and preserves the original runtime exception. */
    public static Object invoke(final Object owner, final String name, final Class<?>[] parameters,
            final Object... arguments) throws Exception {
        final Method method = owner.getClass().getMethod(name, parameters);
        try {
            return method.invoke(owner, arguments);
        } catch (final InvocationTargetException exception) {
            final Throwable cause = exception.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw (Error) cause;
        }
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }
}
