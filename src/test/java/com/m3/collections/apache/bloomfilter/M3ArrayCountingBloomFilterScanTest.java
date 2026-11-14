/*
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.m3.collections.apache.bloomfilter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Regression coverage for primitive counting and detached, ordered index exports. */
class M3ArrayCountingBloomFilterScanTest {

    private static CellExtractor cell(final int index, final int count) {
        return consumer -> consumer.test(index, count);
    }

    private static void assertPositiveCells(final ArrayCountingBloomFilter filter, final int[] cells) {
        final BitSet expected = new BitSet(cells.length);
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] > 0) {
                expected.set(i);
            }
        }
        assertEquals(expected.cardinality(), filter.cardinality());
        final int[] indices = filter.asIndexArray();
        assertEquals(expected.cardinality(), indices.length);
        int index = expected.nextSetBit(0);
        for (final int actual : indices) {
            assertEquals(index, actual);
            index = expected.nextSetBit(index + 1);
        }
        assertEquals(-1, index);
    }

    @Test
    void emptyAndFullExportsAtArrayBoundaries() {
        for (final int size : new int[] {1, 2, 3, 31, 32, 63, 64, 65, 127, 128, 129, 4097}) {
            final ArrayCountingBloomFilter filter = new ArrayCountingBloomFilter(Shape.fromKM(1, size));
            final int[] cells = new int[size];
            assertPositiveCells(filter, cells);
            assertNotSame(filter.asIndexArray(), filter.asIndexArray());
            for (int i = size - 1; i >= 0; i--) {
                cells[i] = i % 3 + 1;
                assertTrue(filter.add(cell(i, cells[i])));
            }
            assertPositiveCells(filter, cells);
            filter.clear();
            assertEquals(0, filter.cardinality());
            assertArrayEquals(new int[0], filter.asIndexArray());
        }
    }

    @Test
    void exportIsDetachedFromCellsAndLaterMutations() {
        final ArrayCountingBloomFilter filter = new ArrayCountingBloomFilter(Shape.fromKM(1, 65));
        filter.add(cell(0, 2));
        filter.add(cell(32, 1));
        filter.add(cell(64, 4));
        final int[] first = filter.asIndexArray();
        final int[] second = filter.asIndexArray();
        assertNotSame(first, second);
        first[0] = 55;
        assertArrayEquals(new int[] {0, 32, 64}, second);
        assertArrayEquals(second, filter.asIndexArray());
        filter.subtract(cell(32, 1));
        filter.add(cell(1, 1));
        assertArrayEquals(new int[] {0, 32, 64}, second);
        assertArrayEquals(new int[] {0, 1, 64}, filter.asIndexArray());
    }

    @Test
    void negativeAndOverflowedCellsKeepHistoricalPositiveOnlyExport() {
        final ArrayCountingBloomFilter filter = new ArrayCountingBloomFilter(Shape.fromKM(1, 8));
        assertTrue(filter.add(cell(1, Integer.MAX_VALUE)));
        assertFalse(filter.add(cell(1, 1)));
        assertFalse(filter.subtract(cell(2, 1)));
        assertFalse(filter.add(cell(3, 1)));
        assertFalse(filter.add(cell(7, Integer.MAX_VALUE)));
        assertPositiveCells(filter, new int[] {0, Integer.MIN_VALUE, -1, 1, 0, 0, 0, Integer.MAX_VALUE});
        // Callback extraction has an intentionally different, nonzero-cell contract.
        final BitSet nonzero = new BitSet();
        assertTrue(filter.processIndices(index -> { nonzero.set(index); return true; }));
        assertArrayEquals(new int[] {1, 2, 3, 7}, nonzero.stream().toArray());
        assertFalse(filter.subtract(cell(1, 1)));
        assertFalse(filter.add(cell(2, 2)));
        assertPositiveCells(filter, new int[] {0, Integer.MAX_VALUE, 1, 1, 0, 0, 0, Integer.MAX_VALUE});
        filter.clear();
        assertFalse(filter.isValid());
        assertEquals(0, filter.cardinality());
        assertArrayEquals(new int[0], filter.asIndexArray());
    }

    @Test
    void copyOwnsIndependentCellsAndExports() {
        final ArrayCountingBloomFilter filter = new ArrayCountingBloomFilter(Shape.fromKM(1, 129));
        filter.add(cell(0, 1));
        filter.add(cell(64, 2));
        filter.add(cell(128, 3));
        final ArrayCountingBloomFilter copy = filter.copy();
        copy.subtract(cell(64, 2));
        filter.add(cell(127, 1));
        assertArrayEquals(new int[] {0, 128}, copy.asIndexArray());
        assertArrayEquals(new int[] {0, 64, 127, 128}, filter.asIndexArray());
        assertEquals(2, copy.cardinality());
        assertEquals(4, filter.cardinality());
    }

    @Test
    void partialMutationAndCallbacksObserveTheCurrentCount() {
        final ArrayCountingBloomFilter filter = new ArrayCountingBloomFilter(Shape.fromKM(1, 8));
        assertThrows(IllegalStateException.class, () -> filter.add(consumer -> {
            consumer.test(1, 1);
            assertEquals(1, filter.cardinality());
            consumer.test(3, 2);
            assertEquals(2, filter.cardinality());
            throw new IllegalStateException("after two writes");
        }));
        assertArrayEquals(new int[] {1, 3}, filter.asIndexArray());
        assertThrows(IllegalArgumentException.class, () -> filter.subtract(consumer -> {
            consumer.test(1, 1);
            assertEquals(1, filter.cardinality());
            return consumer.test(8, 1);
        }));
        assertEquals(1, filter.cardinality());
        assertArrayEquals(new int[] {3}, filter.asIndexArray());
        assertThrows(IllegalArgumentException.class, () -> filter.add(cell(-1, 1)));
        assertEquals(1, filter.cardinality());
    }

    @Test
    void seededMutationsMatchIndependentCellModel() {
        final Random random = new Random(0x4d33424c4f4f4dL);
        final int[] deltas = {0, 1, 2, -1, -2, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (final int size : new int[] {1, 2, 7, 31, 64, 65, 127, 257}) {
            final int[] cells = new int[size];
            final ArrayCountingBloomFilter filter = new ArrayCountingBloomFilter(Shape.fromKM(1, size));
            for (int step = 0; step < 2000; step++) {
                final int index = random.nextInt(size);
                final int delta = deltas[random.nextInt(deltas.length)];
                if (random.nextBoolean()) {
                    filter.add(cell(index, delta));
                    cells[index] += delta;
                } else {
                    filter.subtract(cell(index, delta));
                    cells[index] -= delta;
                }
                assertPositiveCells(filter, cells);
                if (step % 97 == 0) {
                    assertPositiveCells(filter.copy(), cells);
                }
            }
        }
    }
}
