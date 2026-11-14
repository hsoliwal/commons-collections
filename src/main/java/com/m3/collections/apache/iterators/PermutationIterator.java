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
// Modified 2026 by Hitesh Soliwal and Contributors to the Synexia Project:
// parent-owned payload array decoded by the existing permutation keys.
// Modified 2026 by Hitesh Soliwal and Synexia contributors: cached mobile key and bulk snapshot copying.
package com.m3.collections.apache.iterators;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * This iterator creates permutations of an input collection, using the
 * Steinhaus-Johnson-Trotter algorithm (also called plain changes).
 * <p>
 * The iterator will return exactly n! permutations of the input collection.
 * The {@code remove()} operation is not supported, and will throw an
 * {@code UnsupportedOperationException}.
 * </p>
 * <p>
 * NOTE: in case an empty collection is provided, the iterator will
 * return exactly one empty list as result, as 0! = 1.
 * </p>
 *
 * @param <E>  the type of the objects being permuted
 * @since 4.0
 */
public class PermutationIterator<E> implements Iterator<List<E>> {

    /**
     * Permutation is done on these keys to handle equal objects.
     */
    private final int[] keys;

    /**
     * Payloads owned by dense, one-based permutation keys.
     */
    private final Object[] objects;

    /**
     * Direction table used in the algorithm:
     * <ul>
     *   <li>false is left</li>
     *   <li>true is right</li>
     * </ul>
     */
    private final boolean[] direction;

    /**
     * Next permutation to return. When a permutation is requested
     * this instance is provided and the next one is computed.
     */
    private List<E> nextPermutation;

    /**
     * Low 31 bits cache the largest key's position; MAX_VALUE disables the cache.
     * The sign bit marks a next result decoded from the current keys.
     */
    private int permutationState = Integer.MAX_VALUE;

    /**
     * Standard constructor for this class.
     *
     * @param collection  The collection to generate permutations for
     * @throws NullPointerException if coll is null
     */
    public PermutationIterator(final Collection<? extends E> collection) {
        Objects.requireNonNull(collection, "collection");
        keys = new int[collection.size()];
        direction = new boolean[collection.size()];
        Arrays.fill(direction, false);
        int value = 1;
        objects = new Object[keys.length];
        for (final E e : collection) {
            objects[value - 1] = e;
            keys[value - 1] = value;
            value++;
        }
        nextPermutation = new ArrayList<>(collection);
        if (keys.length != 0 && value - 1 == keys.length && keys.length == direction.length) {
            permutationState = keys.length - 1;
        }
    }

    /**
     * Decodes an ordinal without boxing or retaining an entry object. A zero key
     * is an unfilled constructor slot and retains the previous absent-map value.
     *
     * @param key One-based permutation key, or zero for an unfilled slot.
     * @return The original payload reference, or null for an unfilled slot.
     */
    @SuppressWarnings("unchecked")
    private E elementAtKey(final int key) {
        return key == 0 ? null : (E) objects[key - 1];
    }

    /**
     * Indicates if there are more permutation available.
     *
     * @return true if there are more permutations, otherwise false
     */
    @Override
    public boolean hasNext() {
        return nextPermutation != null;
    }

