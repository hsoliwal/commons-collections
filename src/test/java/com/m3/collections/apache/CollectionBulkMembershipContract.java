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
package com.m3.collections.apache;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.m3.collections.apache.iterators.ListIteratorWrapper;
import com.m3.collections.apache.iterators.PermutationIterator;
import com.m3.collections.apache.list.TreeList;
import com.m3.collections.apache.map.StaticBucketMap;

/** Executable real-owner membership, snapshot and cross-collection regressions. */
public final class CollectionBulkMembershipContract {
    private long checks;
    private long digest = 1;
    private static final String[] LANES = {"values", "left", "right", "relativePosition", "heights", "state"};

    private void check(final boolean condition, final String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static Object field(final Object owner, final String name) {
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                final Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (final NoSuchFieldException absent) {
                type = type.getSuperclass();
            } catch (final IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
        throw new AssertionError(name);
    }

    private void state(final List<?> expected, final TreeList<?> actual, final Object[] before) {
        check(expected.size() == actual.size(), "size");
        final Object[] result = actual.toArray();
        check(result.length == expected.size(), "array length");
        for (int i = 0; i < result.length; i++) {
            check(expected.get(i) == result[i] && result[i] == actual.get(i), "order/identity/rank");
            digest = 31 * digest + (result[i] == null ? 0 : i);
        }
        final Object store = field(actual, "slots");
        final Object[] values = (Object[]) field(store, "values");
        final int[] left = (int[]) field(store, "left");
        final int[] right = (int[]) field(store, "right");
        final byte[] flags = (byte[]) field(store, "state");
        final byte[] heights = (byte[]) field(store, "heights");
        final int[] ranks = (int[]) field(store, "relativePosition");
        final int used = (Integer) field(store, "used");
        final boolean[] free = new boolean[used];
        int freeCount = 0;
        for (int slot = (Integer) field(store, "free"); slot != -1; slot = left[slot]) {
            check(slot >= 0 && slot < used && !free[slot], "free cycle/bounds");
            free[slot] = true;
            check(values[slot] == null && flags[slot] == 0, "released reference retained");
            freeCount++;
        }
        check(used - freeCount == actual.size(), "slot accounting");
        for (int i = 0; i < LANES.length; i++) {
            check(before[i] == field(store, LANES[i]), "backing lane replaced");
        }
        final int[] position = {0};
        final List<Integer> slots = new ArrayList<>();
        visit((Integer) field(actual, "root"), 0, left, right, ranks, heights, flags, position, slots);
        check(position[0] == result.length, "tree size");
        for (int i = 0; i < slots.size(); i++) {
            final int slot = slots.get(i);
            check((flags[slot] & ~7) == 0 && !free[slot], "live ownership/state");
            if ((flags[slot] & 1) != 0) {
                check(left[slot] == (i == 0 ? -1 : slots.get(i - 1)), "predecessor thread");
            }
            if ((flags[slot] & 2) != 0) {
                check(right[slot] == (i + 1 == slots.size() ? -1 : slots.get(i + 1)), "successor thread");
            }
        }
    }

    private int visit(final int slot, final int parentRank, final int[] left, final int[] right,
            final int[] ranks, final byte[] heights, final byte[] flags, final int[] position,
            final List<Integer> slots) {
        if (slot == -1) {
            return -1;
        }
        final int rank = parentRank + ranks[slot];
        final int lh = (flags[slot] & 1) != 0 ? -1
                : visit(left[slot], rank, left, right, ranks, heights, flags, position, slots);
        check(rank == position[0]++, "absolute rank");
        slots.add(slot);
        final int rh = (flags[slot] & 2) != 0 ? -1
                : visit(right[slot], rank, left, right, ranks, heights, flags, position, slots);
        check(Math.abs(lh - rh) <= 1 && heights[slot] == Math.max(lh, rh) + 1, "height/balance");
        return Math.max(lh, rh) + 1;
    }

    private static Object[] lanes(final TreeList<?> list) {
        final Object store = field(list, "slots");
        final Object[] result = new Object[LANES.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = field(store, LANES[i]);
        }
        return result;
    }

    private void ordinaryCases() {
        for (final int n : new int[] {0, 1, 31, 4095, 4096, 8192, 16384}) {
            for (int pattern = 0; pattern < 6; pattern++) {
                for (final boolean retain : new boolean[] {false, true}) {
                    final List<Object> expected = new ArrayList<>();
                    final HashSet<Object> selected = new HashSet<>();
                    for (int i = 0; i < n; i++) {
                        final Object value = i % 17 == 0 ? null : i % 3 == 0 ? Integer.valueOf(i % 4093)
                                : i % 3 == 1 ? Long.valueOf(i % 4093) : "v" + i % 4093;
                        expected.add(value);
                        if (pattern == 1 || pattern == 2 && i % 2 == 0 || pattern == 3 && i % 4 != 0
                                || pattern == 4 && i == n / 2 || pattern == 5 && i % 31 == 0) {
                            selected.add(value);
                        }
                    }
                    final TreeList<Object> actual = new TreeList<>(expected);
                    final Object[] before = lanes(actual);
                    final Iterator<Object> stale = actual.iterator();
                    final boolean changed = retain ? expected.retainAll(selected) : expected.removeAll(selected);
                    check(changed == (retain ? actual.retainAll(selected) : actual.removeAll(selected)), "return flag");
                    state(expected, actual, before);
                    if (changed) {
                        try {
                            stale.next();
                            throw new AssertionError("stale iterator not invalidated");
                        } catch (final ConcurrentModificationException expectedFailure) {
                            checks++;
                        }
                    } else if (!expected.isEmpty()) {
                        check(stale.next() == expected.get(0), "no-op iterator invalidated");
                    }
                    if (!actual.isEmpty()) {
                        actual.subList(0, actual.size() / 2).clear();
                        expected.subList(0, expected.size() / 2).clear();
                        state(expected, actual, before);
                    }
                    final List<Object> refill = Arrays.asList(null, "refill", 42, 43L);
                    actual.addAll(refill);
                    expected.addAll(refill);
                    check(Arrays.equals(actual.toArray(), expected.toArray()), "refill");
                }
            }
        }
    }

    private static final class CallbackSet extends HashSet<Object> {
        private static final long serialVersionUID = 1L;
        private int calls;
        private final RuntimeException failure = new IllegalStateException("contains callback");
        @Override
        public boolean contains(final Object value) {
            if (++calls == 7) {
                throw failure;
            }
            return (Integer) value % 2 == 0;
        }
    }

    private static final class SubList extends TreeList<Integer> {
        private int removals;
        @Override
        public Integer remove(final int index) {
            removals++;
            return super.remove(index);
        }
    }

    private static final class HashFailure {
        private final RuntimeException failure = new IllegalArgumentException("hash callback");
        @Override
        public int hashCode() {
            throw failure;
        }
        @Override
        public boolean equals(final Object other) {
            return this == other;
        }
    }

    private void callbacks() {
        for (final boolean retain : new boolean[] {false, true}) {
            final TreeList<Object> list = new TreeList<>();
            for (int i = 0; i < 8192; i++) {
                list.add(i);
            }
            final CallbackSet set = new CallbackSet();
            try {
                if (retain) {
                    list.retainAll(set);
                } else {
                    list.removeAll(set);
                }
                throw new AssertionError("callback failure bypassed");
            } catch (final IllegalStateException failure) {
                check(failure == set.failure, "failure identity");
            }
            check(set.calls == 7 && list.size() == 8189, "partial effects/callback count");
            for (int i = 0; i < 3; i++) {
                check(list.get(i).equals(i * 2 + (retain ? 0 : 1)), "partial removal order");
            }
            final SubList subclass = new SubList();
            final HashSet<Integer> selected = new HashSet<>();
            for (int i = 0; i < 8192; i++) {
                subclass.add(i);
                if (i % 2 == 0) {
                    selected.add(i);
                }
            }
            if (retain) {
                subclass.retainAll(selected);
            } else {
                subclass.removeAll(selected);
            }
            check(subclass.removals == 4096, "subclass callback count");
        }
        final HashFailure bad = new HashFailure();
        final TreeList<Object> custom = new TreeList<>();
        final HashSet<Object> all = new HashSet<>();
        for (int i = 0; i < 8192; i++) {
            custom.add(i == 15 ? bad : Integer.valueOf(i));
            all.add(i);
        }
        try {
            custom.removeAll(all);
            throw new AssertionError("custom hash callback bypassed");
        } catch (final IllegalArgumentException failure) {
            check(failure == bad.failure && custom.size() == 8177 && custom.get(0) == bad,
                    "custom hash partial effects");
        }
        final TreeList<Integer> alias = new TreeList<>();
        for (int i = 0; i < 8192; i++) {
            alias.add(i);
        }
        check(!alias.retainAll(alias), "self retain");
        check(alias.removeAll(alias) && alias.isEmpty(), "self remove");
        final TreeList<Object> empty = new TreeList<>();
        for (final boolean retain : new boolean[] {false, true}) {
            try {
                if (retain) {
                    empty.retainAll(null);
                } else {
                    empty.removeAll(null);
                }
                throw new AssertionError("null collection accepted");
            } catch (final NullPointerException expected) {
                checks++;
            }
        }
    }

    private void integration() {
        final StaticBucketMap<Integer, Integer> map = new StaticBucketMap<>();
        for (int i = 0; i < 8192; i++) {
            map.put(i, i);
        }
        final Map.Entry<Integer, Integer> held = map.entrySet().iterator().next();
        final TreeList<Integer> tree = new TreeList<>(map.keySet());
        final List<Integer> expected = new ArrayList<>(tree);
        final HashSet<Integer> selected = new HashSet<>();
        for (int i = 0; i < 8192; i += 2) {
            selected.add(i);
        }
        final Iterator<Integer> source = tree.iterator();
        final ListIteratorWrapper<Integer> replay = new ListIteratorWrapper<>(new Iterator<Integer>() {
            @Override
            public boolean hasNext() {
                return source.hasNext();
            }
            @Override
            public Integer next() {
                return source.next();
            }
        });
        final Integer cached = replay.next();
        check(tree.removeAll(selected) == expected.removeAll(selected), "integrated removal");
        check(replay.previous() == cached, "cached prefix changed");
        check(Arrays.equals(tree.toArray(), expected.toArray()), "integrated result");
        final PermutationIterator<Integer> permutations = new PermutationIterator<>(Arrays.asList(1, 3, 5, 7));
        while (permutations.hasNext()) {
            final List<Integer> output = permutations.next();
            tree.addAll(output);
            expected.addAll(output);
        }
        check(tree.retainAll(selected) == expected.retainAll(selected), "integrated retention");
        check(tree.isEmpty() && expected.isEmpty(), "retained removed values");
        tree.addAll(map.keySet());
        check(tree.size() == 8192, "recycled tree slots");
        final Integer key = held.getKey();
        map.put(key, -1);
        check(held.getValue() == -1, "live entry");
        map.remove(key);
        map.put(key, -2);
        check(held.getValue() == -1 && map.get(key) == -2, "detached entry");
        final List<Integer> keys = new ArrayList<>(map.keySet());
        final List<Integer> entryKeys = new ArrayList<>();
        for (final Map.Entry<Integer, Integer> entry : map.entrySet()) {
            entryKeys.add(entry.getKey());
        }
        check(keys.equals(entryKeys), "snapshot encounter order");
    }

    public static void verify() {
        final CollectionBulkMembershipContract test = new CollectionBulkMembershipContract();
        test.ordinaryCases();
        test.callbacks();
        test.integration();
        System.out.println("BULK_MEMBERSHIP_PASS checks=" + test.checks + " digest=" + Long.toHexString(test.digest));
    }

    public static void main(final String[] args) {
        verify();
    }
}
