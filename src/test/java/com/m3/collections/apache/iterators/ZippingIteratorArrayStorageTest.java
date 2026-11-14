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
package com.m3.collections.apache.iterators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.m3.collections.apache.FluentIterable;

/**
 * Behavioral contracts for the array-owned round-robin iterator.
 * The differential reference retains the previous production algorithm.
 */
class ZippingIteratorArrayStorageTest {

    /** Incumbent algorithm, independent of the new array ownership. */
    private static final class LegacyZippingIterator<E> implements Iterator<E> {
        private final Iterator<Iterator<? extends E>> iterators;
        private Iterator<? extends E> nextIterator;
        private Iterator<? extends E> lastReturned;

        LegacyZippingIterator(final Iterator<? extends E>[] children) {
            final List<Iterator<? extends E>> list = new ArrayList<>();
            for (final Iterator<? extends E> child : children) {
                Objects.requireNonNull(child, "iterator");
                list.add(child);
            }
            iterators = FluentIterable.of(list).loop().iterator();
        }

        @Override
        public boolean hasNext() {
            if (nextIterator != null) {
                return true;
            }
            while (iterators.hasNext()) {
                final Iterator<? extends E> child = iterators.next();
                if (child.hasNext()) {
                    nextIterator = child;
                    return true;
                }
                iterators.remove();
            }
            return false;
        }

        @Override
        public E next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            final E value = nextIterator.next();
            lastReturned = nextIterator;
            nextIterator = null;
            return value;
        }

