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
// First-party differential regression: Copyright 2026 Synexia contributors.
package com.m3.collections.apache.queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Differential original-iterator oracle for the dense ring membership and removal fast path. */
class CircularFifoQueueRingMembershipTest {
    private enum Item { FIRST, SECOND }

    /** Subclass forces the existing AbstractCollection iterator path. */
    private static final class IteratorBaseline<E> extends CircularFifoQueue<E> {
        private static final long serialVersionUID = 1L;
        private IteratorBaseline(int capacity) { super(capacity); }
    }

    private static final class ProjectedQueue extends CircularFifoQueue<String> {
        private static final long serialVersionUID = 1L;
        private final ArrayList<String> projection = new ArrayList<>(Arrays.asList("projected"));
        private int iteratorCalls;
        private ProjectedQueue() { super(4); }
        @Override public int size() { return projection.size(); }
        @Override public Iterator<String> iterator() {
            iteratorCalls++;
            return projection.iterator();
        }
    }

    private static final class MutableEquals {
        private final Runnable onEquals;
        private int calls;
        private MutableEquals(Runnable onEquals) { this.onEquals = onEquals; }
        @Override public boolean equals(Object other) {
            calls++;
            if (calls == 1) onEquals.run();
            return other instanceof MutableEquals;
        }
        @Override public int hashCode() { return 7; }
    }

    private static void same(CircularFifoQueue<?> optimized, CircularFifoQueue<?> baseline) {
        assertEquals(baseline.size(), optimized.size());
        assertArrayEquals(baseline.toArray(), optimized.toArray());
    }

    @Test
    void randomizedWrappedRingsMatchInheritedIteratorMethods() {
        Object[] values = {"x", "y", "z", 5, 5L, (short) 5, (byte) 5,
                '5', true, 5.0, 5.0f, Item.FIRST, Item.SECOND};
        Random random = new Random(0xC011EC710L);
        for (int capacity : new int[] {1, 2, 3, 4, 7, 16, 33, 128}) {
            CircularFifoQueue<Object> optimized = new CircularFifoQueue<>(capacity);
            IteratorBaseline<Object> baseline = new IteratorBaseline<>(capacity);
            for (int step = 0; step < 8_000; step++) {
                Object value = random.nextInt(9) == 0 ? null : values[random.nextInt(values.length)];
                switch (random.nextInt(6)) {
                    case 0:
                        if (value != null) assertEquals(baseline.add(value), optimized.add(value));
                        break;
                    case 1:
                        assertEquals(baseline.contains(value), optimized.contains(value));
                        break;
                    case 2:
                        assertEquals(baseline.remove(value), optimized.remove(value));
                        break;
                    case 3:
                        if (!baseline.isEmpty()) assertEquals(baseline.remove(), optimized.remove());
                        break;
                    case 4:
                        assertEquals(baseline.peek(), optimized.peek());
                        break;
                    default:
                        if (step % 47 == 0) { baseline.clear(); optimized.clear(); }
                        break;
                }
                same(optimized, baseline);
            }
        }
    }

    @Test
    void duplicateRemovalNullAndFloatingPointEqualityMatch() {
        CircularFifoQueue<Object> optimized = new CircularFifoQueue<>(4);
        IteratorBaseline<Object> baseline = new IteratorBaseline<>(4);
        for (Object item : Arrays.asList("a", "a", Double.NaN, -0.0d, "b", "a")) {
            optimized.add(item); baseline.add(item);
        }
        for (Object item : Arrays.asList(null, "absent", Double.NaN, +0.0d, -0.0d, "a", "a")) {
            assertEquals(baseline.contains(item), optimized.contains(item));
            assertEquals(baseline.remove(item), optimized.remove(item));
            same(optimized, baseline);
        }
        assertFalse(optimized.remove("absent"));
        assertFalse(optimized.contains(null));
    }

    @Test
    void customEqualsRetainsReentrantIteratorSemantics() {
        CircularFifoQueue<Object> optimized = new CircularFifoQueue<>(6);
        IteratorBaseline<Object> baseline = new IteratorBaseline<>(6);
        optimized.add(new MutableEquals(() -> {}));
        baseline.add(new MutableEquals(() -> {}));
        MutableEquals a = new MutableEquals(() -> optimized.add("after"));
        MutableEquals b = new MutableEquals(() -> baseline.add("after"));
        assertEquals(baseline.contains(b), optimized.contains(a));
        assertEquals(b.calls, a.calls);
        same(optimized, baseline);

        MutableEquals c = new MutableEquals(() -> optimized.add("last"));
        MutableEquals d = new MutableEquals(() -> baseline.add("last"));
        assertEquals(baseline.remove(d), optimized.remove(c));
        assertEquals(d.calls, c.calls);
        same(optimized, baseline);
    }

    @Test
    void subclassesKeepTheirVirtualIteratorAndRemove() {
        ProjectedQueue projected = new ProjectedQueue();
        projected.add("physical-only");
        assertTrue(projected.contains("projected"));
        assertEquals(1, projected.iteratorCalls);
        assertTrue(projected.remove("projected"));
        assertEquals(2, projected.iteratorCalls);
        assertFalse(projected.contains("physical-only"));
        assertEquals(3, projected.iteratorCalls);
        assertEquals(Collections.emptyList(), projected.projection);
    }

    @Test
    void unsafeObjectQueriesStillUseEqualsAndPreserveExceptions() {
        CircularFifoQueue<Object> optimized = new CircularFifoQueue<>(3);
        IteratorBaseline<Object> baseline = new IteratorBaseline<>(3);
        optimized.add("x"); baseline.add("x");
        class FailingEquals {
            @Override public boolean equals(Object candidate) {
                Objects.requireNonNull(candidate);
                throw new IllegalStateException("same exception");
            }
            @Override public int hashCode() { return 5; }
        }
        FailingEquals query = new FailingEquals();
        assertEquals("same exception", assertThrows(IllegalStateException.class,
                () -> baseline.contains(query)).getMessage());
        assertEquals("same exception", assertThrows(IllegalStateException.class,
                () -> optimized.contains(query)).getMessage());
        assertEquals("same exception", assertThrows(IllegalStateException.class,
                () -> baseline.remove(query)).getMessage());
        assertEquals("same exception", assertThrows(IllegalStateException.class,
                () -> optimized.remove(query)).getMessage());
        same(optimized, baseline);
    }
}
