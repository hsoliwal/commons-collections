/*
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.
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

import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;
import java.util.function.Function;

/** Independent before/after class-loader oracle for the intrinsic array lowering. */
public final class M3TreeListArrayStorageProof {
    private static long observations;

    private M3TreeListArrayStorageProof() {
    }

    private static void require(final boolean condition, final String message) {
        observations++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static Object outcome(final List<Integer> list,
            final Function<List<Integer>, Object> action, final boolean messages) {
        try {
            return action.apply(list);
        } catch (final RuntimeException failure) {
            return failure.getClass().getName() + (messages ? ":" + failure.getMessage() : "");
        }
    }

    static void exercise(final List<Integer> before, final List<Integer> after,
            final long seed, final int steps, final boolean messages) throws ReflectiveOperationException {
        final Random random = new Random(seed);
        for (int step = 0; step < steps; step++) {
            final int size = before.size();
            final int index = random.nextInt(size + 1);
            final Integer value = random.nextInt(5) == 0 ? null : random.nextInt(41) - 20;
            final int choice = size > 512 ? 10 : random.nextInt(14);
            final Function<List<Integer>, Object> action;
            switch (choice) {
            case 0:
                action = list -> { list.add(index, value); return null; };
                break;
            case 1:
                action = list -> list.remove(index == size ? index - 1 : index);
                break;
            case 2:
                action = list -> list.set(index, value);
                break;
            case 3:
                action = list -> list.addAll(Arrays.asList(value, null, index));
                break;
            case 4:
                action = list -> {
                    final ListIterator<Integer> it = list.listIterator(index);
                    final List<Object> trace = new ArrayList<>();
                    if (it.hasNext()) {
                        trace.add(it.next());
                        it.set(value);
                    }
                    it.add(value);
                    trace.add(it.previous());
                    it.remove();
                    trace.add(it.nextIndex());
                    trace.add(it.previousIndex());
                    return trace;
                };
                break;
            case 5:
                action = list -> {
                    final ListIterator<Integer> it = list.listIterator(index);
                    if (it.hasPrevious()) {
                        it.previous();
                        it.remove();
                    }
                    it.set(value);
                    return null;
                };
                break;
            case 6:
                action = list -> { list.subList(index / 2, index).clear(); return null; };
                break;
            case 7:
                action = list -> {
                    final Integer[] target = new Integer[list.size() + 3];
                    Arrays.fill(target, -999);
                    return Arrays.asList(list.toArray(target));
                };
                break;
            case 8:
                action = list -> list.addAll(list);
                break;
            case 9:
                action = list -> Arrays.asList(list.indexOf(value), list.lastIndexOf(value), list.contains(value));
                break;
            case 10:
                action = list -> { list.clear(); return null; };
                break;
            case 11:
                action = list -> { list.add(-1, value); return null; };
                break;
            case 12:
                action = list -> {
                    final Iterator<Integer> stale = list.iterator();
                    list.add(value);
                    return stale.next();
                };
                break;
            default:
                action = list -> list.remove((Object) value);
                break;
            }
            final Object expected = outcome(before, action, messages);
            final Object actual = outcome(after, action, messages);
            require(java.util.Objects.equals(expected, actual),
                    "result seed=" + seed + " step=" + step + " operation=" + choice
                            + " expected=" + expected + " actual=" + actual);
            require(before.equals(after) && after.equals(before),
                    "contents seed=" + seed + " step=" + step + " operation=" + choice);
            require(Arrays.equals(before.toArray(), after.toArray()), "toArray order");
            final ListIterator<Integer> left = before.listIterator(before.size());
            final ListIterator<Integer> right = after.listIterator(after.size());
            while (left.hasPrevious()) {
                require(right.hasPrevious() && java.util.Objects.equals(left.previous(), right.previous()),
                        "reverse iteration");
            }
            require(!right.hasPrevious(), "reverse exhaustion");
            inspectStorage(after);
            if (choice == 10) {
                require(((Object[]) field(field(after, "slots"), "values")).length == 0,
                        "explicit clear releases payload backing");
            }
        }
    }

    private static Object field(final Object owner, final String name) throws ReflectiveOperationException {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    /** Proves storage/retention obligations independently of the AVL mutation algorithms. */
    static void inspectStorage(final List<?> list) throws ReflectiveOperationException {
        require(list.getClass().getDeclaredField("root").getType() == int.class, "root is a slot index");
        final Object slots = field(list, "slots");
        final Object[] payload = (Object[]) field(slots, "values");
        final byte[] state = (byte[]) field(slots, "state");
        final int[] links = (int[]) field(slots, "left");
        final int used = (Integer) field(slots, "used");
        final boolean[] freeSeen = new boolean[used];
        int freeCount = 0;
        for (int free = (Integer) field(slots, "free"); free != -1; free = links[free]) {
            require(free >= 0 && free < used && !freeSeen[free], "free slots form a finite in-bounds chain");
            require((state[free] & 4) == 0, "free slot cannot be live");
            freeSeen[free] = true;
            freeCount++;
        }
        int live = 0;
        for (int index = 0; index < payload.length; index++) {
            if ((state[index] & 4) != 0) {
                live++;
            } else {
                require(payload[index] == null, "released slot retains payload");
            }
        }
        require(live == list.size(), "one live payload slot per logical element");
        require(live + freeCount == used, "every acquired slot is either live or reusable");
        for (final Field member : slots.getClass().getDeclaredFields()) {
            require(member.getType().isPrimitive() || member.getType().isArray(),
                    "slot store retains a non-array structural object: " + member.getName());
        }
    }

    static void failedBulkBuild(final List<Integer> before, final List<Integer> after)
            throws ReflectiveOperationException {
        before.addAll(Arrays.asList(1, 2, null, 3));
        after.addAll(Arrays.asList(1, 2, null, 3));
        final Function<List<Integer>, Object> action = list -> list.addAll(throwingSource());
        require(java.util.Objects.equals(outcome(before, action, true), outcome(after, action, true)),
                "failed bulk source exception");
        require(before.equals(after), "failed bulk source leaves logical contents");
        require(after.equals(Arrays.asList(1, 2, null, 3)), "failed build preserves the frozen input state");
        inspectStorage(after);
        final List<Integer> growth = new ArrayList<>();
        for (int index = 0; index < 4097; index++) {
            growth.add(index);
        }
        before.addAll(growth);
        after.addAll(growth);
        require(before.equals(after), "balanced build survives repeated lane growth");
        inspectStorage(after);
    }

    static void boundedBulkReuse(final List<Integer> before, final List<Integer> after)
            throws ReflectiveOperationException {
        before.add(-1);
        after.add(-1);
        final List<Integer> batch = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            batch.add(index);
        }
        int capacity = 0;
        for (int cycle = 0; cycle < 1024; cycle++) {
            before.addAll(batch);
            after.addAll(batch);
            require(before.equals(after), "bulk reuse contents at peak");
            for (int index = 0; index < batch.size(); index++) {
                require(java.util.Objects.equals(before.remove(1), after.remove(1)), "bulk reuse removal");
            }
            require(after.equals(Arrays.asList(-1)), "bounded bulk cycle preserves sentinel");
            inspectStorage(after);
            final int nextCapacity = ((Object[]) field(field(after, "slots"), "values")).length;
            if (cycle == 0) {
                capacity = nextCapacity;
                require(capacity <= 2 * (batch.size() + 1), "backing capacity is bounded by peak live size");
            } else {
                require(nextCapacity == capacity, "bulk churn must reuse slots without backing growth");
            }
            final Function<List<Integer>, Object> failing = list -> list.addAll(throwingSource());
            require(java.util.Objects.equals(outcome(before, failing, true), outcome(after, failing, true)),
                    "recycled-slot build failure contract");
            require(before.equals(after), "failed recycled build leaves logical contents");
            inspectStorage(after);
            require(((Object[]) field(field(after, "slots"), "values")).length == capacity,
                    "failed bulk churn must reuse slots without backing growth");
        }
    }

    private static Collection<Integer> throwingSource() {
        return new AbstractCollection<Integer>() {
            @Override
            public int size() { return 64; }
            @Override
            public Iterator<Integer> iterator() {
                return new Iterator<Integer>() {
                    private int count;
                    @Override
                    public boolean hasNext() { return true; }
                    @Override
                    public Integer next() {
                        if (++count == 9) {
                            throw new IllegalArgumentException("frozen source failure");
                        }
                        return 10000 + count;
                    }
                };
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> fresh(final ClassLoader loader) throws ReflectiveOperationException {
        return (List<Integer>) Class.forName("com.m3.collections.apache.list.TreeList", true, loader)
                .getConstructor().newInstance();
    }

    /** Arguments are separate immutable original and generated class directories. */
    public static void main(final String[] args) throws Exception {
        if (args.length != 2 || new File(args[0]).getCanonicalFile().equals(new File(args[1]).getCanonicalFile())) {
            throw new IllegalArgumentException("distinct original and generated class directories required");
        }
        try (URLClassLoader original = new URLClassLoader(new URL[] {new File(args[0]).toURI().toURL()}, null);
                URLClassLoader generated = new URLClassLoader(new URL[] {new File(args[1]).toURI().toURL()}, null)) {
            for (int seed = 0; seed < 12; seed++) {
                exercise(fresh(original), fresh(generated), 81013L + seed, 6000, true);
            }
            failedBulkBuild(fresh(original), fresh(generated));
            boundedBulkReuse(fresh(original), fresh(generated));
        }
        System.out.println("M3_TREE_LIST_ORIGINAL_DIFFERENTIAL_PASS seeds=12 operations=72000 observations=" + observations);
    }
}
