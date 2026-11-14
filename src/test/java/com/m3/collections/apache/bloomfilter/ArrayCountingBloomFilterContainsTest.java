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
// Copyright 2026 Hitesh Soliwal and contributors to the Synexia Project.
// SPDX-License-Identifier: Apache-2.0
package com.m3.collections.apache.bloomfilter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

import org.junit.jupiter.api.Test;

/** Differential containment contracts against the original per-cell route. */
class ArrayCountingBloomFilterContainsTest {

    private static CellExtractor cell(final int index, final int count) {
        return consumer -> consumer.test(index, count);
    }

    private static ArrayCountingBloomFilter filter(final int size) {
        return new ArrayCountingBloomFilter(Shape.fromKM(1, size));
    }

    private static boolean baseline(final ArrayCountingBloomFilter filter, final BitMapExtractor source) {
        return filter.contains(IndexExtractor.fromBitMapExtractor(source));
    }

    private static Object outcome(final BooleanSupplier operation) {
        try {
            return operation.getAsBoolean();
        } catch (final RuntimeException ex) {
            return ex;
        }
    }

    private static void assertOutcomeEquals(final Object expected, final Object actual) {
        if (expected instanceof RuntimeException && actual instanceof RuntimeException) {
            final RuntimeException first = (RuntimeException) expected;
            final RuntimeException second = (RuntimeException) actual;
            assertEquals(first.getClass(), second.getClass());
            // HotSpot may omit messages at frequently executed implicit throw sites.
            // Compare the exact index diagnostic whenever both VMs/sites retain it.
            if (first.getMessage() != null && second.getMessage() != null) {
                assertEquals(first.getMessage(), second.getMessage());
            }
        } else {
            assertEquals(expected, actual);
        }
    }

    private static BitMapExtractor source(final long[] words, final boolean ignoreFalse,
            final List<Boolean> callbacks) {
        return consumer -> {
            for (final long word : words) {
                final boolean result = consumer.test(word);
                callbacks.add(result);
                if (!result && !ignoreFalse) {
                    return false;
                }
            }
            return true;
        };
    }

    private static void assertMatches(final ArrayCountingBloomFilter filter, final long[] words,
            final boolean ignoreFalse) {
        final List<Boolean> expectedCalls = new ArrayList<>();
        final List<Boolean> actualCalls = new ArrayList<>();
        final long[] before = filter.asBitMapArray();
        final int cardinality = filter.cardinality();
        final boolean valid = filter.isValid();
        final Object expected = outcome(() -> baseline(filter, source(words, ignoreFalse, expectedCalls)));
        final Object actual = outcome(() -> filter.contains(source(words, ignoreFalse, actualCalls)));
        assertOutcomeEquals(expected, actual);
        assertEquals(expectedCalls, actualCalls);
        assertArrayEquals(before, filter.asBitMapArray());
        assertEquals(cardinality, filter.cardinality());
        assertEquals(valid, filter.isValid());
    }

    @Test
    void exhaustiveSmallMasksPreservePositiveAndNegativeOccupancy() {
        for (int mask = 0; mask < 256; mask++) {
            final ArrayCountingBloomFilter filter = filter(8);
            for (int bit = 0; bit < 8; bit++) {
                if ((mask & 1 << bit) != 0) {
                    filter.add(cell(bit, (bit & 1) == 0 ? 1 : -1));
                }
            }
            for (int query = 0; query < 256; query++) {
                final boolean expected = (query & mask) == query;
                assertEquals(expected, filter.contains(BitMapExtractor.fromBitMapArray(query)));
                assertEquals(expected, baseline(filter, BitMapExtractor.fromBitMapArray(query)));
            }
        }
    }

    @Test
    void seededDifferentialCasesCoverSparseDenseAndPartialWords() {
        final Random random = new Random(0x636f6e7461696e73L);
        for (final int size : new int[] {1, 2, 63, 64, 65, 127, 128, 129, 256, 511, 1024}) {
            for (int sample = 0; sample < 160; sample++) {
                final ArrayCountingBloomFilter filter = filter(size);
                final int density = sample % 4;
                for (int bit = 0; bit < size; bit++) {
                    if (random.nextInt(4) <= density) {
                        filter.add(cell(bit, random.nextBoolean() ? 1 : -1));
                    }
                }
                final int count = BitMaps.numberOfBitMaps(size);
                final long[] query = new long[count + random.nextInt(3)];
                for (int word = 0; word < query.length; word++) {
                    query[word] = random.nextBoolean() ? random.nextLong() : 0L;
                }
                assertMatches(filter, query, false);
                assertMatches(filter, query, true);
                assertMatches(filter, filter.asBitMapArray(), false);
            }
        }
    }

