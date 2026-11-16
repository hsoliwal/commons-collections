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
// Modified 2026 by Hitesh Soliwal and contributors: sparse lifecycle array hot paths.
// Modified 2026 by Synexia contributors: sorted-lane bulk union without per-key tree updates.
// Modified 2026 by Synexia contributors: array-backed dense-peer sorted merge and matching.
package com.m3.collections.apache.bloomfilter;

import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.LongPredicate;

/**
 * A bloom filter using primitive array AVL slots to track enabled bits in ascending order.
 * Insertion is amortized O(log n), with occasional O(n) growth; lookup is worst-case O(log n).
 * Traversal follows a primitive successor lane; clearing releases the backing arrays.
 *
 * @since 4.5.0-M1
 */
public final class SparseBloomFilter implements BloomFilter<SparseBloomFilter> {

    /**
     * Primitive AVL slots with an ascending successor lane. The rotation and balance
     * pattern follows the licensed TreeList slot owner; indexes need neither relative
     * ranks nor individual deletion. Slot acquisition is dense until clear.
     */
    private static final class IndexStore {
        private static final int NONE = -1;
        private static final int[] EMPTY_INTS = new int[0];
        private static final byte[] EMPTY_BYTES = new byte[0];
        private int[] keys = EMPTY_INTS;
        private int[] left = EMPTY_INTS;
        private int[] right = EMPTY_INTS;
        private int[] next = EMPTY_INTS;
        private byte[] heights = EMPTY_BYTES;
        private int root = NONE;
        private int first = NONE;
        private int last = NONE;
        private int size;
        private int modCount;

        private IndexStore() {
        }

        private IndexStore(final IndexStore source) {
            if (source.size != 0) {
                keys = Arrays.copyOf(source.keys, source.size);
                left = Arrays.copyOf(source.left, source.size);
                right = Arrays.copyOf(source.right, source.size);
                next = Arrays.copyOf(source.next, source.size);
                heights = Arrays.copyOf(source.heights, source.size);
                root = source.root;
                first = source.first;
                last = source.last;
                size = source.size;
                modCount = 1;
            }
        }

        /** Publishes every lane only after all allocations have succeeded. */
        private void grow() {
            reserve((long) size + 1L);
        }

        /** Retain the geometric endpoint, without allocating intermediate generations. */
        private void reserve(final long required) {
            if (required <= keys.length) {
                return;
            }
            final long maximum = Integer.MAX_VALUE - 8L;
            if (required > maximum) {
                throw new OutOfMemoryError("SparseBloomFilter slot capacity exhausted");
            }
            long proposed = keys.length;
            do {
                proposed = Math.min(maximum, Math.max(8L, proposed + (proposed >>> 1)));
            } while (proposed < required);
            final int capacity = (int) proposed;
            final int[] newKeys = Arrays.copyOf(keys, capacity);
            final int[] newLeft = Arrays.copyOf(left, capacity);
            final int[] newRight = Arrays.copyOf(right, capacity);
            final int[] newNext = Arrays.copyOf(next, capacity);
            final byte[] newHeights = Arrays.copyOf(heights, capacity);
            keys = newKeys;
            left = newLeft;
            right = newRight;
            next = newNext;
            heights = newHeights;
        }

        private int newLeaf(final int key, final int previous, final int successor) {
            if (size == keys.length) {
                grow();
            }
            final int slot = size++;
            keys[slot] = key;
            left[slot] = NONE;
            right[slot] = NONE;
            next[slot] = successor;
            heights[slot] = 0;
            if (previous == NONE) {
                first = slot;
            } else {
                next[previous] = slot;
            }
            if (successor == NONE) {
                last = slot;
            }
            modCount++;
            return slot;
        }

        private int height(final int slot) {
            return slot == NONE ? -1 : heights[slot];
        }

        private void recalcHeight(final int slot) {
            heights[slot] = (byte) (Math.max(height(left[slot]), height(right[slot])) + 1);
        }

