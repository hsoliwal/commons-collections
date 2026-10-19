/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.m3.collections.apache.bloomfilter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.LongPredicate;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Original-source callback and representation contracts for the private primitive owner. */
class M3SparseBloomFilterArrayStorageTest {
    private static final String OWNER = "com.m3.collections.apache.bloomfilter.SparseBloomFilter";
    private static final Map<String, String> ORIGINALS = Map.of(
            "SparseBloomFilter", "701a3785df9c0ba1dc6a8ff9e39f10a10727ab4ca3b52366b9f184aa2e812fc7",
            "BitMaps", "3fb3ffee8d8bb198d7a34a38c0deed0297a8fcd5216475c45416148fb75fa614");
    private static URLClassLoader originalLoader;
    private static Class<?> originalClass;
    private static long observations;

    @TempDir
    static Path originalWork;

    private static final class Marker extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @BeforeAll
    static void compileOriginal() throws Exception {
        final Path classes = originalWork.resolve("classes");
        Files.createDirectories(classes);
        final List<Path> sources = new ArrayList<>();
        for (final Map.Entry<String, String> entry : ORIGINALS.entrySet()) {
            final byte[] bytes;
            try (InputStream input = M3SparseBloomFilterArrayStorageTest.class.getResourceAsStream(
                    "/m3/sparse-bloom-arrays-20261019/original/" + entry.getKey() + ".java.txt")) {
                if (input == null) {
                    throw new IllegalStateException("Missing sealed original " + entry.getKey());
                }
                bytes = input.readAllBytes();
            }
            assertEquals(entry.getValue(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            final Path source = originalWork.resolve("source/" + entry.getKey() + ".java");
            Files.createDirectories(source.getParent());
            Files.write(source, bytes);
            sources.add(source);
        }
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A JDK is required for the original-source oracle");
        }
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            final List<String> options = Arrays.asList("--release", "21", "-g", "-Xlint:all", "-Werror", "-proc:none",
                    "-classpath", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    "-d", classes.toString());
            assertTrue(compiler.getTask(null, files, null, options, null,
                    files.getJavaFileObjectsFromPaths(sources)).call());
        }
        originalLoader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, M3SparseBloomFilterArrayStorageTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    final boolean owned = ORIGINALS.keySet().stream().anyMatch(leaf ->
                            name.equals("com.m3.collections.apache.bloomfilter." + leaf)
                                    || name.startsWith("com.m3.collections.apache.bloomfilter." + leaf + "$"));
                    if (!owned) {
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
        originalClass = originalLoader.loadClass(OWNER);
        assertNotSame(SparseBloomFilter.class, originalClass);
    }

    @AfterAll
    static void closeOriginal() throws Exception {
        if (originalLoader != null) {
            originalLoader.close();
        }
    }

    private static BloomFilter<?> original(final Shape shape) {
        try {
            return (BloomFilter<?>) originalClass.getConstructor(Shape.class).newInstance(shape);
        } catch (final ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static List<Integer> indices(final BloomFilter<?> filter) {
        final List<Integer> result = new ArrayList<>();
        assertTrue(filter.processIndices(value -> {
            result.add(value);
            return true;
        }));
        return result;
    }

    private static Object outcome(final BloomFilter<?> filter, final Function<BloomFilter<?>, Object> action) {
        try {
            return action.apply(filter);
        } catch (final RuntimeException failure) {
            return failure.getClass().getName() + ":" + failure.getMessage();
        }
    }

    private static void equalState(final BloomFilter<?> before, final BloomFilter<?> after, final boolean bitmaps) {
        observations += 5;
        assertSame(before.getShape(), after.getShape());
        assertEquals(before.cardinality(), after.cardinality());
        assertEquals(before.isEmpty(), after.isEmpty());
        assertEquals(before.characteristics(), after.characteristics());
        assertEquals(indices(before), indices(after));
        if (bitmaps) {
            observations++;
            assertArrayEquals(before.asBitMapArray(), after.asBitMapArray());
        }
    }

    @Test
    void ordinaryMergeContainsAndIndependentCopiesMatchOriginal() {
        final Shape shape = Shape.fromKM(3, 2049);
        for (int seed = 0; seed < 6; seed++) {
            final Random random = new Random(90173L + seed);
            final BloomFilter<?> before = original(shape);
            final BloomFilter<?> after = new SparseBloomFilter(shape);
            for (int step = 0; step < 1500; step++) {
                final int[] added = random.ints(random.nextInt(7), 0, 2049).toArray();
                final int probe = random.nextInt(2049);
                if (step % 41 == 0) {
                    before.clear();
                    after.clear();
                }
                observations += 2;
                assertEquals(before.merge(IndexExtractor.fromIndexArray(added)), after.merge(IndexExtractor.fromIndexArray(added)));
                assertEquals(before.contains(IndexExtractor.fromIndexArray(probe)), after.contains(IndexExtractor.fromIndexArray(probe)));
                equalState(before, after, true);
                if (step % 97 == 0) {
                    final BloomFilter<?> copyBefore = before.copy();
                    final BloomFilter<?> copyAfter = after.copy();
                    equalState(copyBefore, copyAfter, true);
                    assertSame(shape, copyAfter.getShape());
                    final List<Integer> retained = indices(after);
                    copyBefore.clear();
                    copyAfter.clear();
                    copyBefore.merge(IndexExtractor.fromIndexArray(7));
                    copyAfter.merge(IndexExtractor.fromIndexArray(7));
                    equalState(copyBefore, copyAfter, true);
                    assertEquals(retained, indices(after));
                }
            }
        }
    }

    @Test
    void delayedValidationAndExtractorProtocolRetainOriginalPartialState() {
        final Shape shape = Shape.fromKM(2, 257);
        final int[][] tapes = {{-1, 257}, {257, -1}, {Integer.MIN_VALUE}, {Integer.MAX_VALUE},
                {0, 256, 256, -1}, {5, 7}, {257, 5, -100}, {}};
        for (final int[] tape : tapes) {
            final BloomFilter<?> before = original(shape);
            final BloomFilter<?> after = new SparseBloomFilter(shape);
            final Function<BloomFilter<?>, Object> merge = filter -> filter.merge((IndexExtractor) consumer -> {
                for (final int value : tape) {
                    assertTrue(consumer.test(value));
                }
                return false;
            });
            observations++;
            assertEquals(outcome(before, merge), outcome(after, merge));
            equalState(before, after, false);
            equalState(before.copy(), after.copy(), false);
            for (final int value : tape) {
                observations++;
                assertEquals(before.contains(IndexExtractor.fromIndexArray(value)), after.contains(IndexExtractor.fromIndexArray(value)));
            }
            observations++;
            assertEquals(outcome(before, filter -> Arrays.toString(filter.asBitMapArray())),
                    outcome(after, filter -> Arrays.toString(filter.asBitMapArray())));
            final List<Long> beforeCalls = new ArrayList<>();
            final List<Long> afterCalls = new ArrayList<>();
            assertFalse(before.processBitMaps(value -> {
                beforeCalls.add(value);
                return false;
            }));
            assertFalse(after.processBitMaps(value -> {
                afterCalls.add(value);
                return false;
            }));
            assertEquals(beforeCalls, afterCalls);
        }
        final List<Function<BloomFilter<?>, Object>> nulls = Arrays.asList(
                filter -> filter.merge((IndexExtractor) null), filter -> filter.merge((BitMapExtractor) null),
                filter -> filter.merge((Hasher) null), filter -> filter.merge((BloomFilter<?>) null),
                filter -> filter.contains((IndexExtractor) null), filter -> filter.contains((BitMapExtractor) null),
                filter -> filter.processIndices((IntPredicate) null), filter -> filter.processBitMaps((LongPredicate) null));
        for (final Function<BloomFilter<?>, Object> action : nulls) {
            observations++;
            assertEquals(outcome(original(shape), action), outcome(new SparseBloomFilter(shape), action));
        }
    }

    private static void mutation(final BloomFilter<?> filter, final int mode, final Marker marker, final List<Object> trace) {
        switch (mode) {
        case 0:
            filter.merge(IndexExtractor.fromIndexArray(200));
            break;
        case 1:
            filter.merge(IndexExtractor.fromIndexArray(1));
            break;
        case 2:
            filter.clear();
            break;
        case 3:
            filter.clear();
            filter.clear();
            filter.merge(IndexExtractor.fromIndexArray(1, 7));
            break;
        case 4:
            throw marker;
        case 5:
            filter.merge(IndexExtractor.fromIndexArray(257));
            break;
        case 6:
            trace.add(outcome(filter, owner -> owner.merge(IndexExtractor.fromIndexArray(257, -1))));
            break;
        default:
            throw new AssertionError(mode);
        }
    }

    private static List<Object> callbackTrace(final Function<Shape, BloomFilter<?>> factory, final boolean bitmap,
            final int[] initial, final int mode, final int trigger, final boolean accepted) {
        final BloomFilter<?> filter = factory.apply(Shape.fromKM(2, 257));
        filter.merge(IndexExtractor.fromIndexArray(initial));
        final List<Object> trace = new ArrayList<>();
        final Marker marker = new Marker();
        final int[] calls = {0};
        try {
            if (bitmap) {
                trace.add(filter.processBitMaps(value -> {
                    trace.add("b:" + value);
                    if (++calls[0] == trigger) {
                        mutation(filter, mode, marker, trace);
                    }
                    return accepted;
                }));
            } else {
                trace.add(filter.processIndices(value -> {
                    trace.add("i:" + value);
                    if (++calls[0] == trigger) {
                        mutation(filter, mode, marker, trace);
                    }
                    return accepted;
                }));
            }
        } catch (final RuntimeException failure) {
            trace.add(failure.getClass().getName() + ":" + failure.getMessage());
            trace.add(failure == marker);
        }
        trace.add(indices(filter));
        trace.add(filter.cardinality());
        return trace;
    }

    @Test
    void streamAndIteratorCallbackEpochBoundariesMatchOriginal() {
        final int[][] starts = {{}, {1}, {1, 64}, {64, 128}, {1, 129, 255}, {1, 200, 256}};
        for (final boolean bitmap : new boolean[] {false, true}) {
            for (final int[] initial : starts) {
                for (int mode = 0; mode < 7; mode++) {
                    for (int trigger = 1; trigger <= 5; trigger++) {
                        for (final boolean accepted : new boolean[] {false, true}) {
                            observations++;
                            assertEquals(callbackTrace(M3SparseBloomFilterArrayStorageTest::original, bitmap, initial, mode, trigger, accepted),
                                    callbackTrace(SparseBloomFilter::new, bitmap, initial, mode, trigger, accepted),
                                    "bitmap=" + bitmap + " initial=" + Arrays.toString(initial) + " mode=" + mode
                                            + " trigger=" + trigger + " accepted=" + accepted);
                        }
                    }
                }
            }
        }
    }

    private static Object field(final Object owner, final String name) throws ReflectiveOperationException {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static int checkTree(final int slot, final long low, final long high, final int[] keys,
            final int[] left, final int[] right, final byte[] heights, final boolean[] seen) {
        if (slot == -1) {
            return -1;
        }
        observations += 4;
        assertTrue(slot >= 0 && slot < seen.length);
        assertFalse(seen[slot]);
        seen[slot] = true;
        assertTrue(keys[slot] > low && keys[slot] < high);
        final int a = checkTree(left[slot], low, keys[slot], keys, left, right, heights, seen);
        final int b = checkTree(right[slot], keys[slot], high, keys, left, right, heights, seen);
        assertTrue(Math.abs(a - b) <= 1);
        assertEquals(Math.max(a, b) + 1, heights[slot]);
        return Math.max(a, b) + 1;
    }

    private static void checkStorage(final SparseBloomFilter filter) throws ReflectiveOperationException {
        final Object store = field(filter, "indices");
        final int[] keys = (int[]) field(store, "keys");
        final int size = (Integer) field(store, "size");
        assertEquals(filter.cardinality(), size);
        for (final Field field : store.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                observations++;
                assertTrue(field.getType().isPrimitive() || field.getType().isArray() && field.getType().getComponentType().isPrimitive(),
                        "retained owner lane must be primitive: " + field);
            }
        }
        final boolean[] seen = new boolean[size];
        checkTree((Integer) field(store, "root"), Long.MIN_VALUE, Long.MAX_VALUE, keys,
                (int[]) field(store, "left"), (int[]) field(store, "right"), (byte[]) field(store, "heights"), seen);
        for (final boolean occupied : seen) {
            observations++;
            assertTrue(occupied);
        }
        final int[] next = (int[]) field(store, "next");
        int current = (Integer) field(store, "first");
        long previous = Long.MIN_VALUE;
        int count = 0;
        int last = -1;
        while (current != -1) {
            observations += 2;
            assertTrue(count++ < size);
            assertTrue(keys[current] > previous);
            previous = keys[current];
            last = current;
            current = next[current];
        }
        assertEquals(size, count);
        assertEquals(last, field(store, "last"));
        if (size == 0) {
            for (final String lane : Arrays.asList("keys", "left", "right", "next", "heights")) {
                assertEquals(0, java.lang.reflect.Array.getLength(field(store, lane)));
            }
        }
    }

    @Test
    void logarithmicTreeOwnershipCopyAndClearHaveNoRetainedBoxes() throws ReflectiveOperationException {
        final Shape shape = Shape.fromKM(2, 8193);
        for (int order = 0; order < 3; order++) {
            final SparseBloomFilter filter = new SparseBloomFilter(shape);
            for (int index = 0; index < 8192; index++) {
                final int value = order == 0 ? index : order == 1 ? 8191 - index : index % 2 == 0 ? index / 2 : 8191 - index / 2;
                filter.merge(IndexExtractor.fromIndexArray(value));
            }
            checkStorage(filter);
            assertEquals(8192, filter.cardinality());
            final SparseBloomFilter copy = filter.copy();
            checkStorage(copy);
            final Object sourceStore = field(filter, "indices");
            final Object copyStore = field(copy, "indices");
            for (final String lane : Arrays.asList("keys", "left", "right", "next", "heights")) {
                assertNotSame(field(sourceStore, lane), field(copyStore, lane));
            }
            filter.clear();
            checkStorage(filter);
            assertEquals(8192, copy.cardinality());
            for (int cycle = 0; cycle < 128; cycle++) {
                filter.merge(IndexExtractor.fromIndexArray(0, 2, 4, 6, 8, 10, 12, 14, 16));
                checkStorage(filter);
                filter.clear();
                checkStorage(filter);
            }
        }
    }

    public static void main(final String[] args) throws Exception {
        originalWork = Path.of(args[0]);
        compileOriginal();
        try {
            final M3SparseBloomFilterArrayStorageTest tests = new M3SparseBloomFilterArrayStorageTest();
            tests.ordinaryMergeContainsAndIndependentCopiesMatchOriginal();
            tests.delayedValidationAndExtractorProtocolRetainOriginalPartialState();
            tests.streamAndIteratorCallbackEpochBoundariesMatchOriginal();
            tests.logarithmicTreeOwnershipCopyAndClearHaveNoRetainedBoxes();
            System.out.println("SPARSE_BLOOM_ORIGINAL_CONTRACT_PASS deterministic_operations=9000 callback_cases=840 observations=" + observations);
        } finally {
            closeOriginal();
        }
    }
}
