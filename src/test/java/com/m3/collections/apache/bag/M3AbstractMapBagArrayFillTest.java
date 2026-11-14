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
package com.m3.collections.apache.bag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests the contiguous array-store paths without changing the Bag contract. */
class M3AbstractMapBagArrayFillTest {

    @Test
    void repeatedAndNullMembersKeepExactCardinalitiesInBothExports() {
        final HashBag<Object> bag = new HashBag<>();
        bag.add(null, 64);
        bag.add("alpha", 1_024);
        bag.add("beta", 513);
        final Map<Object, Integer> expected = new HashMap<>();
        expected.put(null, 64);
        expected.put("alpha", 1_024);
        expected.put("beta", 513);

        assertEquals(expected, counts(bag.toArray()));
        assertEquals(expected, counts(bag.toArray(new Object[0])));
        final Object[] existing = new Object[bag.size() + 9];
        Arrays.fill(existing, "old");
        assertSame(existing, bag.toArray(existing));
        assertEquals(expected, counts(Arrays.copyOf(existing, bag.size())));
        for (int i = bag.size(); i < existing.length; i++) {
            assertNull(existing[i]);
        }
    }

    @Test
    void sortedBagOrderAndTypedExportStayIdentical() {
        final TreeBag<String> bag = new TreeBag<>();
        bag.add("c", 2);
        bag.add("a", 3);
        bag.add("b");
        final String[] expected = { "a", "a", "a", "b", "c", "c" };
        assertArrayEquals(expected, bag.toArray());
        assertArrayEquals(expected, bag.toArray(new String[0]));
    }

    @Test
    void incompatibleTypedDestinationStillThrowsArrayStoreException() {
        final HashBag<Object> bag = new HashBag<>();
        bag.add("valid", 3);
        bag.add(123, 2);
        assertThrows(ArrayStoreException.class, () -> bag.toArray(new String[0]));
    }

    @Test
    void emptyBagClearsEverySlotOfOversizedTypedArray() {
        final HashBag<String> bag = new HashBag<>();
        final String[] existing = { "one", "two", "three" };
        assertSame(existing, bag.toArray(existing));
        assertArrayEquals(new String[3], existing);
        assertEquals(0, bag.toArray().length);
    }

    @Test
    void overriddenNonPositiveCountRetainsOriginalNoWriteBehavior() {
        final HashBag<String> bag = new HashBag<String>() {
            @Override
            public int getCount(final Object object) {
                return -1;
            }
        };
        bag.add("ignored", 2);
        assertArrayEquals(new Object[2], bag.toArray());
        assertArrayEquals(new String[2], bag.toArray(new String[0]));
    }

    private static Map<Object, Integer> counts(final Object[] elements) {
        final Map<Object, Integer> result = new HashMap<>();
        for (final Object element : elements) {
            result.merge(element, 1, Integer::sum);
        }
        return result;
    }
}
