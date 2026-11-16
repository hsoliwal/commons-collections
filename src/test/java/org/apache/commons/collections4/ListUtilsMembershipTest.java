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
package org.apache.commons.collections4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.Vector;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Differential tests of {@link ListUtils#removeAll(Collection, Collection)} and {@link ListUtils#retainAll(Collection, Collection)} against the plain loop
 * over {@link Collection#contains(Object)} that defines them, across membership collection types, sizes on both sides of the hash probe thresholds, null
 * elements and duplicates.
 */
class ListUtilsMembershipTest {

    /** A list whose {@code contains} counts its calls: a custom collection type must keep being asked directly. */
    private static final class CountingList<E> extends ArrayList<E> {
        private static final long serialVersionUID = 1L;
        private int containsCalls;

        CountingList(final Collection<? extends E> elements) {
            super(elements);
        }

        @Override
        public boolean contains(final Object o) {
            containsCalls++;
            return super.contains(o);
        }
    }

    /** Honors the equals and hashCode contract and counts equals calls. */
    private static final class Key {
        private static long equalsCalls;
        private final int id;

        Key(final int id) {
            this.id = id;
        }

        @Override
        public boolean equals(final Object other) {
            equalsCalls++;
            return other instanceof Key && ((Key) other).id == id;
        }

        @Override
        public int hashCode() {
            return id * 31;
        }
    }

    /** Overrides equals and keeps the identity hash code: equal instances hash differently. */
    private static final class Loose {
        private final int id;

        Loose(final int id) {
            this.id = id;
        }

        @Override
        @SuppressWarnings("EqualsHashCode")
        public boolean equals(final Object other) {
            return other instanceof Loose && ((Loose) other).id == id;
        }
    }

    private static final int[] MEMBER_SIZES = {0, 1, 31, 32, 33, 64, 256, 257};
    private static final int[] SOURCE_SIZES = {0, 1, 17, 127, 128, 129, 512, 1100};

    private static <E> List<E> referenceRemoveAll(final Collection<E> collection, final Collection<?> remove) {
        final List<E> list = new ArrayList<>();
        for (final E obj : collection) {
            if (!remove.contains(obj)) {
                list.add(obj);
            }
        }
        return list;
    }

    private static <E> List<E> referenceRetainAll(final Collection<E> collection, final Collection<?> retain) {
        final List<E> list = new ArrayList<>(Math.min(collection.size(), retain.size()));
        for (final E obj : collection) {
            if (retain.contains(obj)) {
                list.add(obj);
            }
        }
        return list;
    }

    private static List<Integer> randomIntegers(final Random random, final int size, final int range, final boolean nulls) {
        final List<Integer> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(nulls && random.nextInt(9) == 0 ? null : Integer.valueOf(random.nextInt(range)));
        }
        return list;
    }

    private static <E> void assertSameSelections(final Collection<E> source, final Collection<?> members, final String label) {
        final List<E> removed = ListUtils.removeAll(source, members);
        final List<E> retained = ListUtils.retainAll(source, members);
        assertEquals(referenceRemoveAll(source, members), removed, () -> "removeAll " + label);
        assertEquals(referenceRetainAll(source, members), retained, () -> "retainAll " + label);
        assertSame(ArrayList.class, removed.getClass(), label);
        assertSame(ArrayList.class, retained.getClass(), label);
        assertEquals(source.size(), removed.size() + retained.size(), () -> "partition " + label);
    }

    @Test
    void testCustomCollectionIsAskedForEveryElement() {
        final List<Integer> source = randomIntegers(new Random(11), 200, 400, true);
        final CountingList<Integer> members = new CountingList<>(randomIntegers(new Random(12), 300, 400, true));
        assertEquals(referenceRemoveAll(source, new ArrayList<>(members)), ListUtils.removeAll(source, members));
        assertEquals(source.size(), members.containsCalls);
        members.containsCalls = 0;
        assertEquals(referenceRetainAll(source, new ArrayList<>(members)), ListUtils.retainAll(source, members));
        assertEquals(source.size(), members.containsCalls);
    }

    @Test
    void testEqualsWithoutHashCodeKeepsTheScan() {
        final List<Object> members = new ArrayList<>();
        final List<Object> source = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            members.add(new Loose(i * 2));
            source.add(new Loose(i * 3));
        }
        assertSameSelections(source, members, "loose members and elements");
        assertEquals(100, ListUtils.retainAll(source, members).size());
        final List<Object> integers = new ArrayList<>(randomIntegers(new Random(5), 200, 300, true));
        final List<Object> mixedSource = new ArrayList<>(integers);
        mixedSource.addAll(source);
        Collections.shuffle(mixedSource, new Random(6));
        assertSameSelections(mixedSource, integers, "loose elements, hashable members");
        final List<Object> mixedMembers = new ArrayList<>(integers);
        mixedMembers.add(new Loose(3));
        assertSameSelections(mixedSource, mixedMembers, "a loose member among hashable ones");
    }

    @Test
    void testLargeMemberListIsNotScannedForEveryElement() {
        final int size = 400;
        final List<Key> members = new ArrayList<>();
        final List<Key> source = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            members.add(new Key(i));
            source.add(new Key(size + i));
        }
        Key.equalsCalls = 0;
        assertEquals(size, ListUtils.removeAll(source, members).size());
        final long calls = Key.equalsCalls;
        assertTrue(calls < (long) size * size / 8, () -> "equals calls " + calls);
        Key.equalsCalls = 0;
        assertEquals(size, referenceRemoveAll(source, members).size());
        assertEquals((long) size * size, Key.equalsCalls);
    }

    @Test
    void testMembershipTypesAndSizesMatchTheLoop() {
        final Random random = new Random(20261116L);
        final List<Function<List<Integer>, Collection<Integer>>> memberTypes = Arrays.asList(
                ArrayList::new, LinkedList::new, list -> Arrays.asList(list.toArray(new Integer[0])), HashSet::new, LinkedHashSet::new, Vector::new,
                CountingList::new, Collections::unmodifiableList);
        final List<Function<List<Integer>, Collection<Integer>>> sourceTypes = Arrays.asList(ArrayList::new, LinkedList::new, LinkedHashSet::new);
        for (final int memberSize : MEMBER_SIZES) {
            for (final int sourceSize : SOURCE_SIZES) {
                final List<Integer> memberValues = randomIntegers(random, memberSize, Math.max(4, memberSize), true);
                final List<Integer> sourceValues = randomIntegers(random, sourceSize, Math.max(4, 2 * memberSize), true);
                for (final Function<List<Integer>, Collection<Integer>> memberType : memberTypes) {
                    for (final Function<List<Integer>, Collection<Integer>> sourceType : sourceTypes) {
                        final Collection<Integer> members = memberType.apply(memberValues);
                        final Collection<Integer> source = sourceType.apply(sourceValues);
                        assertSameSelections(source, members, members.getClass().getSimpleName() + "[" + memberSize + "] over "
                                + source.getClass().getSimpleName() + "[" + sourceSize + "]");
                    }
                }
            }
        }
    }

    @Test
    void testNullFreeCollectionsMatchTheLoop() {
        final Random random = new Random(7);
        final List<Integer> memberValues = randomIntegers(random, 500, 700, false);
        final List<Integer> sourceValues = randomIntegers(random, 600, 900, false);
        assertSameSelections(new ArrayDeque<>(sourceValues), new TreeSet<>(memberValues), "TreeSet over ArrayDeque");
        assertSameSelections(new ArrayDeque<>(sourceValues), new ArrayList<>(memberValues), "ArrayList over ArrayDeque");
        final List<Integer> immutable = List.copyOf(memberValues);
        assertSameSelections(sourceValues, immutable, "immutable list");
        final List<Integer> withNull = new ArrayList<>(sourceValues);
        withNull.add(20, null);
        assertThrows(NullPointerException.class, () -> referenceRemoveAll(withNull, immutable));
        assertThrows(NullPointerException.class, () -> ListUtils.removeAll(withNull, immutable));
        assertThrows(NullPointerException.class, () -> ListUtils.retainAll(withNull, immutable));
    }

    @Test
    void testSameListOnBothSides() {
        final List<Integer> list = randomIntegers(new Random(3), 250, 60, true);
        assertEquals(Collections.emptyList(), ListUtils.removeAll(list, list));
        assertEquals(list, ListUtils.retainAll(list, list));
        final List<Integer> linked = new LinkedList<>(list);
        assertEquals(Collections.emptyList(), ListUtils.removeAll(linked, linked));
        assertEquals(list, ListUtils.retainAll(linked, linked));
    }
}
