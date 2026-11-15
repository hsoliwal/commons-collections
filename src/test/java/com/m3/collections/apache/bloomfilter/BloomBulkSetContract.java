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
import java.util.Objects;
import java.util.Random;

/** Checks real bulk owners against their unchanged arbitrary-producer paths. */
public final class BloomBulkSetContract {
    private long checks;
    private long digest = 1;

    private BloomBulkSetContract() { }

    private void check(final boolean value, final String label) {
        checks++;
        if (!value) { throw new AssertionError(label); }
    }

    private static Object field(final Object owner, final String name) {
        try {
            final Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (final ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void set(final Object owner, final String name, final Object value) {
        try {
            final Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(owner, value);
        } catch (final ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static String outcome(final java.util.function.BooleanSupplier action) {
        try { return Boolean.toString(action.getAsBoolean()); }
        catch (final RuntimeException failure) {
            return failure.getClass().getName() + ":" + failure.getMessage();
        }
    }

    private void sameOutcome(final String expected, final String actual) {
        check(expected.equals(actual), "different outcome: " + expected + " != " + actual);
        digest = 31 * digest + expected.hashCode();
    }

    private void sameArray(final int[] expected, final int[] actual) {
        check(Arrays.equals(expected, actual), "index output differs");
        digest = 31 * digest + Arrays.hashCode(actual);
    }

    private void dense() {
        final Random random = new Random(0xB100C0DEL);
        for (final int bits : new int[] {1, 2, 63, 64, 65, 127, 128, 129, 513}) {
            for (final int sourceBits : new int[] {1, 63, 64, 65, 128, 257, 1024}) {
                for (int pattern = 0; pattern < 10; pattern++) {
                    final SimpleBloomFilter source = new SimpleBloomFilter(Shape.fromKM(1, sourceBits));
                    final long[] sourceWords = (long[]) field(source, "bitMap");
                    for (int i = 0; i < sourceWords.length; i++) {
                        sourceWords[i] = pattern == 0 ? 0 : pattern == 1 ? -1L
                                : pattern == 2 ? 1L : pattern == 3 ? Long.MIN_VALUE : random.nextLong();
                    }
                    final long[] frozenSource = sourceWords.clone();
                    final SimpleBloomFilter actual = new SimpleBloomFilter(Shape.fromKM(1, bits));
                    final long[] targetWords = (long[]) field(actual, "bitMap");
                    for (int i = 0; i < targetWords.length; i++) {
                        targetWords[i] = pattern < 4 ? -1L : random.nextLong();
                    }
                    set(actual, "cardinality", pattern % 3 == 0 ? 0 : pattern % 3 == 1 ? -1 : 7);
                    final SimpleBloomFilter reference = actual.copy();
                    final IndexExtractor originalPath = source::processIndices;
                    sameOutcome(outcome(() -> reference.contains(originalPath)),
                            outcome(() -> actual.contains((IndexExtractor) source)));
                    check(Arrays.equals(targetWords, reference.asBitMapArray()), "contains changed target");
                    check(Objects.equals(field(actual, "cardinality"), field(reference, "cardinality")),
                            "contains changed cached cardinality");
                    sameOutcome(outcome(() -> reference.merge(originalPath)),
                            outcome(() -> actual.merge((IndexExtractor) source)));
                    check(Arrays.equals(actual.asBitMapArray(), reference.asBitMapArray()),
                            "merge successful-prefix bits differ");
                    check(Objects.equals(field(actual, "cardinality"), field(reference, "cardinality")),
                            "merge cache behavior differs");
                    sameArray(reference.asIndexArray(), actual.asIndexArray());
                    check(Arrays.equals(sourceWords, frozenSource), "source modified");
                    sameOutcome(outcome(() -> actual.merge((IndexExtractor) actual)),
                            outcome(() -> reference.merge((IndexExtractor) reference)));
                    check(Arrays.equals(actual.asBitMapArray(), reference.asBitMapArray()), "self merge differs");
                }
            }
        }
        for (final int bits : new int[] {1, 64, 65, 65536}) {
            final SimpleBloomFilter target = new SimpleBloomFilter(Shape.fromKM(1, bits));
            final RuntimeException sentinel = new IllegalStateException("producer failure");
            final int[] callbacks = {0};
            try {
                target.merge((IndexExtractor) predicate -> {
                    callbacks[0]++;
                    predicate.test(0);
                    throw sentinel;
                });
                throw new AssertionError("producer failure swallowed");
            } catch (final IllegalStateException failure) { check(failure == sentinel, "producer identity"); }
            check(callbacks[0] == 1 && target.asIndexArray()[0] == 0, "producer partial effect");
            check((Integer) field(target, "cardinality") == 0, "stale cache changed after failure");
            check(!target.contains((IndexExtractor) predicate -> false), "producer result ignored");
            check(target.merge((IndexExtractor) predicate -> false), "merge producer result changed");
            sameOutcome("java.lang.NullPointerException:indexExtractor",
                    outcome(() -> target.merge((IndexExtractor) null)));
        }
    }

    private static SparseBloomFilter sparse(final int count, final int offset, final int step, final int bits) {
        final SparseBloomFilter filter = new SparseBloomFilter(Shape.fromKM(1, bits));
        final int[] values = new int[count];
        for (int i = 0; i < count; i++) { values[i] = offset + i * step; }
        outcome(() -> filter.merge(IndexExtractor.fromIndexArray(values)));
        return filter;
    }

    private int visit(final Object store, final int slot, final long minimum, final long maximum,
            final boolean[] seen) {
        if (slot == -1) { return -1; }
        check(slot >= 0 && slot < seen.length && !seen[slot], "tree slot ownership");
        seen[slot] = true;
        final int key = ((int[]) field(store, "keys"))[slot];
        check(key > minimum && key < maximum, "search-tree order");
        final int left = visit(store, ((int[]) field(store, "left"))[slot], minimum, key, seen);
        final int right = visit(store, ((int[]) field(store, "right"))[slot], key, maximum, seen);
        final int height = ((byte[]) field(store, "heights"))[slot];
        check(height == Math.max(left, right) + 1 && Math.abs(left - right) <= 1, "AVL height/balance");
        return height;
    }

    private void invariant(final SparseBloomFilter filter) {
        final Object store = field(filter, "indices");
        final int size = (Integer) field(store, "size");
        final boolean[] seen = new boolean[size];
        visit(store, (Integer) field(store, "root"), Long.MIN_VALUE, Long.MAX_VALUE, seen);
        for (final boolean found : seen) { check(found, "orphaned slot"); }
        final int[] order = filter.asIndexArray();
        check(order.length == size && filter.cardinality() == size, "sparse count");
        final int[] keys = (int[]) field(store, "keys");
        final int[] next = (int[]) field(store, "next");
        int at = (Integer) field(store, "first");
        int last = -1;
        for (int i = 0; i < size; i++) {
            check(at >= 0 && at < size && keys[at] == order[i], "successor lane");
            check(i == 0 || order[i - 1] < order[i], "unique ascending indices");
            last = at;
            at = next[at];
        }
        check(at == -1 && (Integer) field(store, "last") == last, "successor boundaries");
    }

    private void sparse() {
        for (final int size : new int[] {0, 1, 8, 63, 64, 255, 4096}) {
            for (final int added : new int[] {0, 1, 7, 64, 257, 4096}) {
                for (int mode = 0; mode < 6; mode++) {
                    final int offset = mode == 0 ? 0 : mode == 1 ? 1 : mode == 2 ? size * 2
                            : mode == 3 ? -3 : mode == 4 ? 100000 : 4;
                    final SparseBloomFilter actual = sparse(size, 0, 2, 32768);
                    final SparseBloomFilter reference = actual.copy();
                    // Match starting capacities: copy intentionally compacts to live size.
                    final SparseBloomFilter target = actual.copy();
                    final SparseBloomFilter source = sparse(added, offset, mode == 5 ? 1 : 2, 32768);
                    final int[] sourceBefore = source.asIndexArray();
                    sameOutcome(outcome(() -> reference.merge((IndexExtractor) source::processIndices)),
                            outcome(() -> target.merge((IndexExtractor) source)));
                    sameArray(reference.asIndexArray(), target.asIndexArray());
                    sameArray(sourceBefore, source.asIndexArray());
                    check(Objects.equals(field(field(target, "indices"), "modCount"),
                            field(field(reference, "indices"), "modCount")), "distinct insert modCount");
                    check(((int[]) field(field(target, "indices"), "keys")).length
                            == ((int[]) field(field(reference, "indices"), "keys")).length,
                            "geometric capacity changed");
                    invariant(target);
                    sameOutcome(outcome(() -> reference.merge((IndexExtractor) reference)),
                            outcome(() -> target.merge((IndexExtractor) target)));
                    for (int k = 0; k < 8; k++) {
                        final int value = 7 + k * 37;
                        sameOutcome(outcome(() -> reference.merge(IndexExtractor.fromIndexArray(value))),
                                outcome(() -> target.merge(IndexExtractor.fromIndexArray(value))));
                    }
                    sameArray(reference.asIndexArray(), target.asIndexArray());
                    invariant(target);
                    invariant(target.copy());
                    target.clear();
                    check(target.isEmpty() && target.cardinality() == 0, "clear");
                }
            }
        }
        // An arbitrary producer must retain callbacks and already applied mutations.
        final SparseBloomFilter target = sparse(100, 0, 2, 65536);
        final RuntimeException failure = new IllegalStateException("source callback");
        try {
            target.merge((IndexExtractor) predicate -> { predicate.test(777); throw failure; });
            throw new AssertionError("missing callback failure");
        } catch (final IllegalStateException caught) { check(caught == failure, "callback identity"); }
        check(target.contains(IndexExtractor.fromIndexArray(777)), "callback prefix lost");
        invariant(target);
    }

    public static void verify() {
        final BloomBulkSetContract test = new BloomBulkSetContract();
        test.dense();
        test.sparse();
        System.out.println("BLOOM_BULK_SET_PASS checks=" + test.checks
                + " digest=" + Long.toHexString(test.digest));
    }

    public static void main(final String[] args) { verify(); }
}
