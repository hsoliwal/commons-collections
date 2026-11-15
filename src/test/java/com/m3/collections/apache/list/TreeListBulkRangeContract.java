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
package com.m3.collections.apache.list;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;

/** Real-owner range-removal, backed-view and physical-storage checks. */
public final class TreeListBulkRangeContract {
    private static final String[] LANES = {"values", "left", "right", "relativePosition", "heights", "state"};
    private long checks;
    private long ranges;
    private long digest = 1;

    private static final class Item {
        private final int id;
        private Item(final int id) { this.id = id; }
        @Override public boolean equals(final Object value) { throw new AssertionError("unexpected payload equals"); }
        @Override public int hashCode() { throw new AssertionError("unexpected payload hash"); }
    }

    private void check(final boolean result, final String message) {
        checks++;
        if (!result) { throw new AssertionError(message); }
    }

    private static Object field(final Object owner, final Class<?> type, final String name) {
        try {
            final Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (final ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Object field(final Object owner, final String name) { return field(owner, owner.getClass(), name); }
    private static Object store(final TreeList<?> list) { return field(list, TreeList.class, "slots"); }
    private static int modifications(final TreeList<?> list) { return (Integer) field(list.listIterator(), "expectedModCount"); }
    private static Object[] lanes(final TreeList<?> list) {
        final Object[] result = new Object[LANES.length];
        for (int i = 0; i < result.length; i++) { result[i] = field(store(list), LANES[i]); }
        return result;
    }

    private static List<Item> input(final int n) {
        final List<Item> result = new ArrayList<>(n);
        final Item repeated = new Item(-17);
        for (int i = 0; i < n; i++) { result.add(i % 11 == 0 ? null : i % 7 == 0 ? repeated : new Item(i)); }
        return result;
    }

    private int validateTree(final int slot, final int parentRank, final int[] left, final int[] right,
            final int[] ranks, final byte[] heights, final byte[] flags, final boolean[] seen, final List<Integer> order) {
        if (slot == -1) { return -1; }
        check(slot >= 0 && slot < seen.length && !seen[slot], "tree cycle or invalid slot");
        seen[slot] = true;
        check((flags[slot] & 4) != 0, "tree slot not live");
        final int rank = parentRank + ranks[slot];
        final int lh = validateTree((flags[slot] & 1) == 0 ? left[slot] : -1, rank,
                left, right, ranks, heights, flags, seen, order);
        check(rank == order.size(), "rank differs from encounter index");
        order.add(slot);
        final int rh = validateTree((flags[slot] & 2) == 0 ? right[slot] : -1, rank,
                left, right, ranks, heights, flags, seen, order);
        check(Math.abs(lh - rh) <= 1, "AVL balance broken");
        check(heights[slot] == Math.max(lh, rh) + 1, "height stale");
        return heights[slot];
    }

    private void verifyState(final TreeList<Item> actual, final List<Item> expected) {
        check(actual.size() == expected.size(), "size mismatch");
        final Object slots = store(actual);
        final Object[] values = (Object[]) field(slots, "values");
        final int[] left = (int[]) field(slots, "left");
        final int[] right = (int[]) field(slots, "right");
        final int[] ranks = (int[]) field(slots, "relativePosition");
        final byte[] heights = (byte[]) field(slots, "heights");
        final byte[] flags = (byte[]) field(slots, "state");
        final int used = (Integer) field(slots, "used");
        final boolean[] seen = new boolean[used];
        final List<Integer> order = new ArrayList<>();
        validateTree((Integer) field(actual, TreeList.class, "root"), 0,
                left, right, ranks, heights, flags, seen, order);
        check(order.size() == actual.size(), "reachable size mismatch");
        final Object[] exported = actual.toArray();
        final Item[] typed = actual.toArray(new Item[0]);
        final ListIterator<Item> backwards = actual.listIterator(actual.size());
        for (int i = 0; i < order.size(); i++) {
            final int slot = order.get(i);
            final Item item = expected.get(i);
            check(values[slot] == item && actual.get(i) == item && exported[i] == item && typed[i] == item,
                    "payload reference or order changed");
            check(backwards.previous() == expected.get(expected.size() - 1 - i), "reverse thread wrong");
            if ((flags[slot] & 1) != 0) { check(left[slot] == (i == 0 ? -1 : order.get(i - 1)), "predecessor thread wrong"); }
            if ((flags[slot] & 2) != 0) { check(right[slot] == (i + 1 == order.size() ? -1 : order.get(i + 1)), "successor thread wrong"); }
            digest = 31 * digest + (item == null ? -1 : item.id);
        }
        check(!backwards.hasPrevious(), "reverse traversal end");
        for (int free = (Integer) field(slots, "free"); free != -1; free = left[free]) {
            check(free >= 0 && free < used && !seen[free], "free/live overlap or cycle");
            seen[free] = true;
            check(values[free] == null && flags[free] == 0, "freed payload not released");
        }
        for (final boolean accounted : seen) { check(accounted, "orphan slot"); }
        check((Integer) field(slots, "detached") == -1, "unreleased detached slot");
    }

    private void remove(final TreeList<Item> actual, final List<Item> expected, final int from, final int to) {
        final Object[] before = lanes(actual);
        final int modifications = modifications(actual);
        final Iterator<Item> stale = actual.iterator();
        actual.subList(from, to).clear();
        expected.subList(from, to).clear();
        ranges++;
        check(modifications(actual) - modifications == to - from, "modCount increment changed");
        final Object[] after = lanes(actual);
        for (int i = 0; i < before.length; i++) { check(before[i] == after[i], "range removal replaced storage"); }
        if (from != to) {
            try { stale.next(); throw new AssertionError("stale iterator accepted"); }
            catch (final ConcurrentModificationException correct) { checks++; }
        } else if (stale.hasNext()) { check(stale.next() == expected.get(0), "empty range invalidated iterator"); }
        verifyState(actual, expected);
    }

    private void boundaries(final boolean bounded) {
        final int[] sizes = bounded ? new int[] {4095, 4096, 4097, 8192}
                : new int[] {0, 1, 8, 127, 4095, 4096, 4097, 8192, 16385, 65536};
        for (final int n : sizes) {
            final int[][] rangesToRemove = {{0, 0}, {n, n}, {0, n}, {0, n / 2}, {n / 2, n},
                {n / 4, n - n / 4}, {n / 3, n - n / 8}, {n / 2, Math.min(n, n / 2 + 1)}};
            for (int layout = 0; layout < (bounded ? 1 : 3); layout++) {
                for (final int[] range : rangesToRemove) {
                    final List<Item> expected = input(n);
                    final TreeList<Item> actual = layout == 0 ? new TreeList<>(expected) : new TreeList<>();
                    if (layout != 0) {
                        for (final Item item : expected) { actual.add(item); }
                        if (layout == 2) {
                            for (int i = n / 3; i > 0; i--) {
                                final int at = i * 2;
                                final Item moved = actual.remove(at);
                                actual.add(0, moved);
                                expected.add(0, expected.remove(at));
                            }
                        }
                    }
                    remove(actual, expected, range[0], range[1]);
                    final List<Item> extra = input(129);
                    actual.addAll(extra); expected.addAll(extra);
                    actual.add(actual.size() / 2, extra.get(1)); expected.add(expected.size() / 2, extra.get(1));
                    check(actual.remove(actual.size() / 3) == expected.remove(expected.size() / 3), "reuse removal result");
                    verifyState(actual, expected);
                }
            }
        }
    }

    private void nestedViews() {
        final TreeList<Item> list = new TreeList<>(input(16384));
        final List<Item> reference = new ArrayList<>(list);
        final List<Item> parent = list.subList(100, 16200);
        final List<Item> child = parent.subList(37, 15000);
        final List<Item> refParent = reference.subList(100, 16200);
        final List<Item> refChild = refParent.subList(37, 15000);
        final Iterator<Item> stale = parent.iterator();
        child.clear(); refChild.clear();
        check(child.isEmpty() && parent.size() == refParent.size(), "backed view size update");
        child.add(new Item(-3)); refChild.add(child.get(0));
        check(parent.size() == refParent.size(), "nested view insertion size");
        try { stale.next(); throw new AssertionError("stale parent cursor accepted"); }
        catch (final ConcurrentModificationException correct) { checks++; }
        verifyState(list, reference);
        parent.clear(); refParent.clear();
        verifyState(list, reference);
    }

    private static final class CallbackList extends TreeList<Item> {
        private int calls;
        private int failAt = -1;
        private final RuntimeException failure = new IllegalStateException("remove callback");
        @Override public Item remove(final int index) {
            if (calls++ == failAt) { throw failure; }
            return super.remove(index);
        }
    }

    private void subclasses() {
        final CallbackList list = new CallbackList();
        list.addAll(input(8192));
        list.subList(1024, 7168).clear();
        check(list.calls == 6144 && list.size() == 2048, "subclass remove callbacks bypassed");
        final CallbackList throwing = new CallbackList();
        throwing.addAll(input(8192)); throwing.failAt = 5;
        try { throwing.subList(0, 7000).clear(); throw new AssertionError("callback failure lost"); }
        catch (final IllegalStateException actual) { check(actual == throwing.failure, "exception identity lost"); }
        check(throwing.calls == 6 && throwing.size() == 8187, "partial subclass removal changed");
    }

    private void randomized(final int rounds) {
        for (int seed = 0; seed < 4; seed++) {
            final Random random = new Random(73313L + seed);
            final List<Item> reference = input(8192);
            final TreeList<Item> list = new TreeList<>(reference);
            for (int step = 0; step < rounds; step++) {
                final int count = list.size() / 2 + random.nextInt(list.size() - list.size() / 2 + 1);
                final int from = random.nextInt(list.size() - count + 1);
                remove(list, reference, from, from + count);
                final List<Item> extra = input(4096 + random.nextInt(4096));
                list.addAll(extra); reference.addAll(extra);
                final ListIterator<Item> cursor = list.listIterator(list.size() / 2);
                final int at = cursor.nextIndex();
                check(cursor.next() == reference.get(at), "cross-range cursor mismatch");
                cursor.remove(); reference.remove(at);
                verifyState(list, reference);
            }
        }
    }

    public static void verify(final int rounds) {
        final TreeListBulkRangeContract test = new TreeListBulkRangeContract();
        test.boundaries(false); test.nestedViews(); test.subclasses(); test.randomized(rounds);
        System.out.println("BULK_RANGE_PASS ranges=" + test.ranges + " checks=" + test.checks
                + " digest=" + Long.toHexString(test.digest));
    }

    public static void main(final String[] args) {
        if (args.length != 0 && "bounded".equals(args[0])) {
            final TreeListBulkRangeContract test = new TreeListBulkRangeContract();
            test.boundaries(true); test.nestedViews(); test.subclasses(); test.randomized(1);
            System.out.println("BULK_RANGE_BOUNDED_PASS ranges=" + test.ranges + " checks=" + test.checks
                    + " digest=" + Long.toHexString(test.digest));
        } else {
            verify(args.length == 0 ? 24 : Integer.parseInt(args[0]));
        }
    }
}
