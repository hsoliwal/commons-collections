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
// Modified 2026-11-13 by Hitesh Soliwal and Synexia contributors: direct array/bitmap hot paths.
// Modified 2026-11-15 by Hitesh Soliwal and contributors: bounded array snapshots.
// Modified 2026 by Synexia contributors: compact sorted sparse unique snapshots.
package org.apache.commons.collections4.bloomfilter;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.LongPredicate;

/**
 * An object that produces indices of a Bloom filter.
 * <p><em>
 * The default implementation of {@code asIndexArray} is slow. Implementers should reimplement the
 * method where possible.</em></p>
 *
 * @since 4.5.0-M2
 */
@FunctionalInterface
public interface IndexExtractor {

    /**
     * Creates an IndexExtractor from a {@code BitMapExtractor}.
     *
     * @param bitMapExtractor The {@code BitMapExtractor}
     * @return A new {@code IndexExtractor}.
     */
    static IndexExtractor fromBitMapExtractor(final BitMapExtractor bitMapExtractor) {
        Objects.requireNonNull(bitMapExtractor, "bitMapExtractor");
        return consumer -> {
            final LongPredicate longPredicate = new LongPredicate() {
                int wordIdx;

                @Override
                public boolean test(long word) {
                    final int base = wordIdx;
                    while (word != 0) {
                        // Visit only enabled bits; keep this word's snapshot across callbacks.
                        if (!consumer.test(base + Long.numberOfTrailingZeros(word))) {
                            return false;
                        }
                        word &= word - 1;
                    }
                    wordIdx += 64;
                    return true;
                }
            };
            return bitMapExtractor.processBitMaps(longPredicate::test);
        };
    }

