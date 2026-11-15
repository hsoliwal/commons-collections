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
package com.m3.collections.apache.set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ListOrderedSetBulkRemovalTest {

    private static boolean originalRemoveAll(final List<?> order, final Set<?> members,
            final Collection<?> removed) {
        boolean changed = false;
        for (final Object value : removed) {
            if (members.remove(value)) {
                order.remove(value);
                changed = true;
            }
        }
        return changed;
    }

    @Test
    void removeAllSelfClearsBothOwnersAndAllowsReinsertion() {
        for (final int count : new int[] {0, 1, 2, 63, 8192}) {
            final ListOrderedSet<Integer> actual = new ListOrderedSet<>();
            for (int i = 0; i < count; i++) {
                actual.add(i == 0 ? null : i);
            }
            final Iterator<Integer> stale = actual.iterator();
            assertEquals(count != 0, actual.removeAll(actual));
            assertTrue(actual.isEmpty());
            assertTrue(actual.asList().isEmpty());
            if (count != 0) {
                assertThrows(ConcurrentModificationException.class, stale::next);
            }
            assertFalse(actual.removeAll(actual));
            assertTrue(actual.add(null));
            assertTrue(actual.add(1));
            assertEquals(Arrays.asList(null, 1), actual.asList());
        }
    }

    @Test
    void denseRemovalMatchesSerialOrderIdentityAndHashTableState() {
        final Random random = new Random(0x51a7eL);
        for (final int count : new int[] {0, 1, 127, 8191, 8192, 8193, 16384}) {
            for (int mode = 0; mode < 4; mode++) {
                final ArrayList<Object> order = new ArrayList<>();
                final HashSet<Object> members = new HashSet<>();
                final ListOrderedSet<Object> actual = ListOrderedSet.listOrderedSet(members, order);
                for (int i = 0; i < count; i++) {
                    actual.add(i == 0 ? null : mode == 0 ? Long.valueOf(i) : "key-" + i);
                }
                final List<Object> expectedOrder = new ArrayList<>(order);
                final Set<Object> expectedMembers = new HashSet<>(members);
                final HashSet<Object> removed = new HashSet<>();
                for (int i = 0; i < 2048; i++) {
                    final int selected = mode == 3 ? count + i : random.nextInt(count + 2048);
                    removed.add(selected == 0 ? null : mode == 0 ? Long.valueOf(selected) : "key-" + selected);
                }
                final boolean expected = originalRemoveAll(expectedOrder, expectedMembers, removed);
                final Iterator<Object> stale = actual.iterator();
                assertEquals(expected, actual.removeAll(removed));
                assertEquals(expectedOrder, order);
                assertEquals(expectedMembers, members);
                assertEquals(new ArrayList<>(expectedMembers), new ArrayList<>(members));
                for (int i = 0; i < order.size(); i++) {
                    assertSame(expectedOrder.get(i), actual.get(i));
                }
                if (expected) {
                    assertThrows(ConcurrentModificationException.class, stale::next);
                }
                assertFalse(actual.removeAll(removed));
            }
        }
    }

    @Test
    void externallyCorruptedOwnerRetainsSerialSemantics() {
        final ArrayList<String> order = new ArrayList<>();
        final HashSet<String> members = new HashSet<>();
        final ListOrderedSet<String> actual = ListOrderedSet.listOrderedSet(members, order);
        for (int i = 0; i < 8192; i++) {
            actual.add("key-" + i);
        }
        order.set(0, order.get(1));
        final List<String> expectedOrder = new ArrayList<>(order);
        final Set<String> expectedMembers = new HashSet<>(members);
        final HashSet<String> removed = new HashSet<>();
        removed.add("key-0");
        for (int i = 0; i < 1024; i++) {
            removed.add("absent-" + i);
        }
        assertEquals(originalRemoveAll(expectedOrder, expectedMembers, removed), actual.removeAll(removed));
        assertEquals(expectedOrder, order);
        assertEquals(expectedMembers, members);
    }

    @Test
    void subclassRemovalAndUnknownSourceKeepEncounterOrder() {
        final class Observed extends ListOrderedSet<Integer> {
            private static final long serialVersionUID = 1L;
            private final List<Object> calls = new ArrayList<>();

            @Override
            public boolean remove(final Object value) {
                calls.add(value);
                return super.remove(value);
            }
        }
        final Observed actual = new Observed();
        for (int i = 0; i < 8192; i++) {
            actual.add(i);
        }
        final HashSet<Integer> removed = new HashSet<>();
        for (int i = 0; i < 2048; i++) {
            removed.add(i * 2);
        }
        assertTrue(actual.removeAll(removed));
        assertEquals(new ArrayList<>(removed), actual.calls);
    }

    @Test
    void throwingSourceKeepsSuccessfulPrefixAndOriginalException() {
        final ListOrderedSet<Integer> actual = new ListOrderedSet<>();
        actual.addAll(Arrays.asList(1, 2, 3));
        final IllegalStateException failure = new IllegalStateException("source");
        final Collection<Integer> removed = new AbstractCollection<Integer>() {
            @Override
            public int size() {
                return 3;
            }

            @Override
            public Iterator<Integer> iterator() {
                return new Iterator<Integer>() {
                    private int cursor;

                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public Integer next() {
                        if (cursor++ == 0) {
                            return 2;
                        }
                        throw failure;
                    }
                };
            }
        };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> actual.removeAll(removed)));
        assertEquals(Arrays.asList(1, 3), actual.asList());
        assertFalse(actual.contains(2));
        assertThrows(NullPointerException.class, () -> actual.removeAll(null));
    }

    @Test
    void userHashAndEqualityCallbacksRemainOnSerialPath() {
        final List<String> events = new ArrayList<>();
        final class Value {
            private final int id;

            Value(final int id) {
                this.id = id;
            }

            @Override
            public int hashCode() {
                events.add("hash:" + id);
                return id;
            }

            @Override
            public boolean equals(final Object other) {
                events.add("equals:" + id);
                return other instanceof Value && ((Value) other).id == id;
            }
        }
        final ListOrderedSet<Object> actual = new ListOrderedSet<>();
        for (int i = 0; i < 8192; i++) {
            actual.add(i == 8191 ? new Value(i) : i);
        }
        final List<Object> expectedOrder = new ArrayList<>(actual.asList());
        final Set<Object> expectedMembers = new HashSet<>(actual);
        final HashSet<Object> removed = new HashSet<>();
        removed.add(new Value(8191));
        for (int i = 0; i < 1024; i++) {
            removed.add(i);
        }
        events.clear();
        final boolean expected = originalRemoveAll(expectedOrder, expectedMembers, removed);
        final List<String> expectedEvents = new ArrayList<>(events);
        events.clear();
        assertEquals(expected, actual.removeAll(removed));
        assertEquals(expectedEvents, events);
        assertEquals(expectedOrder, actual.asList());
    }
}
