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
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Random;

/** Executable public-behavior and complete physical-slot join checks. */
public final class TreeListJoinContract {
    private long checks;
    private long operations;
    private long digest = 0x4a4f494eL;

    private TreeListJoinContract() {
    }

    private void check(final boolean condition, final String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void mix(final int value) {
        digest = Long.rotateLeft(digest, 7) * 0x9e3779b97f4a7c15L ^ value;
    }

    private static Object read(final Object owner, final String name) {
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (final NoSuchFieldException absent) {
                type = type.getSuperclass();
            } catch (final IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
        throw new AssertionError("missing field: " + name);
    }

    private static List<Integer> input(final int count) {
        final List<Integer> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(index % 11 == 0 ? null : index % 127);
        }
        return result;
    }

    private int walk(final int slot, final int parentPosition, final int[] left,
            final int[] right, final int[] positions, final byte[] heights,
            final byte[] state, final boolean[] seen, final int[] order, final int[] cursor) {
        if (slot == -1) {
            return -1;
        }
        check(slot >= 0 && slot < seen.length && !seen[slot], "acyclic in-bounds children");
        seen[slot] = true;
        check((state[slot] & 4) != 0, "reachable live slot");
        final int position = parentPosition + positions[slot];
        final int leftHeight = walk((state[slot] & 1) == 0 ? left[slot] : -1, position,
                left, right, positions, heights, state, seen, order, cursor);
        check(position == cursor[0], "exact relative rank");
        check(cursor[0] < order.length, "too many live nodes");
        order[cursor[0]++] = slot;
        final int rightHeight = walk((state[slot] & 2) == 0 ? right[slot] : -1, position,
                left, right, positions, heights, state, seen, order, cursor);
        check(Math.abs(leftHeight - rightHeight) <= 1, "AVL balance");
        check(heights[slot] == 1 + Math.max(leftHeight, rightHeight), "cached height");
        return 1 + Math.max(leftHeight, rightHeight);
    }

    private void verifyState(final TreeList<Integer> actual, final List<Integer> expected) {
        operations++;
        check(actual.size() == expected.size(), "public size");
        check(Arrays.equals(actual.toArray(), expected.toArray()), "public encounter order");
        check(Arrays.equals(actual.toArray(new Integer[0]), expected.toArray(new Integer[0])), "typed export");
        final ListIterator<Integer> iterator = actual.listIterator(actual.size());
        for (int index = expected.size() - 1; index >= 0; index--) {
            check(Objects.equals(iterator.previous(), expected.get(index)), "reverse iteration");
            check(Objects.equals(actual.get(index), expected.get(index)), "indexed read");
        }
        final Object owner = read(actual, "slots");
        final Object[] values = (Object[]) read(owner, "values");
        final int[] left = (int[]) read(owner, "left");
        final int[] right = (int[]) read(owner, "right");
        final int[] positions = (int[]) read(owner, "relativePosition");
        final byte[] heights = (byte[]) read(owner, "heights");
        final byte[] state = (byte[]) read(owner, "state");
        final int used = (Integer) read(owner, "used");
        final int root = (Integer) read(actual, "root");
        final int free = (Integer) read(owner, "free");
        check(used >= 0 && used <= values.length, "high-water bound");
        check(left.length == values.length && right.length == values.length
                && positions.length == values.length && heights.length == values.length
                && state.length == values.length, "matching lane lengths");
        check((Integer) read(owner, "detached") == -1, "no detached pivot after join");
        final boolean[] seen = new boolean[used];
        final int[] order = new int[actual.size()];
        final int[] cursor = {0};
        walk(root, 0, left, right, positions, heights, state, seen, order, cursor);
        check(cursor[0] == actual.size(), "live node count");
        for (int index = 0; index < order.length; index++) {
            final int slot = order[index];
            check(Objects.equals(values[slot], expected.get(index)), "slot payload");
            if ((state[slot] & 1) != 0) {
                check(left[slot] == (index == 0 ? -1 : order[index - 1]), "predecessor thread");
            }
            if ((state[slot] & 2) != 0) {
                check(right[slot] == (index + 1 == order.length ? -1 : order[index + 1]), "successor thread");
            }
        }
        int freeCount = 0;
        for (int slot = free; slot != -1; slot = left[slot]) {
            check(slot >= 0 && slot < used && !seen[slot], "acyclic disjoint free chain");
            seen[slot] = true;
            check(values[slot] == null && state[slot] == 0, "released payload and state");
            freeCount++;
        }
        check(freeCount + actual.size() == used, "slot conservation");
        mix(root);
        mix(free);
        mix(used);
        mix(values.length);
        // Include unused cells too: compare the entire physical state across binaries.
        for (int index = 0; index < values.length; index++) {
            mix(values[index] == null ? Integer.MIN_VALUE : (Integer) values[index]);
            mix(left[index]);
            mix(right[index]);
            mix(positions[index]);
            mix(heights[index]);
            mix(state[index]);
        }
    }

    private void joinedSizes() {
        final int[] sizes = {0, 1, 2, 3, 7, 8, 9, 15, 16, 17, 31, 63, 128, 257, 1024};
        for (final int before : sizes) {
            for (final int batch : sizes) {
                for (int layout = 0; layout < 2; layout++) {
                    final List<Integer> expected = new ArrayList<>();
                    final TreeList<Integer> actual = new TreeList<>();
                    if (layout == 0) {
                        expected.addAll(input(before));
                        actual.addAll(expected);
                    } else {
                        final Random random = new Random(31L * before + batch);
                        for (int index = 0; index < before + 41; index++) {
                            final int at = random.nextInt(expected.size() + 1);
                            expected.add(at, index % 127);
                            actual.add(at, index % 127);
                        }
                        while (expected.size() > before) {
                            final int at = random.nextInt(expected.size());
                            expected.remove(at);
                            actual.remove(at);
                        }
                    }
                    final Iterator<Integer> stale = actual.iterator();
                    final List<Integer> source = input(batch);
                    check(actual.addAll(source) == expected.addAll(source), "bulk return");
                    if (batch != 0) {
                        try {
                            stale.next();
                            throw new AssertionError("stale iterator accepted");
                        } catch (final ConcurrentModificationException correct) {
                            checks++;
                        }
                    }
                    verifyState(actual, expected);
                }
            }
        }
    }

    private void callbacksAndFailures() {
        for (final int before : new int[] {0, 1, 9, 32, 257}) {
            for (final int batch : new int[] {1, 8, 9, 33}) {
                for (int failAt = -1; failAt < batch; failAt++) {
                    for (int kind = 0; kind < 3; kind++) {
                        final TreeList<Integer> actual = new TreeList<>(input(before));
                        final List<Integer> expected = input(before);
                        final int failureIndex = failAt;
                        final int variant = kind;
                        final RuntimeException failure = new IllegalArgumentException("source failure");
                        final Error fatal = new AssertionError("source error");
                        final List<String> trace = new ArrayList<>();
                        final Collection<Integer> source = new AbstractCollection<Integer>() {
                            @Override
                            public int size() {
                                trace.add("size");
                                return batch;
                            }
                            @Override
                            public Iterator<Integer> iterator() {
                                trace.add("iterator");
                                return new Iterator<Integer>() {
                                    private int index;
                                    @Override
                                    public boolean hasNext() {
                                        trace.add("hasNext");
                                        return index < batch;
                                    }
                                    @Override
                                    public Integer next() {
                                        trace.add("next:" + index);
                                        check(Arrays.equals(actual.toArray(), expected.toArray()), "visible tree during build");
                                        if (index == 0 && variant == 2) {
                                            actual.add(0, 91);
                                            expected.add(0, 91);
                                        }
                                        if (index == failureIndex) {
                                            if (variant == 1) {
                                                throw fatal;
                                            }
                                            throw failure;
                                        }
                                        return index++;
                                    }
                                };
                            }
                        };
                        try {
                            actual.addAll(source);
                            check(failureIndex == -1, "failure was swallowed");
                            for (int index = 0; index < batch; index++) {
                                expected.add(index);
                            }
                        } catch (final RuntimeException | Error caught) {
                            check(failureIndex >= 0 && caught == (variant == 1 ? fatal : failure), "failure identity");
                        }
                        mix(trace.hashCode());
                        verifyState(actual, expected);
                        actual.addAll(input(65));
                        expected.addAll(input(65));
                        verifyState(actual, expected);
                    }
                }
            }
        }
    }

    /** Exercise joins after removals and nested joins during the outer source callback. */
    private void nestedRemovalAndJoin() {
        for (final int before : new int[] {1, 2, 3, 8, 17, 64, 257}) {
            for (final int batch : new int[] {9, 33, 128}) {
                for (int mode = 0; mode < 6; mode++) {
                    final TreeList<Integer> actual = new TreeList<>(input(before + 3));
                    final List<Integer> expected = input(before + 3);
                    for (int index = 0; index < 3; index++) {
                        check(Objects.equals(actual.remove(0), expected.remove(0)), "prepare recycled slots");
                    }
                    final int variant = mode;
                    final RuntimeException failure = new IllegalStateException("nested source failure");
                    final Error fatal = new AssertionError("nested source error");
                    final Collection<Integer> source = new AbstractCollection<Integer>() {
                        @Override
                        public int size() {
                            return batch;
                        }
                        @Override
                        public Iterator<Integer> iterator() {
                            return new Iterator<Integer>() {
                                private int index;
                                @Override
                                public boolean hasNext() {
                                    return index < batch;
                                }
                                @Override
                                public Integer next() {
                                    if (index == 0) {
                                        final int rank = expected.size() / 2;
                                        check(Objects.equals(actual.remove(rank), expected.remove(rank)),
                                                "logical removal during outer build");
                                        if (variant >= 3) {
                                            final List<Integer> nested = input(17);
                                            check(actual.addAll(nested), "nested join result");
                                            expected.addAll(nested);
                                        }
                                    }
                                    if (index == batch / 2) {
                                        if (variant % 3 == 1) {
                                            throw failure;
                                        }
                                        if (variant % 3 == 2) {
                                            throw fatal;
                                        }
                                    }
                                    return index++ % 29;
                                }
                            };
                        }
                    };
                    try {
                        check(actual.addAll(source), "outer join result");
                        check(variant % 3 == 0, "nested source failure was swallowed");
                        for (int index = 0; index < batch; index++) {
                            expected.add(index % 29);
                        }
                    } catch (final RuntimeException | Error caught) {
                        check(variant % 3 != 0 && caught == (variant % 3 == 1 ? failure : fatal),
                                "nested source exception identity");
                    }
                    verifyState(actual, expected);
                    final List<Integer> refill = input(65);
                    actual.addAll(refill);
                    expected.addAll(refill);
                    verifyState(actual, expected);
                }
            }
        }
    }

    private void random(final int steps) {
        for (int seed = 0; seed < 6; seed++) {
            final Random random = new Random(18471L + seed);
            final TreeList<Integer> actual = new TreeList<>();
            final List<Integer> expected = new ArrayList<>();
            for (int step = 0; step < steps; step++) {
                if (expected.size() > 384) {
                    final int remove = expected.size() / 2;
                    actual.subList(0, remove).clear();
                    expected.subList(0, remove).clear();
                }
                final int at = random.nextInt(expected.size() + 1);
                switch (random.nextInt(8)) {
                case 0:
                case 1:
                    final List<Integer> batch = input(random.nextInt(65));
                    check(actual.addAll(batch) == expected.addAll(batch), "random bulk return");
                    break;
                case 2:
                    actual.add(at, step % 127);
                    expected.add(at, step % 127);
                    break;
                case 3:
                    if (!expected.isEmpty()) {
                        final int index = at % expected.size();
                        check(Objects.equals(actual.remove(index), expected.remove(index)), "random remove");
                    }
                    break;
                case 4:
                    if (!expected.isEmpty()) {
                        final int index = at % expected.size();
                        check(Objects.equals(actual.set(index, null), expected.set(index, null)), "random set");
                    }
                    break;
                case 5:
                    final ListIterator<Integer> iterator = actual.listIterator(at);
                    iterator.add(77);
                    expected.add(at, 77);
                    check(Objects.equals(iterator.previous(), expected.remove(at)), "iterator add/previous");
                    iterator.remove();
                    break;
                case 6:
                    if (expected.size() < 96) {
                        check(actual.addAll(actual) == expected.addAll(new ArrayList<>(expected)), "self append");
                    }
                    break;
                default:
                    if ((step & 31) == 0) {
                        actual.clear();
                        expected.clear();
                    } else {
                        final List<Integer> tail = input(3);
                        actual.addAll(at, tail);
                        expected.addAll(at, tail);
                    }
                    break;
                }
                verifyState(actual, expected);
            }
        }
    }

    public static void verify(final int steps) {
        final TreeListJoinContract contract = new TreeListJoinContract();
        contract.joinedSizes();
        contract.callbacksAndFailures();
        contract.nestedRemovalAndJoin();
        contract.random(steps);
        System.out.println("JOIN_CONTRACT_PASS states=" + contract.operations + " checks="
                + contract.checks + " physicalDigest=" + Long.toHexString(contract.digest));
    }

    public static void main(final String[] args) {
        verify(args.length == 0 ? 2000 : Integer.parseInt(args[0]));
    }
}
