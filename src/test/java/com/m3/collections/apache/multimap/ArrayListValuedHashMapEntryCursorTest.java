/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Array-backed iterator test contribution: Copyright (c) 2026 Synexia contributors.
package com.m3.collections.apache.multimap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Behavioral regression for the array-key, per-key fused entry cursor.
 * No entry-per-key snapshot is stored; duplicate value occurrences remain distinct.
 */
class ArrayListValuedHashMapEntryCursorTest {
    private static String pair(final Object key, final Object value) {
        return String.valueOf(key) + "=" + String.valueOf(value);
    }

    private static List<String> flattened(final Map<String, List<Integer>> model) {
        final List<String> result = new ArrayList<>();
        for (final Entry<String, List<Integer>> entry : model.entrySet()) {
            for (final Integer value : entry.getValue()) {
                result.add(pair(entry.getKey(), value));
            }
        }
        result.sort(null);
        return result;
    }

    private static List<String> entries(final ArrayListValuedHashMap<String, Integer> actual) {
        final List<String> result = new ArrayList<>();
        for (final Entry<String, Integer> entry : actual.entries()) {
            result.add(pair(entry.getKey(), entry.getValue()));
        }
        result.sort(null);
        return result;
    }

    @Test
    void emptyAndNullKeysAndRepeatedValues() {
        final ArrayListValuedHashMap<String, Integer> map = new ArrayListValuedHashMap<>();
        assertFalse(map.entries().iterator().hasNext());
        assertThrows(IllegalStateException.class, () -> map.entries().iterator().remove());
        map.put(null, null);
        map.put(null, null);
        map.put("same", 1);
        map.put("same", 1);
        map.put("other", null);
        final Map<String, List<Integer>> model = new HashMap<>();
        model.put(null, new ArrayList<>(java.util.Arrays.asList(null, null)));
        model.put("same", new ArrayList<>(java.util.Arrays.asList(1, 1)));
        model.put("other", new ArrayList<>(java.util.Arrays.asList((Integer) null)));
        assertEquals(flattened(model), entries(map));
        assertEquals(5, map.size());
    }

    @Test
    void iteratorRemovalKeepsKeyAndRemovesEmptyValueCollection() {
        final ArrayListValuedHashMap<String, Integer> map = new ArrayListValuedHashMap<>();
        for (int i = 0; i < 40; i++) {
            map.put("key" + i, i);
            map.put("key" + i, i + 40);
        }
        final Iterator<Entry<String, Integer>> iterator = map.entries().iterator();
        int removed = 0;
        while (iterator.hasNext()) {
            final Entry<String, Integer> entry = iterator.next();
            if (entry.getValue() >= 40) {
                iterator.remove();
                removed++;
                assertThrows(IllegalStateException.class, iterator::remove);
            }
        }
        assertEquals(40, removed);
        assertEquals(40, map.size());
        for (int i = 0; i < 40; i++) {
            assertEquals(java.util.Collections.singletonList(i), map.get("key" + i));
        }

        final Iterator<Entry<String, Integer>> drain = map.entries().iterator();
        while (drain.hasNext()) {
            drain.next();
            drain.remove();
        }
        assertEquals(0, map.size());
        assertTrue(map.asMap().isEmpty());
        assertFalse(map.entries().iterator().hasNext());
    }

    @Test
    void snapshotIgnoresNewKeysButReadsExistingValuesWhenReached() {
        final ArrayListValuedHashMap<String, Integer> map = new ArrayListValuedHashMap<>();
        map.put("old", 1);
        final Iterator<Entry<String, Integer>> iterator = map.entries().iterator();
        map.put("new", 2);
        assertTrue(iterator.hasNext());
        assertEquals("old", iterator.next().getKey());
        assertFalse(iterator.hasNext());
        assertEquals(2, map.entries().size());
    }

    @Test
    void randomizedEquivalenceWithIndependentValueLists() {
        final Random random = new Random(0x51A2A9L);
        final ArrayListValuedHashMap<String, Integer> actual = new ArrayListValuedHashMap<>();
        final Map<String, List<Integer>> model = new HashMap<>();
        for (int step = 0; step < 4_000; step++) {
            final String key = random.nextInt(13) == 0 ? null : "k" + random.nextInt(95);
            final Integer value = random.nextInt(7) == 0 ? null : random.nextInt(30);
            switch (random.nextInt(4)) {
                case 0:
                case 1:
                    actual.put(key, value);
                    model.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
                    break;
                case 2:
                    actual.removeMapping(key, value);
                    final List<Integer> list = model.get(key);
                    if (list != null) {
                        list.remove(value);
                        if (list.isEmpty()) {
                            model.remove(key);
                        }
                    }
                    break;
                default:
                    actual.remove(key);
                    model.remove(key);
                    break;
            }
            if ((step & 15) == 0) {
                assertEquals(flattened(model), entries(actual), "step=" + step);
            }
        }
        assertEquals(flattened(model), entries(actual));
        assertEquals(model.values().stream().mapToInt(List::size).sum(), actual.size());
    }
}
