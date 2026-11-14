/*
 * Copyright 2026 Hitesh Soliwal and contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.bloomfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

/** Independent executable contracts for the existing bitmap collection owners. */
public final class BitmapHotPathContract {
    private static long checks;
    private static long fingerprint;

    private BitmapHotPathContract() {
    }

    private static void check(final boolean condition, final String description) {
        checks++;
        if (!condition) {
            throw new AssertionError(description + " at check " + checks);
        }
    }

    private static void equal(final long actual, final long expected) {
        check(actual == expected, actual + " != " + expected);
        fingerprint = 31 * fingerprint + expected;
    }

    private static void words(final long[] actual, final long[] expected) {
        equal(actual.length, expected.length);
        for (int i = 0; i < expected.length; i++) {
            equal(actual[i], expected[i]);
        }
    }

    private static List<Integer> indices(final long[] values) {
        final BitSet model = BitSet.valueOf(values);
        final List<Integer> result = new ArrayList<>();
        for (int bit = model.nextSetBit(0); bit >= 0; bit = model.nextSetBit(bit + 1)) {
            result.add(bit);
        }
        return result;
    }

    private static void trace(final IndexExtractor source, final List<Integer> expected, final int limit) {
        final List<Integer> actual = new ArrayList<>();
        final boolean result = source.processIndices(value -> {
            actual.add(value);
            return actual.size() < limit;
        });
        final int count = Math.min(expected.size(), limit);
        equal(actual.size(), count);
        for (int i = 0; i < count; i++) {
            equal(actual.get(i), expected.get(i));
        }
        check(result == (expected.size() < limit), "short circuit result");
    }

    private static SimpleBloomFilter filter(final long[] values) {
        final SimpleBloomFilter result = new SimpleBloomFilter(Shape.fromKM(1, Math.max(1, values.length) * 64));
        result.merge(BitMapExtractor.fromBitMapArray(values));
        return result;
    }

    private static void extraction() {
        final Random random = new Random(0x6d334249544d4150L);
        for (int example = 0; example < 2000; example++) {
            final long[] values = new long[random.nextInt(65)];
            for (int i = 0; i < values.length; i++) {
                switch (example % 5) {
                case 0: values[i] = 0; break;
                case 1: values[i] = 1L << random.nextInt(64); break;
                case 2: values[i] = Long.MIN_VALUE; break;
                case 3: values[i] = -1L; break;
                default: values[i] = random.nextLong(); break;
                }
            }
            final List<Integer> expected = indices(values);
            final IndexExtractor extracted = IndexExtractor.fromBitMapExtractor(BitMapExtractor.fromBitMapArray(values));
            final SimpleBloomFilter simple = filter(values);
            for (final int limit : new int[] {1, 2, 17, Integer.MAX_VALUE}) {
                trace(extracted, expected, limit);
                trace(simple, expected, limit);
            }
            equal(simple.cardinality(), expected.size());
            equal(simple.cardinality(), expected.size());
            equal(SetOperations.cardinality(simple), expected.size());
            check(simple.isEmpty() == expected.isEmpty(), "empty");
            final SimpleBloomFilter copy = simple.copy();
            words(copy.asBitMapArray(), simple.asBitMapArray());
            simple.clear();
            equal(simple.cardinality(), 0);
            trace(copy, expected, Integer.MAX_VALUE);
        }
        // Every bit position, including the sign bit, and both word boundaries.
        for (int bit = 0; bit < 192; bit++) {
            final long[] values = new long[3];
            values[bit / 64] = 1L << bit;
            trace(IndexExtractor.fromBitMapExtractor(BitMapExtractor.fromBitMapArray(values)),
                    Arrays.asList(bit), Integer.MAX_VALUE);
        }
    }

