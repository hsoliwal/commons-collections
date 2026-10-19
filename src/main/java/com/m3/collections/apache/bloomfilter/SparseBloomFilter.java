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
            final long proposed = Math.max(8L, keys.length + (long) (keys.length >>> 1));
            final int capacity = (int) Math.min(Integer.MAX_VALUE - 8L, proposed);
            if (capacity <= keys.length) {
                throw new OutOfMemoryError("SparseBloomFilter slot capacity exhausted");
            }
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
                left[slot] = child;
            } else if (key > keys[slot]) {
                final int child = insert(right[slot], key, slot, successor);
                right[slot] = child;
            } else {
                return slot;
            }
            recalcHeight(slot);
            return balance(slot);
        }

        private void add(final int key) {
            root = insert(root, key, NONE, NONE);
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
        return contains(IndexExtractor.fromBitMapExtractor(bitMapExtractor));
    }

    @Override
    public boolean contains(final IndexExtractor indexExtractor) {
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
        return this.merge(IndexExtractor.fromBitMapExtractor(bitMapExtractor));
    }

    @Override
    public boolean merge(final BloomFilter<?> other) {
        Objects.requireNonNull(other, "other");
        final IndexExtractor indexExtractor = (other.characteristics() & SPARSE) != 0 ? (IndexExtractor) other : IndexExtractor.fromBitMapExtractor(other);
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
        indexExtractor.processIndices(this::add);
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