    /**
     * Creates an IndexExtractor from an array of integers.
     *
     * @param values The index values
     * @return An IndexExtractor that uses the values.
     */
    static IndexExtractor fromIndexArray(final int... values) {
        return new IndexExtractor() {

            @Override
            public int[] asIndexArray() {
                return values.clone();
            }

            @Override
            public boolean processIndices(final IntPredicate predicate) {
                for (final int value : values) {
                    if (!predicate.test(value)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public IndexExtractor uniqueIndices() {
                if (values == null || values.length == 0) {
                    return IndexExtractor.super.uniqueIndices();
                }
                int maximum = 0;
                int minimum = Integer.MAX_VALUE;
                int previous = Integer.MIN_VALUE;
                int sortedDistinct = 0;
                boolean nondecreasing = true;
                for (final int value : values) {
                    minimum = Math.min(minimum, value);
                    maximum = Math.max(maximum, value);
                    if (value < previous) {
                        nondecreasing = false;
                    }
                    if (value != previous) {
                        sortedDistinct++;
                    }
                    previous = value;
                }
                if (minimum < 0 || maximum == Integer.MAX_VALUE) {
                    // Preserve default rejection and overflow-boundary behavior.
                    return IndexExtractor.super.uniqueIndices();
                }
                // Preserve the compact bitmap path for genuinely dense inputs.
                // When an already-ordered source has few distinct keys compared
                // with its highest index, an exact-sized int[] is much smaller.
                final long bitmapBytes = (((long) maximum >>> 6) + 1L) * Long.BYTES;
                final boolean compactOrdered = nondecreasing
                        && (long) sortedDistinct * Integer.BYTES + 32L < bitmapBytes;
                if ((long) maximum <= (long) values.length * Long.SIZE && !compactOrdered) {
                    // Dense inputs retain the bitmap algorithm without growing it.
                    final BitSet bitSet = new BitSet(maximum + 1);
                    for (final int value : values) {
                        bitSet.set(value);
                    }
                    return new IndexExtractor() {
                        @Override
                        public boolean processIndices(final IntPredicate predicate) {
                            for (int idx = bitSet.nextSetBit(0); idx >= 0; idx = bitSet.nextSetBit(idx + 1)) {
                                if (!predicate.test(idx)) {
                                    return false;
                                }
                            }
                            return true;
                        }

                        @Override
                        public IndexExtractor uniqueIndices() {
                            return this;
                        }
                    };
                }
                final int[] sorted;
                final int distinct;
                if (nondecreasing) {
                    // The source is already ordered; compact directly into the
                    // exact-sized detached snapshot, avoiding clone and sort.
                    sorted = new int[sortedDistinct];
                    int write = 0;
                    int last = Integer.MIN_VALUE;
                    for (final int value : values) {
                        if (value != last) {
                            sorted[write++] = value;
                        }
                        last = value;
                    }
                    distinct = sorted.length;
                } else {
                    sorted = values.clone();
                    Arrays.sort(sorted);
                    int count = 0;
                    for (final int value : sorted) {
                        if (count == 0 || value != sorted[count - 1]) {
                            sorted[count++] = value;
                        }
                    }
                    distinct = count;
                }
                final int[] unique = distinct == sorted.length ? sorted : Arrays.copyOf(sorted, distinct);
                return new IndexExtractor() {
                    @Override
                    public int[] asIndexArray() {
                        return unique.clone();
                    }

                    @Override
                    public boolean processIndices(final IntPredicate predicate) {
                        for (final int value : unique) {
                            if (!predicate.test(value)) {
                                return false;
                            }
                        }
                        return true;
                    }

                    @Override
                    public IndexExtractor uniqueIndices() {
                        return this;
                    }
                };
            }
        };
    }

    /**
     * Return a copy of the IndexExtractor data as an int array.
     *
     * <p>Indices ordering and uniqueness is not guaranteed.</p>
     *
     * <p><em>
     * The default implementation of this method creates an array and populates
     * it.  Implementations that have access to an index array should consider
     * returning a copy of that array if possible.
     * </em></p>
     *
     * @return An int array of the data.
     */
    default int[] asIndexArray() {
        final class Indices {
            private int[] data = new int[32];
            private int size;

            boolean add(final int index) {
                data = IndexUtils.ensureCapacityForAdd(data, size);
                data[size++] = index;
                return true;
            }

            int[] toArray() {
                // Edge case to avoid a large array copy
                return size == data.length ? data : Arrays.copyOf(data, size);
            }
        }
        final Indices indices = new Indices();
        processIndices(indices::add);
        return indices.toArray();
    }

    /**
     * Each index is passed to the predicate. The predicate is applied to each
     * index value, if the predicate returns {@code false} the execution is stopped, {@code false}
     * is returned, and no further indices are processed.
     *
     * <p>Any exceptions thrown by the action are relayed to the caller.</p>
     *
     * <p>Indices ordering and uniqueness is not guaranteed.</p>
     *
     * @param predicate The action to be performed for each non-zero bit index.
     * @return {@code true} if all indexes return true from consumer, {@code false} otherwise.
     * @throws NullPointerException if the specified action is null
     */
    boolean processIndices(IntPredicate predicate);

    /**
     * Creates an IndexExtractor comprising the unique indices for this extractor.
     *
     * <p>By default creates a new extractor with some overhead to remove
     * duplicates.  IndexExtractors that return unique indices by default
     * should override this to return {@code this}.</p>
     *
     * <p>The default implementation will filter the indices from this instance
     * and return them in ascending order.</p>
     *
     * @return The IndexExtractor of unique values.
     * @throws IndexOutOfBoundsException if any index is less than zero.
     */
    default IndexExtractor uniqueIndices() {
        final BitSet bitSet = new BitSet();
        processIndices(i -> {
            bitSet.set(i);
            return true;
        });

        return new IndexExtractor() {
            @Override
            public boolean processIndices(final IntPredicate predicate) {
                for (int idx = bitSet.nextSetBit(0); idx >= 0; idx = bitSet.nextSetBit(idx + 1)) {
                    if (!predicate.test(idx)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public IndexExtractor uniqueIndices() {
                return this;
            }
        };
    }
}