    @Test
    void firstMissingBitPreservesTailBoundsExceptionOrder() {
        final ArrayCountingBloomFilter filter = filter(65);
        filter.add(cell(64, 1));
        assertMatches(filter, new long[] {0L, 3L}, false);
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> filter.contains(BitMapExtractor.fromBitMapArray(0L, 3L)));
        filter.clear();
        assertMatches(filter, new long[] {0L, 3L}, false);
        assertFalse(filter.contains(BitMapExtractor.fromBitMapArray(0L, 3L)));
        assertMatches(filter, new long[] {0L, 0L, 0L, Long.MIN_VALUE}, false);
        assertMatches(filter, new long[] {0L, 0L, 0L}, false);
        assertMatches(filter, new long[0], false);
    }

    @Test
    void ignoredFalseDoesNotAdvanceTheWordCursor() {
        final ArrayCountingBloomFilter filter = filter(128);
        filter.add(cell(1, 1));
        assertMatches(filter, new long[] {3L, 2L, 1L, 0L, 1L}, true);
        final List<Boolean> calls = new ArrayList<>();
        assertTrue(filter.contains(source(new long[] {3L, 2L, 1L}, true, calls)));
        assertEquals(java.util.Arrays.asList(false, true, false), calls);
    }

    private static BitMapExtractor mutatingSource(final ArrayCountingBloomFilter receiver) {
        return consumer -> {
            receiver.add(cell(63, -1));
            if (!consumer.test(Long.MIN_VALUE)) {
                return false;
            }
            receiver.clear();
            receiver.add(cell(64, 1));
            if (!consumer.test(1L)) {
                return false;
            }
            receiver.add(cell(128, Integer.MAX_VALUE));
            receiver.add(cell(128, 1));
            return consumer.test(1L);
        };
    }

    @Test
    void producerMutationsRemainVisibleBetweenWordCallbacks() {
        final ArrayCountingBloomFilter expected = filter(129);
        final ArrayCountingBloomFilter actual = filter(129);
        assertEquals(baseline(expected, mutatingSource(expected)), actual.contains(mutatingSource(actual)));
        assertArrayEquals(expected.asBitMapArray(), actual.asBitMapArray());
        assertEquals(expected.cardinality(), actual.cardinality());
        assertEquals(expected.isValid(), actual.isValid());
        assertFalse(actual.isValid());
        assertTrue(actual.contains(BitMapExtractor.fromBitMapArray(0L, 1L, 1L)));
    }

    @Test
    void retainedPredicateUsesLiveReceiverStateAndOriginalCursorRules() {
        final ArrayCountingBloomFilter filter = filter(128);
        final LongPredicate[] expected = new LongPredicate[1];
        final LongPredicate[] actual = new LongPredicate[1];
        assertTrue(baseline(filter, predicate -> {
            expected[0] = predicate;
            return true;
        }));
        assertTrue(filter.contains((BitMapExtractor) predicate -> {
            actual[0] = predicate;
            return true;
        }));
        for (final int bit : new int[] {0, 63, 64, 127}) {
            filter.add(cell(bit, 1));
        }
        for (final long word : new long[] {2L, Long.MIN_VALUE | 1L, 2L, Long.MIN_VALUE | 1L, 0L, 1L}) {
            assertOutcomeEquals(outcome(() -> expected[0].test(word)), outcome(() -> actual[0].test(word)));
        }
    }

    @Test
    void streamingZeroWordsPreserveSignedIndexWraparound() {
        final ArrayCountingBloomFilter filter = filter(1);
        final BitMapExtractor source = consumer -> {
            for (int word = 0; word < 1 << 25; word++) {
                if (!consumer.test(0L)) {
                    return false;
                }
            }
            return consumer.test(1L);
        };
        final Object expected = outcome(() -> baseline(filter, source));
        assertOutcomeEquals(expected, outcome(() -> filter.contains(source)));
        assertTrue(expected instanceof ArrayIndexOutOfBoundsException);
        final String message = ((RuntimeException) expected).getMessage();
        if (message != null) {
            assertTrue(message.contains("-2147483648"));
        }
    }

    @Test
    void nullProducerAndProducerReturnOrExceptionRemainUnchanged() {
        assertEquals("bitMapExtractor", assertThrows(NullPointerException.class,
                () -> filter(1).contains((BitMapExtractor) null)).getMessage());
        assertFalse(filter(1).contains((BitMapExtractor) consumer -> false));
        final IllegalStateException failure = new IllegalStateException("producer failure");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> filter(1).contains((BitMapExtractor) consumer -> {
            assertTrue(consumer.test(0L));
            throw failure;
        })));
    }
}
