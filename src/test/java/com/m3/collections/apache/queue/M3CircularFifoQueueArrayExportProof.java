/*
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.queue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;

/** Frozen behavioral oracle for inherited array exports and subclass dispatch. */
public final class M3CircularFifoQueueArrayExportProof {
    private static int checks;

    public static void main(final String[] args) throws Exception {
        run();
        System.out.println("FIFO array exports: " + checks + " behavioral checks passed");
    }

    public static void run() throws Exception {
        for (final int capacity : new int[] {1, 2, 3, 7, 16, 33}) {
            final CircularFifoQueue<Integer> queue = new CircularFifoQueue<>(capacity);
            final ArrayDeque<Integer> oracle = new ArrayDeque<>();
            final Random random = new Random(0x7713L + capacity);
            for (int step = 0; step < 1_000; step++) {
                if (oracle.isEmpty() || random.nextInt(5) > 1) {
                    final int value = random.nextInt();
                    if (oracle.size() == capacity) {
                        oracle.removeFirst();
                    }
                    queue.add(value);
                    oracle.addLast(value);
                } else if (random.nextBoolean()) {
                    equal(oracle.removeFirst(), queue.remove());
                } else {
                    final Iterator<Integer> a = queue.iterator();
                    final Iterator<Integer> b = oracle.iterator();
                    final int position = random.nextInt(oracle.size());
                    for (int i = 0; i <= position; i++) {
                        equal(b.next(), a.next());
                    }
                    a.remove();
                    b.remove();
                }
                export(queue, oracle.toArray());
                if (step % 97 == 0) {
                    export(roundTrip(queue), oracle.toArray());
                    queue.clear();
                    oracle.clear();
                    export(queue, oracle.toArray());
                }
            }
        }
        export(new CircularFifoQueue<>(Arrays.asList(1, 2, 3)), new Object[] {1, 2, 3});
        final CircularFifoQueue<Object> mixed = new CircularFifoQueue<>(4);
        mixed.add(7);
        mixed.add("wrong component");
        mixed.add(9);
        final Number[] partial = new Number[] {-1, -2, -3, -4, -5};
        expect(ArrayStoreException.class, () -> mixed.toArray(partial));
        array(new Object[] {7, -2, -3, -4, -5}, partial);
        mixed.clear();
        mixed.add(0);
        mixed.add(0);
        mixed.add(7);
        mixed.add("wrong component");
        mixed.add(9);
        mixed.remove();
        Arrays.fill(partial, -1);
        expect(ArrayStoreException.class, () -> mixed.toArray(partial));
        array(new Object[] {7, -1, -1, -1, -1}, partial);
        final CircularFifoQueue<Object> nullable = new CircularFifoQueue<>(1);
        nullable.add(new SerializedNull());
        array(new Object[] {null}, roundTrip(nullable).toArray());
        final CircularFifoQueue<Integer> empty = new CircularFifoQueue<>(2);
        expect(NullPointerException.class, () -> empty.toArray((Object[]) null));
        expect(NullPointerException.class, () -> generatorToArray(empty, null));
        final Number[] emptyTyped = {1, 2};
        same(emptyTyped, empty.toArray(emptyTyped));
        array(new Object[] {null, 2}, emptyTyped);
        final VirtualQueue subclass = new VirtualQueue();
        subclass.reset();
        array(new Object[] {41, 42}, subclass.toArray());
        equal(1, subclass.sizes);
        equal(1, subclass.iterators);
        subclass.reset();
        final Integer[] target = new Integer[4];
        same(target, subclass.toArray(target));
        array(new Object[] {41, 42, null, null}, target);
        equal(1, subclass.sizes);
        equal(1, subclass.iterators);
        subclass.reset();
        array(new Object[] {41, 42}, generatorToArray(subclass, Integer[]::new));
        equal(1, subclass.sizes);
        equal(1, subclass.iterators);
        for (final int reported : new int[] {0, 1, 3, 7}) {
            subclass.reportedSize = reported;
            array(new Object[] {41, 42}, subclass.toArray());
            array(new Object[] {41, 42}, subclass.toArray(new Integer[0]));
            final Integer[] destination = {-1, -2, -3, -4};
            same(destination, subclass.toArray(destination));
            array(new Object[] {41, 42, null, -4}, destination);
        }
    }

    private static void export(final CircularFifoQueue<Integer> queue, final Object[] expected) {
        final Object[] detached = queue.toArray();
        array(expected, detached);
        equal(Object[].class, detached.getClass());
        final Integer[] exact = new Integer[expected.length];
        same(exact, queue.toArray(exact));
        array(expected, exact);
        final Integer[] small = queue.toArray(new Integer[0]);
        array(expected, small);
        equal(Integer[].class, small.getClass());
        final Integer[] large = new Integer[expected.length + 2];
        Arrays.fill(large, -173);
        same(large, queue.toArray(large));
        array(expected, Arrays.copyOf(large, expected.length));
        equal(null, large[expected.length]);
        equal(-173, large[expected.length + 1]);
        final int[] generatorCalls = {0};
        array(expected, generatorToArray(queue, length -> {
            equal(0, length);
            generatorCalls[0]++;
            return new Integer[length];
        }));
        equal(1, generatorCalls[0]);
        if (detached.length > 0) {
            detached[0] = new Object();
            equal(expected[0], queue.peek());
        }
        equal(expected.length, queue.size());
    }

    private static Object[] generatorToArray(final java.util.Collection<?> queue,
            final java.util.function.IntFunction<Object[]> generator) {
        try {
            // Exercise the Java 11+ inherited default without raising donor release 8.
            return (Object[]) java.util.Collection.class.getMethod("toArray",
                    java.util.function.IntFunction.class).invoke(queue, generator);
        } catch (final java.lang.reflect.InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new AssertionError(cause);
        } catch (final ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E> CircularFifoQueue<E> roundTrip(final CircularFifoQueue<E> queue) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(queue);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (CircularFifoQueue<E>) in.readObject();
        }
    }

    private static void array(final Object[] expected, final Object[] actual) {
        checks++;
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(Arrays.toString(expected) + " != " + Arrays.toString(actual));
        }
    }

    private static void equal(final Object expected, final Object actual) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(expected + " != " + actual);
        }
    }

    private static void same(final Object expected, final Object actual) {
        checks++;
        if (expected != actual) {
            throw new AssertionError("array identity changed");
        }
    }

    private static void expect(final Class<? extends Throwable> type, final Runnable action) {
        checks++;
        try {
            action.run();
        } catch (final Throwable failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError(failure);
        }
        throw new AssertionError("missing " + type.getName());
    }

    private static final class SerializedNull implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        private Object writeReplace() {
            return null;
        }
    }

    private static final class VirtualQueue extends CircularFifoQueue<Integer> {
        private int sizes;
        private int iterators;
        private int reportedSize = 2;

        private VirtualQueue() {
            super(2);
        }

        @Override
        public int size() {
            sizes++;
            return reportedSize;
        }

        @Override
        public Iterator<Integer> iterator() {
            iterators++;
            return Arrays.asList(41, 42).iterator();
        }

        private void reset() {
            sizes = 0;
            iterators = 0;
        }
    }
}
