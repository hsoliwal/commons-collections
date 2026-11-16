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
package org.apache.commons.collections4.list;

import java.lang.reflect.Field;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Random;

/** Independent logical, storage, subclass and failure contracts for indexed batches. */
public final class TreeListIndexedBulkContract {
    private static long checks;
    private static long digest;

    private TreeListIndexedBulkContract() { }

    private static void check(final boolean condition, final String label) {
        checks++;
        if (!condition) {
            throw new AssertionError(label + " at check " + checks);
        }
    }

    private static void equal(final Object actual, final Object expected) {
        check(Objects.equals(actual, expected), "value mismatch: " + actual + " != " + expected);
        digest = 31 * digest + Objects.hashCode(expected);
    }

    private static Object field(final Object object, final String name) throws ReflectiveOperationException {
        final Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static ArrayList<Integer> values(final int count, final int salt) {
        final ArrayList<Integer> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(i % 17 == 0 ? null : Integer.valueOf((i + salt) & 255));
        }
        return result;
    }

    private static TreeList<Integer> owner(final int count, final int spare) {
        final TreeList<Integer> list = new TreeList<>(values(count + spare, 0));
        list.subList(count, list.size()).clear();
        return list;
    }

    private static int visit(final int slot, final int parent, final int[] left, final int[] right,
            final int[] positions, final byte[] heights, final byte[] flags,
            final boolean[] live, final List<Integer> order) {
        if (slot == -1) { return -1; }
        check(slot >= 0 && slot < live.length && !live[slot], "invalid or repeated live slot");
        check((flags[slot] & 4) != 0, "non-live tree slot");
        live[slot] = true;
        final int rank = parent + positions[slot];
        final int lh = (flags[slot] & 1) != 0 ? -1
                : visit(left[slot], rank, left, right, positions, heights, flags, live, order);
        check(rank == order.size(), "rank differs from encounter order");
        order.add(slot);
        final int rh = (flags[slot] & 2) != 0 ? -1
                : visit(right[slot], rank, left, right, positions, heights, flags, live, order);
        check(heights[slot] == Math.max(lh, rh) + 1, "height mismatch");
        check(Math.abs(lh - rh) <= 1, "AVL imbalance");
        return heights[slot];
    }

    private static void state(final TreeList<Integer> list, final List<Integer> model)
            throws ReflectiveOperationException {
        equal(list.size(), model.size());
        check(Arrays.equals(list.toArray(), model.toArray()), "untyped export");
        check(Arrays.equals(list.toArray(new Integer[0]), model.toArray(new Integer[0])), "typed export");
        equal(list.hashCode(), model.hashCode());
        final ListIterator<Integer> backwards = list.listIterator(list.size());
        for (int i = model.size() - 1; i >= 0; i--) {
            check(Objects.equals(backwards.previous(), model.get(i)), "backwards traversal");
        }
        check(!backwards.hasPrevious(), "backwards exhaustion");
        final Object store = field(list, "slots");
        final Object[] payload = (Object[]) field(store, "values");
        final int[] left = (int[]) field(store, "left");
        final int[] right = (int[]) field(store, "right");
        final int[] positions = (int[]) field(store, "relativePosition");
        final byte[] heights = (byte[]) field(store, "heights");
        final byte[] flags = (byte[]) field(store, "state");
        check(left.length == payload.length && right.length == payload.length
                && positions.length == payload.length && heights.length == payload.length
                && flags.length == payload.length, "lane lengths");
        final int used = (Integer) field(store, "used");
        final boolean[] live = new boolean[used];
        final List<Integer> order = new ArrayList<>();
        visit((Integer) field(list, "root"), 0, left, right, positions, heights, flags, live, order);
        check(order.size() == model.size(), "live slot count");
        for (int i = 0; i < order.size(); i++) {
            final int slot = order.get(i);
            check(payload[slot] == model.get(i), "payload identity changed");
            if ((flags[slot] & 1) != 0) {
                check(left[slot] == (i == 0 ? -1 : order.get(i - 1)), "left thread");
            }
            if ((flags[slot] & 2) != 0) {
                check(right[slot] == (i + 1 == order.size() ? -1 : order.get(i + 1)), "right thread");
            }
        }
        for (int slot = (Integer) field(store, "free"); slot != -1; slot = left[slot]) {
            check(slot >= 0 && slot < used && !live[slot], "free-chain cycle or overlap");
            live[slot] = true;
            check(payload[slot] == null && flags[slot] == 0 && heights[slot] == 0
                    && positions[slot] == 0 && right[slot] == -1, "free slot retains state");
        }
        for (final boolean accounted : live) { check(accounted, "orphaned slot"); }
        for (int slot = used; slot < payload.length; slot++) { check(payload[slot] == null, "unused payload"); }
        check((Integer) field(store, "detached") == -1, "unreleased detached slot");
    }

