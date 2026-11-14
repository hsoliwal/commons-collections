/*
 * Copyright 2026 Hitesh Soliwal and Synexia contributors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
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
import java.util.List;
import java.util.Random;

/** Independently executable clear/reuse checks against the actual filter. */
public final class CountingBloomClearContract {
    private static long checks;
    private static long digest;
    private static long transitions;

    private CountingBloomClearContract() {
    }

    private static Object read(final Object owner, final String name) {
        try {
            final Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (final ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void check(final boolean condition, final String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Model {
        private final int[] values;
        private int history;

        private Model(final int size) {
            values = new int[size];
        }

        private void change(final int index, final int amount, final boolean subtract) {
            values[index] = subtract ? values[index] - amount : values[index] + amount;
            history |= values[index];
        }

        private void clear() {
            Arrays.fill(values, 0);
        }
    }

    private static void change(final ArrayCountingBloomFilter filter, final Model model,
            final int index, final int amount, final boolean subtract) {
        final CellExtractor source = consumer -> consumer.test(index, amount);
        final boolean result = subtract ? filter.subtract(source) : filter.add(source);
        model.change(index, amount, subtract);
        check(result == (model.history >= 0), "validity result");
        transitions++;
    }

    private static void verify(final ArrayCountingBloomFilter filter, final Model model) {
        final int[] actual = (int[]) read(filter, "cells");
        final long[] mask = (long[]) read(filter, "nonzeroMask");
        final long[] expectedMask = new long[(model.values.length + 63) >>> 6];
        int positive = 0;
        int nonzero = 0;
        for (int index = 0; index < model.values.length; index++) {
            check(actual[index] == model.values[index], "counter " + index);
            if (model.values[index] != 0) {
                nonzero++;
                expectedMask[index >>> 6] |= 1L << index;
            }
            if (model.values[index] > 0) {
                positive++;
            }
        }
        check(Arrays.equals(expectedMask, mask), "mask matches all nonzero counters");
        check((Integer) read(filter, "nonzeroCount") == nonzero, "nonzero count");
        check(filter.cardinality() == positive, "positive cardinality");
        check((Integer) read(filter, "state") == model.history, "permanent state bits");
        check(filter.isValid() == (model.history >= 0), "permanent validity");
        check(filter.isEmpty() == (nonzero == 0), "empty includes negative counters");
        check(filter.getShape().getNumberOfBits() == actual.length, "capacity retained");
        final int[] exported = filter.asIndexArray();
        check(exported.length == positive, "positive export length");
        int offset = 0;
        for (int index = 0; index < model.values.length; index++) {
            if (model.values[index] > 0) {
                check(exported[offset++] == index, "positive export order");
            }
        }
        final int[] position = { -1, 0 };
        check(filter.processCells((index, value) -> {
            check(index > position[0], "cell order");
            check(value == model.values[index] && value != 0, "cell export");
            position[0] = index;
            position[1]++;
            return true;
        }), "complete traversal");
        check(position[1] == nonzero, "nonzero export length");
        digest = Long.rotateLeft(digest, 5) ^ Arrays.hashCode(actual) ^ model.history;
    }

    private static void clear(final ArrayCountingBloomFilter filter, final Model model) {
        final Object cells = read(filter, "cells");
        final Object mask = read(filter, "nonzeroMask");
        filter.clear();
        model.clear();
        check(cells == read(filter, "cells") && mask == read(filter, "nonzeroMask"), "same owned arrays");
        verify(filter, model);
        transitions++;
    }

    private static void boundaries() {
        for (final int size : new int[] {1, 2, 63, 64, 65, 127, 128, 129, 1023, 1024, 4097, 65537}) {
            final int threshold = size >>> 6;
            final int[] counts = {0, 1, Math.max(0, threshold - 1), threshold,
                Math.min(size, threshold + 1), size / 4, size};
            for (final int count : counts) {
                for (int variant = 0; variant < 3; variant++) {
                    final ArrayCountingBloomFilter filter = new ArrayCountingBloomFilter(Shape.fromKM(1, size));
                    final Model model = new Model(size);
                    for (int index = 0; index < count; index++) {
                        final int at = count == 0 ? 0 : (int) ((long) index * size / count);
                        final int value = variant == 0 ? 7 : variant == 1 ? -3
                                : index % 2 == 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
                        change(filter, model, at, value, false);
                    }
                    verify(filter, model);
                    final ArrayCountingBloomFilter copy = filter.copy();
                    final int[] snapshot = ((int[]) read(copy, "cells")).clone();
                    final boolean copyValid = copy.isValid();
                    clear(filter, model);
                    check(Arrays.equals(snapshot, (int[]) read(copy, "cells")), "copy detached from clear");
                    check(copy.isValid() == copyValid, "copy validity unchanged");
                    clear(filter, model);
                    for (final int at : new int[] {0, size - 1, Math.min(63, size - 1), Math.min(64, size - 1)}) {
                        change(filter, model, at, Integer.MAX_VALUE, false);
                        change(filter, model, at, 1, false);
                    }
                    verify(filter, model);
                    clear(filter, model);
                    change(filter, model, size - 1, 1, false);
                    verify(filter, model);
                }
            }
        }
    }

    private static void partialFailures() {
        final ArrayCountingBloomFilter filter = new ArrayCountingBloomFilter(Shape.fromKM(1, 4097));
        final Model model = new Model(4097);
        for (final int bad : new int[] {-1, 4097, Integer.MAX_VALUE}) {
            final CellExtractor invalid = consumer -> {
                consumer.test(63, -1);
                return consumer.test(bad, 2);
            };
            try {
                filter.add(invalid);
                throw new AssertionError("invalid index accepted");
            } catch (final IllegalArgumentException expected) {
                check(expected.getCause() instanceof IndexOutOfBoundsException, "invalid-index cause");
            }
            model.change(63, -1, false);
            verify(filter, model);
            clear(filter, model);
        }
        final RuntimeException failure = new IllegalStateException("producer failure");
        try {
            filter.add(consumer -> {
                consumer.test(4096, 5);
                throw failure;
            });
            throw new AssertionError("producer exception swallowed");
        } catch (final RuntimeException expected) {
            check(expected == failure, "producer exception identity");
        }
        model.change(4096, 5, false);
        verify(filter, model);
        clear(filter, model);
        change(filter, model, 128, -5, false);
        change(filter, model, 128, -5, true);
        check(filter.cardinality() == 0 && !filter.isValid(), "empty but permanently invalid");
        clear(filter, model);
    }

    private static void callbackClear() {
        for (final int dense : new int[] {0, 1}) {
            for (int mode = 0; mode < 3; mode++) {
                final ArrayCountingBloomFilter filter = new ArrayCountingBloomFilter(Shape.fromKM(1, 4097));
                final Model model = new Model(4097);
                final int limit = dense == 0 ? 4 : 2048;
                for (int index = 0; index < limit; index++) {
                    change(filter, model, index, index % 2 == 0 ? 3 : -1, false);
                }
                final List<Integer> observed = new ArrayList<>();
                final int[] calls = {0};
                if (mode == 0) {
                    filter.processCells((index, count) -> {
                        observed.add(index);
                        if (calls[0]++ == 0) {
                            clear(filter, model);
                            change(filter, model, 64, -7, false);
                            change(filter, model, 4096, 9, false);
                        }
                        return true;
                    });
                    check(observed.equals(Arrays.asList(0, 64, 4096)), "clear in cell callback");
                } else if (mode == 1) {
                    filter.processIndices(index -> {
                        observed.add(index);
                        if (calls[0]++ == 0) {
                            clear(filter, model);
                            change(filter, model, 63, -7, false);
                            change(filter, model, 4096, 9, false);
                        }
                        return true;
                    });
                    check(observed.equals(Arrays.asList(0, 63, 4096)), "clear in index callback");
                } else {
                    filter.processBitMaps(word -> {
                        if (calls[0]++ == 0) {
                            clear(filter, model);
                            change(filter, model, 4096, 9, false);
                        }
                        return true;
                    });
                    check(calls[0] == 65, "bitmap callback count");
                }
                verify(filter, model);
            }
        }
    }

    private static void random(final int steps) {
        final int[] amounts = {0, 1, -1, 17, -19, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (final int size : new int[] {1, 63, 64, 65, 127, 129, 1024, 4097}) {
            final Random random = new Random(0x434c454152L + size);
            final ArrayCountingBloomFilter filter = new ArrayCountingBloomFilter(Shape.fromKM(1, size));
            final Model model = new Model(size);
            for (int step = 0; step < steps; step++) {
                if (random.nextInt(11) == 0) {
                    clear(filter, model);
                } else {
                    change(filter, model, random.nextInt(size), amounts[random.nextInt(amounts.length)], random.nextBoolean());
                }
                if ((step & 15) == 0) {
                    verify(filter, model);
                }
            }
            clear(filter, model);
        }
    }

    public static void runBehavior(final int steps) {
        checks = 0;
        digest = 0;
        transitions = 0;
        boundaries();
        partialFailures();
        callbackClear();
        random(steps);
        System.out.println("BLOOM_CLEAR_PASS transitions=" + transitions + " checks=" + checks
                + " digest=" + Long.toHexString(digest));
    }

    public static void main(final String[] args) {
        runBehavior(args.length == 0 ? 4000 : Integer.parseInt(args[0]));
    }
}
