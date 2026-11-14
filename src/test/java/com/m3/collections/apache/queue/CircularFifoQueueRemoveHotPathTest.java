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
// Copyright (c) 2026 Hitesh Soliwal and Synexia contributors.
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

/** Direct array-ring removal versus the inherited AbstractCollection iterator algorithm. */
class CircularFifoQueueRemoveHotPathTest {
    private static final class InheritedRemove<E> extends CircularFifoQueue<E> {
        private static final long serialVersionUID = 1L;

        InheritedRemove(final int capacity) {
            super(capacity);
        }
    }

    private static final class ProjectedQueue extends CircularFifoQueue<String> {
        private static final long serialVersionUID = 1L;
        final List<String> projected = new ArrayList<>(Arrays.asList("a", "b", "a"));
        private int iteratorCalls;

        ProjectedQueue() {
            super(3);
        }

        @Override
        public Iterator<String> iterator() {
            iteratorCalls++;
            return projected.iterator();
        }
    }

    private static void sameState(final CircularFifoQueue<?> a, final CircularFifoQueue<?> b) {
        assertEquals(a.size(), b.size());
        assertEquals(a.peek(), b.peek());
        assertArrayEquals(a.toArray(), b.toArray());
    }

    @Test
    void allRingLayoutsAndRemovalPositionsMatchInheritedPath() {
        for (int capacity = 1; capacity <= 15; capacity++) {
            for (int rotation = 0; rotation < capacity; rotation++) {
                for (int count = 0; count <= capacity; count++) {
                    for (int target = -1; target <= 8; target++) {
                        final InheritedRemove<Integer> reference = new InheritedRemove<>(capacity);
                        final CircularFifoQueue<Integer> actual = new CircularFifoQueue<>(capacity);
                        for (int i = 0; i < rotation; i++) {
                            reference.add(-999);
                            actual.add(-999);
                            assertEquals(reference.remove(), actual.remove());
                        }
                        for (int i = 0; i < count; i++) {
                            final int value = i % 7;
                            reference.add(value);
                            actual.add(value);
                        }
                        assertEquals(reference.remove(Integer.valueOf(target)),
                                actual.remove(Integer.valueOf(target)),
                                "capacity=" + capacity + " rotation=" + rotation
                                        + " count=" + count + " target=" + target);
                        sameState(reference, actual);
                        assertEquals(reference.contains(target), actual.contains(target));
                    }
                }
            }
        }
    }

    @Test
    void randomizedMutationTraceMatchesInheritedRemove() {
        final Random random = new Random(0x52454d4f56454cL);
        for (int capacity = 1; capacity <= 18; capacity++) {
            final InheritedRemove<Integer> reference = new InheritedRemove<>(capacity);
            final CircularFifoQueue<Integer> actual = new CircularFifoQueue<>(capacity);
            for (int step = 0; step < 3500; step++) {
                final int action = random.nextInt(9);
                if (action <= 2) {
                    final int value = random.nextInt(23) - 11;
                    reference.add(value);
                    actual.add(value);
                } else if (action == 3) {
                    final Integer value = Integer.valueOf(random.nextInt(27) - 13);
                    assertEquals(reference.remove(value), actual.remove(value));
                } else if (action == 4 && !actual.isEmpty()) {
                    assertEquals(reference.remove(), actual.remove());
                } else if (action == 5) {
                    reference.clear();
                    actual.clear();
                } else if (action == 6 && !actual.isEmpty()) {
                    final int rank = random.nextInt(actual.size());
                    final Iterator<Integer> a = actual.iterator();
                    final Iterator<Integer> b = reference.iterator();
                    for (int i = 0; i <= rank; i++) {
                        assertEquals(b.next(), a.next());
                    }
                    a.remove();
                    b.remove();
                } else if (action == 7) {
                    assertEquals(reference.remove(null), actual.remove(null));
                }
                sameState(reference, actual);
            }
        }
    }

    private static final class ExpectedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class Query {
        private final Object sought;
        private final int throwAt;
        final List<Object> seen = new ArrayList<>();

