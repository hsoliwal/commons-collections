/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Copyright 2026 Hitesh Soliwal and contributors to the Synexia Project.
// SPDX-License-Identifier: Apache-2.0
package com.m3.collections.apache.bloomfilter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Nonzero word-mask, mutable callback, sparse/dense and invalid-state contracts.
 */
class ArrayCountingBloomFilterMaskTest {

    private static CellExtractor cell(final int index, final int count) {
        return consumer -> consumer.test(index, count);
    }

    private static long[] words(final ArrayCountingBloomFilter filter) {
        final List<Long> masks = new ArrayList<>();
        assertTrue(filter.processBitMaps(mask -> {
            masks.add(mask);
            return true;
        }));
        final long[] result = new long[masks.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = masks.get(index);
        }
        return result;
    }

    private static int[] occupied(final ArrayCountingBloomFilter filter) {
        final List<Integer> result = new ArrayList<>();
        assertTrue(filter.processIndices(index -> {
            result.add(index);
            return true;
        }));
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void assertOwner(final ArrayCountingBloomFilter filter,
            final int[] reference) {
        final long[] bitMasks = new long[BitMaps.numberOfBitMaps(reference.length)];
        int positive = 0;
        final List<Integer> nonzero = new ArrayList<>();
        for (int index = 0; index < reference.length; index++) {
            if (reference[index] > 0) {
                positive++;
            }
            if (reference[index] != 0) {
                bitMasks[index >>> 6] |= 1L << index;
                nonzero.add(index);
            }
        }
        assertEquals(positive, filter.cardinality());
        assertArrayEquals(bitMasks, words(filter));
        assertArrayEquals(nonzero.stream().mapToInt(Integer::intValue).toArray(),
                occupied(filter));
        final List<String> cells = new ArrayList<>();
        assertTrue(filter.processCells((index, count) -> {
            cells.add(index + ":" + count);
            return true;
        }));
        final List<String> expected = new ArrayList<>();
        for (int index = 0; index < reference.length; index++) {
            if (reference[index] != 0) {
                expected.add(index + ":" + reference[index]);
            }
        }
        assertEquals(expected, cells);
    }

    @Test
    void sparseAndDenseMasksAreExactAtWordBoundaries() {
        for (int size : new int[] {1, 2, 63, 64, 65, 127, 128, 129, 4097}) {
            final ArrayCountingBloomFilter filter =
                    new ArrayCountingBloomFilter(Shape.fromKM(1, size));
            final int[] reference = new int[size];
            assertOwner(filter, reference);
            for (int index = 0; index < size; index++) {
                if ((index & 7) == 0 || (index & 31) == 31) {
                    filter.add(cell(index, index + 1));
                    reference[index] = index + 1;
                }
            }
            assertOwner(filter, reference);
            for (int index = 0; index < size; index++) {
                if (reference[index] != 0) {
                    filter.subtract(cell(index, reference[index]));
                    reference[index] = 0;
                }
            }
            assertOwner(filter, reference);
        }
    }

    @Test
    void sparseTraversalReReadsCurrentWordAfterCallbackMutations() {
        final ArrayCountingBloomFilter filter =
                new ArrayCountingBloomFilter(Shape.fromKM(1, 129));
        filter.add(cell(1, 1));
        final List<Integer> observed = new ArrayList<>();
        assertTrue(filter.processIndices(index -> {
            observed.add(index);
            if (index == 1) {
                filter.add(cell(3, 1));
                filter.add(cell(65, 1));
                filter.subtract(cell(3, 1));
                filter.add(cell(63, 1));
            }
            return true;
        }));
        assertEquals(Arrays.asList(1, 63, 65), observed);
        final int[] reference = new int[129];
        reference[1] = 1;
        reference[63] = 1;
        reference[65] = 1;
        assertOwner(filter, reference);

        filter.clear();
        filter.add(cell(4, 5));
        final List<String> cells = new ArrayList<>();
        assertTrue(filter.processCells((index, count) -> {
            cells.add(index + ":" + count);
            if (index == 4) {
                filter.add(cell(5, 6));
                filter.add(cell(68, 7));
                filter.subtract(cell(68, 7));
            }
            return true;
        }));
        assertEquals(Arrays.asList("4:5", "5:6"), cells);

        filter.clear();
        filter.add(cell(1, 1));
        final List<Integer> afterClear = new ArrayList<>();
        assertTrue(filter.processIndices(index -> {
            afterClear.add(index);
            if (index == 1) {
                filter.clear();
                filter.add(cell(90, 1));
            }
            return true;
        }));
        assertEquals(Arrays.asList(1, 90), afterClear);
    }

    @Test
    void bitmapConsumerObservesLaterWordMutationsButNotRetrospectiveReads() {
        final ArrayCountingBloomFilter filter =
                new ArrayCountingBloomFilter(Shape.fromKM(1, 129));
        filter.add(cell(5, 1));
        final List<Long> history = new ArrayList<>();
        assertTrue(filter.processBitMaps(mask -> {
            history.add(mask);
            if (history.size() == 1) {
                filter.add(cell(70, 1));
            }
            return true;
        }));
        assertEquals(Arrays.asList(1L << 5, 1L << 6, 0L), history);
        assertArrayEquals(new long[] {1L << 5, 1L << 6, 0L}, words(filter));
    }

    @Test
    void invalidNegativeCellsStayIndexedAndClearKeepsInvalidHistory() {
        final ArrayCountingBloomFilter filter =
                new ArrayCountingBloomFilter(Shape.fromKM(1, 65));
        filter.add(cell(1, Integer.MAX_VALUE));
        filter.add(cell(1, 1));
        filter.subtract(cell(64, 1));
        final int[] reference = new int[65];
        reference[1] = Integer.MIN_VALUE;
        reference[64] = -1;
        assertOwner(filter, reference);
        assertFalse(filter.isValid());
        final ArrayCountingBloomFilter copy = filter.copy();
        assertOwner(copy, reference);
        filter.clear();
        assertFalse(filter.isValid());
        assertOwner(filter, new int[65]);
        assertOwner(copy, reference);
    }

    @Test
    void partialMutationErrorsKeepWordAndCellArraysConsistent() {
        final ArrayCountingBloomFilter filter =
                new ArrayCountingBloomFilter(Shape.fromKM(1, 129));
        assertThrows(IllegalArgumentException.class, () -> filter.add(consumer -> {
            consumer.test(63, 1);
            consumer.test(64, 2);
            return consumer.test(129, 1);
        }));
        final int[] reference = new int[129];
        reference[63] = 1;
        reference[64] = 2;
        assertOwner(filter, reference);
        assertThrows(IllegalStateException.class, () -> filter.subtract(consumer -> {
            consumer.test(63, 1);
            throw new IllegalStateException("after one removal");
        }));
        reference[63] = 0;
        assertOwner(filter, reference);
    }

    @Test
    void copiesHaveIndependentArrayStorageAndDenseScanningStillWorks()
            throws ReflectiveOperationException {
        final ArrayCountingBloomFilter filter =
                new ArrayCountingBloomFilter(Shape.fromKM(1, 129));
        for (int index = 0; index < 129; index++) {
            filter.add(cell(index, 1));
        }
        final ArrayCountingBloomFilter copy = filter.copy();
        final Field words = ArrayCountingBloomFilter.class.getDeclaredField("nonzeroMask");
        words.setAccessible(true);
        assertNotSame(words.get(filter), words.get(copy));
        final List<Integer> visited = new ArrayList<>();
        assertTrue(filter.processIndices(index -> {
            visited.add(index);
            if (index == 1) {
                filter.subtract(cell(120, 1));
            }
            return true;
        }));
        assertEquals(128, visited.size());
        assertEquals(129, copy.cardinality());
        assertEquals(128, filter.cardinality());
        assertEquals(129, occupied(copy).length);
    }

    @Test
    void seededPositiveAndNegativeTransitionsMatchIndependentArrayModel() {
        final Random random = new Random(0x4d334d41534bL);
        final int[] deltas = {0, 1, -1, 2, -2, 17,
                Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (int size : new int[] {1, 2, 7, 63, 64, 65, 127, 128, 129, 257}) {
            final ArrayCountingBloomFilter filter =
                    new ArrayCountingBloomFilter(Shape.fromKM(1, size));
            final int[] reference = new int[size];
            for (int step = 0; step < 2400; step++) {
                if (random.nextInt(23) == 0) {
                    filter.clear();
                    Arrays.fill(reference, 0);
                } else {
                    final int index = random.nextInt(size);
                    final int delta = deltas[random.nextInt(deltas.length)];
                    if (random.nextBoolean()) {
                        filter.add(cell(index, delta));
                        reference[index] += delta;
                    } else {
                        filter.subtract(cell(index, delta));
                        reference[index] -= delta;
                    }
                }
                if (step % 31 == 0) {
                    assertOwner(filter, reference);
                }
            }
            assertOwner(filter, reference);
        }
    }
}
