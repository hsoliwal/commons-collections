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
package com.m3.collections.apache.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

/** Contracts for projecting the flat owner's slots without materializing entries. */
class M3Flat3MapProjectionTest {

    @Test
    void keysAndValuesDoNotRetainPerElementEntries() throws Exception {
        final Flat3Map<String, String> map = new Flat3Map<>();
        map.put(null, null);
        map.put("second", "same");
        map.put("third", "same");
        for (final Iterator<?> iterator : Arrays.asList(map.keySet().iterator(), map.values().iterator())) {
            while (iterator.hasNext()) {
                iterator.next();
                for (Class<?> type = iterator.getClass(); type != Object.class; type = type.getSuperclass()) {
                    for (final Field field : type.getDeclaredFields()) {
                        if (Map.Entry.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            assertNull(field.get(iterator), "key/value traversal retained a per-element entry");
                        }
                    }
                }
            }
        }
    }

    @Test
    void slotProjectionsPreserveOrderLiveValuesAndRemoval() {
        final Flat3Map<String, Integer> map = new Flat3Map<>();
        map.put(null, null);
        map.put("second", 2);
        map.put("third", 3);
        final List<String> expectedKeys = new ArrayList<>();
        final List<Integer> expectedValues = new ArrayList<>();
        map.entrySet().forEach(entry -> {
            expectedKeys.add(entry.getKey());
            expectedValues.add(entry.getValue());
        });
        assertEquals(expectedKeys, new ArrayList<>(map.keySet()));
        assertEquals(expectedValues, new ArrayList<>(map.values()));

        final Iterator<Integer> values = map.values().iterator();
        assertThrows(IllegalStateException.class, values::remove);
        final String firstKey = expectedKeys.get(0);
        map.put(firstKey, 91);
        assertEquals(91, values.next());
        values.remove();
        assertFalse(map.containsKey(firstKey));
        assertThrows(IllegalStateException.class, values::remove);
        while (values.hasNext()) {
            values.next();
            values.remove();
        }
        assertTrue(map.isEmpty());
        assertThrows(NoSuchElementException.class, values::next);

        map.put("one", 1);
        map.put("two", 2);
        map.put("three", 3);
        final Iterator<String> keys = map.keySet().iterator();
        while (keys.hasNext()) {
            final String key = keys.next();
            keys.remove();
            assertFalse(map.containsKey(key));
        }
        assertTrue(map.isEmpty());
        assertThrows(NoSuchElementException.class, keys::next);
        assertThrows(IllegalStateException.class, keys::remove);
    }

    @Test
    void publicEntriesRemainDistinctAndTrackIteratorRemoval() {
        final Flat3Map<String, String> map = new Flat3Map<>();
        map.put("one", "first");
        map.put("two", "second");
        final Iterator<Map.Entry<String, String>> iterator = map.entrySet().iterator();
        final Map.Entry<String, String> first = iterator.next();
        final Map.Entry<String, String> second = iterator.next();
        assertNotSame(first, second);
        final String firstKey = first.getKey();
        first.setValue("updated");
        assertEquals("updated", map.get(firstKey));
        iterator.remove();
        assertThrows(IllegalStateException.class, second::getKey);
        assertThrows(IllegalStateException.class, second::getValue);
        assertThrows(IllegalStateException.class, () -> second.setValue("detached"));
        assertEquals(firstKey, first.getKey());
        assertThrows(IllegalStateException.class, iterator::remove);
    }

    @Test
    void delegatedAndSerializedMapsKeepProjectionContracts() throws Exception {
        for (int size = 0; size <= 5; size++) {
            final Flat3Map<Integer, Integer> original = new Flat3Map<>();
            for (int key = 0; key < size; key++) {
                original.put(key, key == 0 ? null : key);
            }
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject(original);
            }
            final Flat3Map<?, ?> restored;
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                restored = (Flat3Map<?, ?>) input.readObject();
            }
            assertEquals(original, restored);
            assertEquals(new ArrayList<>(original.keySet()), new ArrayList<>(restored.keySet()));
            assertEquals(new ArrayList<>(original.values()), new ArrayList<>(restored.values()));
            assertEquals(original, original.clone());
            final Iterator<?> iterator = restored.values().iterator();
            while (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
            assertTrue(restored.isEmpty());
        }
    }
}