        Query(final Object sought, final int throwAt) {
            this.sought = sought;
            this.throwAt = throwAt;
        }

        @Override
        public boolean equals(final Object candidate) {
            seen.add(candidate);
            if (seen.size() == throwAt) {
                throw new ExpectedFailure();
            }
            return candidate == sought;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    @Test
    void equalsDirectionAndExceptionTimingMatch() {
        final InheritedRemove<Object> reference = new InheritedRemove<>(5);
        final CircularFifoQueue<Object> actual = new CircularFifoQueue<>(5);
        for (int i = 0; i < 2; i++) {
            final Object marker = new Object();
            reference.add(marker);
            actual.add(marker);
            assertSame(reference.remove(), actual.remove());
        }
        final Object[] values = {new Object(), new Object(), new Object(), new Object()};
        for (final Object value : values) {
            reference.add(value);
            actual.add(value);
        }

        final Query expected = new Query(values[2], -1);
        final Query query = new Query(values[2], -1);
        assertEquals(reference.remove(expected), actual.remove(query));
        assertTrue(query.seen.size() == 3);
        assertEquals(expected.seen, query.seen);
        sameState(reference, actual);

        final Query throwingA = new Query(null, 2);
        final Query throwingB = new Query(null, 2);
        assertThrows(ExpectedFailure.class, () -> reference.remove(throwingA));
        assertThrows(ExpectedFailure.class, () -> actual.remove(throwingB));
        assertEquals(throwingA.seen, throwingB.seen);
        sameState(reference, actual);
    }

    private static final class ReentrantQuery {
        private final CircularFifoQueue<Object> queue;
        private final Object inserted;
        final List<Object> seen = new ArrayList<>();

        ReentrantQuery(final CircularFifoQueue<Object> queue, final Object inserted) {
            this.queue = queue;
            this.inserted = inserted;
        }

        @Override
        public boolean equals(final Object compared) {
            seen.add(compared);
            if (seen.size() == 1) {
                queue.remove();
                queue.add(inserted);
            }
            return compared == inserted;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    @Test
    void reentrantEqualsMatchesOriginalPhysicalRemoval() {
        final InheritedRemove<Object> reference = new InheritedRemove<>(5);
        final CircularFifoQueue<Object> actual = new CircularFifoQueue<>(5);
        for (int i = 0; i < 2; i++) {
            final Object rotated = new Object();
            reference.add(rotated);
            actual.add(rotated);
            assertSame(reference.remove(), actual.remove());
        }
        for (int i = 0; i < 4; i++) {
            final Object shared = new Object();
            reference.add(shared);
            actual.add(shared);
        }
        final Object inserted = new Object();
        final ReentrantQuery a = new ReentrantQuery(reference, inserted);
        final ReentrantQuery b = new ReentrantQuery(actual, inserted);
        assertEquals(reference.remove(a), actual.remove(b));
        assertEquals(a.seen, b.seen);
        sameState(reference, actual);
        assertFalse(actual.contains(inserted));
    }

    @Test
    void subclassIteratorRemovalRemainsAuthoritative() {
        final ProjectedQueue queue = new ProjectedQueue();
        assertTrue(queue.remove("a"));
        assertEquals(Arrays.asList("b", "a"), queue.projected);
        assertFalse(queue.remove("missing"));
        assertEquals(2, queue.iteratorCalls);
        assertEquals(0, queue.size());
    }

    @Test
    void malformedNullSlotRetainsOldIteratorRemoveBehavior() throws Exception {
        final InheritedRemove<Object> reference = new InheritedRemove<>(3);
        final CircularFifoQueue<Object> actual = new CircularFifoQueue<>(3);
        final Object value = new Object();
        reference.add(value);
        actual.add(value);
        final Field field = CircularFifoQueue.class.getDeclaredField("elements");
        field.setAccessible(true);
        ((Object[]) field.get(reference))[0] = null;
        ((Object[]) field.get(actual))[0] = null;
        assertEquals(reference.remove(null), actual.remove(null));
        sameState(reference, actual);
    }
}
