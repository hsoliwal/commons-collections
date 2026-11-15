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
package com.m3.collections.apache.iterators;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

/** Reflection oracle for reference-array fast paths and their subclass fallbacks. */
public final class ArrayIteratorReadContract {
    private static long checks;
    private static long digest;

    private ArrayIteratorReadContract() {
    }

    private static void equal(final Object actual, final Object expected) {
        checks++;
        if (!Objects.deepEquals(actual, expected)) {
            throw new AssertionError("check=" + checks + " actual=" + actual + " expected=" + expected);
        }
        // Array references are compared above by contents; do not fingerprint their identity hash.
        digest = digest * 31 + (expected != null && expected.getClass().isArray()
                ? expected.getClass().getName().hashCode() : Objects.hashCode(expected));
    }

    private static String observation(final Supplier<?> action) {
        try {
            final Object value = action.get();
            if (value == null) {
                return "null";
            }
            return value.getClass().getName() + ":" + (value.getClass().isArray()
                    ? Arrays.deepToString(new Object[] {value}) : value.toString());
        } catch (final RuntimeException failure) {
            return failure.getClass().getName() + ":" + failure.getMessage();
        }
    }

    private static Object copy(final Object array) {
        final int length = Array.getLength(array);
        final Object copy = Array.newInstance(array.getClass().getComponentType(), length);
        System.arraycopy(array, 0, copy, 0, length);
        return copy;
    }