    private static void callbackContracts() {
        check(IndexExtractor.fromBitMapExtractor(BitMapExtractor.fromBitMapArray(0, 0)).processIndices(null),
                "generic extractor keeps lazy null on zero words");
        boolean failed = false;
        try {
            filter(new long[0]).processIndices(null);
        } catch (final NullPointerException expected) {
            failed = true;
        }
        check(failed, "simple owner checks null eagerly");
        final RuntimeException sentinel = new IllegalArgumentException("sentinel");
        for (int variant = 0; variant < 2; variant++) {
            final long[] values = {3, 1};
            final SimpleBloomFilter simple = filter(values);
            final IndexExtractor source = variant == 0 ? simple
                    : IndexExtractor.fromBitMapExtractor(BitMapExtractor.fromBitMapArray(values));
            final List<Integer> actual = new ArrayList<>();
            source.processIndices(bit -> {
                actual.add(bit);
                if (bit == 0) {
                    if (source == simple) {
                        simple.clear();
                        simple.merge(IndexExtractor.fromIndexArray(2, 65));
                    } else {
                        values[0] = 4;
                        values[1] = 2;
                    }
                }
                return true;
            });
            check(actual.equals(Arrays.asList(0, 1, 65)), "current word frozen; future word live");
            failed = false;
            try {
                source.processIndices(bit -> { throw sentinel; });
            } catch (final RuntimeException failure) {
                check(failure == sentinel, "callback exception identity");
                failed = true;
            }
            check(failed, "callback threw");
        }
        // A non-conforming producer can ignore false; do not silently change its cursor state.
        final BitMapExtractor ignoresFalse = predicate -> {
            predicate.test(3);
            predicate.test(2);
            return true;
        };
        final List<Integer> observed = new ArrayList<>();
        IndexExtractor.fromBitMapExtractor(ignoresFalse).processIndices(bit -> {
            observed.add(bit);
            return false;
        });
        check(observed.equals(Arrays.asList(0, 1)), "refused word does not advance base");
        final long[] reentrantWords = {1, Long.MIN_VALUE};
        final IndexExtractor reentrant = IndexExtractor.fromBitMapExtractor(BitMapExtractor.fromBitMapArray(reentrantWords));
        final int[] calls = {0};
        reentrant.processIndices(bit -> {
            calls[0]++;
            trace(reentrant, Arrays.asList(0, 127), Integer.MAX_VALUE);
            return true;
        });
        equal(calls[0], 2);
        // Reentry through the very same producer callback, not a new traversal.
        final java.util.function.LongPredicate[] visitor = {null};
        final BitMapExtractor sharedVisitor = predicate -> {
            visitor[0] = predicate;
            return predicate.test(3L) && predicate.test(1L);
        };
        final boolean[] nested = {false};
        final List<Integer> reentered = new ArrayList<>();
        IndexExtractor.fromBitMapExtractor(sharedVisitor).processIndices(bit -> {
            reentered.add(bit);
            if (!nested[0]) {
                nested[0] = true;
                check(visitor[0].test(1L), "nested producer callback completes");
            }
            return true;
        });
        check(reentered.equals(Arrays.asList(0, 0, 1, 128)), "reentrant word retains original base");
    }

