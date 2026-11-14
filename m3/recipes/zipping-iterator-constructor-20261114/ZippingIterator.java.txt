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

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Provides an interleaved iteration over the elements contained in a
 * collection of Iterators.
 * <p>
 * Given two {@link Iterator} instances {@code A} and {@code B}, the
 * {@link #next} method on this iterator will switch between {@code A.next()}
 * and {@code B.next()} until both iterators are exhausted.
 * </p>
 *
 * @param <E> The type of elements returned by this iterator.
 * @since 4.1
 */
public class ZippingIterator<E> implements Iterator<E> {

    /** The child iterators, indexed by stable slots. Exhausted slots are cleared. */
    private final Iterator<? extends E>[] iterators;

    /** The next live slot in the circular chain. */
    private final int[] nextIndices;

    /** The next slot to probe. */
    private int currentIndex;

    /** The live predecessor of the next slot to probe. */
    private int previousIndex;

    /** The number of child slots still in the circular chain. */
    private int remaining;

    /** The last examined slot, also shared by reentrant child callbacks. */
    private int lastExaminedIndex = -1;

    /** The predecessor of the last examined slot. */
    private int lastExaminedPredecessor;

    /** The next iterator to use for next(). */
    private Iterator<? extends E> nextIterator;

    /** The last iterator which was used for next(). */
    private Iterator<? extends E> lastReturned;

    /**
     * Constructs a new {@code ZippingIterator} that will provide
     * interleaved iteration of the specified iterators.
     *
     * @param iterators  The array of iterators
     * @throws NullPointerException if any iterator is null
     */
    public ZippingIterator(final Iterator<? extends E>... iterators) {
        this(iterators, true);
    }

    /**
     * Caller-owned varargs are copied; privately created two/three-argument
     * arrays are already exclusively owned and need not be copied again.
     */
    private ZippingIterator(final Iterator<? extends E>[] iterators,
                            final boolean snapshotCallerArray) {
        final int length = iterators.length;
        this.iterators = snapshotCallerArray ? iterators.clone() : iterators;
        nextIndices = new int[length];
        for (int i = 0; i < length; i++) {
            Objects.requireNonNull(this.iterators[i], "iterator");
            nextIndices[i] = i + 1 == length ? 0 : i + 1;
        }
        remaining = length;
        previousIndex = length - 1;
    }

    /**
     * Constructs a new {@code ZippingIterator} that will provide
     * interleaved iteration over the two given iterators.
     *
     * @param a  The first child iterator
     * @param b  The second child iterator
     * @throws NullPointerException if either iterator is null
     */
    @SuppressWarnings("unchecked")
    public ZippingIterator(final Iterator<? extends E> a, final Iterator<? extends E> b) {
        this(new Iterator[] {a, b}, false);
    }

    /**
     * Constructs a new {@code ZippingIterator} that will provide
     * interleaved iteration over the three given iterators.
     *
     * @param a  The first child iterator
     * @param b  The second child iterator
     * @param c  The third child iterator
     * @throws NullPointerException if either iterator is null
     */
    @SuppressWarnings("unchecked")
    public ZippingIterator(final Iterator<? extends E> a,
                           final Iterator<? extends E> b,
                           final Iterator<? extends E> c) {
        this(new Iterator[] {a, b, c}, false);
    }

    /**
     * Returns {@code true} if any child iterator has remaining elements.
     *
     * @return true if this iterator has remaining elements
     */
    @Override
    public boolean hasNext() {
        // the next iterator has already been determined
        // this might happen if hasNext() is called multiple
        if (nextIterator != null) {
            return true;
        }

        while (remaining != 0) {
            final Iterator<? extends E> childIterator = iterators[currentIndex];
            lastExaminedIndex = currentIndex;
            lastExaminedPredecessor = previousIndex;
            // Advance before invoking user code, including when hasNext() throws.
            currentIndex = nextIndices[currentIndex];
            previousIndex = lastExaminedIndex;
            if (childIterator.hasNext()) {
                nextIterator = childIterator;
                return true;
            }
            // Match the shared cursor's remove state after a reentrant callback.
            if (lastExaminedIndex == -1) {
                throw new IllegalStateException();
            }
            nextIndices[lastExaminedPredecessor] = currentIndex;
            previousIndex = lastExaminedPredecessor;
            iterators[lastExaminedIndex] = null;
            lastExaminedIndex = -1;
            remaining--;
        }
        return false;
    }

    /**
     * Returns the next element from a child iterator.
     *
     * @return The next interleaved element
     * @throws NoSuchElementException if no child iterator has any more elements
     */
    @Override
    public E next() throws NoSuchElementException {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        final E val = nextIterator.next();
        lastReturned = nextIterator;
        nextIterator = null;
        return val;
    }

    /**
     * Removes the last returned element from the child iterator that produced it.
     *
     * @throws IllegalStateException if there is no last returned element, or if
     *   the last returned element has already been removed
     */
    @Override
    public void remove() {
        if (lastReturned == null) {
            throw new IllegalStateException("No value can be removed at present");
        }
        lastReturned.remove();
        lastReturned = null;
    }

}