    private static void matrix() throws ReflectiveOperationException {
        for (final int count : new int[] {0, 1, 8, 63, 4095, 4096, 4097, 8192}) {
            for (final int added : new int[] {0, 1, count / 2, count / 2 + 1, count + 1}) {
                for (int mode = 0; mode < 6; mode++) {
                    final boolean recycled = mode < 4;
                    final TreeList<Integer> list = owner(count, recycled ? added + 8 : 0);
                    final ArrayList<Integer> model = new ArrayList<>(list);
                    final ArrayList<Integer> input = values(added, 19);
                    final Collection<Integer> source = (mode & 1) == 0 ? input : new TreeList<>(input);
                    final int index = mode % 3 == 0 ? 0 : mode % 3 == 1 ? count / 2 : count;
                    final Iterator<Integer> stale = list.iterator();
                    final int beforeMod = (Integer) field(stale, "expectedModCount");
                    final Object store = field(list, "slots");
                    final Object oldValues = field(store, "values");
                    equal(list.addAll(index, source), model.addAll(index, input));
                    check((Integer) field(list.iterator(), "expectedModCount") == beforeMod + added, "modCount delta");
                    if (recycled) { check(field(store, "values") == oldValues, "unnecessary payload reallocation"); }
                    if (added != 0) {
                        try { stale.next(); throw new AssertionError("stale iterator accepted mutation"); }
                        catch (final ConcurrentModificationException expected) { checks++; }
                    }
                    check(Arrays.equals(source.toArray(), input.toArray()), "source changed");
                    state(list, model);
                    // Exercise subsequent ranked updates and the previously optimized bulk-removal owner.
                    final Random random = new Random(31L * count + mode + added);
                    for (int step = 0; step < 20 && !model.isEmpty(); step++) {
                        final int at = random.nextInt(model.size());
                        equal(list.remove(at), model.remove(at));
                    }
                    if (model.size() > 32) {
                        final int end = model.size() * 3 / 4;
                        list.subList(2, end).clear();
                        model.subList(2, end).clear();
                    }
                    state(list, model);
                }
            }
        }
    }

    /** A subclass deliberately keeps AbstractList's original per-element dispatch. */
    private static final class Observed extends TreeList<Integer> {
        private final List<String> calls = new ArrayList<>();
        @Override public void add(final int index, final Integer value) {
            calls.add("add:" + index + ":" + value);
            super.add(index, value);
        }
    }

    private static String run(final TreeList<Integer> list, final int at, final Collection<Integer> source) {
        try { return "result:" + list.addAll(at, source); }
        catch (final RuntimeException failure) { return failure.getClass().getName() + ":" + failure.getMessage(); }
    }

