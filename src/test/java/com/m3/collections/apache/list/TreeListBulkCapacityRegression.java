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
package com.m3.collections.apache.list;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;

/** Dependency-free regression body, also invoked by the native JUnit adapter. */
public final class TreeListBulkCapacityRegression {
    private static final String[] LANES = {
        "values", "left", "right", "relativePosition", "heights", "state"
    };
    private static long checks;
    private static long transitions;
    private static long digest;

    private TreeListBulkCapacityRegression() {
    }

    private static void check(final boolean condition, final String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static Object field(final Object owner, final String name) {
        try {
            final Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int integer(final Object owner, final String name) {
        return (Integer) field(owner, name);
    }

    private static Object[] lanes(final TreeList<?> list) {
        final Object store = field(list, "slots");
        final Object[] result = new Object[LANES.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = field(store, LANES[i]);
        }
        return result;
    }

    private static int capacity(final TreeList<?> list) {
        return Array.getLength(field(field(list, "slots"), "values"));
    }

    private static void sameLanes(final Object[] before, final TreeList<?> list) {
        final Object[] after = lanes(list);
        for (int i = 0; i < before.length; i++) {
            check(before[i] == after[i], "unexpected replacement of " + LANES[i]);
        }
    }

    private static List<Integer> values(final int size) {
        final List<Integer> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(i % 13 == 0 ? null : i % 71);
        }
        return result;
    }

    private static void contents(final TreeList<Integer> list, final List<Integer> expected) {
        check(list.size() == expected.size(), "size mismatch");
        check(Arrays.equals(list.toArray(), expected.toArray()), "contents/order mismatch");
        check(Arrays.equals(list.toArray(new Integer[0]), expected.toArray(new Integer[0])), "typed array");
        digest = 31 * digest + expected.hashCode();
    }

    private static void invariants(final TreeList<Integer> list) {
        final Object store = field(list, "slots");
        final Object[] a = (Object[]) field(store, "values");
        final int[] left = (int[]) field(store, "left");
        final byte[] state = (byte[]) field(store, "state");
        final int used = integer(store, "used");
        check(used >= 0 && used <= a.length, "high-water bounds");
        for (final Object lane : lanes(list)) {
            check(Array.getLength(lane) == a.length, "lane lengths differ");
        }
        final boolean[] seen = new boolean[used];
        int freeCount = 0;
        for (int slot = integer(store, "free"); slot != -1; slot = left[slot]) {
            check(slot >= 0 && slot < used && !seen[slot], "free chain bounds/cycle");
            seen[slot] = true;
            check(state[slot] == 0 && a[slot] == null, "free payload retained");
            freeCount++;
        }
        int live = 0;
        for (int slot = 0; slot < used; slot++) {
            if (!seen[slot]) {
                check((state[slot] & 4) != 0, "unaccounted slot");
                live++;
            }
        }
        check(live == list.size(), "live count");
        // Baseline has no counter. Both versions must satisfy the derived invariant.
        for (final Field f : store.getClass().getDeclaredFields()) {
            if (f.getName().equals("freeCount")) {
                check(integer(store, "freeCount") == freeCount, "free counter drift");
            }
        }
        final ListIterator<Integer> it = list.listIterator(list.size());
        for (int i = list.size() - 1; i >= 0; i--) {
            check(it.hasPrevious() && Objects.equals(it.previous(), list.get(i)), "reverse order");
        }
        check(!it.hasPrevious(), "reverse end");
    }

    private static void requireCme(final Iterator<?> it) {
        try {
            it.next();
            throw new AssertionError("stale iterator survived structural change");
        } catch (final ConcurrentModificationException expected) {
            checks++;
        }
    }

    public static void runContract() {
        checks = 0;
        transitions = 0;
        digest = 0;
        for (final int initial : new int[] {0, 1, 2, 7, 8, 9, 31, 64, 511}) {
            for (final int added : new int[] {1, 2, 3, 7, 17, 128, 1024}) {
                final List<Integer> expected = values(initial);
                final TreeList<Integer> list = new TreeList<>(expected);
                contents(list, expected);
                for (int i = initial - 1; i >= 0; i -= 3) {
                    check(Objects.equals(list.remove(i), expected.remove(i)), "remove for reuse");
                }
                final Iterator<Integer> stale = list.iterator();
                final List<Integer> extra = values(added);
                check(list.addAll(extra), "bulk result");
                expected.addAll(extra);
                requireCme(stale);
                contents(list, expected);
                invariants(list);
                list.clear();
                check(capacity(list) == 0, "clear must release lanes");
                list.addAll(extra);
                contents(list, extra);
                invariants(list);
            }
        }
        rollback(false, false);
        rollback(true, false);
        rollback(false, true);
        overflow();
        for (int seed = 0; seed < 8; seed++) {
            final Random random = new Random(0x54524545L + seed);
            final TreeList<Integer> list = new TreeList<>();
            final List<Integer> expected = new ArrayList<>();
            for (int step = 0; step < 10000; step++) {
                final int op = list.size() > 512 ? 3 : random.nextInt(11);
                final Integer value = random.nextInt(13) == 0 ? null : random.nextInt(73);
                switch (op) {
                case 0:
                case 1: {
                    final int index = random.nextInt(list.size() + 1);
                    list.add(index, value);
                    expected.add(index, value);
                    break;
                }
                case 2: {
                    final List<Integer> extra = values(random.nextInt(35));
                    check(list.addAll(extra) == expected.addAll(extra), "addAll result");
                    break;
                }
                case 3:
                    if (!list.isEmpty()) {
                        final int index = random.nextInt(list.size());
                        check(Objects.equals(list.remove(index), expected.remove(index)), "remove result");
                    }
                    break;
                case 4:
                    if (!list.isEmpty()) {
                        final int index = random.nextInt(list.size());
                        check(Objects.equals(list.set(index, value), expected.set(index, value)), "set result");
                    }
                    break;
                case 5:
                    if (list.size() < 128) {
                        final List<Integer> copy = new ArrayList<>(expected);
                        check(list.addAll(list) == expected.addAll(copy), "self add");
                    }
                    break;
                case 6: {
                    final int index = random.nextInt(list.size() + 1);
                    final ListIterator<Integer> a = list.listIterator(index);
                    final ListIterator<Integer> b = expected.listIterator(index);
                    a.add(value);
                    b.add(value);
                    if (a.hasPrevious()) {
                        check(Objects.equals(a.previous(), b.previous()), "iterator previous");
                        a.set(value);
                        b.set(value);
                        a.remove();
                        b.remove();
                    }
                    break;
                }
                case 7:
                    if (!list.isEmpty()) {
                        final int from = random.nextInt(list.size());
                        final int to = from + random.nextInt(list.size() - from + 1);
                        list.subList(from, to).clear();
                        expected.subList(from, to).clear();
                    }
                    break;
                case 8: {
                    final List<Integer> extra = values(random.nextInt(8));
                    final int index = random.nextInt(list.size() + 1);
                    check(list.addAll(index, extra) == expected.addAll(index, extra), "indexed addAll");
                    break;
                }
                case 9:
                    if (random.nextInt(16) == 0) {
                        list.clear();
                        expected.clear();
                    }
                    break;
                default:
                    check(list.indexOf(value) == expected.indexOf(value), "indexOf");
                    check(list.lastIndexOf(value) == expected.lastIndexOf(value), "lastIndexOf");
                    break;
                }
                transitions++;
                contents(list, expected);
                if ((step & 127) == 0) {
                    invariants(list);
                }
            }
            invariants(list);
        }
        System.out.println("TREE_LIST_CONTRACT_PASS transitions=" + transitions
                + " digest=" + Long.toUnsignedString(digest));
        System.err.println("contract assertions=" + checks);
    }

    private static void rollback(final boolean error, final boolean reentrant) {
        for (final int failAt : new int[] {0, 1, 3, 17, 63}) {
            final TreeList<Integer> list = new TreeList<>(values(128));
            final List<Integer> expected = values(128);
            for (int i = 0; i < 80; i++) {
                list.remove(0);
                expected.remove(0);
            }
            final RuntimeException failure = new IllegalStateException("iterator failure");
            final AssertionError fatal = new AssertionError("iterator error");
            final Collection<Integer> broken = new AbstractCollection<Integer>() {
                @Override
                public int size() {
                    return 64;
                }
                @Override
                public Iterator<Integer> iterator() {
                    return new Iterator<Integer>() {
                        private int index;
                        @Override
                        public boolean hasNext() {
                            return index < 64;
                        }
                        @Override
                        public Integer next() {
                            if (index == failAt) {
                                if (reentrant) {
                                    list.add(0, -99);
                                    expected.add(0, -99);
                                }
                                if (error) {
                                    throw fatal;
                                }
                                throw failure;
                            }
                            return index++;
                        }
                    };
                }
            };
            final Iterator<Integer> stale = list.iterator();
            try {
                list.addAll(broken);
                throw new AssertionError("failure was swallowed");
            } catch (final RuntimeException caught) {
                check(!error && caught == failure, "exception identity");
            } catch (final AssertionError caught) {
                check(error && caught == fatal, "error identity");
            }
            requireCme(stale);
            contents(list, expected);
            invariants(list);
            final List<Integer> refill = values(129);
            list.addAll(refill);
            expected.addAll(refill);
            contents(list, expected);
            invariants(list);
        }
    }

    private static void overflow() {
        final TreeList<Integer> list = new TreeList<>(values(8));
        final Object[] before = lanes(list);
        final Collection<Integer> oversized = new AbstractCollection<Integer>() {
            @Override
            public int size() {
                return Integer.MAX_VALUE;
            }
            @Override
            public Iterator<Integer> iterator() {
                return Collections.emptyIterator();
            }
        };
        try {
            list.addAll(oversized);
            throw new AssertionError("oversized build admitted");
        } catch (final OutOfMemoryError expected) {
            check("TreeList build capacity exhausted".equals(expected.getMessage()), "overflow refusal");
        }
        contents(list, values(8));
        sameLanes(before, list);
        invariants(list);
    }

    public static void runReservation() {
        for (final int count : new int[] {1, 2, 8, 9, 64, 1024, 100000}) {
            final TreeList<Integer> list = new TreeList<>();
            final Object[][] first = new Object[1][];
            final Collection<Integer> observed = new AbstractCollection<Integer>() {
                @Override
                public int size() {
                    return count;
                }
                @Override
                public Iterator<Integer> iterator() {
                    return new Iterator<Integer>() {
                        private int index;
                        @Override
                        public boolean hasNext() {
                            return index < count;
                        }
                        @Override
                        public Integer next() {
                            if (!hasNext()) {
                                throw new NoSuchElementException();
                            }
                            check(capacity(list) >= count, "bulk capacity not reserved before first next: "
                                    + capacity(list) + " < " + count);
                            if (first[0] == null) {
                                first[0] = lanes(list);
                            } else {
                                sameLanes(first[0], list);
                            }
                            return index++;
                        }
                    };
                }
            };
            list.addAll(observed);
            sameLanes(first[0], list);
            check(list.size() == count, "reserved build count");
            invariants(list);
        }
        final TreeList<Integer> reused = new TreeList<>(values(10000));
        for (int i = 0; i < 9900; i++) {
            reused.remove(0);
        }
        final Object[] before = lanes(reused);
        reused.addAll(values(9900));
        sameLanes(before, reused);
        invariants(reused);
        reused.clear();
        reused.addAll(values(8193));
        invariants(reused);
        System.out.println("TREE_LIST_RESERVATION_PASS");
    }

    public static void runAllocationFailure() {
        final TreeList<Integer> list = new TreeList<>(values(8));
        final Object[] before = lanes(list);
        try {
            list.addAll(Collections.nCopies(4000000, 1));
            throw new AssertionError("run this case with -Xmx32m");
        } catch (final OutOfMemoryError expected) {
            // The journal fits, but all six final-capacity lanes do not fit.
        }
        sameLanes(before, list);
        contents(list, values(8));
        list.add(2);
        list.remove(list.size() - 1);
        invariants(list);
        System.out.println("TREE_LIST_ALLOCATION_FAILURE_PASS");
    }

    public static void main(final String[] args) {
        final String mode = args.length == 0 ? "all" : args[0];
        if ("contract".equals(mode) || "all".equals(mode)) {
            runContract();
        }
        if ("reservation".equals(mode) || "all".equals(mode)) {
            runReservation();
        }
        if ("oom".equals(mode)) {
            runAllocationFailure();
        }
    }
}
