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
// Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.
package com.m3.collections.apache.queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

/**
 * Array export contracts, independent of the queue's storage implementation.
 */
class CircularFifoQueueToArrayTest {

    private static final class ProjectedQueue extends CircularFifoQueue<String> {
        private static final long serialVersionUID = 1L;
        private final int estimate;
        private final List<String> projection = Arrays.asList("one", "two", "three");
        private int iteratorCalls;
        private int sizeCalls;

        private ProjectedQueue(final int estimate) {
            super(8);
            this.estimate = estimate;
        }

        @Override
        public Iterator<String> iterator() {
            iteratorCalls++;
            return projection.iterator();
        }

        @Override
        public int size() {
            sizeCalls++;
            return estimate;
        }
    }

    private static final class RawOverrideQueue extends CircularFifoQueue<String> {
        private static final long serialVersionUID = 1L;
        private int rawCalls;

        @Override
        public Object[] toArray() {
            rawCalls++;
            return new Object[] {"custom raw export"};
        }
    }

    private static final class TypedOverrideQueue extends CircularFifoQueue<String> {
        private static final long serialVersionUID = 1L;
        private int typedCalls;

        @Override
        public <T> T[] toArray(final T[] target) {
            typedCalls++;
            return super.toArray(target);
        }
    }

    private static void assertExports(final CircularFifoQueue<Integer> queue,
            final List<Integer> expected) {
        assertArrayEquals(expected.toArray(), queue.toArray());
        assertArrayEquals(expected.toArray(new Integer[0]), queue.toArray(new Integer[0]));
        final Integer[] exact = new Integer[expected.size()];
        assertSame(exact, queue.toArray(exact));
        assertArrayEquals(expected.toArray(new Integer[0]), exact);
        final Integer[] larger = new Integer[expected.size() + 3];
        Arrays.fill(larger, Integer.MIN_VALUE);
        assertSame(larger, queue.toArray(larger));
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), larger[i]);
        }
        assertNull(larger[expected.size()]);
        assertEquals(Integer.valueOf(Integer.MIN_VALUE), larger[expected.size() + 1]);
        assertEquals(Integer.valueOf(Integer.MIN_VALUE), larger[expected.size() + 2]);
    }

    private static CircularFifoQueue<Object> objectQueue(final int capacity, final int rotations,
            final Object... values) {
        final CircularFifoQueue<Object> queue = new CircularFifoQueue<>(capacity);
        for (int i = 0; i < rotations; i++) {
            queue.add("discard");
            queue.remove();
        }
        queue.addAll(Arrays.asList(values));
        return queue;
    }

    @SuppressWarnings("unchecked")
    private static <T> T[] generatedArray(final Collection<?> collection, final IntFunction<T[]> generator) {
        try {
            // Retain Java 8 source compatibility while exercising the Java 11+ API.
            return (T[]) Collection.class.getMethod("toArray", IntFunction.class).invoke(collection, generator);
        } catch (final InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new AssertionError("Unexpected checked exception from Collection.toArray", cause);
        } catch (final ReflectiveOperationException failure) {
            throw new AssertionError("Java 11+ array generator API unavailable", failure);
        }
    }

    @Test
    void testAllSmallRingPositionsAndSizes() {
        for (int capacity = 1; capacity <= 17; capacity++) {
            for (int rotations = 0; rotations < capacity; rotations++) {
                for (int count = 0; count <= capacity; count++) {
                    final CircularFifoQueue<Integer> queue = new CircularFifoQueue<>(capacity);
                    for (int i = 0; i < rotations; i++) {
                        queue.add(-1);
                        queue.remove();
                    }
                    final List<Integer> expected = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        queue.add(i);
                        expected.add(i);
                    }
                    assertExports(queue, expected);
                    if (!expected.isEmpty()) {
                        assertEquals(expected.remove(0), queue.remove());
                        assertExports(queue, expected);
                    }
                }
            }
        }
    }

    @Test
    void testArrayStoreFailurePreservesWrittenPrefixAtEveryPosition() {
        // Rotating an empty queue before insertion exercises either side of wrap.
        for (int rotations = 0; rotations < 5; rotations++) {
            for (int incompatible = 0; incompatible < 5; incompatible++) {
                final Object[] values = {"a", "b", "c", "d", "e"};
                values[incompatible] = Integer.valueOf(17);
                final CircularFifoQueue<Object> queue = objectQueue(5, rotations, values);
                for (final int length : new int[] {5, 8}) {
                    final String[] target = new String[length];
                    Arrays.fill(target, "untouched");
                    assertThrows(ArrayStoreException.class, () -> queue.toArray(target));
                    for (int i = 0; i < incompatible; i++) {
                        assertEquals(values[i], target[i]);
                    }
                    for (int i = incompatible; i < target.length; i++) {
                        assertEquals("untouched", target[i]);
                    }
                    assertArrayEquals(values, queue.toArray());
                }
            }
        }
    }

    @Test
    void testArrayStoreFailureWithNewArrayLeavesCallerArrayUntouched() {
        final CircularFifoQueue<Object> queue = objectQueue(5, 3, "a", "b", 17, "d");
        final String[] target = {"untouched"};
        assertThrows(ArrayStoreException.class, () -> queue.toArray(target));
        assertArrayEquals(new String[] {"untouched"}, target);
        assertArrayEquals(new Object[] {"a", "b", 17, "d"}, queue.toArray());
    }

    @Test
    void testEmptyQueuePreservesTypedArrayAndTail() {
        final CircularFifoQueue<Object> queue = objectQueue(4, 3);
        final String[] zero = new String[0];
        assertSame(zero, queue.toArray(zero));
        final String[] target = {"old first", "tail", "tail two"};
        assertSame(target, queue.toArray(target));
        assertArrayEquals(new String[] {null, "tail", "tail two"}, target);
        assertEquals(Object[].class, queue.toArray().getClass());
        assertEquals(0, queue.toArray().length);
    }

    @Test
    void testExportedArraysAreIndependentSnapshots() {
        final Object first = new Object();
        final Object second = new Object();
        final Object third = new Object();
        final CircularFifoQueue<Object> queue = objectQueue(3, 2, first, second, third);
        final Object[] raw = queue.toArray();
        final Object[] typed = queue.toArray(new Object[0]);
        assertNotSame(raw, typed);
        assertSame(first, raw[0]);
        assertSame(second, raw[1]);
        assertSame(third, raw[2]);
        raw[0] = "outside mutation";
        typed[1] = "another outside mutation";
        assertSame(first, queue.peek());
        assertSame(second, queue.get(1));
        queue.add("newest");
        queue.clear();
        assertSame(third, raw[2]);
        assertSame(first, typed[0]);
        assertSame(third, typed[2]);
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_11)
    void testGeneratorReceivesZeroOnceAndDispatchesToTypedOverride() {
        final TypedOverrideQueue queue = new TypedOverrideQueue();
        queue.addAll(Arrays.asList("first", "second"));
        final AtomicInteger calls = new AtomicInteger();
        final String[] result = generatedArray(queue, length -> {
            assertEquals(0, length);
            calls.incrementAndGet();
            return new String[length];
        });
        assertArrayEquals(new String[] {"first", "second"}, result);
        assertEquals(1, calls.get());
        assertEquals(1, queue.typedCalls);
        for (final CircularFifoQueue<Object> nullQueue : Arrays.asList(objectQueue(2, 1),
                objectQueue(2, 1, "first", "second"))) {
            final Object[] before = nullQueue.toArray();
            assertThrows(NullPointerException.class,
                    () -> generatedArray(nullQueue, (IntFunction<Object[]>) null));
            assertThrows(NullPointerException.class, () -> generatedArray(nullQueue, length -> null));
            assertArrayEquals(before, nullQueue.toArray());
        }
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_11)
    void testGeneratorObservesItsOwnMutationBeforeExport() {
        final CircularFifoQueue<String> queue = new CircularFifoQueue<>(3);
        queue.addAll(Arrays.asList("first", "second", "third"));
        final AtomicInteger calls = new AtomicInteger();
        final String[] result = generatedArray(queue, length -> {
            assertEquals(0, length);
            calls.incrementAndGet();
            queue.add("fourth");
            return new String[length];
        });
        assertArrayEquals(new String[] {"second", "third", "fourth"}, result);
        assertEquals(1, calls.get());
    }

    @Test
    void testIteratorRemovalAndSubsequentOverwrite() {
        for (int rotations = 0; rotations < 7; rotations++) {
            for (int removed = 0; removed < 7; removed++) {
                final CircularFifoQueue<Integer> queue = new CircularFifoQueue<>(7);
                final List<Integer> expected = new ArrayList<>();
                for (int i = 0; i < 7 + rotations; i++) {
                    queue.add(i);
                    if (expected.size() == 7) {
                        expected.remove(0);
                    }
                    expected.add(i);
                }
                final Iterator<Integer> iterator = queue.iterator();
                for (int i = 0; i <= removed; i++) {
                    iterator.next();
                }
                iterator.remove();
                expected.remove(removed);
                assertExports(queue, expected);
                queue.add(100);
                expected.add(100);
                assertExports(queue, expected);
                queue.add(101);
                expected.remove(0);
                expected.add(101);
                assertExports(queue, expected);
            }
        }
    }

    @Test
    void testNullArrayIsRejectedWithoutMutation() {
        for (final CircularFifoQueue<Object> queue : Arrays.asList(objectQueue(2, 1),
                objectQueue(2, 1, "first", "second"))) {
            final Object[] before = queue.toArray();
            assertThrows(NullPointerException.class, () -> queue.toArray((Object[]) null));
            assertArrayEquals(before, queue.toArray());
        }
    }

    @Test
    void testRandomMutationSequencesAgainstArrayListModel() {
        for (final int capacity : new int[] {1, 2, 3, 7, 16, 31, 64}) {
            final CircularFifoQueue<Integer> queue = new CircularFifoQueue<>(capacity);
            final List<Integer> expected = new ArrayList<>();
            final Random random = new Random(0x3c01ec7L + capacity);
            for (int operation = 0; operation < 3000; operation++) {
                final int choice = random.nextInt(12);
                if (choice < 7) {
                    final Integer value = random.nextInt(31);
                    if (expected.size() == capacity) {
                        expected.remove(0);
                    }
                    expected.add(value);
                    if ((choice & 1) == 0) {
                        queue.add(value);
                    } else {
                        queue.offer(value);
                    }
                } else if (choice < 9) {
                    assertEquals(expected.isEmpty() ? null : expected.remove(0), queue.poll());
                } else if (choice < 11 && !expected.isEmpty()) {
                    final int removed = random.nextInt(expected.size());
                    final Iterator<Integer> iterator = queue.iterator();
                    for (int i = 0; i <= removed; i++) {
                        assertEquals(expected.get(i), iterator.next());
                    }
                    iterator.remove();
                    expected.remove(removed);
                } else if (choice == 11) {
                    queue.clear();
                    expected.clear();
                }
                assertExports(queue, expected);
            }
        }
    }

    @Test
    void testRuntimeArrayTypesAndUnchangedUndersizedTarget() {
        final CircularFifoQueue<Object> queue = objectQueue(5, 4, "one", "two", "three");
        final CharSequence[] shortTarget = {"untouched"};
        final CharSequence[] result = queue.toArray(shortTarget);
        assertNotSame(shortTarget, result);
        assertEquals(CharSequence[].class, result.getClass());
        assertArrayEquals(new CharSequence[] {"one", "two", "three"}, result);
        assertArrayEquals(new CharSequence[] {"untouched"}, shortTarget);
        final CharSequence[] exact = new CharSequence[3];
        assertSame(exact, queue.toArray(exact));
        assertArrayEquals(result, exact);
        final Object[] objects = queue.toArray(new Object[0]);
        assertEquals(Object[].class, objects.getClass());
        assertArrayEquals(result, objects);
        final CircularFifoQueue<Object> numbers = objectQueue(4, 3, 1, 2L, 3.5);
        final Number[] numberArray = numbers.toArray(new Number[0]);
        assertEquals(Number[].class, numberArray.getClass());
        assertArrayEquals(new Number[] {1, 2L, 3.5}, numberArray);
    }

    @Test
    void testSerializationRoundTripPreservesExportOrder() throws Exception {
        final CircularFifoQueue<Object> queue = objectQueue(5, 3, "a", "b", "c", "d");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(queue);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            final CircularFifoQueue<?> restored = (CircularFifoQueue<?>) in.readObject();
            assertArrayEquals(queue.toArray(), restored.toArray());
            assertArrayEquals(queue.toArray(new String[0]), restored.toArray(new String[0]));
        }
    }

    @Test
    void testSubclassVirtualIteratorAndSizeAreHonored() {
        for (final int estimate : new int[] {0, 1, 3, 8}) {
            final ProjectedQueue raw = new ProjectedQueue(estimate);
            assertArrayEquals(new Object[] {"one", "two", "three"}, raw.toArray());
            assertEquals(1, raw.sizeCalls);
            assertEquals(1, raw.iteratorCalls);
            for (final int length : new int[] {0, 1, 3, 5}) {
                final ProjectedQueue typed = new ProjectedQueue(estimate);
                final String[] target = new String[length];
                Arrays.fill(target, "untouched");
                final String[] result = typed.toArray(target);
                assertArrayEquals(new String[] {"one", "two", "three"},
                        Arrays.copyOf(result, 3));
                if (length >= 3) {
                    assertSame(target, result);
                    if (length > 3) {
                        assertNull(result[3]);
                        assertEquals("untouched", result[4]);
                    }
                }
                assertEquals(1, typed.sizeCalls);
                assertEquals(1, typed.iteratorCalls);
            }
        }
    }

    @Test
    void testTypedExportDoesNotInvokeSubclassRawOverride() {
        final RawOverrideQueue queue = new RawOverrideQueue();
        queue.addAll(Arrays.asList("stored one", "stored two"));
        assertArrayEquals(new String[] {"stored one", "stored two"}, queue.toArray(new String[0]));
        assertEquals(0, queue.rawCalls);
        assertArrayEquals(new Object[] {"custom raw export"}, queue.toArray());
        assertEquals(1, queue.rawCalls);
    }
}
