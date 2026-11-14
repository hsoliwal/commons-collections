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

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;

/** Independent executable checks for ranked removal, cursors and reused storage. */
public final class TreeListSinglePassRemovalContract {
    private long checks;
    private long digest = 1;
    private long transitions;
    private static final String[] LANES = {
        "left", "right", "relativePosition", "heights", "state"
    };

    private static final class Item {
        private final int id;
        private Item(final int id) { this.id = id; }
        @Override
        public boolean equals(final Object other) {
            throw new AssertionError("indexed removal must not call payload equals");
        }
        @Override
        public int hashCode() {
            throw new AssertionError("indexed removal must not hash payloads");
        }
    }

    private void check(final boolean condition, final String message) {
        checks++;
        if (!condition) { throw new AssertionError(message); }
    }

    private void mark(final long value) { digest = Long.rotateLeft(digest, 9) ^ value; }

    private void same(final Object expected, final Object actual) {
        check(expected == actual, "returned payload identity differs");
        mark(actual == null ? -1 : ((Item) actual).id);
    }

    private static Object read(final Object owner, final Class<?> type, final String name) {
        try {
            final Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (final ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Object read(final Object owner, final String name) {
        return read(owner, owner.getClass(), name);
    }

    private List<Item> input(final int count) {
        final List<Item> values = new ArrayList<>();
        final Item duplicate = new Item(-7);
        for (int i = 0; i < count; i++) {
            values.add(i % 9 == 0 ? null : i % 5 == 0 ? duplicate : new Item(i));
        }
        return values;
    }

    private void state(final List<Item> expected, final TreeList<Item> actual) {
        check(expected.size() == actual.size(), "size mismatch");
        final Object[] raw = actual.toArray();
        final Item[] typed = actual.toArray(new Item[0]);
        check(raw.length == expected.size() && typed.length == raw.length, "export lengths");
        final ListIterator<Item> backwards = actual.listIterator(actual.size());
        for (int i = 0; i < expected.size(); i++) {
            same(expected.get(i), actual.get(i));
            same(expected.get(i), raw[i]);
            same(expected.get(i), typed[i]);
            same(expected.get(expected.size() - 1 - i), backwards.previous());
        }
        check(!backwards.hasPrevious(), "reverse boundary");
        final Object slots = read(actual, TreeList.class, "slots");
        final int used = (Integer) read(slots, "used");
        final Object[] payload = (Object[]) read(slots, "values");
        final int[] left = (int[]) read(slots, "left");
        final byte[] flags = (byte[]) read(slots, "state");
        final boolean[] free = new boolean[used];
        int freeCount = 0;
        for (int slot = (Integer) read(slots, "free"); slot != -1; slot = left[slot]) {
            check(slot >= 0 && slot < used && !free[slot], "free list bounds or cycle");
            free[slot] = true;
            check(payload[slot] == null && flags[slot] == 0, "released payload retained");
            freeCount++;
        }
        check(used - freeCount == expected.size(), "slot conservation");
        check((Integer) read(slots, "detached") == -1, "detached slot not released");
        mark((Integer) read(actual, TreeList.class, "root"));
        mark((Integer) read(slots, "free"));
        mark(used);
        for (final String lane : LANES) {
            final Object array = read(slots, lane);
            check(Array.getLength(array) == payload.length, "inconsistent lane capacities");
            for (int i = 0; i < used; i++) { mark(((Number) Array.get(array, i)).longValue()); }
        }
        for (int i = 0; i < used; i++) {
            mark(payload[i] == null ? -1 : ((Item) payload[i]).id);
            check(free[i] || (flags[i] & 4) != 0, "unowned slot");
        }
    }

    private void boundaries() {
        for (int n = 1; n <= 48; n++) {
            for (int removed = 0; removed < n; removed++) {
                for (int layout = 0; layout < 3; layout++) {
                    final List<Item> expected = input(n);
                    final TreeList<Item> actual = layout == 0 ? new TreeList<>(expected) : new TreeList<>();
                    if (layout != 0) {
                        for (int i = 0; i < n; i++) { actual.add(expected.get(i)); }
                        if (layout == 2) {
                            for (int i = 0; i < n / 2; i++) {
                                final Item item = actual.remove(i);
                                actual.add(i, item);
                            }
                        }
                    }
                    final Iterator<Item> stale = actual.iterator();
                    same(expected.remove(removed), actual.remove(removed));
                    transitions++;
                    try {
                        stale.next();
                        throw new AssertionError("stale iterator survived removal");
                    } catch (final ConcurrentModificationException correct) { checks++; }
                    state(expected, actual);
                    final List<Item> extra = input(13);
                    expected.addAll(extra); actual.addAll(extra);
                    state(expected, actual);
                    for (int cursor = 0; cursor <= actual.size(); cursor++) {
                        final ListIterator<Item> a = actual.listIterator(cursor);
                        check(a.nextIndex() == cursor && a.previousIndex() == cursor - 1, "cursor indexes");
                        if (a.hasNext()) { same(expected.get(cursor), a.next()); }
                        if (cursor > 0) {
                            final ListIterator<Item> b = actual.listIterator(cursor);
                            same(expected.get(cursor - 1), b.previous());
                        }
                    }
                }
            }
        }
        for (final int n : new int[] {0, 1, 7, 64}) {
            final TreeList<Item> list = new TreeList<>(input(n));
            for (final int bad : new int[] {-1, Integer.MIN_VALUE, n, Integer.MAX_VALUE}) {
                final Iterator<Item> cursor = list.iterator();
                try { list.remove(bad); throw new AssertionError("bad removal index admitted"); }
                catch (final IndexOutOfBoundsException correct) {
                    check(("Invalid index:" + bad + ", size=" + n).equals(correct.getMessage()), "bounds message");
                }
                if (n != 0) { same(list.get(0), cursor.next()); }
            }
        }
    }

    private static final class CallbackList extends TreeList<Item> {
        private int reads;
        private int sizes;
        private int mode;
        private final Item sentinel = new Item(-999);
        private final RuntimeException failure = new IllegalStateException("get callback");
        @Override
        public int size() { sizes++; return super.size(); }
        @Override
        public Item get(final int index) {
            reads++;
            final int action = mode;
            mode = 0;
            if (action == 1) { return sentinel; }
            if (action == 2) { throw failure; }
            if (action == 3) { add(0, sentinel); }
            if (action == 4) { set(index, sentinel); }
            if (action == 5) { remove(0); }
            return super.get(index);
        }
        private int modifications() { return modCount; }
    }

    private void subclasses() {
        for (int mode = 0; mode < 6; mode++) {
            final CallbackList list = new CallbackList();
            list.addAll(input(17));
            final Iterator<Item> stale = list.iterator();
            final int before = list.modifications();
            list.reads = 0; list.sizes = 0; list.mode = mode;
            if (mode == 2) {
                try { list.remove(8); throw new AssertionError("get failure bypassed"); }
                catch (final IllegalStateException correct) { check(correct == list.failure, "exception identity"); }
                check(list.modifications() == before + 1, "failed callback modCount");
            } else {
                final Item result = list.remove(8);
                if (mode == 1 || mode == 4) { check(result == list.sentinel, "subclass result overridden"); }
                mark(result == null ? -1 : result.id);
            }
            check(list.reads > 0, "subclass get callback skipped");
            mark(list.reads); mark(list.sizes); mark(list.modifications());
            for (final Object item : list.toArray()) { mark(item == null ? -1 : ((Item) item).id); }
            try { stale.next(); throw new AssertionError("subclass iterator not invalidated"); }
            catch (final ConcurrentModificationException correct) { checks++; }
            list.sizes = 0;
            final ListIterator<Item> end = list.listIterator(list.size());
            check(list.sizes == 3, "end iterator added size callbacks");
            if (end.hasPrevious()) { end.previous(); }
        }
    }

    private void random(final int steps) {
        for (int seed = 0; seed < 8; seed++) {
            final Random random = new Random(0x5A17BEEFL + seed);
            final List<Item> expected = new ArrayList<>();
            final TreeList<Item> actual = new TreeList<>();
            for (int step = 0; step < steps; step++) {
                int op = random.nextInt(8);
                if (expected.size() > 192) { op = 1; }
                final Item item = step % 7 == 0 ? null : new Item(step);
                final int at = random.nextInt(expected.size() + 1);
                if (op == 0) { expected.add(at, item); actual.add(at, item); }
                else if (op == 1 && !expected.isEmpty()) {
                    final int index = at % expected.size();
                    same(expected.remove(index), actual.remove(index));
                } else if (op == 2) {
                    final List<Item> extra = input(random.nextInt(24));
                    check(expected.addAll(extra) == actual.addAll(extra), "bulk add result");
                } else if (op == 3 && !expected.isEmpty()) {
                    final int index = at % expected.size();
                    same(expected.set(index, item), actual.set(index, item));
                } else if (op == 4 && !expected.isEmpty()) {
                    final int index = at % expected.size();
                    final ListIterator<Item> a = actual.listIterator(index);
                    same(expected.remove(index), a.next()); a.remove();
                    if (a.hasNext()) { same(expected.get(index), a.next()); }
                    if (a.hasPrevious()) { a.previous(); a.add(item); expected.add(a.previousIndex(), item); }
                } else if (op == 5 && !expected.isEmpty()) {
                    final int to = at + random.nextInt(expected.size() - at + 1);
                    actual.subList(at, to).clear(); expected.subList(at, to).clear();
                } else if (op == 6 && step % 13 == 0) { actual.clear(); expected.clear(); }
                else if (op == 7 && !expected.isEmpty()) {
                    final ListIterator<Item> a = actual.listIterator(at);
                    if (a.hasPrevious()) { same(expected.remove(at - 1), a.previous()); a.remove(); }
                }
                transitions++;
                state(expected, actual);
            }
        }
    }

    public static void verify(final int steps) {
        final TreeListSinglePassRemovalContract contract = new TreeListSinglePassRemovalContract();
        contract.boundaries(); contract.subclasses(); contract.random(steps);
        System.out.println("TREE_REMOVAL_PASS transitions=" + contract.transitions + " checks=" + contract.checks
                + " digest=" + Long.toHexString(contract.digest));
    }
    public static void main(final String[] args) { verify(args.length == 0 ? 5000 : Integer.parseInt(args[0])); }
}
