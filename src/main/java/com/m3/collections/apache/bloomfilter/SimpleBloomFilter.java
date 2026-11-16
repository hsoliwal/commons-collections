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
// Modified 2026 by Synexia contributors: exact-peer word-level index operations.
// Modified 2026 by Synexia contributors: read-only word cursor for sorted peer merge.
package com.m3.collections.apache.bloomfilter;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.LongPredicate;

/**
 * A bloom filter using an array of bit maps to track enabled bits. This is a standard implementation and should work well for most Bloom filters.
 *
 * @since 4.5.0-M1
 */
public final class SimpleBloomFilter implements BloomFilter<SimpleBloomFilter> {

    /**
     * The array of bit map longs that defines this Bloom filter. Will be null if the filter is empty.
     */
    private final long[] bitMap;

    /**
     * The Shape of this Bloom filter.
     */
    private final Shape shape;

    /**
     * The cardinality of this Bloom filter.
     */
    private int cardinality;

    /**
     * Creates an empty instance.
     *
     * @param shape The shape for the filter.
     */
    public SimpleBloomFilter(final Shape shape) {
        Objects.requireNonNull(shape, "shape");
        this.shape = shape;
        this.bitMap = BitMaps.newBitMap(shape);
        this.cardinality = 0;
    }

    /**
     * Copy constructor for {@code copy()} use.
     *
     * @param source
     */
    private SimpleBloomFilter(final SimpleBloomFilter source) {
        this.shape = source.shape;
        this.bitMap = source.bitMap.clone();
        this.cardinality = source.cardinality;
    }

    @Override
    public long[] asBitMapArray() {
        return Arrays.copyOf(bitMap, bitMap.length);
    }

    /** Copies enabled indices without a growing intermediate accumulator. */
    @Override
    public int[] asIndexArray() {
        // Failed merges can leave cardinality stale. Count the actual words
        // without changing that existing observable cache state.
        long count = 0;
        int occupiedWord = -1;
        for (int i = 0; i < bitMap.length; i++) {
            final long word = bitMap[i];
            if (word != 0) {
                count += Long.bitCount(word);
                occupiedWord = i;
            }
        }
        if (count > Integer.MAX_VALUE) {
            throw new OutOfMemoryError("SimpleBloomFilter index array capacity exhausted");
        }
        final int[] result = new int[(int) count];
        if (count != 0) {
            int offset = 0;
            // A single occupied word needs no second scan over empty words.
            final int firstWord = count == Long.bitCount(bitMap[occupiedWord]) ? occupiedWord : 0;
            for (int i = firstWord; i < bitMap.length; i++) {
                long word = bitMap[i];
                while (word != 0) {
                    result[offset++] = (i << 6) + Long.numberOfTrailingZeros(word);
                    word &= word - 1;
                }
                if (offset == result.length) {
                    break;
                }
            }
        }
        return result;
    }

    /** Internal read-only word cursor; never exposes the mutable bitmap. */
    long wordAt(final int index) {
        return bitMap[index];
    }

    @Override
    public int cardinality() {
        // Lazy evaluation with caching
        int c = cardinality;
        if (c < 0) {
            c = 0;
            for (final long word : bitMap) {
                c += Long.bitCount(word);
            }
            cardinality = c;
        }
        return c;
    }

    @Override
    public int characteristics() {
        return 0;
    }

    @Override
    public void clear() {
        Arrays.fill(bitMap, 0L);
        cardinality = 0;
    }

    @Override
    public boolean contains(final IndexExtractor indexExtractor) {
        if (indexExtractor instanceof SimpleBloomFilter) {
            final long[] other = ((SimpleBloomFilter) indexExtractor).bitMap;
            for (int i = 0; i < other.length; i++) {
                final long word = other[i];
                // Zero words produce no original index callback, even past our capacity.
                // A nonzero oversized word retains the original array-bound failure.
                if (word != 0 && (bitMap[i] & word) != word) {
                    return false;
                }
            }
            return true;
        }
        return indexExtractor.processIndices(idx -> BitMaps.contains(bitMap, idx));
    }

    /**
     * Creates a new instance of this {@link SimpleBloomFilter} with the same properties as the current one.
     *
     * @return A copy of this {@link SimpleBloomFilter}.
     */
    @Override
    public SimpleBloomFilter copy() {
        return new SimpleBloomFilter(this);
    }

    @Override
    public Shape getShape() {
        return shape;
    }

    @Override
    public boolean isEmpty() {
        return cardinality == 0 || processBitMaps(y -> y == 0);
    }

