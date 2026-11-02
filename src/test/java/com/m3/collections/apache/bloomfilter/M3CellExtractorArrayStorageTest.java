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
package com.m3.collections.apache.bloomfilter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.m3.collections.apache.M3ArrayStorageOracle;

/** The immutable original class and a separate frequency oracle qualify the new lanes. */
class M3CellExtractorArrayStorageTest {
    @TempDir
    static Path work;

    private static M3ArrayStorageOracle oracle;
    private static Class<?> originalType;
    private static Class<?> originalPredicate;

    @BeforeAll
    static void compileOriginal() throws Exception {
        oracle = new M3ArrayStorageOracle(work);
        originalType = oracle.originalClass("bloomfilter.CellExtractor");
        originalPredicate = oracle.originalClass("bloomfilter.CellExtractor$CellPredicate");
    }

    @AfterAll
    static void closeOriginal() throws Exception {
        if (oracle != null) {
            oracle.close();
        }
    }

    private static final class View {
        private final IndexExtractor extractor;
        private final boolean original;

        private View(final IndexExtractor extractor, final boolean original) {
            this.extractor = extractor;
            this.original = original;
        }

        private boolean cells(final CellExtractor.CellPredicate consumer) throws Exception {
            if (!original) {
                return ((CellExtractor) extractor).processCells(consumer);
            }
            final Object predicate = consumer == null ? null : Proxy.newProxyInstance(
                    originalPredicate.getClassLoader(), new Class<?>[] {originalPredicate},
                    (proxy, method, arguments) -> consumer.test((Integer) arguments[0], (Integer) arguments[1]));
            try {
                return (Boolean) originalType.getMethod("processCells", originalPredicate).invoke(extractor, predicate);
            } catch (final InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception) {
                    throw (Exception) failure.getCause();
                }
                throw (Error) failure.getCause();
            }
        }

