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
// Modified 2026-11-02 by Hitesh Soliwal and Synexia contributors: use primitive count lanes.
package com.m3.collections.apache.bloomfilter;

import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.function.BiPredicate;
import java.util.function.IntPredicate;

/**
 * Some Bloom filter implementations use a count rather than a bit flag. The term {@code Cell} is used to
 * refer to these counts and their associated index.  This class is the equivalent of the index extractor except
 * that it produces cells.
 *
 * <p>Note that a CellExtractor must not return duplicate indices and must be ordered.</p>
 *
 * <p>Implementations must guarantee that:</p>
 *
 * <ul>
 * <li>The IndexExtractor implementation returns unique ordered indices.</li>
 * <li>The cells are produced in IndexExtractor order.</li>
 * <li>For every value produced by the IndexExtractor there will be only one matching
 * cell produced by the CellExtractor.</li>
 * <li>The CellExtractor will not generate cells with indices that are not output by the IndexExtractor.</li>
 * <li>The IndexExtractor will not generate indices that have a zero count for the cell.</li>
 * </ul>
 *
 * @since 4.5.0-M2
 */
@FunctionalInterface
public interface CellExtractor extends IndexExtractor {

    /**
     * Represents an operation that accepts an {@code <index, count>} pair.
     * Returns {@code true} if processing should continue, {@code false} otherwise.
     *
     * <p>Note: This is a functional interface as a specialization of
     * {@link BiPredicate} for {@code int}.</p>
     */
    @FunctionalInterface
    interface CellPredicate {

        /**
         * Performs an operation on the given {@code <index, count>} pair.
         *
         * @param index The bit index.
         * @param count The cell value at the specified bit index.
         * @return {@code true} if processing should continue, {@code false} if processing should stop.
         */
        boolean test(int index, int count);
    }

