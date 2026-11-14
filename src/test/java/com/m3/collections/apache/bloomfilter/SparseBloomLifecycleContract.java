/*
 * Copyright 2026 Hitesh Soliwal and contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.m3.collections.apache.bloomfilter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.TreeSet;

/** Independent set model, exact array invariants and producer callback regressions. */
public final class SparseBloomLifecycleContract {
    private static long checks;
    private static long digest;

    private SparseBloomLifecycleContract() { }

    private static void check(final boolean condition, final String message) {
        checks++;
        if (!condition) { throw new AssertionError(message + " at " + checks); }
    }

    private static void equal(final Object actual, final Object expected) {
        check(Objects.equals(actual, expected), actual + " != " + expected);
        digest = 31 * digest + Objects.hashCode(expected);
    }

    private static Object field(final Object owner, final String name) throws ReflectiveOperationException {
        final Field member = owner.getClass().getDeclaredField(name);
        member.setAccessible(true);
        return member.get(owner);
    }

    private static int visit(final int slot, final long lower, final long upper,
            final int[] keys, final int[] left, final int[] right, final byte[] heights,
            final boolean[] seen) {
        if (slot == -1) { return -1; }
        check(slot >= 0 && slot < seen.length && !seen[slot], "invalid/cyclic child");
        seen[slot] = true;
        check(keys[slot] > lower && keys[slot] < upper, "unordered child");
        final int lh = visit(left[slot], lower, keys[slot], keys, left, right, heights, seen);
        final int rh = visit(right[slot], keys[slot], upper, keys, left, right, heights, seen);
        equal((int) heights[slot], 1 + Math.max(lh, rh));
        check(Math.abs(lh - rh) <= 1, "unbalanced tree");
        return heights[slot];
    }

    private static void state(final SparseBloomFilter filter, final TreeSet<Integer> model)
            throws ReflectiveOperationException {
        final int[] result = filter.asIndexArray();
        equal(result.length, model.size());
        int index = 0;
        for (final int expected : model) { equal(result[index++], expected); }
        equal(filter.cardinality(), model.size());
        equal(filter.isEmpty(), model.isEmpty());
        final int[] second = filter.asIndexArray();
        check(result != second, "export aliases another result");
        if (result.length != 0) { result[0] ^= 1; }
        check(Arrays.equals(second, filter.asIndexArray()), "export aliases owner");
        final Object store = field(filter, "indices");
        final int[] keys = (int[]) field(store, "keys");
        final int[] left = (int[]) field(store, "left");
        final int[] right = (int[]) field(store, "right");
        final int[] next = (int[]) field(store, "next");
        final byte[] heights = (byte[]) field(store, "heights");
        final boolean[] seen = new boolean[model.size()];
        visit((Integer) field(store, "root"), Long.MIN_VALUE, Long.MAX_VALUE,
                keys, left, right, heights, seen);
        for (final boolean present : seen) { check(present, "orphaned slot"); }
        int slot = (Integer) field(store, "first");
        int last = -1;
        for (final int expected : model) {
            check(slot != -1, "short successor lane");
            equal(keys[slot], expected);
            last = slot;
            slot = next[slot];
        }
        equal(slot, -1);
        equal(field(store, "last"), last);
        digest = 31 * digest + Arrays.hashCode(keys);
        digest = 31 * digest + Arrays.hashCode(left);
        digest = 31 * digest + Arrays.hashCode(right);
        digest = 31 * digest + Arrays.hashCode(next);
        digest = 31 * digest + Arrays.hashCode(heights);
        digest = 31 * digest + (Integer) field(store, "modCount");
    }

    private static SparseBloomFilter owner(final int size) {
        final SparseBloomFilter result = new SparseBloomFilter(Shape.fromKM(1, 65536));
        for (int i = 0; i < size; i++) { result.merge(IndexExtractor.fromIndexArray(i * 2)); }
        return result;
    }

    private static String result(final Runnable action) {
        try { action.run(); return "OK"; }
        catch (final RuntimeException exception) {
            return exception.getClass().getName() + ":" + exception.getMessage();
        }
    }

    private static void randomLifecycle() throws ReflectiveOperationException {
        for (int seed = 0; seed < 12; seed++) {
            final Random random = new Random(0x5345544cL + seed);
            final SparseBloomFilter filter = owner(0);
            final TreeSet<Integer> model = new TreeSet<>();
            for (int step = 0; step < 2400; step++) {
                if (step % 211 == 0) { filter.clear(); model.clear(); }
                final int count = random.nextInt(15);
                final int[] input = new int[count];
                for (int i = 0; i < count; i++) { input[i] = random.nextInt(1024); model.add(input[i]); }
                equal(filter.merge(IndexExtractor.fromIndexArray(input)), true);
                if ((step & 15) == 0) {
                    state(filter, model);
                    final SparseBloomFilter copy = filter.copy();
                    equal(copy.contains((IndexExtractor) filter), true);
                    equal(filter.contains((BloomFilter<?>) copy), true);
                    copy.clear();
                    equal(filter.cardinality(), model.size());
                    state(filter, model);
                }
                final int query = random.nextInt(1200);
                equal(filter.contains(IndexExtractor.fromIndexArray(query)), model.contains(query));
            }
            state(filter, model);
        }
    }

