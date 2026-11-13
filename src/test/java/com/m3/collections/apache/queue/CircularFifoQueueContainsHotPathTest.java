/*
 * Copyright (c) 2026 Hitesh Soliwal and Synexia contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Independent AbstractCollection.contains oracle for direct array-ring membership.
 * A subclass deliberately retains the inherited iterator-based membership path.
 */
class CircularFifoQueueContainsHotPathTest {
    private static final class InheritedContains<E> extends CircularFifoQueue<E> {
        private static final long serialVersionUID = 1L;

        InheritedContains(final int capacity) {
            super(capacity);
        }
    }

    private static final class ProjectedQueue extends CircularFifoQueue<String> {
        private static final long serialVersionUID = 1L;
        private int iteratorCalls;

        ProjectedQueue() {
            super(3);
        }

        @Override
        public Iterator<String> iterator() {
            iteratorCalls++;
            return Arrays.asList("external-one", "external-two").iterator();
        }
    }

    private static final class MarkerFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** Deliberately direction-sensitive: candidate.equals(element), not the reverse. */
    private static final class ObservingCandidate {
        final Object expected;
        final int throwAt;
        final List<Object> seen = new ArrayList<>();

        ObservingCandidate(final Object expected, final int throwAt) {
            this.expected = expected;
            this.throwAt = throwAt;
        }

