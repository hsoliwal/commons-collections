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
// Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.
package com.m3.collections.apache.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Contract tests for the original array-backed AbstractHashedMap capacity and clear paths. */
class AbstractHashedMapArrayHotPathTest {
    private static final int MAX_CAP = 1 << 30;

    private static final class Probe extends HashedMap<Integer, Integer> {
        private static final long serialVersionUID = 1L;
        Probe(int initialCapacity) { super(initialCapacity); }
        int round(int suggested) { return calculateNewCapacity(suggested); }
        int buckets() { return data.length; }
        int changes() { return modCount; }
        boolean allHeadsEmpty() {
            for (Object entry : data) {
                if (entry != null) return false;
            }
            return true;
        }
    }

    /** Original loop is retained as a completely independent capacity reference. */
    private static int referenceCapacity(int suggested) {
        int capacity = 1;
        if (suggested > MAX_CAP) {
            capacity = MAX_CAP;
        } else {
            while (capacity < suggested) capacity <<= 1;
            if (capacity > MAX_CAP) capacity = MAX_CAP;
        }
        return capacity;
    }

    @Test
    void capacityBoundaryAndRandomDifferential() {
        Probe map = new Probe(1);
        int[] special = {Integer.MIN_VALUE, -1, 0, 1, 2, 3, 4, 5, MAX_CAP - 1,
                MAX_CAP, MAX_CAP + 1, Integer.MAX_VALUE};
        for (int value : special) assertEquals(referenceCapacity(value), map.round(value));
        for (int bit = 0; bit <= 30; ++bit) {
            int capacity = 1 << bit;
            for (long delta = -2; delta <= 2; ++delta) {
                long proposed = (long) capacity + delta;
                if (proposed <= Integer.MAX_VALUE && proposed >= Integer.MIN_VALUE) {
                    int input = (int) proposed;
                    assertEquals(referenceCapacity(input), map.round(input));
                }
            }
        }
        Random random = new Random(0x5A7B10L);
        for (int i = 0; i < 200_000; ++i) {
            int proposed = random.nextInt();
            assertEquals(referenceCapacity(proposed), map.round(proposed));
        }
        assertEquals(1, map.buckets());
    }

    @Test
    void emptyClearRetainsArrayAndAdvancesFailFastCounter() {
        Probe map = new Probe(1 << 15);
        int initial = map.changes();
        Object backing = map.data;
        map.clear();
        assertSame(backing, map.data);
        assertEquals(initial + 1, map.changes());
        assertEquals(0, map.size());
        assertTrue(map.allHeadsEmpty());
        for (int i = 0; i < 128; ++i) map.clear();
        assertSame(backing, map.data);
        assertEquals(initial + 129, map.changes());
    }

    @Test
    void nonEmptyClearReleasesAllBucketReferencesAndReusesMap() {
        Probe map = new Probe(128);
        for (int i = 0; i < 300; ++i) map.put(i, i * 7);
        assertEquals(300, map.size());
        map.clear();
        assertTrue(map.allHeadsEmpty());
        assertEquals(0, map.size());
        assertNull(map.get(42));
        map.put(42, 294);
        assertEquals(Integer.valueOf(294), map.get(42));
        assertEquals(1, map.size());
        map.remove(42);
        assertEquals(0, map.size());
        map.clear();
        assertTrue(map.allHeadsEmpty());
    }

    @Test
    void randomizedMapAndClearParity() {
        Probe map = new Probe(16);
        Map<Integer, Integer> reference = new HashMap<>();
        Random random = new Random(20261114);
        for (int iteration = 0; iteration < 25_000; ++iteration) {
            Integer key = random.nextInt(140) == 0 ? null : random.nextInt(350) - 175;
            Integer value = random.nextInt(70) == 0 ? null : random.nextInt();
            switch (random.nextInt(5)) {
                case 0:
                case 1:
                    assertEquals(reference.put(key, value), map.put(key, value));
                    break;
                case 2:
                    assertEquals(reference.remove(key), map.remove(key));
                    break;
                case 3:
                    assertEquals(reference.get(key), map.get(key));
                    break;
                default:
                    reference.clear();
                    map.clear();
                    break;
            }
            assertEquals(reference.size(), map.size());
            assertEquals(reference, new HashMap<>(map));
        }
    }
}
