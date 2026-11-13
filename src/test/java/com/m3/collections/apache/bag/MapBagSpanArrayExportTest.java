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
package com.m3.collections.apache.bag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Verifies counted run export without altering typed destination failure prefixes. */
class MapBagSpanArrayExportTest {

    @Test
    void duplicateRunsPreserveIteratorOrderAndObjectIdentity() {
        final HashBag<Object> bag = new HashBag<>();
        final Object repeated = new Object();
        bag.add(repeated, 512);
        bag.add(null, 17);
        final Object[] fromIterator = new ArrayList<>(bag).toArray();
        final Object[] result = bag.toArray();
        assertArrayEquals(fromIterator, result);
        assertEquals(529, result.length);
        for (Object item : result) {
            if (item != null) {
                assertSame(repeated, item);
            }
        }
    }

    @Test
    void typedDestinationPreservesFullTrailingNullingAndIdentity() {
        final TreeBag<String> bag = new TreeBag<>();
        bag.add("b", 12);
        bag.add("a", 3);
        final String[] destination = new String[24];
        Arrays.fill(destination, "sentinel");
        final String[] returned = bag.toArray(destination);
        assertSame(destination, returned);
        for (int i = 0; i < 3; i++) {
            assertEquals("a", destination[i]);
        }
        for (int i = 3; i < 15; i++) {
            assertEquals("b", destination[i]);
        }
        for (int i = 15; i < destination.length; i++) {
            assertEquals(null, destination[i]);
        }

        final String[] tooSmall = new String[4];
        final String[] allocated = bag.toArray(tooSmall);
        assertNotSame(tooSmall, allocated);
        assertArrayEquals(Arrays.copyOf(destination, 15), allocated);
    }

    @Test
    void typedArrayStoreFailurePreservesSuccessfulPrefix() {
        final Comparator<Object> stringsFirst = (left, right) ->
                Boolean.compare(left instanceof Integer, right instanceof Integer);
        final TreeBag<Object> bag = new TreeBag<>(stringsFirst);
        bag.add("compatible", 9);
        bag.add(Integer.valueOf(42), 8);
        final String[] destination = new String[32];
        Arrays.fill(destination, "sentinel");

        assertThrows(ArrayStoreException.class, () -> bag.toArray(destination));
        for (int i = 0; i < 9; i++) {
            assertEquals("compatible", destination[i]);
        }
        for (int i = 9; i < destination.length; i++) {
            assertEquals("sentinel", destination[i]);
        }
    }

    @Test
    void zeroAndSmallRunsRetainOriginalExpansionAndNullBehavior() {
        final HashBag<String> bag = new HashBag<>();
        assertArrayEquals(new Object[0], bag.toArray());
        final String[] destination = {"old", "old", "old"};
        assertSame(destination, bag.toArray(destination));
        assertArrayEquals(new String[]{null, null, null}, destination);

        for (int i = 0; i < 7; i++) {
            bag.add("small", 1);
        }
        final List<String> expected = new ArrayList<>(bag);
        assertArrayEquals(expected.toArray(), bag.toArray());
        assertArrayEquals(expected.toArray(new String[0]), bag.toArray(new String[0]));
    }
}