    /**
     * Creates a CellExtractor from an IndexExtractor.
     *
     * <p>Note the following properties:</p>
     * <ul>
     * <li>Each index returned from the IndexExtractor is assumed to have a cell value of 1.</li>
     * <li>The CellExtractor aggregates duplicate indices from the IndexExtractor.</li>
     * </ul>
     *
     * <p>A CellExtractor that outputs the mapping [(1, 2),(2, 3),(3, 1)] can be created from many combinations
     * of indices including:</p>
     * <pre>
     * [1, 1, 2, 2, 2, 3]
     * [1, 3, 1, 2, 2, 2]
     * [3, 2, 1, 2, 1, 2]
     * ...
     * </pre>
     *
     * @param indexExtractor An index indexExtractor.
     * @return A CellExtractor with the same indices as the IndexExtractor.
     */
    static CellExtractor from(final IndexExtractor indexExtractor) {
        return new CellExtractor() {

            // The existing SparseBloomFilter AVL/successor pattern, with one
            // primitive count lane. No tree node or counter object is retained.
            private static final int NONE = -1;
            private int[] indices = new int[0];
            private int[] counts = indices;
            private int[] left = indices;
            private int[] right = indices;
            private int[] next = indices;
            private byte[] heights = new byte[0];
            private int root = NONE;
            private int first = NONE;
            private int size;
            private int modCount;

            @Override
            public int[] asIndexArray() {
                populate();
                final int[] result = new int[size];
                int offset = 0;
                for (int slot = first; slot != NONE; slot = next[slot]) {
                    result[offset++] = indices[slot];
                }
                return result;
            }

            /** Publish all lanes only after every allocation succeeds. */
            private void grow() {
                final long proposed = Math.max(8L, indices.length + (long) (indices.length >>> 1));
                final int capacity = (int) Math.min(Integer.MAX_VALUE - 8L, proposed);
                if (capacity <= indices.length) {
                    throw new OutOfMemoryError("CellExtractor slot capacity exhausted");
                }
                final int[] newIndices = Arrays.copyOf(indices, capacity);
                final int[] newCounts = Arrays.copyOf(counts, capacity);
                final int[] newLeft = Arrays.copyOf(left, capacity);
                final int[] newRight = Arrays.copyOf(right, capacity);
                final int[] newNext = Arrays.copyOf(next, capacity);
                final byte[] newHeights = Arrays.copyOf(heights, capacity);
                indices = newIndices;
                counts = newCounts;
                left = newLeft;
                right = newRight;
                next = newNext;
                heights = newHeights;
            }

            private int height(final int slot) {
                return slot == NONE ? -1 : heights[slot];
            }

            private void recalcHeight(final int slot) {
                heights[slot] = (byte) (Math.max(height(left[slot]), height(right[slot])) + 1);
            }

            private int rotateLeft(final int slot) {
                final int top = right[slot];
                right[slot] = left[top];
                left[top] = slot;
                recalcHeight(slot);
                recalcHeight(top);
                return top;
            }

            private int rotateRight(final int slot) {
                final int top = left[slot];
                left[slot] = right[top];
                right[top] = slot;
                recalcHeight(slot);
                recalcHeight(top);
                return top;
            }

            private int insert(final int slot, final int index, final int previous, final int successor) {
                if (slot == NONE) {
                    if (size == indices.length) {
                        grow();
                    }
                    final int added = size++;
                    indices[added] = index;
                    counts[added] = 1;
                    left[added] = right[added] = NONE;
                    next[added] = successor;
                    if (previous == NONE) {
                        first = added;
                    } else {
                        next[previous] = added;
                    }
                    modCount++;
                    return added;
                }
                if (index < indices[slot]) {
                    final int child = insert(left[slot], index, previous, slot);
                    left[slot] = child;
                } else if (index > indices[slot]) {
                    final int child = insert(right[slot], index, slot, successor);
                    right[slot] = child;
                } else {
                    counts[slot]++;
                    return slot;
                }
                recalcHeight(slot);
                final int balance = height(right[slot]) - height(left[slot]);
                if (balance == -2) {
                    if (height(right[left[slot]]) > height(left[left[slot]])) {
                        left[slot] = rotateLeft(left[slot]);
                    }
                    return rotateRight(slot);
                }
                if (balance == 2) {
                    if (height(left[right[slot]]) > height(right[right[slot]])) {
                        right[slot] = rotateRight(right[slot]);
                    }
                    return rotateLeft(slot);
                }
                return slot;
            }

            private void populate() {
                if (size == 0) {
                    indexExtractor.processIndices(index -> {
                        root = insert(root, index, NONE, NONE);
                        return true;
                    });
                }
            }

            @Override
            public boolean processCells(final CellPredicate consumer) {
                populate();
                final int expectedModCount = modCount;
                int slot = first;
                while (slot != NONE) {
                    if (modCount != expectedModCount) {
                        throw new ConcurrentModificationException();
                    }
                    final int index = indices[slot];
                    final int count = counts[slot];
                    // Like TreeMap's iterator, select the successor before the
                    // callback; check structural changes only before next().
                    slot = next[slot];
                    if (!consumer.test(index, count)) {
                        return false;
                    }
                }
                return true;
            }
        };
    }

    /**
     * Performs the given action for each {@code cell}  where the cell count is non-zero.
     *
     * <p>Some Bloom filter implementations use a count rather than a bit flag.  The term {@code Cell} is used to
     * refer to these counts.</p>
     *
     * <p>Any exceptions thrown by the action are relayed to the caller. The consumer is applied to each
     * cell. If the consumer returns {@code false} the execution is stopped, {@code false}
     * is returned, and no further pairs are processed.</p>
     *
     * @param consumer The action to be performed for each non-zero cell.
     * @return {@code true} if all cells return true from consumer, {@code false} otherwise.
     * @throws NullPointerException if the specified consumer is null
     */
    boolean processCells(CellPredicate consumer);

    /**
     * The default implementation returns distinct and ordered indices for all cells with a non-zero count.
     */
    @Override
    default boolean processIndices(final IntPredicate predicate) {
        return processCells((i, v) -> predicate.test(i));
    }

    @Override
    default IndexExtractor uniqueIndices() {
        return this;
    }
}

