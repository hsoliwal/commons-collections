/*
 * Copyright 2026 Hitesh Soliwal and contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.list;

import java.lang.reflect.Field;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Random;

/** Executable checks for bulk rollback on the actual array-owned TreeList. */
public final class TreeListBulkRollbackContract {
    private static long checks;
    private static long fingerprint;

    private TreeListBulkRollbackContract() {
    }

    private static void check(final boolean valid, final String message) {
        checks++;
        if (!valid) {
            throw new AssertionError(message + " at check " + checks);
        }
    }

    private static void equal(final Object actual, final Object expected) {
        check(Objects.equals(actual, expected), actual + " != " + expected);
        fingerprint = fingerprint * 31 + Objects.hashCode(expected);
    }

    private static Object field(final Object owner, final String name) throws ReflectiveOperationException {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    /** Check all occupied/free slots, not just the visible sequence. */
    private static void storage(final TreeList<Integer> list) throws ReflectiveOperationException {
        final Object store = field(list, "slots");
        final Object[] values = (Object[]) field(store, "values");
        final int[] left = (int[]) field(store, "left");
        final int[] right = (int[]) field(store, "right");
        final int[] positions = (int[]) field(store, "relativePosition");
        final byte[] heights = (byte[]) field(store, "heights");
        final byte[] states = (byte[]) field(store, "state");
        final int used = (Integer) field(store, "used");
        final boolean[] occupied = new boolean[used];
        final int root = (Integer) field(list, "root");
        final List<Integer> order = new ArrayList<>();
        visit(root, 0, left, right, positions, heights, states, occupied, order);
        equal(order.size(), list.size());
        for (int i = 0; i < order.size(); i++) {
            final int slot = order.get(i);
            equal(values[slot], list.get(i));
            if ((states[slot] & 1) != 0) {
                equal(left[slot], i == 0 ? -1 : order.get(i - 1));
            }
            if ((states[slot] & 2) != 0) {
                equal(right[slot], i + 1 == order.size() ? -1 : order.get(i + 1));
            }
        }
        int freeCount = 0;
        for (int slot = (Integer) field(store, "free"); slot != -1; slot = left[slot]) {
            check(slot >= 0 && slot < used && !occupied[slot], "cyclic/overlapping free chain");
            occupied[slot] = true;
            check(values[slot] == null && states[slot] == 0 && heights[slot] == 0
                    && positions[slot] == 0 && right[slot] == -1, "free slot retains payload/state");
            fingerprint = fingerprint * 31 + slot;
            freeCount++;
        }
        equal(freeCount, field(store, "freeCount"));
        equal(freeCount + list.size(), used);
        for (final boolean seen : occupied) {
            check(seen, "orphaned acquired slot");
        }
        for (int i = used; i < values.length; i++) {
            check(values[i] == null, "payload above high-water mark");
        }
        fingerprint = fingerprint * 31 + used;
        fingerprint = fingerprint * 31 + Arrays.hashCode(left);
        fingerprint = fingerprint * 31 + Arrays.hashCode(right);
        fingerprint = fingerprint * 31 + Arrays.hashCode(positions);
        fingerprint = fingerprint * 31 + Arrays.hashCode(heights);
        fingerprint = fingerprint * 31 + Arrays.hashCode(states);
        equal(field(store, "detached"), -1);
    }

    private static int visit(final int slot, final int parentPosition, final int[] left,
            final int[] right, final int[] positions, final byte[] heights, final byte[] states,
            final boolean[] occupied, final List<Integer> order) {
        if (slot == -1) {
            return -1;
        }
        check(slot >= 0 && slot < occupied.length && !occupied[slot], "cyclic/invalid tree coordinate");
        occupied[slot] = true;
        check((states[slot] & 4) != 0, "tree uses non-live slot");
        final int position = parentPosition + positions[slot];
        final int lh = (states[slot] & 1) == 0
                ? visit(left[slot], position, left, right, positions, heights, states, occupied, order) : -1;
        equal(position, order.size());
        order.add(slot);
        final int rh = (states[slot] & 2) == 0
                ? visit(right[slot], position, left, right, positions, heights, states, occupied, order) : -1;
        check(Math.abs(lh - rh) <= 1, "unbalanced tree");
        equal((int) heights[slot], 1 + Math.max(lh, rh));
        return heights[slot];
    }

    private static void state(final TreeList<Integer> list, final List<Integer> expected)
            throws ReflectiveOperationException {
        equal(Arrays.asList(list.toArray()), expected);
        equal(list.size(), expected.size());
        equal(list.hashCode(), expected.hashCode());
        final List<Integer> reverse = new ArrayList<>();
        final ListIterator<Integer> cursor = list.listIterator(list.size());
        while (cursor.hasPrevious()) {
            reverse.add(cursor.previous());
        }
        final List<Integer> reversed = new ArrayList<>(expected);
        Collections.reverse(reversed);
        equal(reverse, reversed);
        storage(list);
    }

    private static TreeList<Integer> owner(final boolean recycled) {
        final TreeList<Integer> result = new TreeList<>();
        for (int i = 0; i < (recycled ? 150 : 7); i++) {
            result.add(i);
        }
        if (recycled) {
            for (int i = 0; i < 125; i++) {
                result.remove((i * 13) % result.size());
            }
        }
        return result;
    }

    private static final class Source extends AbstractCollection<Integer> {
        private final int length;
        private final int failureAt;
        private final Throwable failure;
        private final Runnable hook;
        private final List<String> calls = new ArrayList<>();

        Source(final int length, final int failureAt, final Throwable failure, final Runnable hook) {
            this.length = length;
            this.failureAt = failureAt;
            this.failure = failure;
            this.hook = hook;
        }

        @Override
        public boolean isEmpty() {
            calls.add("empty");
            return length == 0;
        }

        @Override
        public int size() {
            calls.add("size");
            return length;
        }

        @Override
        public Iterator<Integer> iterator() {
            calls.add("iterator");
            return new Iterator<Integer>() {
                private int next;

                @Override
                public boolean hasNext() {
                    calls.add("hasNext");
                    return next < length;
                }

                @Override
                public Integer next() {
                    calls.add("next:" + next);
                    if (hook != null && next == 0) {
                        hook.run();
                    }
                    if (next == failureAt) {
                        if (failure instanceof Error) {
                            throw (Error) failure;
                        }
                        throw (RuntimeException) failure;
                    }
                    return next++ % 7 == 0 ? null : Integer.valueOf(-next);
                }
            };
        }
    }

    private static void failures() throws ReflectiveOperationException {
        for (final boolean recycled : new boolean[] {false, true}) {
            for (final int size : new int[] {1, 2, 7, 8, 9, 10, 17, 64, 129}) {
                for (int failAt = 0; failAt < size; failAt++) {
                    final TreeList<Integer> list = owner(recycled);
                    final List<Integer> expected = new ArrayList<>(list);
                    final Throwable failure = failAt % 2 == 0
                            ? new IllegalArgumentException("input sentinel") : new AssertionError("input sentinel");
                    final Source source = new Source(size, failAt, failure, null);
                    try {
                        list.addAll(source);
                        throw new AssertionError("missing input failure");
                    } catch (final RuntimeException | Error thrown) {
                        check(thrown == failure, "input exception identity changed");
                    }
                    equal(source.calls.subList(0, 4), Arrays.asList("empty", "size", "iterator", "size"));
                    equal(source.calls.size(), failAt + 5);
                    check(!source.calls.contains("hasNext"), "introduced extra input callback");
                    fingerprint = fingerprint * 31 + source.calls.hashCode();
                    state(list, expected);
                    final List<Integer> refill = Arrays.asList(91, null, 92, 93, 94, 95, 96, 97, 98);
                    list.addAll(refill);
                    expected.addAll(refill);
                    state(list, expected);
                }
            }
        }
    }

    private static void reentrant() throws ReflectiveOperationException {
        for (final boolean recycled : new boolean[] {false, true}) {
            for (final int size : new int[] {8, 9, 32, 129}) {
                for (int mode = 0; mode < 4; mode++) {
                    final TreeList<Integer> list = owner(recycled);
                    final List<Integer> expected = new ArrayList<>(list);
                    final RuntimeException outerFailure = new IllegalStateException("outer");
                    final int selectedMode = mode;
                    final Source source = new Source(size, size / 2, outerFailure, () -> {
                        if (selectedMode == 0) {
                            list.add(0, 777);
                            expected.add(0, 777);
                        } else if (selectedMode == 1) {
                            list.remove(list.size() / 2);
                            expected.remove(expected.size() / 2);
                        } else if (selectedMode == 2) {
                            list.addAll(Collections.nCopies(37, 888));
                            expected.addAll(Collections.nCopies(37, 888));
                        } else {
                            final Error innerFailure = new AssertionError("inner");
                            try {
                                list.addAll(new Source(37, 17, innerFailure, null));
                                throw new AssertionError("missing nested failure");
                            } catch (final Error thrown) {
                                check(thrown == innerFailure, "nested identity");
                            }
                        }
                    });
                    try {
                        list.addAll(source);
                        throw new AssertionError("missing outer failure");
                    } catch (final RuntimeException thrown) {
                        check(thrown == outerFailure, "outer identity");
                    }
                    state(list, expected);
                    list.addAll(Collections.nCopies(size, 999));
                    expected.addAll(Collections.nCopies(size, 999));
                    state(list, expected);
                }
            }
        }
    }

    private static void randomized() throws ReflectiveOperationException {
        for (int seed = 0; seed < 8; seed++) {
            final Random random = new Random(0x62756c6bL + seed);
            final TreeList<Integer> list = new TreeList<>();
            final List<Integer> expected = new ArrayList<>();
            for (int step = 0; step < 5000; step++) {
                final int index = random.nextInt(list.size() + 1);
                final Integer value = step % 11 == 0 ? null : step % 127;
                switch (random.nextInt(7)) {
                case 0:
                    list.add(index, value); expected.add(index, value); break;
                case 1:
                    if (index < list.size()) { equal(list.remove(index), expected.remove(index)); }
                    break;
                case 2:
                    if (index < list.size()) { equal(list.set(index, value), expected.set(index, value)); }
                    break;
                case 3:
                    final List<Integer> input = new ArrayList<>(Collections.nCopies(random.nextInt(65), value));
                    equal(list.addAll(input), expected.addAll(input)); break;
                case 4:
                    if (list.size() < 48) { list.addAll(list); expected.addAll(new ArrayList<>(expected)); }
                    break;
                case 5:
                    if (list.size() > 1) {
                        list.subList(0, list.size() / 2).clear();
                        expected.subList(0, expected.size() / 2).clear();
                    }
                    break;
                default:
                    final ListIterator<Integer> a = list.listIterator(index);
                    final ListIterator<Integer> b = expected.listIterator(index);
                    a.add(value); b.add(value);
                    equal(a.previous(), b.previous());
                    a.remove(); b.remove();
                    break;
                }
                if (list.size() > 600) { list.clear(); expected.clear(); }
                if ((step & 63) == 0) { state(list, expected); }
            }
            state(list, expected);
        }
        for (final int size : new int[] {0, 1, 2, 7, 8, 9, 10, 64, 1024, 100000}) {
            final List<Integer> values = Collections.nCopies(size, 7);
            state(new TreeList<>(values), values);
        }
    }

    public static void verify() throws ReflectiveOperationException {
        checks = 0; fingerprint = 0;
        randomized(); failures(); reentrant();
        System.out.println("PASS assertions=" + checks + " digest=" + Long.toHexString(fingerprint));
    }

    public static void main(final String[] args) throws ReflectiveOperationException {
        verify();
    }
}