        private List<String> snapshot() throws Exception {
            final List<String> result = new ArrayList<>();
            assertTrue(cells((index, count) -> result.add(index + ":" + count)));
            return result;
        }
    }

    private static View create(final boolean original, final IndexExtractor input) throws Exception {
        final IndexExtractor extractor = original
                ? (IndexExtractor) originalType.getMethod("from", IndexExtractor.class).invoke(null, input)
                : CellExtractor.from(input);
        return new View(extractor, original);
    }

    private static View input(final boolean original, final int[] values) throws Exception {
        return create(original, consumer -> {
            for (final int value : values) {
                if (!consumer.test(value)) {
                    return false;
                }
            }
            return true;
        });
    }

    @Test
    void preservesSignedOrderingDuplicatesAndEveryAvlRotationAgainstOriginal() throws Exception {
        final Random random = new Random(0x6d335f63656c6cL);
        for (int scenario = 0; scenario < 400; scenario++) {
            final int[] values = new int[scenario * 3];
            final TreeMap<Integer, Integer> expected = new TreeMap<>();
            for (int index = 0; index < values.length; index++) {
                final int value;
                switch (scenario % 6) {
                case 0:
                    value = index;
                    break;
                case 1:
                    value = -index;
                    break;
                case 2:
                    value = index % 2 == 0 ? index : -index;
                    break;
                case 3:
                    value = random.nextInt(33) - 16;
                    break;
                case 4:
                    value = index % 2 == 0 ? Integer.MIN_VALUE + index : Integer.MAX_VALUE - index;
                    break;
                default:
                    value = random.nextInt();
                    break;
                }
                values[index] = value;
                expected.merge(value, 1, Integer::sum);
            }
            final View original = input(true, values);
            final View candidate = input(false, values);
            final List<String> frequencies = new ArrayList<>();
            expected.forEach((index, count) -> frequencies.add(index + ":" + count));
            assertEquals(frequencies, original.snapshot(), "original scenario " + scenario);
            assertEquals(frequencies, candidate.snapshot(), "candidate scenario " + scenario);
            assertArrayEquals(original.extractor.asIndexArray(), candidate.extractor.asIndexArray());
            assertLanes(candidate.extractor);
        }
    }

    @Test
    void populationRemainsLazyAndEmptyInputsRetryWhileNonemptyInputsCache() throws Exception {
        for (final boolean original : new boolean[] {true, false}) {
            final AtomicInteger calls = new AtomicInteger();
            final View empty = create(original, consumer -> {
                calls.incrementAndGet();
                return true;
            });
            assertEquals(0, calls.get());
            assertTrue(empty.cells(null));
            assertArrayEquals(new int[0], empty.extractor.asIndexArray());
            assertTrue(empty.extractor.processIndices(null));
            assertEquals(3, calls.get());
            assertSame(empty.extractor, empty.extractor.uniqueIndices());

            calls.set(0);
            final View nonempty = create(original, consumer -> {
                calls.incrementAndGet();
                consumer.test(42);
                consumer.test(-1);
                return false;
            });
            assertEquals(0, calls.get());
            final int[] first = nonempty.extractor.asIndexArray();
            final int[] second = nonempty.extractor.asIndexArray();
            assertNotSame(first, second);
            first[0] = 99;
            assertArrayEquals(new int[] {-1, 42}, second);
            assertEquals(Arrays.asList("-1:1", "42:1"), nonempty.snapshot());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void inputFailureKeepsANonemptyPrefixAndRetriesAnEmptyPrefix() throws Exception {
        for (final boolean original : new boolean[] {true, false}) {
            final RuntimeException failure = new IllegalStateException("source failure");
            final AtomicInteger calls = new AtomicInteger();
            final View prefix = create(original, consumer -> {
                calls.incrementAndGet();
                consumer.test(7);
                consumer.test(-3);
                consumer.test(7);
                throw failure;
            });
            assertSame(failure, assertThrows(IllegalStateException.class, prefix::snapshot));
            assertEquals(Arrays.asList("-3:1", "7:2"), prefix.snapshot());
            assertEquals(1, calls.get());
            final View empty = create(original, consumer -> {
                calls.incrementAndGet();
                throw failure;
            });
            assertSame(failure, assertThrows(IllegalStateException.class, empty::snapshot));
            assertSame(failure, assertThrows(IllegalStateException.class, empty::snapshot));
            assertEquals(3, calls.get());
        }
    }

    @Test
    void callbackFalseExceptionsNullsAndIndexProjectionMatchOriginal() throws Exception {
        for (final boolean original : new boolean[] {true, false}) {
            final View view = input(original, new int[] {9, 1, 4, 4, -1});
            final List<Integer> seen = new ArrayList<>();
            assertFalse(view.cells((index, count) -> {
                seen.add(index);
                return index != 4;
            }));
            assertEquals(Arrays.asList(-1, 1, 4), seen);
            final RuntimeException failure = new IllegalArgumentException("consumer failure");
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> view.cells((i, c) -> {
                throw failure;
            })));
            assertThrows(NullPointerException.class, () -> view.cells(null));
            assertThrows(NullPointerException.class, () -> view.extractor.processIndices(null));
            assertEquals(Arrays.asList("-1:1", "1:1", "4:2", "9:1"), view.snapshot());
            final View nullSource = create(original, null);
            assertThrows(NullPointerException.class, nullSource::snapshot);
            assertThrows(NullPointerException.class, nullSource.extractor::asIndexArray);
        }
    }

    @Test
    void retainedSourcePredicatePreservesStructuralFailFastAndEarlyFalse() throws Exception {
        for (int shape = 0; shape < 7; shape++) {
            assertEquals(retainedMutationTrace(true, shape), retainedMutationTrace(false, shape), "shape " + shape);
        }
    }

    private static List<String> retainedMutationTrace(final boolean original, final int shape) throws Exception {
        final AtomicReference<IntPredicate> retained = new AtomicReference<>();
        final View view = create(original, consumer -> {
            retained.set(consumer);
            consumer.test(1);
            consumer.test(3);
            consumer.test(5);
            return true;
        });
        final List<String> trace = new ArrayList<>();
        try {
            final boolean result = view.cells((index, count) -> {
                trace.add(index + ":" + count);
                if (shape == 0 && index == 1) {
                    retained.get().test(2);
                } else if (shape == 1 && index == 1) {
                    retained.get().test(2);
                    return false;
                } else if (shape == 2 && index == 5) {
                    retained.get().test(7);
                } else if (shape == 3 && index == 1) {
                    retained.get().test(3);
                } else if (shape == 4 && index == 1) {
                    retained.get().test(0);
                } else if (shape == 5 && index == 5) {
                    retained.get().test(0);
                } else if (shape == 6 && index == 1) {
                    for (int added = 10; added < 80; added++) {
                        retained.get().test(added);
                    }
                }
                return true;
            });
            trace.add("returned:" + result);
        } catch (final ConcurrentModificationException expected) {
            trace.add("ConcurrentModificationException");
        }
        trace.addAll(view.snapshot());
        return trace;
    }

    @Test
    void reentrantSourceSeesAnAlreadyPopulatedPrefixWithoutReplaying() throws Exception {
        assertEquals(reentrantTrace(true), reentrantTrace(false));
    }

    private static List<String> reentrantTrace(final boolean original) throws Exception {
        final List<String> trace = new ArrayList<>();
        final AtomicReference<View> current = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final View view = create(original, consumer -> {
            calls.incrementAndGet();
            consumer.test(7);
            trace.add(Arrays.toString(current.get().extractor.asIndexArray()));
            consumer.test(2);
            consumer.test(7);
            trace.add(Arrays.toString(current.get().extractor.asIndexArray()));
            return true;
        });
        current.set(view);
        trace.addAll(view.snapshot());
        trace.add("calls:" + calls.get());
        return trace;
    }

    @Test
    void primitiveCountsKeepIntOverflowAndZeroCountsExactlyAsTheOriginal() throws Exception {
        for (final boolean original : new boolean[] {true, false}) {
            final AtomicReference<IntPredicate> retained = new AtomicReference<>();
            final View view = create(original, consumer -> {
                retained.set(consumer);
                return consumer.test(42);
            });
            view.extractor.asIndexArray();
            setCount(view, Integer.MAX_VALUE);
            retained.get().test(42);
            assertEquals(Arrays.asList("42:" + Integer.MIN_VALUE), view.snapshot());
            setCount(view, -1);
            retained.get().test(42);
            assertEquals(Arrays.asList("42:0"), view.snapshot());
            assertArrayEquals(new int[] {42}, view.extractor.asIndexArray());
        }
    }

    private static void setCount(final View view, final int value) throws Exception {
        if (!view.original) {
            ((int[]) field(view.extractor, "counts"))[0] = value;
            return;
        }
        final Map<?, ?> cells = (Map<?, ?>) field(view.extractor, "counterCells");
        final Object cell = cells.values().iterator().next();
        final Field count = cell.getClass().getDeclaredField("count");
        count.setAccessible(true);
        count.setInt(cell, value);
    }

    @Test
    void denseMonotonicAdmissionRetainsOnlyPrimitiveLanesAndBalancedHeight() throws Exception {
        final int[] values = new int[32_768];
        for (int index = 0; index < values.length; index++) {
            values[index] = index;
        }
        final View candidate = input(false, values);
        assertArrayEquals(values, candidate.extractor.asIndexArray());
        assertLanes(candidate.extractor);
        for (final Field field : candidate.extractor.getClass().getDeclaredFields()) {
            if (!field.isSynthetic()) {
                assertTrue(field.getType().isPrimitive()
                        || field.getType().isArray() && field.getType().getComponentType().isPrimitive(),
                        "Retained object storage: " + field);
            }
        }
    }

    @Test
    void publicFactoryAndDefaultMethodDescriptorsRemainIdentical() {
        final List<String> original = descriptors(originalType);
        final List<String> current = descriptors(CellExtractor.class);
        assertEquals(original, current);
    }

    private static List<String> descriptors(final Class<?> type) {
        final List<String> result = new ArrayList<>();
        for (final Method method : type.getDeclaredMethods()) {
            if (!method.isSynthetic()) {
                result.add(method.getModifiers() + ":" + method.getName() + ":"
                        + method.getReturnType().getName() + ":"
                        + Arrays.toString(Arrays.stream(method.getParameterTypes()).map(Class::getName).toArray()));
            }
        }
        result.sort(String::compareTo);
        return result;
    }

    private static Object field(final Object target, final String name) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void assertLanes(final Object target) throws Exception {
        final int size = (Integer) field(target, "size");
        final int[] indices = (int[]) field(target, "indices");
        final int[] left = (int[]) field(target, "left");
        final int[] right = (int[]) field(target, "right");
        final int[] next = (int[]) field(target, "next");
        final byte[] heights = (byte[]) field(target, "heights");
        assertEquals(indices.length, ((int[]) field(target, "counts")).length);
        assertEquals(indices.length, left.length);
        assertEquals(indices.length, right.length);
        assertEquals(indices.length, next.length);
        assertEquals(indices.length, heights.length);
        final boolean[] visited = new boolean[size];
        assertTree((Integer) field(target, "root"), Long.MIN_VALUE, Long.MAX_VALUE,
                indices, left, right, heights, visited);
        for (final boolean present : visited) {
            assertTrue(present);
        }
        long previous = Long.MIN_VALUE;
        int count = 0;
        for (int slot = (Integer) field(target, "first"); slot != -1; slot = next[slot]) {
            assertTrue(count++ < size, "Successor cycle");
            assertTrue(indices[slot] > previous);
            previous = indices[slot];
        }
        assertEquals(size, count);
    }

    private static int assertTree(final int slot, final long lower, final long upper,
            final int[] indices, final int[] left, final int[] right, final byte[] heights,
            final boolean[] visited) {
        if (slot == -1) {
            return -1;
        }
        assertTrue(slot >= 0 && slot < visited.length);
        assertFalse(visited[slot], "Tree cycle or shared child");
        visited[slot] = true;
        assertTrue(indices[slot] > lower && indices[slot] < upper);
        final int leftHeight = assertTree(left[slot], lower, indices[slot], indices, left, right, heights, visited);
        final int rightHeight = assertTree(right[slot], indices[slot], upper, indices, left, right, heights, visited);
        assertTrue(Math.abs(leftHeight - rightHeight) <= 1);
        final int height = Math.max(leftHeight, rightHeight) + 1;
        assertEquals(height, heights[slot]);
        return height;
    }
}
