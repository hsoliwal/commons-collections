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
package org.apache.commons.collections4;

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
public final class ArrayStorageOracle implements AutoCloseable {
    private static final String PREFIX = "org.apache.commons.collections4.";
    private static final Map<String, String> SOURCE_HASHES = Map.of(
            "bloomfilter.CellExtractor", "f52a40c9586b13bf0c18d176e94e9c76ffea1a7024e0552ba9ac38dc058f405d",
            "iterators.CollatingIterator", "e7f2f8e73e359919631d5f20e60ccd1ba4cc6b2c38f3e2882cea2cc20df882e7",
            "iterators.IteratorChain", "7a87da0b110e1e657d670203c542fa3a00413b03161cc85c5de7e1eda5433a58",
            "iterators.UnmodifiableIterator", "9a00fa5aabf154b4bfe18d5e179e269ad6361623f4e8529473d854cc0818aab8",
            "queue.CircularFifoQueue", "d455d399425c40e2c98c8dab0f6d6b2de58b0df3225cecd1f212f13d5173da1e",
            "iterators.PermutationIterator", "92c273d6b5b85e4bd51aabe559e6b4e0b0a01ec53977f18f0ad10419943c81c7");

    private final URLClassLoader loader;

    /** Creates original classes from exact UTF-8 preimages; the live owner is never an oracle. */
    public ArrayStorageOracle(final Path work) throws Exception {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A JDK is required for the original-source proof");
        }
        final List<Path> sources = new ArrayList<>();
        for (final Map.Entry<String, String> entry : SOURCE_HASHES.entrySet()) {
            final String leaf = entry.getKey().substring(entry.getKey().lastIndexOf('.') + 1) + ".java";
            final byte[] bytes;
            try (InputStream input = ArrayStorageOracle.class.getResourceAsStream(
                    "/org/apache/commons/collections4/original/array-storage-20261019/" + leaf + ".txt")) {
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
        loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, ArrayStorageOracle.class.getClassLoader()) {
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
