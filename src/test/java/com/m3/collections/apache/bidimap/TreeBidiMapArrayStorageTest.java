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
/*
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.bidimap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.AbstractMap;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.m3.collections.apache.OrderedMapIterator;

/** Contracts for the owning dual-index arrays and their caller-owned projections. */
class TreeBidiMapArrayStorageTest {

    private static final class Value implements Comparable<Value> {
        private final int order;
        private int hash;
        private int hashCalls;

        Value(final int order) {
            this.order = order;
            hash = order * 31;
        }

        @Override
        public int compareTo(final Value other) {
            return Integer.compare(order, other.order);
        }

        @Override
        public boolean equals(final Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            hashCalls++;
            return hash;
        }
    }

    private static Object field(final TreeBidiMap<?, ?> map, final String name) throws ReflectiveOperationException {
        final Field field = TreeBidiMap.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(map);
    }

    private static Integer put(final TreeMap<Integer, Integer> forward, final TreeMap<Integer, Integer> inverse,
            final Integer key, final Integer value) {
        final Integer previous = forward.remove(key);
        if (previous != null) {
            inverse.remove(previous);
        }
        final Integer oldKey = inverse.remove(value);
        if (oldKey != null) {
            forward.remove(oldKey);
        }
        forward.put(key, value);
        inverse.put(value, key);
        return previous;
    }

    private static Integer remove(final TreeMap<Integer, Integer> forward, final TreeMap<Integer, Integer> inverse,
            final Integer key) {
        final Integer previous = forward.remove(key);
        if (previous != null) {
            inverse.remove(previous);
        }
        return previous;
    }

    @Test
    void randomizedMutationsMatchTwoIndependentTreeMaps() {
        final TreeBidiMap<Integer, Integer> map = new TreeBidiMap<>();
        final TreeMap<Integer, Integer> forward = new TreeMap<>();
        final TreeMap<Integer, Integer> inverse = new TreeMap<>();
        final Random random = new Random(4412099L);
        for (int step = 0; step < 40000; step++) {
            final Integer key = random.nextInt(384);
            final Integer value = 10000 + random.nextInt(384);
            switch (random.nextInt(12)) {
            case 0:
            case 1:
            case 2:
            case 3:
                assertEquals(put(forward, inverse, key, value), map.put(key, value));
                break;
            case 4:
                final Integer oldKey = inverse.get(value);
                put(forward, inverse, key, value);
                assertEquals(oldKey, map.inverseBidiMap().put(value, key));
                break;
            case 5:
                assertEquals(remove(forward, inverse, key), map.remove(key));
                break;
            case 6:
                final Integer valueKey = inverse.get(value);
                if (valueKey != null) {
                    remove(forward, inverse, valueKey);
                }
                assertEquals(valueKey, map.removeValue(value));
                break;
            case 7:
                assertEquals(remove(forward, inverse, key) != null, map.keySet().remove(key));
                break;
            case 8:
                final boolean present = value.equals(forward.get(key));
                if (present) {
                    remove(forward, inverse, key);
                }
                assertEquals(present, map.entrySet().remove(new AbstractMap.SimpleImmutableEntry<>(key, value)));
                break;
            case 9:
                final Iterator<Integer> iterator = map.keySet().iterator();
                while (iterator.hasNext()) {
                    final Integer next = iterator.next();
                    if ((next & 7) == (step & 7)) {
                        iterator.remove();
                        remove(forward, inverse, next);
                    }
                }
                break;
            case 10:
                assertEquals(forward.get(key), map.get(key));
                assertEquals(inverse.get(value), map.getKey(value));
                break;
            default:
                if ((step & 255) == 0) {
                    map.clear();
                    forward.clear();
                    inverse.clear();
                }
                break;
            }
            if ((step & 31) == 0) {
                assertEquals(forward.size(), map.size());
                assertArrayEquals(forward.keySet().toArray(), map.keySet().toArray());
                assertArrayEquals(forward.values().toArray(), map.values().toArray());
                assertArrayEquals(inverse.keySet().toArray(), map.inverseBidiMap().keySet().toArray());
                assertEquals(forward, map);
                assertEquals(inverse, map.inverseBidiMap());
            }
        }
    }