    private static void pairs() {
        final Random random = new Random(0x5041495253L);
        for (int example = 0; example < 800; example++) {
            final long[] a = new long[1 + random.nextInt(33)];
            final long[] b = new long[1 + random.nextInt(33)];
            for (int i = 0; i < a.length; i++) { a[i] = random.nextLong(); }
            for (int i = 0; i < b.length; i++) { b[i] = random.nextLong(); }
            final SimpleBloomFilter first = filter(a);
            final SimpleBloomFilter second = filter(b);
            for (int variant = 0; variant < 2; variant++) {
                final BitMapExtractor other = variant == 0 ? second : BitMapExtractor.fromBitMapArray(b);
                final int maximum = Math.max(a.length, b.length);
                for (final int stop : new int[] {1, 2, maximum, maximum + 1}) {
                    final int[] position = {0};
                    final boolean complete = first.processBitMapPairs(other, (x, y) -> {
                        final int i = position[0]++;
                        equal(x, i < a.length ? a[i] : 0);
                        equal(y, i < b.length ? b[i] : 0);
                        return position[0] < stop;
                    });
                    equal(position[0], Math.min(maximum, stop));
                    check(complete == (maximum < stop), "pair short circuit");
                }
            }
            int intersection = 0;
            int union = 0;
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                final long x = i < a.length ? a[i] : 0;
                final long y = i < b.length ? b[i] : 0;
                intersection += Long.bitCount(x & y);
                union += Long.bitCount(x | y);
            }
            equal(SetOperations.andCardinality(first, second), intersection);
            equal(SetOperations.orCardinality(first, second), union);
        }
        for (int variant = 0; variant < 2; variant++) {
            final SimpleBloomFilter first = filter(new long[] {1, 2, 4});
            final SimpleBloomFilter second = filter(new long[] {8, 16});
            final BitMapExtractor other = variant == 0 ? second : predicate -> second.processBitMaps(predicate);
            final List<Long> seen = new ArrayList<>();
            first.processBitMapPairs(other, (x, y) -> {
                seen.add(x); seen.add(y);
                if (seen.size() == 2) {
                    first.clear(); second.clear();
                    first.merge(IndexExtractor.fromIndexArray(65, 130));
                    second.merge(IndexExtractor.fromIndexArray(66));
                }
                return true;
            });
            check(seen.equals(Arrays.asList(1L, 8L, 2L, 4L, 4L, 0L)), "live pair callback ordering");
            final RuntimeException sentinel = new IllegalStateException("pair sentinel");
            try {
                first.processBitMapPairs(other, (x, y) -> { throw sentinel; });
                throw new AssertionError("missing exception");
            } catch (final RuntimeException failure) {
                check(failure == sentinel, "pair exception identity");
            }
        }
        final SimpleBloomFilter source = filter(new long[] {1, 2});
        final int[] calls = {0};
        check(!source.processBitMapPairs(predicate -> false, (x, y) -> { calls[0]++; return true; }),
                "producer false preserved");
        equal(calls[0], 0);
        for (final BitMapExtractor other : new BitMapExtractor[] {source, BitMapExtractor.fromBitMapArray(1), null}) {
            boolean failed = false;
            try { source.processBitMapPairs(other, null); } catch (final NullPointerException expected) { failed = true; }
            check(failed, "null pair callback/producer");
        }
    }

    private static void counting() {
        final Random random = new Random(0x43454c4cL);
        for (final int size : new int[] {1, 2, 63, 64, 65, 127, 128, 129, 1024, 4097}) {
            final int[] model = new int[size];
            final ArrayCountingBloomFilter filter = new ArrayCountingBloomFilter(Shape.fromKM(1, size));
            for (int step = 0; step < 3000; step++) {
                final int index = random.nextInt(size);
                final int delta = step % 127 == 0 ? Integer.MAX_VALUE : random.nextInt(7) - 3;
                if ((step & 1) == 0) {
                    filter.add(predicate -> predicate.test(index, delta));
                    model[index] += delta;
                } else {
                    filter.subtract(predicate -> predicate.test(index, delta));
                    model[index] -= delta;
                }
                if (step % 89 == 0) {
                    filter.clear(); Arrays.fill(model, 0);
                }
                final long[] expected = new long[(size + 63) / 64];
                int positive = 0;
                for (int i = 0; i < size; i++) {
                    if (model[i] != 0) { expected[i / 64] |= 1L << i; }
                    if (model[i] > 0) { positive++; }
                }
                final long[] actual = filter.asBitMapArray();
                words(actual, expected);
                equal(filter.cardinality(), positive);
                final int[] position = {0};
                filter.processBitMaps(word -> { equal(word, expected[position[0]++]); return true; });
                equal(position[0], expected.length);
                if (step % 31 == 0) {
                    final ArrayCountingBloomFilter copy = filter.copy();
                    words(copy.asBitMapArray(), expected);
                    Arrays.fill(actual, -1);
                    words(filter.asBitMapArray(), expected);
                }
            }
        }
    }

    public static void verify() {
        checks = 0; fingerprint = 0;
        extraction(); callbackContracts(); pairs(); counting();
        System.out.println("PASS checks=" + checks + " fingerprint=" + Long.toHexString(fingerprint));
    }

    public static void main(final String[] args) {
        verify();
    }
}