    private static Object[] arrays() {
        return new Object[] {
            new Object[] {null, "alpha", Integer.valueOf(71), new int[] {3, 4}, null},
            new String[] {null, "alpha", "beta", "gamma", "delta"},
            new Number[] {null, Byte.valueOf((byte) 1), Integer.valueOf(2), Long.valueOf(3), Double.valueOf(4)},
            new int[][] {null, new int[] {2}, new int[0], new int[] {4, 5}, null},
            new boolean[] {true, false, true, true, false},
            new byte[] {Byte.MIN_VALUE, -1, 0, 1, Byte.MAX_VALUE},
            new short[] {Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE},
            new char[] {0, 1, 'a', 'Z', Character.MAX_VALUE},
            new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE},
            new long[] {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE},
            new float[] {Float.NEGATIVE_INFINITY, -0.0f, 0.0f, Float.NaN, Float.POSITIVE_INFINITY},
            new double[] {Double.NEGATIVE_INFINITY, -0.0d, 0.0d, Double.NaN, Double.POSITIVE_INFINITY}
        };
    }

    private static void forwardSlices() {
        for (final Object source : arrays()) {
            final int length = Array.getLength(source);
            for (int start = 0; start <= length; start++) {
                for (int end = start; end <= length; end++) {
                    final ArrayIterator<Object> iterator = new ArrayIterator<>(source, start, end);
                    equal(iterator.getArray() == source, true);
                    equal(iterator.getStartIndex(), start);
                    equal(iterator.getEndIndex(), end);
                    for (int pass = 0; pass < 3; pass++) {
                        for (int i = start; i < end; i++) {
                            equal(iterator.hasNext(), true);
                            final Object value = iterator.next();
                            equal(value, Array.get(source, i));
                            if (source instanceof Object[]) {
                                equal(value == ((Object[]) source)[i], true);
                            }
                        }
                        equal(iterator.hasNext(), false);
                        equal(observation(iterator::next), "java.util.NoSuchElementException:null");
                        iterator.reset();
                    }
                    equal(observation(() -> { iterator.remove(); return null; }),
                            "java.lang.UnsupportedOperationException:remove() method is not supported");
                }
            }
        }
    }

    /** The oracle tracks the specified cursor operations, using JDK reflection for all reads/stores. */
    private static final class Model {
        private final Object array;
        private final int start;
        private final int end;
        private int index;
        private int last = -1;

        Model(final Object array, final int start, final int end) {
            this.array = array;
            this.start = start;
            this.end = end;
            index = start;
        }

        Object operation(final int operation, final Object value) {
            switch (operation) {
            case 0:
                if (index >= end) { throw new NoSuchElementException(); }
                last = index;
                return Array.get(array, index++);
            case 1:
                if (index <= start) { throw new NoSuchElementException(); }
                last = --index;
                return Array.get(array, index);
            case 2:
                if (last == -1) {
                    throw new IllegalStateException("must call next() or previous() before a call to set()");
                }
                Array.set(array, last, value);
                return null;
            case 3:
                index = start;
                last = -1;
                return null;
            case 4: throw new UnsupportedOperationException("remove() method is not supported");
            case 5: throw new UnsupportedOperationException("add() method is not supported");
            case 6: return index < end;
            case 7: return index > start;
            case 8: return index - start;
            default: return index - start - 1;
            }
        }
    }

    private static Object operation(final ArrayListIterator<Object> iterator, final int operation,
            final Object value) {
        switch (operation) {
        case 0: return iterator.next();
        case 1: return iterator.previous();
        case 2: iterator.set(value); return null;
        case 3: iterator.reset(); return null;
        case 4: iterator.remove(); return null;
        case 5: iterator.add(value); return null;
        case 6: return iterator.hasNext();
        case 7: return iterator.hasPrevious();
        case 8: return iterator.nextIndex();
        default: return iterator.previousIndex();
        }
    }

    private static void mixedOperations() {
        final Object[] replacements = {null, "replaced", Byte.valueOf((byte) 7), Short.valueOf((short) 8),
            Character.valueOf('a'), Integer.valueOf(9), Long.valueOf(10), Float.valueOf(11),
            Double.valueOf(12), Boolean.TRUE, new int[] {9}, new Object[] {"nested"}};
        for (final Object input : arrays()) {
            for (int seed = 0; seed < 8; seed++) {
                final Object actual = copy(input);
                final Object expected = copy(input);
                final int start = seed % 3;
                final int end = 3 + seed % 3;
                final ArrayListIterator<Object> iterator = new ArrayListIterator<>(actual, start, end);
                final Model model = new Model(expected, start, end);
                final Random random = new Random(0x6172726179726561L + seed);
                for (int step = 0; step < 2000; step++) {
                    final int op = random.nextInt(10);
                    final Object value = replacements[random.nextInt(replacements.length)];
                    equal(observation(() -> operation(iterator, op, value)),
                            observation(() -> model.operation(op, value)));
                    equal(iterator.nextIndex(), model.index - start);
                    equal(iterator.previousIndex(), model.index - start - 1);
                    equal(actual, expected);
                }
            }
        }
    }

    private static final class PermissiveForward extends ArrayIterator<Object> {
        private int calls;

        PermissiveForward(final Object array) {
            super(array);
        }

        @Override
        public boolean hasNext() {
            calls++;
            return true;
        }
    }

    private static final class Permissive extends ArrayListIterator<Object> {
        private int nextCalls;
        private int previousCalls;
        private int forced = Integer.MAX_VALUE;
        private RuntimeException nextFailure;

        Permissive(final Object array, final int start, final int end) {
            super(array, start, end);
        }

        @Override
        protected void checkBound(final int bound, final int length, final String type) {
            // Existing subclasses may deliberately admit invalid ranges.
        }

        @Override
        public boolean hasNext() {
            nextCalls++;
            if (nextFailure != null) { throw nextFailure; }
            if (forced != Integer.MAX_VALUE) { index = forced; }
            return true;
        }

        @Override
        public boolean hasPrevious() {
            previousCalls++;
            return true;
        }
    }

    private static void subclassFallbacks() {
        for (final Object array : arrays()) {
            final int length = Array.getLength(array);
            for (final int initial : new int[] {-2, -1, 0, length - 1, length, length + 1,
                    Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                final PermissiveForward forward = new PermissiveForward(array);
                forward.index = initial;
                equal(observation(forward::next), observation(() -> Array.get(array, initial)));
                equal(forward.index, initial + 1);
                equal(forward.calls, 1);
                final Permissive next = new Permissive(array, 0, length);
                next.index = initial;
                equal(observation(next::next), observation(() -> Array.get(array, initial)));
                equal(next.index, initial + 1);
                equal(next.nextCalls, 1);
                final String setExpected = initial == -1
                        ? "java.lang.IllegalStateException:must call next() or previous() before a call to set()"
                        : observation(() -> { Array.set(copy(array), initial, null); return null; });
                equal(observation(() -> { next.set(null); return null; }), setExpected);
                final Permissive previous = new Permissive(array, 0, length);
                previous.index = initial;
                equal(observation(previous::previous), observation(() -> Array.get(array, initial - 1)));
                equal(previous.index, initial - 1);
                equal(previous.previousCalls, 1);
            }
            final Permissive admitted = new Permissive(array, -1, length + 1);
            equal(observation(admitted::next), observation(() -> Array.get(array, -1)));
            final Permissive mutated = new Permissive(array, 0, length);
            mutated.forced = length - 1;
            equal(mutated.next(), Array.get(array, length - 1));
            equal(mutated.index, length);
            final RuntimeException sentinel = new IllegalStateException("hasNext sentinel");
            mutated.nextFailure = sentinel;
            try {
                mutated.next();
                throw new AssertionError("subclass failure swallowed");
            } catch (final RuntimeException failure) {
                equal(failure == sentinel, true);
            }
            equal(mutated.index, length);
        }
    }

    private static void forEachAndLiveValues() {
        final Object[] values = {"first", null, "third", "last"};
        final ArrayIterator<Object> iterator = new ArrayIterator<>(values);
        final int[] calls = {0};
        final RuntimeException sentinel = new IllegalArgumentException("consumer sentinel");
        try {
            iterator.forEachRemaining(value -> {
                if (++calls[0] == 1) { values[1] = "changed"; }
                if (calls[0] == 2) {
                    equal(value, "changed");
                    throw sentinel;
                }
            });
            throw new AssertionError("consumer failure swallowed");
        } catch (final RuntimeException failure) {
            equal(failure == sentinel, true);
        }
        equal(iterator.next(), "third");
        equal(iterator.next(), "last");
        equal(iterator.hasNext(), false);
        equal(observation(() -> { iterator.forEachRemaining(null); return null; }),
                "java.lang.NullPointerException:null");
    }

    public static void verify() {
        checks = 0;
        digest = 0;
        forwardSlices();
        mixedOperations();
        subclassFallbacks();
        forEachAndLiveValues();
        System.out.println("ARRAY_READ_PASS checks=" + checks + " digest=" + Long.toHexString(digest));
    }

    public static void main(final String[] args) {
        verify();
    }
}
