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
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;

/** Independent, dependency-free checks against the real fork owner. */
public final class TreeListBulkGrowthContract {
    private static long checks;
    private static long fingerprint = 0x132941277L;
    private static final String[] LANES = {
        "values", "left", "right", "relativePosition", "heights", "state"
    };

    private TreeListBulkGrowthContract() {
    }

    private static Object field(final Object owner, final String name) {
        try {
            final Field member = owner.getClass().getDeclaredField(name);
            member.setAccessible(true);
            return member.get(owner);
        } catch (final ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object slots(final TreeList<?> list) {
        try {
            final Field member = TreeList.class.getDeclaredField("slots");
            member.setAccessible(true);
            return member.get(list);
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

    private static void equal(final Object expected, final Object actual, final String message) {
        check(Objects.equals(expected, actual), message + ": " + expected + " != " + actual);
        fingerprint = Long.rotateLeft(fingerprint, 7) ^ Objects.hashCode(actual);
    }

    private static List<Integer> values(final int count, final int offset) {
        final List<Integer> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(index % 17 == 0 ? null : (index + offset) % 113);
        }
        return result;
    }

    private static void inspect(final TreeList<?> list) {
        final Object owner = slots(list);
        final Object[] payload = (Object[]) field(owner, "values");
        final int[] left = (int[]) field(owner, "left");
        final byte[] state = (byte[]) field(owner, "state");
        final int used = (Integer) field(owner, "used");
        for (final String name : LANES) {
            check(java.lang.reflect.Array.getLength(field(owner, name)) == payload.length,
                    "all lanes published at the same capacity");
        }
        final boolean[] freed = new boolean[used];
        int freeCount = 0;
        for (int slot = (Integer) field(owner, "free"); slot != -1; slot = left[slot]) {
            check(slot >= 0 && slot < used && !freed[slot], "free chain has no cycle or invalid slot");
            freed[slot] = true;
            check(payload[slot] == null && state[slot] == 0, "free slots retain no payload");
            freeCount++;
        }
        int liveCount = 0;
        for (int slot = 0; slot < used; slot++) {
            if ((state[slot] & 4) != 0) {
                check(!freed[slot], "live slot is not in free chain");
                liveCount++;
            } else {
                check(freed[slot], "every acquired slot is live or reusable");
            }
        }
        check(liveCount == list.size() && liveCount + freeCount == used, "slot ownership conservation");
        check((Integer) field(owner, "detached") == -1, "no unpublished detached slot");
    }

    private static void contents(final List<Integer> expected, final TreeList<Integer> actual) {
        equal(expected.size(), actual.size(), "size");
        check(Arrays.equals(expected.toArray(), actual.toArray()), "raw order");
        check(Arrays.equals(expected.toArray(new Integer[0]), actual.toArray(new Integer[0])), "typed order");
        for (int index = 0; index < expected.size(); index++) {
            equal(expected.get(index), actual.get(index), "rank");
        }
        final ListIterator<Integer> iterator = actual.listIterator(actual.size());
        for (int index = expected.size() - 1; index >= 0; index--) {
            equal(expected.get(index), iterator.previous(), "reverse thread");
        }
        check(!iterator.hasPrevious(), "reverse boundary");
        inspect(actual);
    }

    private static final class ObservedSource extends AbstractCollection<Integer> {
        private final TreeList<Integer> receiver;
        private final int count;
        private final Object[] last = new Object[LANES.length];
        private int replacements;
        private int consumed;

        private ObservedSource(final TreeList<Integer> receiver, final int count) {
            this.receiver = receiver;
            this.count = count;
            observe();
            replacements = 0;
        }

        private void observe() {
            final Object owner = slots(receiver);
            boolean changed = false;
            for (int lane = 0; lane < LANES.length; lane++) {
                final Object current = field(owner, LANES[lane]);
                changed |= last[lane] != current;
                last[lane] = current;
            }
            if (changed) {
                replacements++;
            }
        }

        @Override
        public int size() {
            return count;
        }

        @Override
        public Iterator<Integer> iterator() {
            return new Iterator<Integer>() {
                private int position;
                @Override
                public boolean hasNext() {
                    return position < count;
                }
                @Override
                public Integer next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    observe();
                    consumed++;
                    return position++;
                }
            };
        }
    }

    /** Fails on the incumbent implementation: bulk build repeatedly replaces all lanes. */
    public static void growthContract() {
        for (final int recycled : new int[] {0, 37, 4096}) {
            final TreeList<Integer> list = new TreeList<>(values(recycled + 11, 0));
            for (int index = 0; index < recycled; index++) {
                list.remove(0);
            }
            final List<Integer> old = new ArrayList<>(list);
            final int oldUsed = (Integer) field(slots(list), "used");
            final ObservedSource source = new ObservedSource(list, 8192);
            list.addAll(source);
            source.observe();
            equal(8192, source.consumed, "consume each value exactly once");
            check(source.replacements <= 1, "bulk build replaced six backing lanes "
                    + source.replacements + " times; expected at most one");
            for (int index = 0; index < 8192; index++) {
                old.add(index);
            }
            check((Integer) field(slots(list), "used") == Math.max(oldUsed, old.size()),
                    "exhaust recycled slots before acquiring fresh coordinates");
            contents(old, list);
        }
        final TreeList<Integer> list = new TreeList<>(values(8192, 0));
        for (int index = 0; index < 4096; index++) {
            list.remove(0);
        }
        final ObservedSource source = new ObservedSource(list, 2048);
        list.addAll(source);
        equal(0, source.replacements, "recycled slots require no capacity growth");
        equal(8192, field(slots(list), "used"), "recycled insertion preserves high-water mark");
        inspect(list);
    }

    private static final class InjectedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class InjectedError extends Error {
        private static final long serialVersionUID = 1L;
    }

    private static void failedBuilds() {
        for (final int initial : new int[] {0, 31, 96}) {
            for (int failAt = 0; failAt < 65; failAt++) {
                for (int kind = 0; kind < 3; kind++) {
                    final TreeList<Integer> receiver = new TreeList<>(values(initial, 19));
                    for (int count = initial / 2; count > 0; count--) {
                        receiver.remove(receiver.size() / 2);
                    }
                    final List<Integer> expected = new ArrayList<>(receiver);
                    final int failureIndex = failAt;
                    final int failureKind = kind;
                    final Throwable failure = kind == 0 ? new InjectedFailure()
                            : kind == 1 ? new InjectedError() : new NoSuchElementException("short input");
                    final Iterator<Integer> stale = receiver.iterator();
                    final AbstractCollection<Integer> source = new AbstractCollection<Integer>() {
                        @Override
                        public int size() {
                            return 65;
                        }
                        @Override
                        public Iterator<Integer> iterator() {
                            return new Iterator<Integer>() {
                                private int next;
                                @Override
                                public boolean hasNext() {
                                    return next < 65;
                                }
                                @Override
                                public Integer next() {
                                    if (next == failureIndex) {
                                        if (failureKind == 1) {
                                            throw (Error) failure;
                                        }
                                        throw (RuntimeException) failure;
                                    }
                                    return -1000 - next++;
                                }
                            };
                        }
                    };
                    try {
                        receiver.addAll(source);
                        throw new AssertionError("expected source failure");
                    } catch (final RuntimeException | Error caught) {
                        check(caught == failure, "preserve exact failure identity");
                    }
                    contents(expected, receiver);
                    try {
                        stale.next();
                        throw new AssertionError("modCount must still invalidate stale iterator");
                    } catch (final ConcurrentModificationException caught) {
                        checks++;
                    }
                    final List<Integer> incoming = values(129, 29);
                    receiver.addAll(incoming);
                    expected.addAll(incoming);
                    contents(expected, receiver);
                }
            }
        }
    }

    private static void randomized(final int seeds, final int steps) {
        for (int seed = 0; seed < seeds; seed++) {
            final Random random = new Random(87193L + seed);
            final TreeList<Integer> actual = new TreeList<>();
            final List<Integer> expected = new ArrayList<>();
            for (int step = 0; step < steps; step++) {
                if (expected.size() > 384) {
                    final int limit = expected.size() / 2;
                    for (int count = 0; count < limit; count++) {
                        final int at = random.nextInt(expected.size());
                        equal(expected.remove(at), actual.remove(at), "recycle");
                    }
                }
                final int at = random.nextInt(expected.size() + 1);
                switch (random.nextInt(6)) {
                case 0:
                    final List<Integer> incoming = values(random.nextInt(65), step);
                    equal(expected.addAll(incoming), actual.addAll(incoming), "bulk return");
                    break;
                case 1:
                    final Integer value = random.nextInt(7) == 0 ? null : random.nextInt(113);
                    expected.add(at, value);
                    actual.add(at, value);
                    break;
                case 2:
                    if (!expected.isEmpty()) {
                        final int index = at % expected.size();
                        equal(expected.remove(index), actual.remove(index), "remove");
                    }
                    break;
                case 3:
                    if (!expected.isEmpty()) {
                        final int index = at % expected.size();
                        equal(expected.set(index, step), actual.set(index, step), "set");
                    }
                    break;
                case 4:
                    if (step % 19 == 0) {
                        expected.clear();
                        actual.clear();
                    } else {
                        equal(expected.indexOf(step % 113), actual.indexOf(step % 113), "indexOf");
                    }
                    break;
                default:
                    if (!expected.isEmpty()) {
                        final int index = at % expected.size();
                        final ListIterator<Integer> iterator = actual.listIterator(index);
                        equal(expected.remove(index), iterator.next(), "iterator remove value");
                        iterator.remove();
                    }
                    break;
                }
                contents(expected, actual);
            }
        }
    }

    private static void boundaries() {
        for (final int count : new int[] {0, 1, 2, 7, 8, 9, 11, 12, 13, 63, 64, 65, 1024, 65536}) {
            final List<Integer> input = values(count, 11);
            final TreeList<Integer> list = new TreeList<>(input);
            contents(input, list);
            int expectedCapacity = 0;
            while (expectedCapacity < count) {
                expectedCapacity = Math.max(8, expectedCapacity + (expectedCapacity >>> 1));
            }
            equal(expectedCapacity, ((Object[]) field(slots(list), "values")).length,
                    "successful build retains original geometric capacity");
            check(!list.addAll(Collections.<Integer>emptyList()), "empty input return");
            list.clear();
            equal(0, ((Object[]) field(slots(list), "values")).length, "clear releases storage");
            list.addAll(input);
            contents(input, list);
        }
        final TreeList<Integer> self = new TreeList<>(values(17, 0));
        final List<Integer> doubled = new ArrayList<>(self);
        doubled.addAll(new ArrayList<>(self));
        check(self.addAll(self), "self-add return");
        contents(doubled, self);
    }

    public static void behavioralContract() {
        boundaries();
        failedBuilds();
        randomized(12, 2000);
    }

    public static void main(final String[] args) {
        if (args.length == 0 || "behavior".equals(args[0])) {
            behavioralContract();
        } else if ("interpreted".equals(args[0])) {
            boundaries();
            failedBuilds();
            randomized(2, 200);
        } else if ("growth".equals(args[0])) {
            growthContract();
        } else {
            throw new IllegalArgumentException("behavior, interpreted or growth");
        }
        System.out.println("TREE_BULK_PASS checks=" + checks + " fingerprint="
                + Long.toHexString(fingerprint));
    }
}
