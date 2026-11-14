/*
 * Copyright 2026 Hitesh Soliwal and contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.bloomfilter;

import java.util.Arrays;
import java.util.Random;

/** Compare final-peer merging with the original arbitrary-producer route. */
public final class BitmapMergeContract {
    private static long checks;
    private static long fingerprint;

    private BitmapMergeContract() {
    }

    private static void check(final boolean valid, final String message) {
        checks++;
        if (!valid) {
            throw new AssertionError(message);
        }
    }

    private static void equal(final long actual, final long expected) {
        check(actual == expected, actual + " != " + expected);
        fingerprint = fingerprint * 31 + expected;
    }

    private static void same(final SimpleBloomFilter actual, final SimpleBloomFilter expected) {
        final long[] a = actual.asBitMapArray();
        final long[] b = expected.asBitMapArray();
        check(Arrays.equals(a, b), "merge changed partial-write bitmap");
        for (final long word : b) {
            fingerprint = fingerprint * 31 + word;
        }
        // Cached results, even stale after a failed merge, must match the incumbent.
        equal(actual.cardinality(), expected.cardinality());
        check(actual.isEmpty() == expected.isEmpty(), "empty/cache mismatch");
    }

    private static String merge(final SimpleBloomFilter target, final BitMapExtractor source,
            final boolean bloomOverload) {
        try {
            return "return:" + (bloomOverload
                    ? target.merge((BloomFilter<?>) source) : target.merge(source));
        } catch (final RuntimeException failure) {
            final StringBuilder result = new StringBuilder();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                result.append(cause.getClass().getName()).append(':');
                // HotSpot can omit repeated implicit-exception diagnostics at a hot site.
                // Compare public messages always; compare nested VM messages in the
                // additional -XX:-OmitStackTraceInFastThrow diagnostic run.
                if (cause == failure || Boolean.getBoolean("m3.test.fullCauseMessages")) {
                    result.append(cause.getMessage());
                }
                result.append(';');
            }
            return result.toString();
        }
    }

    private static SimpleBloomFilter randomFilter(final int bits, final Random random) {
        final SimpleBloomFilter filter = new SimpleBloomFilter(Shape.fromKM(1, bits));
        filter.merge((IndexExtractor) predicate -> {
            for (int bit = 0; bit < bits; bit++) {
                if (random.nextInt(7) == 0 && !predicate.test(bit)) {
                    return false;
                }
            }
            return true;
        });
        return filter;
    }

    private static void peerParity() {
        final Random random = new Random(0x4d455247454d334cL);
        final int[] sizes = {1, 2, 31, 32, 63, 64, 65, 127, 128, 129, 255, 256};
        for (int iteration = 0; iteration < 12000; iteration++) {
            final int targetBits = iteration < 144 ? sizes[iteration / 12] : 1 + random.nextInt(513);
            final int sourceBits = iteration < 144 ? sizes[iteration % 12] : 1 + random.nextInt(513);
            final SimpleBloomFilter source = randomFilter(sourceBits, random);
            final SimpleBloomFilter target = randomFilter(targetBits, random);
            if ((iteration & 1) == 0) {
                target.cardinality();
            }
            final SimpleBloomFilter reference = target.copy();
            final long[] frozenSource = source.asBitMapArray();
            final BitMapExtractor proxy = predicate -> source.processBitMaps(predicate);
            final String actual = merge(target, source, (iteration & 2) == 0);
            final String expected = merge(reference, proxy, false);
            check(actual.equals(expected), "result/exception mismatch: " + actual + " != " + expected);
            fingerprint = fingerprint * 31 + expected.hashCode();
            same(target, reference);
            check(Arrays.equals(frozenSource, source.asBitMapArray()), "merge changed its source");
        }
    }

    private static void boundaries() {
        for (final int bits : new int[] {1, 31, 32, 63, 64, 65, 127, 128, 129}) {
            final SimpleBloomFilter source = new SimpleBloomFilter(Shape.fromKM(1, bits));
            source.merge(IndexExtractor.fromIndexArray(0, bits - 1));
            final SimpleBloomFilter expected = source.copy();
            final long[] snapshot = source.asBitMapArray();
            check(merge(source, source, false).equals(merge(expected,
                    BitMapExtractor.fromBitMapArray(snapshot), false)), "self-merge result");
            same(source, expected);
        }
        // Overflow is checked after the same successful prefix has been OR-ed.
        final SimpleBloomFilter source = new SimpleBloomFilter(Shape.fromKM(1, 129));
        source.merge(IndexExtractor.fromIndexArray(0, 64, 128));
        final SimpleBloomFilter actual = new SimpleBloomFilter(Shape.fromKM(1, 64));
        final SimpleBloomFilter reference = actual.copy();
        final String a = merge(actual, source, false);
        final String b = merge(reference, p -> source.processBitMaps(p), false);
        check(a.equals(b), "oversize source failure");
        equal(actual.asBitMapArray()[0], 1L);
        equal(actual.cardinality(), 0); // Preserve the original failed-merge cache state.
        same(actual, reference);
        check(merge(actual, null, false).equals(merge(reference, null, false)), "null bitmap producer");
        check(merge(actual, null, true).equals(merge(reference, null, true)), "null Bloom producer");

        final SimpleBloomFilter customTarget = new SimpleBloomFilter(Shape.fromKM(1, 128));
        final int[] calls = {0};
        check(customTarget.merge((BitMapExtractor) p -> {
            calls[0]++;
            check(p.test(4), "merge requests continued production");
            return false;
        }), "original ignored producer result must remain true");
        equal(calls[0], 1);
        equal(customTarget.asBitMapArray()[0], 4L);
        equal(customTarget.cardinality(), 1);
        final RuntimeException sentinel = new IllegalStateException("producer sentinel");
        try {
            customTarget.merge((BitMapExtractor) p -> {
                p.test(8);
                throw sentinel;
            });
            throw new AssertionError("producer exception swallowed");
        } catch (final RuntimeException failure) {
            check(failure == sentinel, "exception identity");
        }
        equal(customTarget.asBitMapArray()[0], 12L);
        equal(customTarget.cardinality(), 1); // Failure must not newly invalidate the cache.
    }

    private static void validExportDensities() {
        final Random random = new Random(0x4558504f52544cL);
        for (final int bits : new int[] {1, 63, 64, 65, 127, 128, 129, 1024, 4097}) {
            final ArrayCountingBloomFilter filter = new ArrayCountingBloomFilter(Shape.fromKM(1, bits));
            final int[] counts = new int[bits];
            final int sparseLimit = bits / Long.SIZE;
            for (final int populated : new int[] {0, 1, Math.max(0, sparseLimit - 1), sparseLimit,
                    Math.min(bits, sparseLimit + 1), bits / 2, bits}) {
                filter.clear();
                Arrays.fill(counts, 0);
                for (int i = 0; i < populated; i++) {
                    final int index = i;
                    filter.add(p -> p.test(index, 1));
                    counts[i] = 1;
                }
                for (int step = 0; step < 120; step++) {
                    final long[] expected = new long[(bits + 63) / 64];
                    int cardinality = 0;
                    for (int i = 0; i < bits; i++) {
                        if (counts[i] != 0) { expected[i >>> 6] |= 1L << i; cardinality++; }
                    }
                    final long[] actual = filter.asBitMapArray();
                    check(Arrays.equals(actual, expected), "valid sparse/dense export");
                    for (final long word : expected) { fingerprint = fingerprint * 31 + word; }
                    equal(filter.cardinality(), cardinality);
                    check(filter.isValid(), "valid fixture unexpectedly invalid");
                    if (actual.length != 0) { actual[0] = ~actual[0]; }
                    check(Arrays.equals(filter.asBitMapArray(), expected), "export aliases the owner");
                    final int index = random.nextInt(bits);
                    if (counts[index] == 0 || random.nextBoolean()) {
                        filter.add(p -> p.test(index, 1)); counts[index]++;
                    } else {
                        filter.subtract(p -> p.test(index, 1)); counts[index]--;
                    }
                }
            }
            filter.clear();
            filter.subtract(p -> p.test(bits - 1, 1));
            equal(filter.cardinality(), 0);
            check(!filter.isValid(), "negative fixture must be invalid");
            equal(filter.asBitMapArray()[(bits - 1) >>> 6], 1L << (bits - 1));
            filter.clear();
            check(!filter.isValid(), "clear must retain the existing permanent invalidation");
            check(Arrays.equals(filter.asBitMapArray(), new long[(bits + 63) / 64]), "invalid cleared export");
        }
    }

    public static void verify() {
        checks = 0;
        fingerprint = 0;
        peerParity();
        boundaries();
        validExportDensities();
        System.out.println("PASS merge_checks=" + checks + " fingerprint=" + Long.toHexString(fingerprint));
    }

    public static void main(final String[] args) {
        verify();
    }
}