        private int heightRightMinusLeft(final int slot) {
            return height(right[slot]) - height(left[slot]);
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

        private int balance(final int slot) {
            switch (heightRightMinusLeft(slot)) {
            case -1:
            case 0:
            case 1:
                return slot;
            case -2:
                if (heightRightMinusLeft(left[slot]) > 0) {
                    left[slot] = rotateLeft(left[slot]);
                }
                return rotateRight(slot);
            case 2:
                if (heightRightMinusLeft(right[slot]) < 0) {
                    right[slot] = rotateRight(right[slot]);
                }
                return rotateLeft(slot);
            default:
                throw new IllegalStateException("tree inconsistent.");
            }
        }

        private int insert(final int slot, final int key, final int previous, final int successor) {
            if (slot == NONE) {
                return newLeaf(key, previous, successor);
            }
            if (key < keys[slot]) {
                final int child = insert(left[slot], key, previous, slot);
                if (child < 0) {
                    // A complemented coordinate reports unchanged subtree height.
                    if (left[slot] != ~child) {
                        left[slot] = ~child;
                    }
                    return ~slot;
                }
                left[slot] = child;
            } else if (key > keys[slot]) {
                final int child = insert(right[slot], key, slot, successor);
                if (child < 0) {
                    if (right[slot] != ~child) {
                        right[slot] = ~child;
                    }
                    return ~slot;
                }
                right[slot] = child;
            } else {
                return ~slot;
            }
            final int oldHeight = heights[slot];
            recalcHeight(slot);
            final int balanced = balance(slot);
            return heights[balanced] == oldHeight ? ~balanced : balanced;
        }

        private void add(final int key) {
            final int result = insert(root, key, NONE, NONE);
            root = result < 0 ? ~result : result;
        }

        /** Merge large exact-peer batches through the existing sorted successor lane. */
        private void addAll(final IndexStore other) {
            if (other == this || other.size == 0) {
                return;
            }
            final int expectedModCount = other.modCount;
            // Keep tree insertion for small batches where a full receiver scan costs more.
            if (other.size < 32 || (size != 0
                    && (long) other.size * (height(root) + 1) < (long) size + other.size)) {
                for (int source = other.first; source != NONE; source = other.next[source]) {
                    add(other.keys[source]);
                    if (other.modCount != expectedModCount) {
                        throw new ConcurrentModificationException();
                    }
                }
                return;
            }
            int current = first;
            int missing = 0;
            for (int source = other.first; source != NONE; source = other.next[source]) {
                final int key = other.keys[source];
                while (current != NONE && keys[current] < key) {
                    current = next[current];
                }
                if (current == NONE || keys[current] != key) {
                    missing++;
                }
                if (other.modCount != expectedModCount) {
                    throw new ConcurrentModificationException();
                }
            }
            if (missing == 0) {
                return;
            }
            // All lanes are allocated before publication; no scratch nodes or key copies.
            reserve((long) size + missing);
            current = first;
            int previous = NONE;
            for (int source = other.first; source != NONE; source = other.next[source]) {
                final int key = other.keys[source];
                while (current != NONE && keys[current] < key) {
                    previous = current;
                    current = next[current];
                }
                if (current != NONE && keys[current] == key) {
                    previous = current;
                    current = next[current];
                } else {
                    previous = newLeaf(key, previous, current);
                }
                if (other.modCount != expectedModCount) {
                    // No caller callbacks run here; concurrent use remains unsupported.
                    root = (int) (rebuild(first, size) >>> 32);
                    throw new ConcurrentModificationException();
                }
            }
            root = (int) (rebuild(first, size) >>> 32);
        }

        /**
         * Scan exact dense peers by primitive word directly, then splice missing
         * keys into the owner-held sorted successor lane. No mutable bitmaps,
         * retained scratch arrays, node objects or callback dispatch escape.
         */
        private void addAll(final SimpleBloomFilter other) {
            final int count = other.cardinality();
            final int words = BitMaps.numberOfBitMaps(other.getShape());
            if (count < 32 || (long) words > (long) count * 2L
                    || (size != 0 && (long) count * (height(root) + 1) < (long) size + count)) {
                other.processIndices(value -> {
                    add(value);
                    return true;
                });
                return;
            }
            int current = first;
            int missing = 0;
            for (int wordIndex = 0; wordIndex < words; wordIndex++) {
                long word = other.wordAt(wordIndex);
                while (word != 0L) {
                    final int key = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                    while (current != NONE && keys[current] < key) {
                        current = next[current];
                    }
                    if (current == NONE || keys[current] != key) {
                        missing++;
                    }
                    word &= word - 1;
                }
            }
            if (missing == 0) {
                return;
            }
            reserve((long) size + missing);
            current = first;
            int previous = NONE;
            for (int wordIndex = 0; wordIndex < words; wordIndex++) {
                long word = other.wordAt(wordIndex);
                while (word != 0L) {
                    final int key = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                    while (current != NONE && keys[current] < key) {
                        previous = current;
                        current = next[current];
                    }
                    if (current != NONE && keys[current] == key) {
                        previous = current;
                        current = next[current];
                    } else {
                        previous = newLeaf(key, previous, current);
                    }
                    word &= word - 1;
                }
            }
            root = (int) (rebuild(first, size) >>> 32);
        }

        /** Ordered bitmap words use one monotone successor scan. */
        private boolean containsAll(final SimpleBloomFilter other) {
            int current = first;
            final int words = BitMaps.numberOfBitMaps(other.getShape());
            for (int wordIndex = 0; wordIndex < words; wordIndex++) {
                long word = other.wordAt(wordIndex);
                while (word != 0L) {
                    final int key = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                    while (current != NONE && keys[current] < key) {
                        current = next[current];
                    }
                    if (current == NONE || keys[current] != key) {
                        return false;
                    }
                    word &= word - 1;
                }
            }
            return true;
        }

        /** Pack the balanced root and first unconsumed successor into one primitive. */
        private long rebuild(final int cursor, final int count) {
            if (count == 0) {
                return ((long) NONE << 32) | (cursor & 0xffffffffL);
            }
            final int leftSize = count >>> 1;
            final long prefix = rebuild(cursor, leftSize);
            final int slot = (int) prefix;
            final long suffix = rebuild(next[slot], count - leftSize - 1);
            left[slot] = (int) (prefix >>> 32);
            right[slot] = (int) (suffix >>> 32);
            recalcHeight(slot);
            return ((long) slot << 32) | (suffix & 0xffffffffL);
        }

        private boolean contains(final int key) {
            int current = root;
            while (current != NONE) {
                if (key < keys[current]) {
                    current = left[current];
                } else if (key > keys[current]) {
                    current = right[current];
                } else {
                    return true;
                }
            }
            return false;
        }

        /** Use sorted lanes for large peer queries, tree lookup for small ones. */
        private boolean containsAll(final IndexStore other) {
            if (other == this || other.size == 0) {
                return true;
            }
            if (other.size == 1) {
                return contains(other.keys[other.first]);
            }
            if (size < other.size || keys[first] > other.keys[other.first]
                    || keys[last] < other.keys[other.last]) {
                return false;
            }
            final int expectedModCount = other.modCount;
            // Estimate coordinate visits without a workload-specific size constant.
            if ((long) other.size * (height(root) + 1) < (long) size + other.size) {
                for (int slot = other.first; slot != NONE; slot = other.next[slot]) {
                    final boolean found = contains(other.keys[slot]);
                    if (other.modCount != expectedModCount) {
                        throw new ConcurrentModificationException();
                    }
                    if (!found) {
                        return false;
                    }
                }
                return true;
            }
            int current = first;
            for (int slot = other.first; slot != NONE; slot = other.next[slot]) {
                final int wanted = other.keys[slot];
                while (current != NONE && keys[current] < wanted) {
                    current = next[current];
                }
                if (other.modCount != expectedModCount) {
                    throw new ConcurrentModificationException();
                }
                if (current == NONE || keys[current] != wanted) {
                    return false;
                }
                current = next[current];
            }
            return true;
        }

        private void clear() {
            modCount++;
            root = first = last = NONE;
            size = 0;
            keys = left = right = next = EMPTY_INTS;
            heights = EMPTY_BYTES;
        }

        private boolean isEmpty() {
            return size == 0;
        }

        /** Stream allMatch checks modification after the callback, including false. */
        private boolean processIndices(final IntPredicate consumer) {
            final int expectedModCount = modCount;
            int current = first;
            while (current != NONE) {
                final int key = keys[current];
                current = next[current];
                final boolean accepted = consumer.test(key);
                if (modCount != expectedModCount) {
                    throw new ConcurrentModificationException();
                }
                if (!accepted) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * The bitSet that defines this BloomFilter.
     */
    private final IndexStore indices;

    /**
     * The shape of this BloomFilter.
     */
    private final Shape shape;

    /**
     * Constructs an empty BitSetBloomFilter.
     *
     * @param shape The shape of the filter.
     */
    public SparseBloomFilter(final Shape shape) {
        Objects.requireNonNull(shape, "shape");
        this.shape = shape;
        this.indices = new IndexStore();
    }

    private SparseBloomFilter(final SparseBloomFilter source) {
        shape = source.shape;
        indices = new IndexStore(source.indices);
    }

    /**
     * Adds the index to the indices.
     *
     * @param idx The index to add.
     * @return {@code true} always
     */
    private boolean add(final int idx) {
        indices.add(idx);
        return true;
    }

    @Override
    public long[] asBitMapArray() {
        final long[] result = BitMaps.newBitMap(shape);
        final int expectedModCount = indices.modCount;
        for (int current = indices.first; current != IndexStore.NONE;) {
            if (indices.modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            final int i = indices.keys[current];
            current = indices.next[current];
            BitMaps.set(result, i);
        }
        return result;
    }

    /** Copies the existing ascending successor lane into one detached result. */
    @Override
    public int[] asIndexArray() {
        final int expectedModCount = indices.modCount;
        final int[] result = new int[indices.size];
        int offset = 0;
        for (int current = indices.first; current != IndexStore.NONE;) {
            final int value = indices.keys[current];
            current = indices.next[current];
            if (indices.modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            result[offset++] = value;
        }
        return result;
    }

    @Override
    public int cardinality() {
        return indices.size;
    }

    @Override
    public int characteristics() {
        return SPARSE;
    }

    @Override
    public void clear() {
        indices.clear();
    }

    @Override
    public boolean contains(final BitMapExtractor bitMapExtractor) {
        if (bitMapExtractor instanceof SimpleBloomFilter) {
            return contains((IndexExtractor) bitMapExtractor);
        }
        return contains(IndexExtractor.fromBitMapExtractor(bitMapExtractor));
    }

    @Override
    public boolean contains(final IndexExtractor indexExtractor) {
        if (indexExtractor instanceof SparseBloomFilter) {
            return indices.containsAll(((SparseBloomFilter) indexExtractor).indices);
        }
        if (indexExtractor instanceof SimpleBloomFilter) {
            final SimpleBloomFilter other = (SimpleBloomFilter) indexExtractor;
            final int count = other.cardinality();
            if (count >= 32 && indices.size > 0
                    && (long) BitMaps.numberOfBitMaps(other.getShape()) <= (long) count * 2L
                    && (long) count * (indices.height(indices.root) + 1) >= (long) indices.size + count) {
                return indices.containsAll(other);
            }
        }
        // Preserve arbitrary-producer callbacks, return values and exception behavior.
        return indexExtractor.processIndices(indices::contains);
    }

    /**
     * Creates a new instance of this {@link SparseBloomFilter} with the same properties as the current one.
     *
     * @return A copy of this {@link SparseBloomFilter}.
     */
    @Override
    public SparseBloomFilter copy() {
        return new SparseBloomFilter(this);
    }

    @Override
    public Shape getShape() {
        return shape;
    }

    @Override
    public boolean isEmpty() {
        return indices.isEmpty();
    }

    @Override
    public boolean merge(final BitMapExtractor bitMapExtractor) {
        Objects.requireNonNull(bitMapExtractor, "bitMapExtractor");
        return bitMapExtractor instanceof SimpleBloomFilter
                ? merge((IndexExtractor) bitMapExtractor)
                : merge(IndexExtractor.fromBitMapExtractor(bitMapExtractor));
    }

    @Override
    public boolean merge(final BloomFilter<?> other) {
        Objects.requireNonNull(other, "other");
        final IndexExtractor indexExtractor = (other.characteristics() & SPARSE) != 0
                || other instanceof SimpleBloomFilter
                ? (IndexExtractor) other : IndexExtractor.fromBitMapExtractor(other);
        merge(indexExtractor);
        return true;
    }

    @Override
    public boolean merge(final Hasher hasher) {
        Objects.requireNonNull(hasher, "hasher");
        merge(hasher.indices(shape));
        return true;
    }

    @Override
    public boolean merge(final IndexExtractor indexExtractor) {
        Objects.requireNonNull(indexExtractor, "indexExtractor");
        if (indexExtractor instanceof SparseBloomFilter) {
            indices.addAll(((SparseBloomFilter) indexExtractor).indices);
        } else if (indexExtractor instanceof SimpleBloomFilter) {
            indices.addAll((SimpleBloomFilter) indexExtractor);
        } else {
            indexExtractor.processIndices(this::add);
        }

        if (!indices.isEmpty()) {
            if (indices.keys[indices.last] >= shape.getNumberOfBits()) {
                throw new IllegalArgumentException(String.format("Value in list %s is greater than maximum value (%s)",
                        indices.keys[indices.last], shape.getNumberOfBits() - 1));
            }
            if (indices.keys[indices.first] < 0) {
                throw new IllegalArgumentException(
                        String.format("Value in list %s is less than 0", indices.keys[indices.first]));
            }
        }
        return true;
    }

    @Override
    public boolean processBitMaps(final LongPredicate consumer) {
        Objects.requireNonNull(consumer, "consumer");
        final int limit = BitMaps.numberOfBitMaps(shape);
        //
        // because our indices are always in order we can shorten the time necessary to
        // create the longs for the consumer
        //
        // the currently constructed bitMap
        long bitMap = 0;
        // the bitmap we are working on
        int idx = 0;
        final int expectedModCount = indices.modCount;
        for (int current = indices.first; current != IndexStore.NONE;) {
            if (indices.modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            final int i = indices.keys[current];
            current = indices.next[current];
            while (BitMaps.getLongIndex(i) != idx) {
                if (!consumer.test(bitMap)) {
                    return false;
                }
                bitMap = 0;
                idx++;
            }
            bitMap |= BitMaps.getLongBit(i);
        }
        // we fall through with data in the bitMap
        if (!consumer.test(bitMap)) {
            return false;
        }
        // account for hte bitMap in the previous block + the next one
        idx++;
        // while there are more blocks to generate send zero to the consumer.
        while (idx < limit) {
            if (!consumer.test(0L)) {
                return false;
            }
            idx++;
        }
        return true;
    }

    @Override
    public boolean processIndices(final IntPredicate consumer) {
        Objects.requireNonNull(consumer, "consumer");
        return indices.processIndices(consumer);
    }
}
