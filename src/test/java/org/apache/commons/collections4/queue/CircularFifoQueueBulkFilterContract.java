/*
 * Copyright 2026 Hitesh Soliwal and contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.collections4.queue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Serial iterator removal is the independent oracle for the bulk fast paths. */
public final class CircularFifoQueueBulkFilterContract {
    private static final Integer REENTERED_VALUE = Integer.valueOf(777);
    private static long checks;
    private static long digest;

    private CircularFifoQueueBulkFilterContract() {
    }

    private static void check(final boolean condition, final String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message + " at check " + checks);
        }
    }

    private static void equal(final Object actual, final Object expected) {
        check(Objects.equals(actual, expected), actual + " != " + expected);
        digest = digest * 31 + Objects.hashCode(expected);
    }

    private static Object field(final CircularFifoQueue<?> queue, final String name)
            throws ReflectiveOperationException {
        final Field field = CircularFifoQueue.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(queue);
    }

    private static Object scalar(final int value, final int family) {
        switch (family) {
        case 0: return Integer.valueOf(value);
        case 1: return Long.valueOf(value);
        default: return "key-" + value;
        }
    }

    private static CircularFifoQueue<Object> fixture(final int capacity, final int rotation,
            final Object[] values) {
        final CircularFifoQueue<Object> queue = new CircularFifoQueue<>(capacity);
        for (int i = 0; i < rotation; i++) {
            queue.add(Integer.valueOf(-1));
            queue.remove();
        }
        queue.addAll(new ArrayList<>(Arrays.asList(values)));
        return queue;
    }

    private static boolean serial(final CircularFifoQueue<?> queue,
            final Collection<?> membership, final boolean retain) {
        Objects.requireNonNull(membership);
        boolean changed = false;
        final Iterator<?> iterator = queue.iterator();
        while (iterator.hasNext()) {
            if (membership.contains(iterator.next()) != retain) {
                iterator.remove();
                changed = true;
            }
        }
        return changed;
    }

    private static boolean bulk(final CircularFifoQueue<?> queue,
            final Collection<?> membership, final boolean retain) {
        return retain ? queue.retainAll(membership) : queue.removeAll(membership);
    }

    private static void state(final CircularFifoQueue<?> actual, final CircularFifoQueue<?> expected)
            throws ReflectiveOperationException {
        equal(actual.size(), expected.size());
        equal(actual.peek(), expected.peek());
        equal(actual.isAtFullCapacity(), expected.isAtFullCapacity());
        equal(field(actual, "start"), field(expected, "start"));
        equal(field(actual, "end"), field(expected, "end"));
        equal(field(actual, "full"), field(expected, "full"));
        final Object[] a = (Object[]) field(actual, "elements");
        final Object[] b = (Object[]) field(expected, "elements");
        equal(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            check(a[i] == b[i], "physical payload identity at " + i);
        }
        digest = digest * 31 + Arrays.hashCode(a);
        check(Arrays.equals(actual.toArray(), expected.toArray()), "logical encounter order");
        for (int i = 0; i < actual.size(); i++) {
            check(actual.get(i) == expected.get(i), "rank identity");
        }
    }

    private static byte[] serialized(final Object value) throws Exception {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(buffer)) {
            output.writeObject(value);
        }
        return buffer.toByteArray();
    }

    private static void stale(final Iterator<?> actual, final Iterator<?> expected, final int limit) {
        for (int i = 0; i < limit; i++) {
            equal(actual.hasNext(), expected.hasNext());
            if (!actual.hasNext()) {
                return;
            }
            check(actual.next() == expected.next(), "pre-existing iterator state");
        }
        throw new AssertionError("stale iterator failed to terminate within a full ring");
    }

    private static void layouts() throws Exception {
        for (final int capacity : new int[] {1, 2, 7, 31, 63, 64, 65, 127, 129, 255, 256, 257}) {
            for (final int count : new int[] {0, 1, capacity / 2, capacity - 1, capacity}) {
                for (final int rotation : new int[] {0, 1 % capacity, capacity / 2, capacity - 1}) {
                    for (int family = 0; family < 3; family++) {
                        final Object[] values = new Object[count];
                        for (int i = 0; i < count; i++) {
                            values[i] = scalar(i % 19, family);
                        }
                        for (int pattern = 0; pattern < 6; pattern++) {
                            final HashSet<Object> membership = new HashSet<>();
                            for (int i = 0; i < 19; i++) {
                                if (pattern == 1 || pattern == 2 && (i & 1) == 0
                                        || pattern == 3 && i < 5 || pattern == 4 && i >= 15
                                        || pattern == 5 && i >= 4 && i < 16) {
                                    membership.add(scalar(i, family));
                                }
                            }
                            final HashSet<Object> before = new HashSet<>(membership);
                            for (final boolean retain : new boolean[] {false, true}) {
                                final CircularFifoQueue<Object> actual = fixture(capacity, rotation, values);
                                final CircularFifoQueue<Object> expected = fixture(capacity, rotation, values);
                                final Object storage = field(actual, "elements");
                                final Iterator<Object> a = actual.iterator();
                                final Iterator<Object> b = expected.iterator();
                                if (count > 1) { check(a.next() == b.next(), "initial iterator identity"); }
                                equal(bulk(actual, membership, retain), serial(expected, membership, retain));
                                check(field(actual, "elements") == storage, "backing array replaced");
                                state(actual, expected);
                                stale(a, b, capacity + 1);
                                equal(membership, before);
                                if (capacity == 257 && count == capacity && rotation == 128 && family == 0) {
                                    final byte[] bytes = serialized(actual);
                                    check(Arrays.equals(bytes, serialized(expected)), "serialization bytes");
                                    try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                                        final CircularFifoQueue<?> restored = (CircularFifoQueue<?>) input.readObject();
                                        check(Arrays.equals(restored.toArray(), actual.toArray()), "serialization round trip");
                                    }
                                }
                                for (int i = 0; i < 3; i++) {
                                    equal(actual.poll(), expected.poll());
                                    final Object value = scalar(100 + i, family);
                                    actual.offer(value);
                                    expected.offer(value);
                                }
                                state(actual, expected);
                            }
                        }
                    }
                }
            }
        }
    }

    private static final class TrackingSet extends HashSet<Object> {
        private static final long serialVersionUID = 1L;
        private final ArrayList<String> trace = new ArrayList<>();
        private CircularFifoQueue<Object> owner;
        private int stop;
        private int mutate;
        private final RuntimeException failure;

        TrackingSet(final RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public boolean contains(final Object value) {
            trace.add(value + ":" + owner.size() + ":" + owner.peek());
            if (trace.size() == mutate) {
                owner.poll();
                owner.add(REENTERED_VALUE);
            }
            if (trace.size() == stop) {
                throw failure;
            }
            return super.contains(value);
        }
    }

    private static String failure(final Runnable task, final RuntimeException sentinel) {
        try {
            task.run();
            return "OK";
        } catch (final RuntimeException thrown) {
            if (sentinel != null) { check(thrown == sentinel, "callback exception identity"); }
            return thrown.getClass().getName() + ":" + thrown.getMessage();
        }
    }

    private static final class HookQueue extends CircularFifoQueue<Object> {
        private static final long serialVersionUID = 1L;
        private final ArrayList<String> trace = new ArrayList<>();

        HookQueue() { super(513); }

        @Override
        public Iterator<Object> iterator() {
            trace.add("iterator");
            final Iterator<Object> iterator = super.iterator();
            return new Iterator<Object>() {
                @Override public boolean hasNext() { trace.add("has"); return iterator.hasNext(); }
                @Override public Object next() { trace.add("next"); return iterator.next(); }
                @Override public void remove() { trace.add("remove"); iterator.remove(); }
            };
        }
    }

    private static void fallbacks() throws Exception {
        for (final boolean retain : new boolean[] {false, true}) {
            final Object[] values = new Object[513];
            for (int i = 0; i < values.length; i++) { values[i] = Integer.valueOf(i); }
            for (final int stop : new int[] {0, 1, 2, 17, 67, 128}) {
                final RuntimeException sentinel = new IllegalArgumentException("membership sentinel");
                final CircularFifoQueue<Object> a = fixture(513, 257, values);
                final CircularFifoQueue<Object> b = fixture(513, 257, values);
                final TrackingSet left = new TrackingSet(sentinel);
                final TrackingSet right = new TrackingSet(sentinel);
                left.owner = a; right.owner = b; left.stop = right.stop = stop;
                left.mutate = right.mutate = 9;
                for (int i = 0; i < values.length; i += 2) { left.add(Integer.valueOf(i)); right.add(Integer.valueOf(i)); }
                equal(failure(() -> bulk(a, left, retain), sentinel), failure(() -> serial(b, right, retain), sentinel));
                equal(left.trace, right.trace);
                state(a, b);
            }
            final HookQueue a = new HookQueue();
            final HookQueue b = new HookQueue();
            for (final Object value : values) { a.add(value); b.add(value); }
            final HashSet<Object> membership = new HashSet<>(Arrays.asList(0, 1, 64, 128));
            equal(bulk(a, membership, retain), serial(b, membership, retain));
            equal(a.trace, b.trace);
            state(a, b);
            for (final int count : new int[] {0, 513}) {
                final CircularFifoQueue<Object> x = fixture(513, 257, Arrays.copyOf(values, count));
                final CircularFifoQueue<Object> y = fixture(513, 257, Arrays.copyOf(values, count));
                equal(failure(() -> bulk(x, null, retain), null), failure(() -> serial(y, null, retain), null));
                state(x, y);
            }
            final CircularFifoQueue<Object> x = fixture(513, 257, values);
            final CircularFifoQueue<Object> y = fixture(513, 257, values);
            equal(bulk(x, x, retain), serial(y, y, retain));
            state(x, y);
            final List<Object> arbitrary = Arrays.asList(values).subList(17, 93);
            final CircularFifoQueue<Object> p = fixture(513, 257, values);
            final CircularFifoQueue<Object> q = fixture(513, 257, values);
            equal(bulk(p, arbitrary, retain), serial(q, arbitrary, retain));
            state(p, q);
        }
    }

    private static final class CallbackKey {
        private final int id;
        private static final List<Integer> TRACE = new ArrayList<>();
        private static int remaining;
        private static RuntimeException failure;

        CallbackKey(final int id) { this.id = id; }
        @Override public int hashCode() {
            TRACE.add(id);
            if (--remaining == 0) { throw failure; }
            return id;
        }
        @Override public boolean equals(final Object value) { return value == this; }
        @Override public String toString() { return "callback-" + id; }
    }

    private static void payloadCallbacks() throws Exception {
        final Object[] values = new Object[513];
        for (int i = 0; i < values.length; i++) {
            values[i] = i < 32 ? Integer.valueOf(i) : new CallbackKey(i);
        }
        final HashSet<Object> membership = new HashSet<>();
        CallbackKey.remaining = -1;
        for (int i = 0; i < values.length; i += 2) { membership.add(values[i]); }
        for (final boolean retain : new boolean[] {false, true}) {
            final CircularFifoQueue<Object> a = fixture(513, 257, values);
            final CircularFifoQueue<Object> b = fixture(513, 257, values);
            final RuntimeException sentinel = new IllegalStateException("hash sentinel");
            CallbackKey.failure = sentinel;
            CallbackKey.TRACE.clear(); CallbackKey.remaining = 23;
            final String actual = failure(() -> bulk(a, membership, retain), sentinel);
            final List<Integer> trace = new ArrayList<>(CallbackKey.TRACE);
            CallbackKey.TRACE.clear(); CallbackKey.remaining = 23;
            final String expected = failure(() -> serial(b, membership, retain), sentinel);
            equal(actual, expected);
            equal(trace, CallbackKey.TRACE);
            CallbackKey.remaining = -1;
            state(a, b);
        }
    }

    private static void randomized() throws Exception {
        final Random random = new Random(0x72696e6766696c74L);
        for (int trial = 0; trial < 800; trial++) {
            final int capacity = 64 + random.nextInt(961);
            final int count = random.nextInt(capacity + 1);
            final int rotation = random.nextInt(capacity);
            final Object[] values = new Object[count];
            final HashSet<Object> membership = new HashSet<>();
            final int family = random.nextInt(3);
            for (int i = 0; i < count; i++) {
                values[i] = scalar(random.nextInt(311), family);
                if (random.nextBoolean()) { membership.add(values[i]); }
            }
            final CircularFifoQueue<Object> a = fixture(capacity, rotation, values);
            final CircularFifoQueue<Object> b = fixture(capacity, rotation, values);
            final boolean retain = random.nextBoolean();
            equal(bulk(a, membership, retain), serial(b, membership, retain));
            state(a, b);
            for (int step = 0; step < 31; step++) {
                if (random.nextBoolean()) {
                    final Object value = scalar(random.nextInt(311), family);
                    equal(a.add(value), b.add(value));
                } else {
                    equal(a.poll(), b.poll());
                }
            }
            state(a, b);
        }
    }

    public static void verify() throws Exception {
        checks = 0;
        digest = 0;
        layouts();
        fallbacks();
        payloadCallbacks();
        randomized();
        System.out.println("RING_FILTER_PASS checks=" + checks + " digest=" + Long.toHexString(digest));
    }

    public static void main(final String[] args) throws Exception {
        verify();
    }
}
