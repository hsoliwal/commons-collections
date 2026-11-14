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

import com.m3.collections.apache.FluentIterable;
import org.junit.jupiter.api.Test;

/** Behavior comparison with the original list-backed looping implementation. */
class M3ZippingIteratorIndexedRingTest {

    @Test
    void interleavesWithExhaustedAndEmptyChildren() {
        final ZippingIterator<Integer> iterator = new ZippingIterator<>(
                Arrays.asList(1, 2, 3).iterator(),
                Collections.<Integer>emptyList().iterator(),
                Collections.singletonList(10).iterator(),
                Arrays.asList(100, 101).iterator(),
                Collections.<Integer>emptyList().iterator());
        final List<Integer> actual = new ArrayList<>();
        while (iterator.hasNext()) {
            assertTrue(iterator.hasNext());
            actual.add(iterator.next());
        }
        assertEquals(Arrays.asList(1, 10, 100, 2, 101, 3), actual);
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    @Test
    void snapshotsCallerArrayAndHandlesDuplicateSourceIdentity() {
        @SuppressWarnings("unchecked")
        final Iterator<Integer>[] input = new Iterator[] {
            Arrays.asList(1, 3).iterator(), Arrays.asList(2, 4).iterator()
        };
        final ZippingIterator<Integer> snapshot = new ZippingIterator<>(input);
        input[0] = Collections.singletonList(999).iterator();
        assertEquals(Arrays.asList(1, 2, 3, 4), drain(snapshot));

        final Iterator<Integer> shared = Arrays.asList(5, 6, 7).iterator();
        assertEquals(Arrays.asList(5, 6, 7), drain(new ZippingIterator<>(shared, shared)));
    }

    @Test
    void preservesRemovalDelegationAndExceptionOrdering() {
        final List<Integer> a = new ArrayList<>(Arrays.asList(1, 3, 5));
        final List<Integer> b = new ArrayList<>(Arrays.asList(2, 4, 6));
        final ZippingIterator<Integer> zipped = new ZippingIterator<>(a.iterator(), b.iterator());
        assertThrows(IllegalStateException.class, zipped::remove);
        assertEquals(Integer.valueOf(1), zipped.next());
        assertTrue(zipped.hasNext());
        zipped.remove();
        assertThrows(IllegalStateException.class, zipped::remove);
        assertEquals(Integer.valueOf(2), zipped.next());
        zipped.remove();
        assertEquals(Arrays.asList(3, 5), a);
        assertEquals(Arrays.asList(4, 6), b);
        assertEquals(Arrays.asList(3, 4, 5, 6), drain(zipped));
    }

    @Test
    void validatesAllChildrenBeforeIterationAndSupportsEmptyInput() {
        assertThrows(NullPointerException.class,
                () -> new ZippingIterator<>(Collections.singletonList(1).iterator(), null));
        final ZippingIterator<Integer> empty = new ZippingIterator<>();
        assertFalse(empty.hasNext());
        assertThrows(NoSuchElementException.class, empty::next);
        assertThrows(IllegalStateException.class, empty::remove);
    }

    @Test
    void removesThousandsOfExhaustedSlotsWithoutChangingTheOrder() {
        @SuppressWarnings("unchecked")
        final Iterator<Integer>[] children = new Iterator[4_096];
        final List<Integer> expected = new ArrayList<>();
        for (int index = 0; index < children.length; index++) {
            if (index % 3 == 0) {
                children[index] = Collections.<Integer>emptyList().iterator();
            } else {
                children[index] = Collections.singletonList(index).iterator();
                expected.add(index);
            }
        }
        assertEquals(expected, drain(new ZippingIterator<>(children)));
    }

    @Test
    void deterministicDifferentialAgainstOriginalListLoop() {
        for (int seed = 0; seed < 256; seed++) {
            final Random random = new Random(seed);
            final int childCount = random.nextInt(9);
            final List<List<Integer>> newLists = new ArrayList<>();
            final List<List<Integer>> oldLists = new ArrayList<>();
            @SuppressWarnings("unchecked")
            final Iterator<Integer>[] newSources = new Iterator[childCount];
            @SuppressWarnings("unchecked")
            final Iterator<Integer>[] oldSources = new Iterator[childCount];
            for (int child = 0; child < childCount; child++) {
                final List<Integer> values = new ArrayList<>();
                final int length = random.nextInt(8);
                for (int item = 0; item < length; item++) {
                    values.add(child * 100 + item);
                }
                newLists.add(values);
                oldLists.add(new ArrayList<>(values));
                newSources[child] = values.iterator();
                oldSources[child] = oldLists.get(child).iterator();
            }
            final ZippingIterator<Integer> candidate = new ZippingIterator<>(newSources);
            final ReferenceZipper<Integer> baseline = new ReferenceZipper<>(oldSources);
            int steps = 0;
            while (true) {
                final boolean hasNext = baseline.hasNext();
                assertEquals(hasNext, candidate.hasNext(), "seed=" + seed);
                assertEquals(hasNext, candidate.hasNext(), "repeated hasNext seed=" + seed);
                if (!hasNext) {
                    break;
                }
                assertEquals(baseline.next(), candidate.next(), "seed=" + seed);
                if (random.nextInt(4) == 0) {
                    baseline.remove();
                    candidate.remove();
                }
                steps++;
                assertTrue(steps <= childCount * 8, "iteration must terminate");
            }
            assertEquals(oldLists, newLists, "removal state seed=" + seed);
        }
    }

    private static <T> List<T> drain(final Iterator<T> iterator) {
        final List<T> result = new ArrayList<>();
        while (iterator.hasNext()) {
            result.add(iterator.next());
        }
        return result;
    }

    /** Frozen original source algorithm for independent behavior comparison. */
    private static final class ReferenceZipper<T> implements Iterator<T> {
        private final Iterator<Iterator<? extends T>> iterators;
        private Iterator<? extends T> nextIterator;
        private Iterator<? extends T> lastReturned;

        private ReferenceZipper(final Iterator<? extends T>[] sources) {
            final List<Iterator<? extends T>> list = new ArrayList<>();
            for (final Iterator<? extends T> source : sources) {
                list.add(Objects.requireNonNull(source, "iterator"));
            }
            iterators = FluentIterable.of(list).loop().iterator();
        }

        @Override
        public boolean hasNext() {
            if (nextIterator != null) {
                return true;
            }
            while (iterators.hasNext()) {
                final Iterator<? extends T> source = iterators.next();
                if (source.hasNext()) {
                    nextIterator = source;
                    return true;
                }
                iterators.remove();
            }
            return false;
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            final T value = nextIterator.next();
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
}