    /**
     * Returns the next permutation of the input collection.
     *
     * @return A list of the permutator's elements representing a permutation
     * @throws NoSuchElementException if there are no more permutations
     */
    @Override
    public List<E> next() {
        if (keys.length >= 4) {
            return nextLarge();
        }
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        // find the largest mobile integer k
        int indexOfLargestMobileInteger = -1;
        int largestKey = -1;
        for (int i = 0; i < keys.length; i++) {
            if (direction[i] && i < keys.length - 1 && keys[i] > keys[i + 1] ||
                !direction[i] && i > 0 && keys[i] > keys[i - 1]) {
                if (keys[i] > largestKey) { // NOPMD
                    largestKey = keys[i];
                    indexOfLargestMobileInteger = i;
                }
            }
        }
        if (largestKey == -1) {
            final List<E> toReturn = nextPermutation;
            nextPermutation = null;
            return toReturn;
        }

        // swap k and the adjacent integer it is looking at
        final int offset = direction[indexOfLargestMobileInteger] ? 1 : -1;
        final int tmpKey = keys[indexOfLargestMobileInteger];
        keys[indexOfLargestMobileInteger] = keys[indexOfLargestMobileInteger + offset];
        keys[indexOfLargestMobileInteger + offset] = tmpKey;
        final boolean tmpDirection = direction[indexOfLargestMobileInteger];
        direction[indexOfLargestMobileInteger] = direction[indexOfLargestMobileInteger + offset];
        direction[indexOfLargestMobileInteger + offset] = tmpDirection;

        // reverse the direction of all integers larger than k and build the result
        final List<E> nextP = new ArrayList<>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] > largestKey) {
                direction[i] = !direction[i];
            }
            nextP.add(elementAtKey(keys[i]));
        }
        final List<E> result = nextPermutation;
        nextPermutation = nextP;
        return result;
    }

    /** Use cached ordinal state and bulk copies when the input is large enough. */
    private List<E> nextLarge() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        // find the largest mobile integer k
        int indexOfLargestMobileInteger = -1;
        int largestKey = -1;
        final int largestIndex = permutationState & Integer.MAX_VALUE;
        if (largestIndex < keys.length && (direction[largestIndex]
                ? largestIndex < keys.length - 1 : largestIndex > 0)) {
            // The largest key is mobile whenever it does not face an end.
            indexOfLargestMobileInteger = largestIndex;
            largestKey = keys[largestIndex];
        } else {
            for (int i = 0; i < keys.length; i++) {
                if (direction[i] && i < keys.length - 1 && keys[i] > keys[i + 1] ||
                    !direction[i] && i > 0 && keys[i] > keys[i - 1]) {
                    if (keys[i] > largestKey) { // NOPMD
                        largestKey = keys[i];
                        indexOfLargestMobileInteger = i;
                    }
                }
            }
        }
        if (largestKey == -1) {
            final List<E> toReturn = nextPermutation;
            nextPermutation = null;
            return toReturn;
        }

        // swap k and the adjacent integer it is looking at
        final int offset = direction[indexOfLargestMobileInteger] ? 1 : -1;
        final int tmpKey = keys[indexOfLargestMobileInteger];
        keys[indexOfLargestMobileInteger] = keys[indexOfLargestMobileInteger + offset];
        keys[indexOfLargestMobileInteger + offset] = tmpKey;
        final boolean tmpDirection = direction[indexOfLargestMobileInteger];
        direction[indexOfLargestMobileInteger] = direction[indexOfLargestMobileInteger + offset];
        direction[indexOfLargestMobileInteger + offset] = tmpDirection;

        final boolean copyPrevious = permutationState < 0;
        // Clear the canonical flag before allocation so a failed call is replayable.
        permutationState = largestIndex;
        if (largestIndex == indexOfLargestMobileInteger) {
            permutationState += offset;
        } else if (largestIndex == indexOfLargestMobileInteger + offset) {
            permutationState = indexOfLargestMobileInteger;
        }
        final List<E> nextP;
        if (copyPrevious) {
            // This ArrayList has never escaped to a caller. Copy its backing
            // values in bulk, then update only the two adjacent changed slots.
            nextP = new ArrayList<>(nextPermutation);
            nextP.set(indexOfLargestMobileInteger, elementAtKey(keys[indexOfLargestMobileInteger]));
            nextP.set(indexOfLargestMobileInteger + offset,
                    elementAtKey(keys[indexOfLargestMobileInteger + offset]));
            if (largestKey != keys.length) {
                for (int i = 0; i < keys.length; i++) {
                    if (keys[i] > largestKey) {
                        direction[i] = !direction[i];
                    }
                }
            }
        } else {
            // Keep the first build's ordinal decoding: collection.toArray()
            // need not have returned the same image as collection.iterator().
            nextP = new ArrayList<>(keys.length);
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] > largestKey) {
                    direction[i] = !direction[i];
                }
                nextP.add(elementAtKey(keys[i]));
            }
        }
        final List<E> result = nextPermutation;
        nextPermutation = nextP;
        permutationState |= Integer.MIN_VALUE;
        return result;
    }

    /**
     * Always throws {@link UnsupportedOperationException}.
     *
     * @throws UnsupportedOperationException Always thrown.
     */
    @Override
    public void remove() {
        throw new UnsupportedOperationException("remove() is not supported");
    }

}
