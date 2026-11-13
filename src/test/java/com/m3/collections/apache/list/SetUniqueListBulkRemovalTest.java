/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.m3.collections.apache.list;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Differential and adversarial safety gates for guarded bulk ArrayList compaction. */
final class SetUniqueListBulkRemovalTest {

    private static <E> boolean originalRemoveAll(
            final List<E> list, final Set<E> set, final Collection<?> values) {
        boolean changed = false;
        for (final Object value : values) {
            final boolean existed = set.remove(value);
            if (existed) {
                list.remove(value);
            }
            changed |= existed;
        }
        return changed;
    }

    @Test
    void bulkRemovesValuesInOneOrderedArrayPass() {
        final ArrayList<Integer> backing = new ArrayList<>();
        for (int i = 0; i < 8_192; i++) {
            backing.add(i);
        }
        final SetUniqueList<Integer> actual = SetUniqueList.setUniqueList(backing);
        final List<Integer> expectedList = new ArrayList<>(backing);
        final Set<Integer> expectedSet = new HashSet<>(backing);
        final HashSet<Integer> remove = new HashSet<>();
        for (int i = 1_024; i < 4_096; i += 2) {
            remove.add(i);
        }
        for (int i = 9_000; i < 10_000; i++) {
            remove.add(i);
        }

        final boolean expectedChanged = originalRemoveAll(expectedList, expectedSet, remove);
        assertEquals(expectedChanged, actual.removeAll(remove));
        assertEquals(expectedList, actual);
        assertEquals(expectedSet, actual.asSet());
        assertSame(backing, actual.decorated());
        assertFalse(actual.removeAll(remove));
        assertEquals(expectedList, backing);
    }

    @Test
    void seededListSizesAndRemovalSetsPreserveEveryElementAndOrder() {
        final Random random = new Random(0x5eede21L);
        for (final int count : new int[]{0, 1, 32, 255, 256, 257, 1024, 4096}) {
            final ArrayList<Object> backing = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                backing.add(i == 0 ? null : "key-" + i);
            }
            final SetUniqueList<Object> actual = SetUniqueList.setUniqueList(backing);
            for (final int removeCount : new int[]{0, 1, 63, 64, 65, 512}) {
                final ArrayList<Object> fresh = new ArrayList<>(actual);
                final SetUniqueList<Object> trial = SetUniqueList.setUniqueList(fresh);
                final List<Object> expected = new ArrayList<>(fresh);
                final Set<Object> membership = new HashSet<>(fresh);
                final HashSet<Object> remove = new HashSet<>();
                for (int i = 0; i < removeCount; i++) {
                    final int index = random.nextInt(count + 64);
                    remove.add(index == 0 ? null : "key-" + index);
                }
                final boolean expectedResult = originalRemoveAll(expected, membership, remove);
                assertEquals(expectedResult, trial.removeAll(remove),
                        "count=" + count + ",removeCount=" + removeCount);
                assertEquals(expected, trial);
                assertEquals(membership, trial.asSet());
            }
        }
    }

    @Test
    void preexistingInconsistentExternalBackingForcesLegacySemantics() {
        final ArrayList<String> backing = new ArrayList<>();
        for (int i = 0; i < 16_384; i++) {
            backing.add("key-" + i);
        }
        final SetUniqueList<String> actual = SetUniqueList.setUniqueList(backing);
        backing.set(0, backing.get(1)); // Duplicate; the retained membership set is now stale.
        final List<String> expected = new ArrayList<>(backing);
        final Set<String> membership = new HashSet<>(actual.asSet());
        final HashSet<String> remove = new HashSet<>();
        remove.add("key-0"); // Set contains this key, while backing list no longer does.
        for (int i = 2_000; i < 3_024; i++) {
            remove.add("absent-" + i);
        }

        final boolean expectedResult = originalRemoveAll(expected, membership, remove);
        assertEquals(expectedResult, actual.removeAll(remove));
        assertEquals(expected, backing);
        assertEquals(membership, actual.asSet());
    }

    @Test
    void linkedInputAndLinkedBackingKeepLegacyPath() {
        final LinkedList<String> backing = new LinkedList<>();
        for (int i = 0; i < 16_384; i++) {
            backing.add("key-" + i);
        }
        final SetUniqueList<String> actual = SetUniqueList.setUniqueList(backing);
        final LinkedHashSet<String> remove = new LinkedHashSet<>();
        for (int i = 0; i < 1_024; i++) {
            remove.add("key-" + i);
        }
        assertTrue(actual.removeAll(remove));
        assertEquals(15_360, actual.size());
        assertEquals("key-1024", actual.get(0));
    }

    @Test
    void exactRuntimeGuardPreservesSubclassRemovalOverrides() {
        final class Tracking extends SetUniqueList<String> {
            private static final long serialVersionUID = 1L;
            private int removalCalls;

            Tracking(final List<String> list, final Set<String> set) {
                super(list, set);
            }

            @Override
            public boolean remove(final Object value) {
                removalCalls++;
                return super.remove(value);
            }
        }
        final ArrayList<String> backing = new ArrayList<>();
        for (int i = 0; i < 16_384; i++) {
            backing.add("key-" + i);
        }
        final Tracking actual = new Tracking(backing, new HashSet<>(backing));
        final HashSet<String> remove = new HashSet<>();
        for (int i = 0; i < 1_024; i++) {
            remove.add("key-" + i);
        }
        assertTrue(actual.removeAll(remove));
        assertEquals(remove.size(), actual.removalCalls);
        assertEquals(15_360, actual.size());
    }

    @Test
    void customEqualityCannotEnterReorderedBulkPath() {
        final class CountingValue {
            private final int number;
            private int comparisons;

            CountingValue(final int number) {
                this.number = number;
            }

            @Override
            public boolean equals(final Object other) {
                comparisons++;
                return other instanceof CountingValue
                        && this.number == ((CountingValue) other).number;
            }

            @Override
            public int hashCode() {
                return number;
            }
        }
        final ArrayList<CountingValue> backing = new ArrayList<>();
        for (int i = 0; i < 16_384; i++) {
            backing.add(new CountingValue(i));
        }
        final SetUniqueList<CountingValue> actual = SetUniqueList.setUniqueList(backing);
        final HashSet<CountingValue> remove = new HashSet<>();
        for (int i = 0; i < 1_024; i++) {
            remove.add(new CountingValue(i));
        }
        assertTrue(actual.removeAll(remove));
        assertEquals(15_360, actual.size());
        assertTrue(remove.stream().anyMatch(e -> e.comparisons > 0));
    }
}