    @Test
    void storageRetainsNoEntryObjectsAndReusesReleasedSlots() throws ReflectiveOperationException {
        final TreeBidiMap<Integer, Integer> map = new TreeBidiMap<>();
        for (int i = 0; i < 256; i++) {
            map.put(i, i + 10000);
        }
        assertTrue(field(map, "rootNode") instanceof int[]);
        assertNull(field(map, "entryHashPages"));
        final int capacity = ((Object[]) field(map, "keys")).length;
        final Object frontier = field(map, "nextSlot");
        for (int i = 0; i < 10000; i++) {
            final int key = i & 255;
            map.remove(key);
            map.put(key, key + 10000);
        }
        assertEquals(capacity, ((Object[]) field(map, "keys")).length);
        assertEquals(frontier, field(map, "nextSlot"));
        final Map.Entry<Integer, Integer> first = map.entrySet().iterator().next();
        final Map.Entry<Integer, Integer> second = map.entrySet().iterator().next();
        assertNotSame(first, second);
        assertEquals(first, second);
        for (final Field field : TreeBidiMap.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                final Object value = field.get(map);
                assertFalse(value instanceof Map.Entry);
                if (value instanceof Object[]) {
                    for (final Object element : (Object[]) value) {
                        assertFalse(element instanceof Map.Entry);
                    }
                }
            }
        }
    }

    @Test
    void releasedPayloadsAndClearDoNotRetainOldMappings() throws ReflectiveOperationException {
        final TreeBidiMap<Value, Value> map = new TreeBidiMap<>();
        final Value key = new Value(1);
        final Value value = new Value(11);
        map.put(key, value);
        map.put(new Value(2), new Value(12));
        final Map.Entry<Value, Value> entry = map.entrySet().iterator().next();
        map.remove(key);
        for (final Object element : (Object[]) field(map, "keys")) {
            assertNotSame(key, element);
        }
        for (final Object element : (Object[]) field(map, "values")) {
            assertNotSame(value, element);
        }
        map.put(new Value(3), new Value(13));
        assertSame(key, entry.getKey());
        assertSame(value, entry.getValue());
        assertThrows(UnsupportedOperationException.class, () -> entry.setValue(value));
        map.clear();
        assertEquals(0, ((Object[]) field(map, "keys")).length);
        assertEquals(0, ((Object[]) field(map, "values")).length);
        assertEquals(0, ((int[]) field(map, "leftKey")).length);
        assertNull(field(map, "entryHashPages"));
        assertNull(field(map, "currentHashPage"));
    }

    @Test
    void liveProjectionsShareLazyHashesWithoutRetainedEntryObjects() {
        final TreeBidiMap<Value, Value> map = new TreeBidiMap<>();
        final Value key = new Value(1);
        final Value value = new Value(2);
        map.put(key, value);
        final Map.Entry<Value, Value> first = map.entrySet().iterator().next();
        final Map.Entry<Value, Value> second = map.entrySet().iterator().next();
        assertEquals(0, key.hashCalls);
        assertEquals(0, value.hashCalls);
        final int hash = first.hashCode();
        assertEquals(1, key.hashCalls);
        assertEquals(1, value.hashCalls);
        key.hash = 990;
        value.hash = 770;
        assertEquals(hash, second.hashCode());
        assertEquals(hash, map.entrySet().hashCode());
        assertEquals(1, key.hashCalls);
        assertEquals(1, value.hashCalls);
        assertEquals(key.hash ^ value.hash, map.hashCode());
        assertEquals(2, key.hashCalls);
        map.remove(key);
        map.put(new Value(3), new Value(4));
        assertEquals(hash, first.hashCode());
        assertEquals(hash, second.hashCode());
    }

    @Test
    void mapIteratorRetainsItsCurrentPayloadAfterExternalSlotReuse() {
        final TreeBidiMap<Integer, Integer> map = new TreeBidiMap<>();
        map.put(1, 101);
        map.put(2, 102);
        final OrderedMapIterator<Integer, Integer> iterator = map.mapIterator();
        assertEquals(Integer.valueOf(1), iterator.next());
        map.remove(1);
        map.put(3, 103);
        assertEquals(Integer.valueOf(1), iterator.getKey());
        assertEquals(Integer.valueOf(101), iterator.getValue());
        assertThrows(ConcurrentModificationException.class, iterator::next);
        assertThrows(ConcurrentModificationException.class, iterator::previous);
        assertThrows(ConcurrentModificationException.class, iterator::remove);
    }

    @Test
    void inverseEntryProjectionsRemainIndependentAndImmutable() {
        final TreeBidiMap<Integer, Integer> map = new TreeBidiMap<>();
        map.put(1, 101);
        final Map.Entry<Integer, Integer> entry = map.inverseBidiMap().entrySet().iterator().next();
        assertNotSame(entry, map.inverseBidiMap().entrySet().iterator().next());
        map.clear();
        map.put(2, 102);
        assertEquals(Integer.valueOf(101), entry.getKey());
        assertEquals(Integer.valueOf(1), entry.getValue());
        assertThrows(UnsupportedOperationException.class, () -> entry.setValue(3));
        assertNull(map.get(1));
    }

    @Test
    void detachedProjectionsStillShareAnUncomputedHashAfterSlotReuse() {
        final TreeBidiMap<Value, Value> map = new TreeBidiMap<>();
        final Value key = new Value(1);
        final Value value = new Value(2);
        map.put(key, value);
        final Map.Entry<Value, Value> first = map.entrySet().iterator().next();
        final Map.Entry<Value, Value> second = map.entrySet().iterator().next();
        map.remove(key);
        map.put(new Value(3), new Value(4));
        final int hash = first.hashCode();
        key.hash = 909;
        value.hash = 707;
        assertEquals(hash, second.hashCode());
        assertEquals(1, key.hashCalls);
        assertEquals(1, value.hashCalls);
    }

    @Test
    void hashPagesContainOnlyBoundedPrimitiveState() throws ReflectiveOperationException {
        final TreeBidiMap<Integer, Integer> map = new TreeBidiMap<>();
        for (int i = 0; i < 64; i++) {
            map.put(i, i + 10000);
        }
        final Map.Entry<Integer, Integer> saved = map.entrySet().iterator().next();
        for (int i = 0; i < 3000; i++) {
            final int key = 1 + i % 63;
            map.remove(key);
            map.put(key, key + 10000);
            for (final Map.Entry<Integer, Integer> entry : map.entrySet()) {
                assertEquals(Integer.valueOf(entry.getKey() + 10000), entry.getValue());
            }
        }
        final Object[] pages = (Object[]) field(map, "entryHashPages");
        final java.util.Set<Object> distinct = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (final Object page : pages) {
            if (page != null) {
                distinct.add(page);
                for (final Field pageField : page.getClass().getDeclaredFields()) {
                    pageField.setAccessible(true);
                    if (!pageField.getType().isPrimitive()) {
                        assertEquals(int[].class, pageField.getType());
                        assertEquals(8, ((int[]) pageField.get(page)).length);
                    }
                }
            }
        }
        assertTrue(distinct.size() <= map.size());
        assertEquals(Integer.valueOf(0), saved.getKey());
        map.clear();
        assertNull(field(map, "entryHashPages"));
        assertNull(field(map, "currentHashPage"));
    }

}
