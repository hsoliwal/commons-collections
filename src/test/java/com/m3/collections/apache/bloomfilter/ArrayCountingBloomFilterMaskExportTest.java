/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.m3.collections.apache.bloomfilter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Differential proof that the new direct bitmap export is detached and exact. */
class ArrayCountingBloomFilterMaskExportTest {
    private static CellExtractor cell(final int index, final int count) {
        return consumer -> consumer.test(index, count);
    }

    private static long[] expectedWords(final int[] counts) {
        final long[] words = new long[BitMaps.numberOfBitMaps(counts.length)];
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] != 0) {
                words[i >>> 6] |= 1L << i;
            }
        }
        return words;
    }

    private static void assertExport(final ArrayCountingBloomFilter filter, final int[] counts) {
        final long[] expected = expectedWords(counts);
        final long[] first = filter.asBitMapArray();
        final long[] second = filter.asBitMapArray();
        assertArrayEquals(expected, first);
        assertArrayEquals(expected, second);
        assertNotSame(first, second);
        if (first.length > 0) {
            first[0] ^= -1L;
            assertArrayEquals(expected, filter.asBitMapArray(), "export must not alias owner state");
        }
        final List<Long> streamed = new ArrayList<>();
        assertTrue(filter.processBitMaps(word -> {
            streamed.add(word);
            return true;
        }));
        final long[] observed = new long[streamed.size()];
        for (int i = 0; i < observed.length; i++) {
            observed[i] = streamed.get(i);
        }
        assertArrayEquals(expected, observed);
    }

    @Test
    void wordBoundaryPositiveNegativeCopyAndInvalidStateStayExact() {
        for (int length : new int[] {1, 2, 63, 64, 65, 127, 128, 129, 4097}) {
            final ArrayCountingBloomFilter filter =
                    new ArrayCountingBloomFilter(Shape.fromKM(1, length));
            final int[] cells = new int[length];
            assertExport(filter, cells);
            for (int i = 0; i < length; i++) {
                if ((i & 7) == 0 || i == length - 1) {
                    filter.add(cell(i, 2));
                    cells[i] += 2;
                }
            }
            assertExport(filter, cells);
            final ArrayCountingBloomFilter copy = filter.copy();
            assertExport(copy, cells);
            filter.subtract(cell(length - 1, 5));
            cells[length - 1] -= 5;
            assertFalse(filter.isValid());
            assertExport(filter, cells);
            final int[] copied = cells.clone();
            copied[length - 1] += 5;
            assertExport(copy, copied);
            filter.clear();
            assertFalse(filter.isValid());
            assertExport(filter, new int[length]);
            assertExport(copy, copied);
        }
    }

    @Test
    void partiallyAppliedMutationsAndFailedExportDoNotLeakParentMask() {
        final ArrayCountingBloomFilter filter =
                new ArrayCountingBloomFilter(Shape.fromKM(1, 129));
        final int[] cells = new int[129];
        try {
            filter.add(consumer -> {
                consumer.test(63, 4);
                consumer.test(64, 7);
                return consumer.test(129, 1);
            });
            throw new AssertionError("expected index failure");
        } catch (IllegalArgumentException expected) {
            cells[63] = 4;
            cells[64] = 7;
        }
        assertExport(filter, cells);
        filter.subtract(cell(63, 4));
        cells[63] = 0;
        assertExport(filter, cells);
    }
}