    private static void peerContainment() {
        final Random random = new Random(0x434f4e5441494eL);
        for (final int size : new int[] {0, 1, 2, 7, 8, 31, 32, 63, 64, 255, 256, 1024, 8192}) {
            final SparseBloomFilter target = owner(size);
            for (final int wanted : new int[] {0, 1, 2, 7, 16, size / 4, size / 2, size, size + 1}) {
                for (int variant = 0; variant < 5; variant++) {
                    final SparseBloomFilter peer = owner(0);
                    final TreeSet<Integer> oracle = new TreeSet<>();
                    for (int i = 0; i < wanted; i++) {
                        final int key = variant == 0 ? i * 2 : variant == 1 ? (i + size) * 2
                                : variant == 2 ? i * 2 + 1 : random.nextInt(Math.max(1, size * 3));
                        peer.merge(IndexExtractor.fromIndexArray(key));
                        oracle.add(key);
                    }
                    boolean expected = true;
                    for (final int key : oracle) { expected &= key % 2 == 0 && key / 2 < size; }
                    final IndexExtractor proxy = consumer -> peer.processIndices(consumer);
                    equal(target.contains((IndexExtractor) peer), expected);
                    equal(target.contains(proxy), expected);
                    equal(target.contains((BloomFilter<?>) peer), expected);
                }
            }
            equal(target.contains((IndexExtractor) target), true);
        }
    }

    private static void invalidResidue() throws ReflectiveOperationException {
        for (final int[] values : new int[][] {{-1}, {65536}, {Integer.MIN_VALUE, Integer.MAX_VALUE},
                {3, -17, 65536, 4}, {7, 7, 7}, {0, 65535}}) {
            final SparseBloomFilter filter = owner(0);
            final TreeSet<Integer> model = new TreeSet<>();
            for (final int value : values) { model.add(value); }
            final String observed = result(() -> filter.merge(IndexExtractor.fromIndexArray(values)));
            final String expected = model.last() >= 65536
                    ? "java.lang.IllegalArgumentException:Value in list " + model.last() + " is greater than maximum value (65535)"
                    : model.first() < 0 ? "java.lang.IllegalArgumentException:Value in list " + model.first() + " is less than 0" : "OK";
            equal(observed, expected);
            state(filter, model);
            final SparseBloomFilter copy = filter.copy();
            equal(copy.contains((IndexExtractor) filter), true);
            equal(filter.contains((IndexExtractor) copy), true);
            equal(result(() -> filter.merge((IndexExtractor) filter)), expected);
            state(filter, model);
        }
    }

    private static void callbacks() {
        final SparseBloomFilter filter = owner(64);
        final List<Integer> seen = new ArrayList<>();
        equal(filter.processIndices(value -> {
            seen.add(value);
            filter.merge(IndexExtractor.fromIndexArray(value));
            return true;
        }), true);
        equal(seen.size(), 64);
        for (final boolean accept : new boolean[] {false, true}) {
            final SparseBloomFilter changed = owner(64);
            equal(result(() -> changed.processIndices(value -> {
                changed.merge(IndexExtractor.fromIndexArray(1));
                return accept;
            })), ConcurrentModificationException.class.getName() + ":null");
        }
        final RuntimeException sentinel = new IllegalStateException("producer sentinel");
        final SparseBloomFilter target = owner(1);
        final int[] calls = {0};
        equal(target.contains((IndexExtractor) consumer -> {
            calls[0]++;
            target.merge(IndexExtractor.fromIndexArray(3));
            return consumer.test(3);
        }), true);
        equal(calls[0], 1);
        check(result(() -> target.contains((IndexExtractor) consumer -> { throw sentinel; }))
                .equals("java.lang.IllegalStateException:producer sentinel"), "producer failure");
        try {
            target.merge((IndexExtractor) consumer -> { consumer.test(9); throw sentinel; });
            throw new AssertionError("missing producer failure");
        } catch (final RuntimeException failure) { check(failure == sentinel, "exception identity"); }
        equal(target.contains(IndexExtractor.fromIndexArray(9)), true);
        equal(target.contains((IndexExtractor) consumer -> false), false);
        equal(target.merge((IndexExtractor) consumer -> false), true);
        check(result(() -> target.contains((IndexExtractor) null)).startsWith("java.lang.NullPointerException:"), "null producer");
        equal(result(() -> target.merge((IndexExtractor) null)), "java.lang.NullPointerException:indexExtractor");
    }

    public static void verify() throws ReflectiveOperationException {
        checks = 0;
        digest = 0;
        randomLifecycle();
        peerContainment();
        invalidResidue();
        callbacks();
        System.out.println("SPARSE_LIFECYCLE_PASS checks=" + checks + " digest=" + Long.toHexString(digest));
    }

    public static void main(final String[] args) throws ReflectiveOperationException { verify(); }
}
