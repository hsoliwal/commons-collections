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

    /** Validate the actual array-backed AVL, not only its successor snapshot. */
    private static int verifyNode(final int slot, final long lower, final long upper,
            final int[] keys, final int[] left, final int[] right,
            final byte[] heights, final boolean[] visited) {
        if (slot == -1) {
            return -1;
        }
        check(slot >= 0 && slot < visited.length && !visited[slot], "AVL slot ownership");
        visited[slot] = true;
        check(keys[slot] > lower && keys[slot] < upper, "AVL key ordering");
        final int a = verifyNode(left[slot], lower, keys[slot], keys, left, right, heights, visited);
        final int b = verifyNode(right[slot], keys[slot], upper, keys, left, right, heights, visited);
        check(Math.abs(a - b) <= 1 && heights[slot] == Math.max(a, b) + 1, "AVL height/balance");
        return heights[slot];
    }

    private static void verifyTree(final SparseBloomFilter filter) {
        final Object store = field(filter, "indices");
        final int size = (Integer) field(store, "size");
        final int[] keys = (int[]) field(store, "keys");
        final int[] left = (int[]) field(store, "left");
        final int[] right = (int[]) field(store, "right");
        final int[] next = (int[]) field(store, "next");
        final byte[] heights = (byte[]) field(store, "heights");
        final boolean[] visited = new boolean[size];
        verifyNode((Integer) field(store, "root"), Long.MIN_VALUE, Long.MAX_VALUE,
                keys, left, right, heights, visited);
        int at = (Integer) field(store, "first");
        int last = -1;
        for (int i = 0; i < size; i++) {
            check(visited[i], "unreachable AVL slot");
            check(at >= 0 && at < size, "successor bounds/cycle");
            if (last != -1) {
                check(keys[last] < keys[at], "successor key ordering");
            }
            check(filter.contains(IndexExtractor.fromIndexArray(keys[at])), "AVL lookup");
            last = at;
            at = next[at];
        }
        check(at == -1 && (Integer) field(store, "last") == last, "successor end");
        check(filter.cardinality() == size, "AVL cardinality");
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
                    verifyTree(direct);
                    verifyTree(byBitMaps);
                    verifyTree(byBloom);
                    final int followUp = Math.max(0, bits - 1);
                    equalOutcome(outcome(() -> oldPath.merge(IndexExtractor.fromIndexArray(followUp))),
                            outcome(() -> direct.merge(IndexExtractor.fromIndexArray(followUp))),
                            "subsequent AVL insertion");
                    same(oldPath.asIndexArray(), direct.asIndexArray(), "subsequent tree update");
                    verifyTree(direct);
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
        // Shape mismatch and invalid tail bits must retain the original
        // successful prefix, post-merge exception and a searchable AVL.
        for (final int sourceBits : new int[] {1024, 4095, 8191}) {
            final SimpleBloomFilter src = source(sourceBits, 0, 37);
            final SparseBloomFilter a = receiver(511, 3);
            final SparseBloomFilter b = a.copy();
            equalOutcome(outcome(() -> a.merge(IndexExtractor.fromBitMapExtractor(src))),
                    outcome(() -> b.merge((IndexExtractor) src)), "large invalid shape");
            same(a.asIndexArray(), b.asIndexArray(), "large invalid prefix");
            verifyTree(b);
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
