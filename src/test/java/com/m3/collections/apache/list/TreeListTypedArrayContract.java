/*
 * Copyright 2026 Hitesh Soliwal and contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.list;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Random;
import java.util.function.IntFunction;

/** Executable differential regression for typed export of the real fork owner. */
public final class TreeListTypedArrayContract {
    private static long checks;
    private static long fingerprint;

    private TreeListTypedArrayContract() {
    }

    private static void equal(final Object a, final Object b) {
        checks++;
        if (!Objects.deepEquals(a, b)) {
            throw new AssertionError("check " + checks + ": " + a + " != " + b);
        }
        fingerprint = fingerprint * 31 + (b instanceof Class<?>
                ? ((Class<?>) b).getName().hashCode() : Objects.hashCode(b));
    }

    private static void arrayEqual(final Object[] a, final Object[] b) {
        equal(Arrays.asList(a), Arrays.asList(b));
    }

    private static Class<?> attempt(final List<?> list, final Object[] target) {
        try {
            list.toArray(target);
            return null;
        } catch (final RuntimeException failure) {
            return failure.getClass();
        }
    }

    private static void export(final List<Object> actual, final List<Object> expected) {
        final int n = expected.size();
        for (final int length : new int[] {0, Math.max(0, n - 1), n, n + 1, n + 7}) {
            final Object[] a = new Object[length];
            final Object[] b = new Object[length];
            Arrays.fill(a, "untouched");
            Arrays.fill(b, "untouched");
            final Object[] ar = actual.toArray(a);
            final Object[] br = expected.toArray(b);
            equal(ar == a, br == b);
            equal(ar.getClass(), br.getClass());
            arrayEqual(ar, br);
            arrayEqual(a, b);
            final String[] sa = new String[length];
            final String[] sb = new String[length];
            Arrays.fill(sa, "tail");
            Arrays.fill(sb, "tail");
            equal(attempt(actual, sa), attempt(expected, sb));
            arrayEqual(sa, sb);
        }
        arrayEqual(actual.toArray(), expected.toArray());
        equal(actual.size(), expected.size());
        equal(actual.hashCode(), expected.hashCode());
        equal(actual.equals(expected), true);
        equal(attempt(actual, null), NullPointerException.class);
    }

    private static void randomized() {
        for (int seed = 0; seed < 16; seed++) {
            final Random random = new Random(0x74726565617272L + seed);
            final TreeList<Object> actual = new TreeList<>();
            final List<Object> expected = new ArrayList<>();
            for (int step = 0; step < 2500; step++) {
                final int index = random.nextInt(expected.size() + 1);
                final int choice = random.nextInt(8);
                final Object value = choice == 0 ? null : choice == 1 ? Integer.valueOf(step) : "v" + (step % 19);
                switch (random.nextInt(6)) {
                case 0:
                case 1:
                    actual.add(index, value);
                    expected.add(index, value);
                    break;
                case 2:
                    if (index < expected.size()) {
                        equal(actual.remove(index), expected.remove(index));
                    }
                    break;
                case 3:
                    if (index < expected.size()) {
                        equal(actual.set(index, value), expected.set(index, value));
                    }
                    break;
                case 4:
                    if (expected.size() > 128) {
                        actual.clear();
                        expected.clear();
                    }
                    break;
                default:
                    final List<Object> values = Arrays.asList(value, null, "batch");
                    equal(actual.addAll(values), expected.addAll(values));
                    break;
                }
                if ((step & 7) == 0) {
                    export(actual, expected);
                    if (expected.size() > 3) {
                        export(actual.subList(1, actual.size() - 1), expected.subList(1, expected.size() - 1));
                    }
                }
            }
            export(actual, expected);
        }
    }

    private static void typedFailures() {
        for (int length = 0; length < 40; length++) {
            for (int bad = 0; bad <= length; bad++) {
                final List<Object> expected = new ArrayList<>();
                for (int i = 0; i < length; i++) {
                    expected.add(i == bad ? Integer.valueOf(7) : i % 3 == 0 ? null : "s" + i);
                }
                final TreeList<Object> actual = new TreeList<>(expected);
                export(actual, expected);
            }
        }
        final TreeList<Number> numbers = new TreeList<>(Arrays.asList(1, 2L, 3.0));
        final Number[] destination = new Number[6];
        equal(numbers.toArray(destination) == destination, true);
        arrayEqual(destination, new Number[] {1, 2L, 3.0, null, null, null});
        final CharSequence[] chars = new TreeList<>(Arrays.asList("a", "b")).toArray(new CharSequence[0]);
        equal(chars.getClass(), CharSequence[].class);
        arrayEqual(chars, new String[] {"a", "b"});
        final TreeList<int[]> nested = new TreeList<>(Arrays.asList(new int[] {1}, new int[] {2}));
        final int[][] result = nested.toArray(new int[0][]);
        equal(result.getClass(), int[][].class);
        equal(result[0] == nested.get(0), true);
        equal(result[1] == nested.get(1), true);
    }

    private static final class Overridden extends TreeList<String> {
        private final List<String> trace = new ArrayList<>();

        @Override
        public int size() {
            trace.add("size");
            return 2;
        }

        @Override
        public Iterator<String> iterator() {
            trace.add("iterator");
            return Arrays.asList("override-a", "override-b").iterator();
        }
    }

    private static void subclasses() {
        final Overridden list = new Overridden();
        arrayEqual(list.toArray(new String[4]), new String[] {"override-a", "override-b", null, null});
        equal(list.trace, Arrays.asList("size", "iterator"));
        list.trace.clear();
        equal(attempt(list, null), NullPointerException.class);
        equal(list.trace, Arrays.asList("size"));
        final TreeList<String> custom = new TreeList<String>() {
            @Override
            public ListIterator<String> listIterator(final int from) {
                return Arrays.asList("custom").listIterator(from);
            }
        };
        arrayEqual(custom.toArray(new String[0]), new String[] {"custom"});
    }

    private static void generator() throws Exception {
        final TreeList<String> list = new TreeList<>(Arrays.asList("a", "b"));
        final int[] calls = {0};
        final IntFunction<String[]> generator = length -> {
            equal(length, 0);
            calls[0]++;
            list.add("c");
            return new String[0];
        };
        final Object result = Collection.class.getMethod("toArray", IntFunction.class).invoke(list, generator);
        arrayEqual((Object[]) result, new String[] {"a", "b", "c"});
        equal(calls[0], 1);
    }

    public static void verify() throws Exception {
        checks = 0;
        fingerprint = 0;
        randomized();
        typedFailures();
        subclasses();
        generator();
        System.out.println("PASS checks=" + checks + " fingerprint=" + Long.toHexString(fingerprint));
    }

    public static void main(final String[] args) throws Exception {
        verify();
    }
}
