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

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Random;

/** Compares the dense-to-sparse fast path with the unchanged generic source path. */
public final class SparseDensePeerContract {
    private static long checks;
    private static long digest = 0x13579BDFL;

    private static void check(final boolean result, final String label) {
        checks++;
        if (!result) {
            throw new AssertionError(label + " check=" + checks);
        }
    }

    private static void same(final int[] expected, final int[] result, final String label) {
        check(Arrays.equals(expected, result), label);
        digest = digest * 31 + Arrays.hashCode(result);
    }

    private static String outcome(final java.util.function.BooleanSupplier run) {
        try {
            return Boolean.toString(run.getAsBoolean());
        } catch (final RuntimeException e) {
            return e.getClass().getName() + ":" + e.getMessage();
        }
    }

    private static void equalOutcome(final String expected, final String result, final String label) {
        check(expected.equals(result), label + " baseline=" + expected + " actual=" + result);
        digest = 31 * digest + expected.hashCode();
    }

    private static SimpleBloomFilter source(final int bits, final int pattern, final long seed) {
        final SimpleBloomFilter result = new SimpleBloomFilter(Shape.fromKM(1, bits));
        final Random random = new Random(seed);
        final int[] keys = new int[bits];
        int count = 0;
        for (int index = 0; index < bits; index++) {
            if (pattern == 0 || pattern == 1 && (index & 1) == 0
                    || pattern == 2 && (index & 3) == 3
                    || pattern == 3 && (index & 15) == 0
                    || pattern == 4 && index == bits - 1
                    || pattern == 5 && random.nextInt(9) == 0) {
                keys[count++] = index;
            }
        }
        if (count > 0) {
            result.merge(IndexExtractor.fromIndexArray(Arrays.copyOf(keys, count)));
        }
        return result;
    }

    private static SparseBloomFilter receiver(final int bits, final int pattern) {
        final SparseBloomFilter result = new SparseBloomFilter(Shape.fromKM(1, bits));
        final int[] keys = new int[bits];
        int count = 0;
        for (int index = 0; index < bits; index++) {
            if (pattern == 0 || pattern == 1 && (index & 1) != 0
                    || pattern == 2 && (index & 3) == 1
                    || pattern == 3 && index == 0
                    || pattern == 4 && index == bits - 1) {
                keys[count++] = index;
            }
        }
        if (count > 0) {
            result.merge(IndexExtractor.fromIndexArray(Arrays.copyOf(keys, count)));
        }
        return result;
    }

    private static Object field(final Object owner, final String name) {
        try {
            final Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    public static void verify() {
        checks = 0;
        digest = 0x13579BDFL;
        for (final int bits : new int[] {1, 2, 31, 32, 63, 64, 65, 127, 128, 129, 1024, 4096, 8192}) {
            for (int targetMode = 0; targetMode < 5; targetMode++) {
                for (int sourceMode = 0; sourceMode < 6; sourceMode++) {
                    final SimpleBloomFilter src = source(bits, sourceMode, 31337 + bits * 3L);
                    final SparseBloomFilter initial = receiver(bits, targetMode);
                    final SparseBloomFilter direct = initial.copy();
                    final SparseBloomFilter oldPath = initial.copy();
                    final SparseBloomFilter byBitMaps = initial.copy();
                    final SparseBloomFilter byBloom = initial.copy();
                    final int[] originalSource = src.asIndexArray();
                    equalOutcome(outcome(() -> oldPath.contains((IndexExtractor) src::processIndices)),
                            outcome(() -> direct.contains((IndexExtractor) src)), "contains index extractor");
                    equalOutcome(outcome(() -> initial.contains(IndexExtractor.fromBitMapExtractor(src))),
                            outcome(() -> byBitMaps.contains((BitMapExtractor) src)), "contains bitmap extractor");
                    equalOutcome(outcome(() -> initial.contains((BloomFilter<?>) src)),
                            outcome(() -> byBloom.contains((BloomFilter<?>) src)), "contains Bloom filter");
                    equalOutcome(outcome(() -> oldPath.merge((IndexExtractor) src::processIndices)),
                            outcome(() -> direct.merge((IndexExtractor) src)), "merge index extractor");
                    equalOutcome(outcome(() -> initial.copy().merge(IndexExtractor.fromBitMapExtractor(src))),
                            outcome(() -> byBitMaps.merge((BitMapExtractor) src)), "merge bitmap extractor");
                    equalOutcome(outcome(() -> initial.copy().merge(IndexExtractor.fromBitMapExtractor(src))),
                            outcome(() -> byBloom.merge((BloomFilter<?>) src)), "merge Bloom filter");
                    same(oldPath.asIndexArray(), direct.asIndexArray(), "merge result ordered");
                    same(direct.asIndexArray(), byBitMaps.asIndexArray(), "bitmap merge result");
                    same(direct.asIndexArray(), byBloom.asIndexArray(), "Bloom merge result");
                    same(originalSource, src.asIndexArray(), "source modified");
                    check(direct.cardinality() == oldPath.cardinality(), "cardinality");
                    check(((int[]) field(field(direct, "indices"), "keys")).length
                            == ((int[]) field(field(oldPath, "indices"), "keys")).length,
                            "geometric growth endpoint");
                    check(field(field(direct, "indices"), "modCount").equals(
                            field(field(oldPath, "indices"), "modCount")), "modCount");
                }
            }
        }
        // The original bitmap adapter observes invalid tail bits as indices.
        for (final int badBits : new int[] {63, 65, 128}) {
            final SimpleBloomFilter src = source(badBits, 5, 15);
            final long[] words = (long[]) field(src, "bitMap");
            words[words.length - 1] |= Long.MIN_VALUE;
            final SparseBloomFilter original = receiver(badBits, 3);
            final SparseBloomFilter candidate = original.copy();
            equalOutcome(outcome(() -> original.merge(IndexExtractor.fromBitMapExtractor(src))),
                    outcome(() -> candidate.merge((IndexExtractor) src)), "tail exception behavior");
            same(original.asIndexArray(), candidate.asIndexArray(), "tail partial result");
        }
        // Arbitrary producers still supply observable callbacks and failures.
        final SparseBloomFilter target = receiver(1024, 1);
        final int[] callbacks = {0};
        final RuntimeException failure = new IllegalStateException("producer failure");
        try {
            target.merge((IndexExtractor) predicate -> {
                callbacks[0]++;
                predicate.test(1);
                throw failure;
            });
            throw new AssertionError("missing producer exception");
        } catch (final IllegalStateException expected) {
            check(expected == failure, "producer exception identity");
        }
        check(callbacks[0] == 1 && target.contains(IndexExtractor.fromIndexArray(1)),
                "producer failure prefix");
        System.out.println("SPARSE_DENSE_PEER_PASS checks=" + checks + " digest=" + Long.toHexString(digest));
    }

    public static void main(final String[] args) {
        verify();
    }
}