        @Override
        public boolean equals(final Object element) {
            seen.add(element);
            if (seen.size() == throwAt) {
                throw new MarkerFailure();
            }
            return expected == element;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    private static final class MutatingCandidate {
        private final CircularFifoQueue<Object> queue;
        private final Object replacement;
        final List<Object> seen = new ArrayList<>();

        MutatingCandidate(final CircularFifoQueue<Object> queue, final Object replacement) {
            this.queue = queue;
            this.replacement = replacement;
        }

        @Override
        public boolean equals(final Object element) {
            seen.add(element);
            if (seen.size() == 1) {
                queue.remove();
                queue.add(replacement);
            }
            return element == replacement;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    @Test
    void exhaustiveLayoutsMatchInheritedAbstractCollection() {
        for (int capacity = 1; capacity <= 20; capacity++) {
            for (int rotation = 0; rotation < capacity; rotation++) {
                for (int count = 0; count <= capacity; count++) {
                    final CircularFifoQueue<Integer> actual = new CircularFifoQueue<>(capacity);
                    final InheritedContains<Integer> inherited = new InheritedContains<>(capacity);
                    for (int i = 0; i < rotation; i++) {
                        actual.add(-777);
                        inherited.add(-777);
                        assertEquals(inherited.remove(), actual.remove());
                    }
                    for (int i = 0; i < count; i++) {
                        actual.add(i % 7);
                        inherited.add(i % 7);
                    }
                    for (int query = -1; query <= 8; query++) {
                        assertEquals(inherited.contains(query), actual.contains(query),
                                "capacity=" + capacity + " rotation=" + rotation
                                        + " count=" + count + " query=" + query);
                    }
                    assertEquals(inherited.contains(null), actual.contains(null));
                    assertArrayEquals(inherited.toArray(), actual.toArray());
                }
            }
        }
    }

    @Test
    void randomizedRingMutationsMatchIteratorOracle() {
        final Random random = new Random(0x4c495354L);
        for (int capacity = 1; capacity <= 14; capacity++) {
            final CircularFifoQueue<Integer> actual = new CircularFifoQueue<>(capacity);
            final InheritedContains<Integer> inherited = new InheritedContains<>(capacity);
            for (int operation = 0; operation < 2500; operation++) {
                final int action = random.nextInt(8);
                if (action <= 3) {
                    final int value = random.nextInt(41) - 20;
                    actual.add(value);
                    inherited.add(value);
                } else if (action == 4 && !actual.isEmpty()) {
                    assertEquals(inherited.remove(), actual.remove());
                } else if (action == 5) {
                    actual.clear();
                    inherited.clear();
                } else if (action == 6 && !actual.isEmpty()) {
                    final int rank = random.nextInt(actual.size());
                    final Iterator<Integer> a = actual.iterator();
                    final Iterator<Integer> b = inherited.iterator();
                    for (int i = 0; i <= rank; i++) {
                        assertEquals(b.next(), a.next());
                    }
                    a.remove();
                    b.remove();
                }
                final int query = random.nextInt(45) - 22;
                assertEquals(inherited.contains(query), actual.contains(query));
                assertEquals(inherited.contains(null), actual.contains(null));
                assertArrayEquals(inherited.toArray(), actual.toArray());
            }
        }
    }

    @Test
    void candidateEqualsOrderAndExceptionsArePreserved() {
        final CircularFifoQueue<Object> actual = new CircularFifoQueue<>(5);
        final InheritedContains<Object> inherited = new InheritedContains<>(5);
        final Object[] items = {new Object(), new Object(), new Object(), new Object()};
        for (int i = 0; i < 3; i++) {
            actual.add("discard");
            inherited.add("discard");
            actual.remove();
            inherited.remove();
        }
        for (final Object item : items) {
            actual.add(item);
            inherited.add(item);
        }

        final ObservingCandidate a = new ObservingCandidate(items[3], -1);
        final ObservingCandidate b = new ObservingCandidate(items[3], -1);
        assertEquals(inherited.contains(b), actual.contains(a));
        assertEquals(b.seen, a.seen);
        assertEquals(Arrays.asList(items), a.seen);

        final ObservingCandidate failingA = new ObservingCandidate(null, 3);
        final ObservingCandidate failingB = new ObservingCandidate(null, 3);
        assertThrows(MarkerFailure.class, () -> actual.contains(failingA));
        assertThrows(MarkerFailure.class, () -> inherited.contains(failingB));
        assertEquals(failingB.seen, failingA.seen);
        assertArrayEquals(inherited.toArray(), actual.toArray());
    }

    @Test
    void reentrantEqualsFollowsDynamicIteratorEnd() {
        final CircularFifoQueue<Object> actual = new CircularFifoQueue<>(5);
        final InheritedContains<Object> inherited = new InheritedContains<>(5);
        for (int i = 0; i < 2; i++) {
            final Object dummy = new Object();
            actual.add(dummy);
            inherited.add(dummy);
            assertSame(inherited.remove(), actual.remove());
        }
        for (int i = 0; i < 4; i++) {
            final Object value = new Object();
            actual.add(value);
            inherited.add(value);
        }
        final Object replacement = new Object();
        final MutatingCandidate a = new MutatingCandidate(actual, replacement);
        final MutatingCandidate b = new MutatingCandidate(inherited, replacement);
        assertEquals(inherited.contains(b), actual.contains(a));
        assertTrue(actual.contains(replacement));
        assertEquals(b.seen, a.seen);
        assertArrayEquals(inherited.toArray(), actual.toArray());
    }

    @Test
    void subclassIteratorProjectionIsStillAuthoritative() {
        final ProjectedQueue queue = new ProjectedQueue();
        assertTrue(queue.contains("external-two"));
        assertFalse(queue.contains("missing"));
        assertFalse(queue.contains(null));
        assertEquals(3, queue.iteratorCalls);
        assertEquals(0, queue.size());
    }

    @Test
    void nullQueryRetainsInheritedBehaviorEvenForMalformedLiveSlots() throws Exception {
        final CircularFifoQueue<Object> actual = new CircularFifoQueue<>(2);
        final InheritedContains<Object> inherited = new InheritedContains<>(2);
        final Object value = new Object();
        actual.add(value);
        inherited.add(value);
        final Field field = CircularFifoQueue.class.getDeclaredField("elements");
        field.setAccessible(true);
        ((Object[]) field.get(actual))[0] = null;
        ((Object[]) field.get(inherited))[0] = null;
        assertEquals(inherited.contains(null), actual.contains(null));
        assertTrue(actual.contains(null));
        assertEquals(inherited.contains(value), actual.contains(value));
    }
}