    @Override
    public boolean merge(final BitMapExtractor bitMapExtractor) {
        Objects.requireNonNull(bitMapExtractor, "bitMapExtractor");
        try {
            final int lastIndex;
            if (bitMapExtractor instanceof SimpleBloomFilter) {
                final long[] otherBits = ((SimpleBloomFilter) bitMapExtractor).bitMap;
                for (int index = 0; index < otherBits.length; index++) {
                    // Preserve successful-prefix writes before an oversized input fails.
                    bitMap[index] |= otherBits[index];
                }
                lastIndex = otherBits.length - 1;
            } else {
                final int[] idx = new int[1];
                bitMapExtractor.processBitMaps(value -> {
                    bitMap[idx[0]++] |= value;
                    return true;
                });
                lastIndex = idx[0] - 1;
            }
            final int idxLimit = BitMaps.getLongIndex(shape.getNumberOfBits());
            if (idxLimit == lastIndex) {
                final long excess = bitMap[idxLimit] >> shape.getNumberOfBits();
                if (excess != 0) {
                    throw new IllegalArgumentException(
                            String.format("BitMapExtractor set a bit higher than the limit for the shape: %s", shape.getNumberOfBits()));
                }
            }
            cardinality = -1;
        } catch (final IndexOutOfBoundsException e) {
            throw new IllegalArgumentException(String.format("BitMapExtractor should send at most %s maps", bitMap.length), e);
        }
        return true;
    }

    @Override
    public boolean merge(final BloomFilter<?> other) {
        Objects.requireNonNull(other, "other");
        if ((other.characteristics() & SPARSE) != 0) {
            merge((IndexExtractor) other);
        } else {
            merge((BitMapExtractor) other);
        }
        return true;
    }

    @Override
    public boolean merge(final Hasher hasher) {
        Objects.requireNonNull(hasher, "hasher");
        return merge(hasher.indices(shape));
    }

    @Override
    public boolean merge(final IndexExtractor indexExtractor) {
        Objects.requireNonNull(indexExtractor, "indexExtractor");
        if (indexExtractor instanceof SimpleBloomFilter) {
            final long[] other = ((SimpleBloomFilter) indexExtractor).bitMap;
            final int limit = shape.getNumberOfBits();
            for (int i = 0; i < other.length; i++) {
                final long word = other[i];
                if (word == 0) {
                    continue;
                }
                final int remaining = limit - (i << 6);
                if (remaining >= Long.SIZE) {
                    bitMap[i] |= word;
                } else {
                    final long allowed = remaining > 0 ? (1L << remaining) - 1L : 0L;
                    if (remaining > 0) {
                        // Valid low indices were applied before the first invalid high bit.
                        bitMap[i] |= word & allowed;
                    }
                    if ((word & ~allowed) != 0) {
                        throw new IllegalArgumentException(String.format(
                                "IndexExtractor should only send values in the range[0,%s)", limit));
                    }
                }
            }
        } else {
            indexExtractor.processIndices(idx -> {
                if (idx < 0 || idx >= shape.getNumberOfBits()) {
                    throw new IllegalArgumentException(String.format("IndexExtractor should only send values in the range[0,%s)", shape.getNumberOfBits()));
                }
                BitMaps.set(bitMap, idx);
                return true;
            });
        }
        cardinality = -1;
        return true;
    }

    @Override
    public boolean processBitMapPairs(final BitMapExtractor other, final LongBiPredicate func) {
        if (other instanceof SimpleBloomFilter) {
            final long[] otherBits = ((SimpleBloomFilter) other).bitMap;
            final int common = Math.min(bitMap.length, otherBits.length);
            int index = 0;
            for (; index < common; index++) {
                if (!func.test(bitMap[index], otherBits[index])) {
                    return false;
                }
            }
            for (; index < otherBits.length; index++) {
                if (!func.test(0L, otherBits[index])) {
                    return false;
                }
            }
            for (; index < bitMap.length; index++) {
                if (!func.test(bitMap[index], 0L)) {
                    return false;
                }
            }
            return true;
        }
        // Arbitrary producers retain their callback and short-circuit behavior.
        final CountingLongPredicate p = new CountingLongPredicate(bitMap, func);
        return other.processBitMaps(p) && p.processRemaining();
    }

    @Override
    public boolean processBitMaps(final LongPredicate consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (final long l : bitMap) {
            if (!consumer.test(l)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean processIndices(final IntPredicate consumer) {
        Objects.requireNonNull(consumer, "consumer");
        int wordIndex = 0;
        for (long word : bitMap) {
            while (word != 0) {
                if (!consumer.test(wordIndex + Long.numberOfTrailingZeros(word))) {
                    return false;
                }
                word &= word - 1;
            }
            wordIndex += Long.SIZE;
        }
        return true;
    }
}