        @Override
        public void remove() {
            if (lastReturned == null) {
                throw new IllegalStateException("No value can be removed at present");
            }
            lastReturned.remove();
            lastReturned = null;
        }
    }

    /** Mutable, observable child with one independently scheduled failure per method. */
    private static final class ProbeIterator implements Iterator<Integer> {
        private final String name;
        private final List<String> trace;
        private final List<Integer> values;
        private int position;
        private int removable = -1;
        private int probes;
        private int nextCalls;
        private int removeCalls;
        private int failProbe;
        private int failNext;
        private int failRemove;

        ProbeIterator(final String name, final List<String> trace, final List<Integer> values) {
            this.name = name;
            this.trace = trace;
            this.values = new ArrayList<>(values);
        }

        @Override
        public boolean hasNext() {
            trace.add(name + ".hasNext:" + ++probes);
            if (probes == failProbe) {
                throw new TestFailure(name + ".hasNext");
            }
            return position < values.size();
        }

        @Override
        public Integer next() {
            trace.add(name + ".next:" + ++nextCalls);
            if (nextCalls == failNext) {
                throw new TestFailure(name + ".next");
            }
            if (position >= values.size()) {
                throw new NoSuchElementException(name);
            }
            removable = position++;
            return values.get(removable);
        }

        @Override
        public void remove() {
            trace.add(name + ".remove:" + ++removeCalls);
            if (removeCalls == failRemove) {
                throw new TestFailure(name + ".remove");
            }
            if (removable < 0) {
                throw new IllegalStateException(name);
            }
            values.remove(removable);
            position--;
            removable = -1;
        }

        String state() {
            return values + ":" + position + ":" + removable;
        }
    }

    private static final class TestFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TestFailure(final String message) {
            super(message);
        }
    }

    private static List<Integer> drain(final Iterator<Integer> iterator) {
        final List<Integer> result = new ArrayList<>();
        iterator.forEachRemaining(result::add);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Iterator<Integer>[] iteratorArray(final int size) {
        return (Iterator<Integer>[]) new Iterator<?>[size];
    }

    private static Iterator<Integer> makeIterator(final boolean legacy, final Iterator<Integer>[] children) {
        return legacy ? new LegacyZippingIterator<>(children) : new ZippingIterator<>(children);
    }

    private static String outcome(final Iterator<Integer> iterator, final int operation) {
        try {
            switch (operation) {
            case 0:
                return "hasNext:" + iterator.hasNext();
            case 1:
            case 2:
                return "next:" + iterator.next();
            default:
                iterator.remove();
                return "remove";
            }
        } catch (final RuntimeException exception) {
            return exception.getClass().getName() + ":" + exception.getMessage();
        }
    }

    private static ProbeIterator probe(final String name, final List<String> trace, final Integer... values) {
        return new ProbeIterator(name, trace, Arrays.asList(values));
    }

    private static List<String> reentrantTrace(final boolean legacy) {
        final List<String> trace = new ArrayList<>();
        final List<Iterator<Integer>> owner = new ArrayList<>();
        final Iterator<Integer> first = new Iterator<Integer>() {
            private boolean once = true;

            @Override
            public boolean hasNext() {
                trace.add("a.hasNext");
                if (once) {
                    once = false;
                    trace.add("inner:" + owner.get(0).hasNext());
                }
                return false;
            }

            @Override
            public Integer next() {
                trace.add("a.next");
                return 1;
            }
        };
        final ProbeIterator second = probe("b", trace, 2);
        final ProbeIterator third = probe("c", trace);
        final Iterator<Integer>[] children = iteratorArray(3);
        children[0] = first;
        children[1] = second;
        children[2] = third;
        final Iterator<Integer> iterator = makeIterator(legacy, children);
        owner.add(iterator);
        for (int step = 0; step < 10; step++) {
            trace.add(outcome(iterator, step % 2));
        }
        return trace;
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    private static ZippingIterator<Integer> zip(final Iterator<? extends Integer>... children) {
        return new ZippingIterator<>(children);
    }

    @Test
    void testCachedHasNextDoesNotProbeChildAgain() {
        final List<String> trace = new ArrayList<>();
        final ProbeIterator child = probe("a", trace, 1);
        final ZippingIterator<Integer> iterator = zip(child);
        for (int repeat = 0; repeat < 20; repeat++) {
            assertTrue(iterator.hasNext());
        }
        assertEquals(1, child.probes);
        assertEquals(Integer.valueOf(1), iterator.next());
        assertEquals(1, child.probes);
        for (int repeat = 0; repeat < 20; repeat++) {
            assertFalse(iterator.hasNext());
        }
        assertEquals(2, child.probes);
    }

    @Test
    void testCallerArrayIsDetached() {
        final Iterator<Integer>[] children = iteratorArray(2);
        children[0] = Arrays.asList(1, 2).iterator();
        children[1] = Arrays.asList(10, 20).iterator();
        final ZippingIterator<Integer> iterator = zip(children);
        children[0] = Collections.<Integer>emptyList().iterator();
        children[1] = null;
        assertEquals(Arrays.asList(1, 10, 2, 20), drain(iterator));
    }

    @Test
    void testDuplicateChildReferencesKeepDistinctTurns() {
        final Iterator<Integer> shared = Arrays.asList(1, 2, 3, 4, 5).iterator();
        final ZippingIterator<Integer> iterator = zip(shared, Arrays.asList(9, 10).iterator(), shared);
        assertEquals(Arrays.asList(1, 9, 2, 3, 10, 4, 5), drain(iterator));
    }

    @Test
    void testEmptyInput() {
        final ZippingIterator<Integer> iterator = zip();
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
        assertThrows(IllegalStateException.class, iterator::remove);
        assertFalse(iterator.hasNext());
    }

    @Test
    void testExhaustionDoesNotRemoveFromChildren() {
        final List<String> trace = new ArrayList<>();
        final ProbeIterator child = probe("empty", trace);
        final ZippingIterator<Integer> iterator = zip(child);
        assertFalse(iterator.hasNext());
        assertEquals(0, child.removeCalls);
        assertThrows(IllegalStateException.class, iterator::remove);
        assertEquals(0, child.removeCalls);
    }

    @Test
    void testExhaustedChildStaysRemovedAfterReplenishment() {
        final List<String> trace = new ArrayList<>();
        final ProbeIterator empty = probe("empty", trace);
        final ProbeIterator live = probe("live", trace, 1);
        final ZippingIterator<Integer> iterator = zip(empty, live);
        assertEquals(Integer.valueOf(1), iterator.next());
        empty.values.add(99);
        live.values.add(2);
        assertEquals(Integer.valueOf(2), iterator.next());
        assertFalse(iterator.hasNext());
        live.values.add(3);
        assertFalse(iterator.hasNext());
        assertEquals(1, empty.probes);
        assertEquals(3, live.probes);
    }

    @Test
    void testFailedNextKeepsCachedChildAndPreviousRemoval() {
        final List<String> trace = new ArrayList<>();
        final ProbeIterator first = probe("a", trace, 1, 2);
        final ProbeIterator second = probe("b", trace, 9);
        second.failNext = 1;
        final ZippingIterator<Integer> iterator = zip(first, second);
        assertEquals(Integer.valueOf(1), iterator.next());
        assertThrows(TestFailure.class, iterator::next);
        iterator.remove();
        assertEquals(Collections.singletonList(2), first.values);
        assertTrue(iterator.hasNext());
        assertEquals(Integer.valueOf(9), iterator.next());
        assertEquals(1, second.probes);
        assertEquals(2, second.nextCalls);
        assertEquals(Integer.valueOf(2), iterator.next());
    }

    @Test
    void testFailedProbeAdvancesTurnButRetainsChild() {
        final List<String> trace = new ArrayList<>();
        final ProbeIterator first = probe("a", trace, 1);
        final ProbeIterator second = probe("b", trace, 9);
        first.failProbe = 1;
        final ZippingIterator<Integer> iterator = zip(first, second);
        assertThrows(TestFailure.class, iterator::hasNext);
        assertEquals(Integer.valueOf(9), iterator.next());
        assertEquals(Integer.valueOf(1), iterator.next());
        assertFalse(iterator.hasNext());
        assertEquals(Arrays.asList("a.hasNext:1", "b.hasNext:1", "b.next:1", "a.hasNext:2",
                "a.next:1", "b.hasNext:2", "a.hasNext:3"), trace);
    }

    @Test
    void testFailedRemoveCanBeRetried() {
        final List<String> trace = new ArrayList<>();
        final ProbeIterator child = probe("a", trace, 1);
        child.failRemove = 1;
        final ZippingIterator<Integer> iterator = zip(child);
        iterator.next();
        assertThrows(TestFailure.class, iterator::remove);
        iterator.remove();
        assertTrue(child.values.isEmpty());
        assertEquals(2, child.removeCalls);
        assertThrows(IllegalStateException.class, iterator::remove);
        assertEquals(2, child.removeCalls);
    }

    @Test
    void testForEachRemainingActionFailureKeepsLastReturnedChild() {
        final List<String> trace = new ArrayList<>();
        final ProbeIterator first = probe("a", trace, 1, 2);
        final ProbeIterator second = probe("b", trace, 10, 11);
        final ZippingIterator<Integer> iterator = zip(first, second);
        assertThrows(NullPointerException.class, () -> iterator.forEachRemaining(null));
        assertTrue(trace.isEmpty());
        final List<Integer> consumed = new ArrayList<>();
        assertThrows(TestFailure.class, () -> iterator.forEachRemaining(value -> {
            consumed.add(value);
            if (value.intValue() == 10) {
                throw new TestFailure("action");
            }
        }));
        assertEquals(Arrays.asList(1, 10), consumed);
        iterator.remove();
        assertEquals(Collections.singletonList(11), second.values);
        assertEquals(Arrays.asList(2, 11), drain(iterator));
    }

    @Test
    void testManyEmptyChildrenAndRaggedRounds() {
        final int count = 32768;
        final Iterator<Integer>[] children = iteratorArray(count);
        final List<List<Integer>> values = new ArrayList<>();
        for (int child = 0; child < count; child++) {
            final List<Integer> lane = new ArrayList<>();
            if (child % 4093 == 0 || child == count - 1) {
                for (int position = 0; position < child % 7 + 1; position++) {
                    lane.add(child * 10 + position);
                }
            }
            values.add(lane);
            children[child] = lane.iterator();
        }
        final List<Integer> expected = new ArrayList<>();
        for (int position = 0; position < 7; position++) {
            for (final List<Integer> lane : values) {
                if (position < lane.size()) {
                    expected.add(lane.get(position));
                }
            }
        }
        assertEquals(expected, drain(zip(children)));
    }

    @Test
    void testNullArrayAndChildRejectWithoutProbing() {
        assertThrows(NullPointerException.class, () -> zip((Iterator<Integer>[]) null));
        final List<String> trace = new ArrayList<>();
        final ProbeIterator child = probe("a", trace, 1);
        final NullPointerException exception = assertThrows(NullPointerException.class, () -> zip(child, null));
        assertEquals("iterator", exception.getMessage());
        assertTrue(trace.isEmpty());
    }

    @Test
    void testRandomizedDifferentialCallbackAndMutationTraces() {
        final Random random = new Random(0x5a495050494e47L);
        for (int scenario = 0; scenario < 256; scenario++) {
            final List<String> expectedTrace = new ArrayList<>();
            final List<String> actualTrace = new ArrayList<>();
            final List<ProbeIterator> expectedPool = new ArrayList<>();
            final List<ProbeIterator> actualPool = new ArrayList<>();
            final int poolSize = random.nextInt(8) + 1;
            for (int child = 0; child < poolSize; child++) {
                final List<Integer> values = new ArrayList<>();
                final int length = random.nextInt(9);
                for (int position = 0; position < length; position++) {
                    values.add(random.nextInt(5) == 0 ? null : Integer.valueOf(random.nextInt(31) - 15));
                }
                final ProbeIterator expected = new ProbeIterator("child" + child, expectedTrace, values);
                final ProbeIterator actual = new ProbeIterator("child" + child, actualTrace, values);
                expected.failProbe = random.nextInt(3) == 0 ? random.nextInt(5) + 1 : 0;
                expected.failNext = random.nextInt(3) == 0 ? random.nextInt(5) + 1 : 0;
                expected.failRemove = random.nextInt(3) == 0 ? random.nextInt(5) + 1 : 0;
                actual.failProbe = expected.failProbe;
                actual.failNext = expected.failNext;
                actual.failRemove = expected.failRemove;
                expectedPool.add(expected);
                actualPool.add(actual);
            }
            final int slots = random.nextInt(17);
            final Iterator<Integer>[] expectedChildren = iteratorArray(slots);
            final Iterator<Integer>[] actualChildren = iteratorArray(slots);
            for (int slot = 0; slot < slots; slot++) {
                final int child = random.nextInt(poolSize);
                expectedChildren[slot] = expectedPool.get(child);
                actualChildren[slot] = actualPool.get(child);
            }
            final Iterator<Integer> expected = new LegacyZippingIterator<>(expectedChildren);
            final Iterator<Integer> actual = zip(actualChildren);
            for (int step = 0; step < 120; step++) {
                final int operation = random.nextInt(4);
                final String location = "scenario=" + scenario + ", step=" + step + ", op=" + operation;
                assertEquals(outcome(expected, operation), outcome(actual, operation), location);
                assertEquals(expectedTrace, actualTrace, location + " callback order");
                for (int child = 0; child < poolSize; child++) {
                    assertEquals(expectedPool.get(child).state(), actualPool.get(child).state(), location);
                }
            }
        }
    }

    @Test
    void testReentrantProbePreservesIncumbentCallbackTrace() {
        assertEquals(reentrantTrace(true), reentrantTrace(false));
    }

    @Test
    void testRemoveAfterCompleteExhaustion() {
        final List<String> trace = new ArrayList<>();
        final ProbeIterator child = probe("a", trace, 1);
        final ZippingIterator<Integer> iterator = zip(child);
        iterator.next();
        assertFalse(iterator.hasNext());
        iterator.remove();
        assertTrue(child.values.isEmpty());
        assertThrows(IllegalStateException.class, iterator::remove);
    }

    @Test
    void testRemoveAfterPrefetchTargetsPreviousChild() {
        final List<String> trace = new ArrayList<>();
        final ProbeIterator first = probe("a", trace, 1);
        final ProbeIterator second = probe("b", trace, 2);
        final ZippingIterator<Integer> iterator = zip(first, second);
        iterator.next();
        assertTrue(iterator.hasNext());
        iterator.remove();
        assertTrue(first.values.isEmpty());
        assertEquals(Collections.singletonList(2), second.values);
        assertEquals(Integer.valueOf(2), iterator.next());
        iterator.remove();
        assertTrue(second.values.isEmpty());
    }

    @Test
    void testSparseUnequalChildrenAndNullPayloads() {
        final ZippingIterator<Integer> iterator = zip(Collections.<Integer>emptyList().iterator(),
                Arrays.asList(null, 10, 11).iterator(), Collections.<Integer>emptyList().iterator(),
                Arrays.asList(20, 21, 22, 23).iterator(), Collections.<Integer>emptyList().iterator());
        assertEquals(Arrays.asList(null, 20, 10, 21, 11, 22, 23), drain(iterator));
    }

    @Test
    void testSubclassHasNextRemainsVirtuallyDispatched() {
        final int[] calls = {0};
        final ZippingIterator<Integer> iterator = new ZippingIterator<Integer>(
                Collections.singletonList(1).iterator(), Collections.singletonList(2).iterator()) {
            @Override
            public boolean hasNext() {
                calls[0]++;
                return super.hasNext();
            }
        };
        assertEquals(Integer.valueOf(1), iterator.next());
        assertEquals(Integer.valueOf(2), iterator.next());
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
        assertEquals(4, calls[0]);
    }
}
