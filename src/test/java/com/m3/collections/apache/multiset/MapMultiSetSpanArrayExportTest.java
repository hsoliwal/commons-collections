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
package com.m3.collections.apache.multiset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import org.junit.jupiter.api.Test;

/** Exact typed and object-array behavior of counted multiset span emission. */
class MapMultiSetSpanArrayExportTest {

    @Test
    void repeatedObjectsAndNullsMatchIteratorOrderAndIdentity() {
        final HashMultiSet<Object> set = new HashMultiSet<>();
        final Object shared = new Object();
        set.add(shared, 256);
        set.add(null, 11);
        final Object[] actual = set.toArray();
        final Object[] expected = new ArrayList<>(set).toArray();
        assertArrayEquals(expected, actual);
        assertEquals(267, actual.length);
        for (Object item : actual) {
            if (item != null) {
                assertSame(shared, item);
            }
        }
    }

    @Test
    void typedArrayFillsRunsAndTheEntireUnusedSuffix() {
        final TreeMultiSet<String> set = new TreeMultiSet<>();
        set.add("b", 17);
        set.add("a", 2);
        final String[] array = new String[29];
        Arrays.fill(array, "old");
        assertSame(array, set.toArray(array));
        for (int i = 0; i < 2; i++) {
            assertEquals("a", array[i]);
        }
        for (int i = 2; i < 19; i++) {
            assertEquals("b", array[i]);
        }
        for (int i = 19; i < array.length; i++) {
            assertEquals(null, array[i]);
        }
        final String[] tooSmall = new String[3];
        final String[] replacement = set.toArray(tooSmall);
        assertNotSame(tooSmall, replacement);
        assertArrayEquals(Arrays.copyOf(array, 19), replacement);
    }

    @Test
    void incompatibleTypedArrayPreservesPartialWrites() {
        final Comparator<Object> stringsFirst = (left, right) ->
                Boolean.compare(left instanceof Integer, right instanceof Integer);
        final TreeMultiSet<Object> set = new TreeMultiSet<>(stringsFirst);
        set.add("ok", 12);
        set.add(Integer.valueOf(5), 12);
        final String[] array = new String[28];
        Arrays.fill(array, "sentinel");
        assertThrows(ArrayStoreException.class, () -> set.toArray(array));
        for (int i = 0; i < 12; i++) {
            assertEquals("ok", array[i]);
        }
        for (int i = 12; i < array.length; i++) {
            assertEquals("sentinel", array[i]);
        }
    }

    @Test
    void smallCountsAndEmptyArraysStayCompatible() {
        final HashMultiSet<String> set = new HashMultiSet<>();
        assertArrayEquals(new Object[0], set.toArray());
        final String[] dst = {"stale", "stale"};
        assertSame(dst, set.toArray(dst));
        assertArrayEquals(new String[] {null, null}, dst);
        set.add("small", 7);
        assertArrayEquals(new ArrayList<>(set).toArray(), set.toArray());
        assertArrayEquals(new ArrayList<>(set).toArray(new String[0]), set.toArray(new String[0]));
    }
}
