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
package org.apache.commons.collections4.bloomfilter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

/** Real-source snapshot contracts with independent BitSet and producer oracles. */
public final class BloomIndexSnapshotContract {
    private static long checks;
    private static long digest;

    private BloomIndexSnapshotContract() { }

    private static void check(final boolean condition, final String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message + " at " + checks);
        }
    }

    private static void array(final int[] actual, final int[] expected) {
        check(Arrays.equals(actual, expected), "index sequence");
        digest = digest * 31 + Arrays.hashCode(actual);
    }

    private static int[] oracle(final BitSet bits) {
        final int[] values = new int[bits.cardinality()];
        int out = 0;
        for (int bit = bits.nextSetBit(0); bit >= 0; bit = bits.nextSetBit(bit + 1)) {
            values[out++] = bit;
        }
        return values;
    }

    private static int cached(final SimpleBloomFilter filter) throws ReflectiveOperationException {
        final Field field = SimpleBloomFilter.class.getDeclaredField("cardinality");
        field.setAccessible(true);
        return field.getInt(filter);
    }

    private static void simple() throws ReflectiveOperationException {
        final Random random = new Random(0x736e617073686f74L);
        for (final int size : new int[] {1, 2, 63, 64, 65, 127, 128, 129, 1024, 8193, 65536}) {
            final SimpleBloomFilter filter = new SimpleBloomFilter(Shape.fromKM(1, size));
            final BitSet model = new BitSet();
            for (int round = 0; round < 200; round++) {
                if (round % 31 == 0) {
                    filter.clear();
                    model.clear();
                }
                final int[] input = new int[random.nextInt(40)];
                for (int i = 0; i < input.length; i++) {
                    input[i] = random.nextInt(size);
                    model.set(input[i]);
                }
                filter.merge(IndexExtractor.fromIndexArray(input));
                if ((round & 1) == 0) {
                    filter.cardinality();
                }
                final int before = cached(filter);
                final int[] expected = oracle(model);
                final int[] exported = filter.asIndexArray();
                array(exported, expected);
                check(cached(filter) == before, "export changed cache state");
                if (exported.length != 0) {
                    exported[0] = -1;
                }
                array(filter.asIndexArray(), expected);
                final SimpleBloomFilter copy = filter.copy();
                array(copy.asIndexArray(), expected);
            }
        }
        // A failed merge can leave a stale cache. Export must not trust or repair it.
        for (int mode = 0; mode < 3; mode++) {
            final SimpleBloomFilter filter = new SimpleBloomFilter(Shape.fromKM(1, mode == 2 ? 65 : 64));
            final RuntimeException sentinel = new IllegalStateException("producer sentinel");
            try {
                if (mode == 0) {
                    filter.merge(IndexExtractor.fromIndexArray(0, 63, 64, 64));
                } else if (mode == 1) {
                    filter.merge((BitMapExtractor) p -> {
                        p.test(3L);
                        throw sentinel;
                    });
                } else {
                    filter.merge(BitMapExtractor.fromBitMapArray(0L, 2L));
                }
                throw new AssertionError("expected failed merge");
            } catch (final RuntimeException expected) {
                if (mode == 1) {
                    check(expected == sentinel, "exception identity");
                }
            }
            final int before = cached(filter);
            final BitSet bits = BitSet.valueOf(filter.asBitMapArray());
            array(filter.asIndexArray(), oracle(bits));
            check(cached(filter) == before, "failed-merge cache changed");
            check(filter.cardinality() == before, "public cached cardinality changed");
        }
    }

    private static void snapshot(final IndexExtractor snapshot, final int[] expected) {
        array(snapshot.asIndexArray(), expected);
        check(snapshot.uniqueIndices() == snapshot, "snapshot uniqueness identity");
        final int[] mutable = snapshot.asIndexArray();
        if (mutable.length > 0) {
            mutable[0] = -1;
        }
        array(snapshot.asIndexArray(), expected);
        for (final int stop : new int[] {1, 2, 31, Integer.MAX_VALUE}) {
            final List<Integer> seen = new ArrayList<>();
            final boolean complete = snapshot.processIndices(value -> {
                seen.add(value);
                return seen.size() < stop;
            });
            check(seen.size() == Math.min(stop, expected.length), "stop prefix length");
            check(complete == (expected.length < stop), "stop result");
            for (int i = 0; i < seen.size(); i++) {
                check(seen.get(i) == expected[i], "stop prefix order");
            }
        }
        try {
            final boolean result = snapshot.processIndices(null);
            check(expected.length == 0 && result, "empty lazy-null behavior");
        } catch (final NullPointerException failure) {
            check(expected.length != 0, "unexpected eager null check");
        }
        if (expected.length > 0) {
            final RuntimeException sentinel = new IllegalArgumentException("callback sentinel");
            try {
                snapshot.processIndices(value -> { throw sentinel; });
                throw new AssertionError("callback not invoked");
            } catch (final RuntimeException failure) {
                check(failure == sentinel, "callback exception identity");
            }
        }
    }

    private static void sparse() {
        final Random random = new Random(0x756e69717565L);
        for (final int bound : new int[] {1, 64, 65, 4096, 1048576, 16777216}) {
            for (int length = 0; length < 130; length++) {
                final int[] input = new int[length];
                final BitSet bits = new BitSet();
                for (int i = 0; i < length; i++) {
                    input[i] = random.nextInt(bound);
                    bits.set(input[i]);
                }
                final IndexExtractor source = IndexExtractor.fromIndexArray(input);
                final int[] expected = oracle(bits);
                final int[] original = input.clone();
                final IndexExtractor snapshot = source.uniqueIndices();
                array(input, original);
                snapshot(snapshot, expected);
                Arrays.fill(input, 0);
                snapshot(snapshot, expected);
                snapshot.processIndices(value -> {
                    array(snapshot.asIndexArray(), expected);
                    return true;
                });
            }
        }
        for (final int[] input : new int[][] {{-5}, {1000000, -5}, {3, -1, 3}}) {
            try {
                IndexExtractor.fromIndexArray(input).uniqueIndices();
                throw new AssertionError("negative index accepted");
            } catch (final IndexOutOfBoundsException expected) {
                final int negative = input.length == 1 ? input[0] : input[1];
                check(("bitIndex < 0: " + negative).equals(expected.getMessage()), "negative-index message");
            }
        }
        try {
            IndexExtractor.fromIndexArray((int[]) null).uniqueIndices();
            throw new AssertionError("null array accepted");
        } catch (final NullPointerException expected) {
            checks++;
        }
        final int[] duplicated = {1000000, 5, 1000000, 5, 9};
        final int[] unchanged = duplicated.clone();
        snapshot(IndexExtractor.fromIndexArray(duplicated).uniqueIndices(), new int[] {5, 9, 1000000});
        array(duplicated, unchanged);
        // General producers must retain their callback order and snapshot behavior.
        final int[] calls = {0};
        final IndexExtractor custom = predicate -> {
            calls[0]++;
            return predicate.test(9) && predicate.test(2) && predicate.test(9);
        };
        snapshot(custom.uniqueIndices(), new int[] {2, 9});
        check(calls[0] == 1, "custom producer reread");
        final SparseBloomFilter filter = new SparseBloomFilter(Shape.fromKM(1, 64));
        check(filter.uniqueIndices() == filter, "BloomFilter unique view changed");
    }

    public static void verify() throws ReflectiveOperationException {
        checks = 0;
        digest = 0;
        simple();
        sparse();
        System.out.println("SNAPSHOT_PASS checks=" + checks + " digest=" + Long.toHexString(digest));
    }

    public static void main(final String[] args) throws ReflectiveOperationException {
        verify();
    }
}