    private static void fallbacks() {
        for (int mode = 0; mode < 5; mode++) {
            final TreeList<Integer> actual = owner(4096, 4096);
            final Observed reference = new Observed();
            reference.addAll(actual);
            final int selected = mode;
            final List<String> aCalls = new ArrayList<>();
            final List<String> bCalls = new ArrayList<>();
            final Collection<Integer> a = mode == 0 ? actual : mode == 1 ? actual.subList(7, 40)
                    : mode == 2 ? null : source(actual, aCalls, selected == 4);
            final Collection<Integer> b = mode == 0 ? reference : mode == 1 ? reference.subList(7, 40)
                    : mode == 2 ? null : source(reference, bCalls, selected == 4);
            equal(run(actual, 29, a), run(reference, 29, b));
            check(Arrays.equals(actual.toArray(), reference.toArray()), "fallback partial mutation");
            equal(aCalls, bCalls);
        }
        for (final int index : new int[] {-1, 5000, Integer.MAX_VALUE}) {
            final TreeList<Integer> actual = owner(4096, 4096);
            final Observed reference = new Observed();
            reference.addAll(actual);
            equal(run(actual, index, null), run(reference, index, null));
        }
        final Observed target = new Observed();
        target.addAll(values(4096, 0));
        target.calls.clear();
        check(target.addAll(2048, values(2048, 3)), "subclass result");
        check(target.calls.size() == 2048, "subclass add dispatch bypassed");
        final ArrayList<Integer> subclassSource = new ArrayList<Integer>() {
            private static final long serialVersionUID = 1L;
            @Override public Iterator<Integer> iterator() { throw new IllegalStateException("source iterator"); }
        };
        subclassSource.addAll(values(4096, 0));
        equal(run(owner(4096, 4096), 0, subclassSource), "java.lang.IllegalStateException:source iterator");
    }

    private static Collection<Integer> source(final TreeList<Integer> target,
            final List<String> calls, final boolean reentrant) {
        return new AbstractCollection<Integer>() {
            @Override public int size() { calls.add("size"); return 4096; }
            @Override public Iterator<Integer> iterator() {
                calls.add("iterator");
                return new Iterator<Integer>() {
                    private int cursor;
                    @Override public boolean hasNext() { calls.add("has:" + cursor); return cursor < 11; }
                    @Override public Integer next() {
                        calls.add("next:" + cursor);
                        if (cursor == 4) {
                            if (reentrant) { target.add(0, 999); }
                            throw new IllegalStateException("source failure");
                        }
                        return cursor++;
                    }
                };
            }
        };
    }

    private static void backedViewsAndMixedFreeSlots() throws ReflectiveOperationException {
        for (int kind = 0; kind < 2; kind++) {
            final TreeList<Integer> list = owner(8192, 8192);
            final ArrayList<Integer> model = new ArrayList<>(list);
            final Random random = new Random(771L + kind);
            for (int i = 0; i < 300; i++) {
                final int at = random.nextInt(model.size());
                equal(list.remove(at), model.remove(at));
            }
            final ArrayList<Integer> input = values(8192, 111);
            final Collection<Integer> source = kind == 0 ? input : new TreeList<>(input);
            final List<Integer> view = list.subList(200, 7000).subList(20, 6000);
            final List<Integer> expected = model.subList(200, 7000).subList(20, 6000);
            equal(view.addAll(300, source), expected.addAll(300, input));
            equal(view.size(), expected.size());
            check(Arrays.equals(view.toArray(), expected.toArray()), "nested target view");
            state(list, model);
            final ListIterator<Integer> a = list.listIterator(521);
            final ListIterator<Integer> b = model.listIterator(521);
            equal(a.next(), b.next());
            final Integer replacement = Integer.valueOf(987);
            a.set(replacement); b.set(replacement);
            a.add(null); b.add(null);
            equal(a.previous(), b.previous());
            a.remove(); b.remove();
            state(list, model);
        }
    }

    public static void verify() throws ReflectiveOperationException {
        checks = 0;
        digest = 0;
        matrix();
        fallbacks();
        backedViewsAndMixedFreeSlots();
        System.out.println("INDEXED_BULK_PASS checks=" + checks + " digest=" + Long.toHexString(digest));
    }

    public static void main(final String[] args) throws ReflectiveOperationException { verify(); }
}
