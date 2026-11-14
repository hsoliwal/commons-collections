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
package com.m3.collections.apache.list;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Contract for ranked-array descent after AVL construction, mutation and iterator traversal. */
public final class TreeListRankedDescentTest {
    @Test
    void everyBalancedRankMatchesArrayList() {
        for (final int size : new int[] {0, 1, 2, 3, 4, 5, 7, 8, 31, 32, 33, 127, 1023, 8191}) {
            final List<Integer> reference = new ArrayList<>();
            for (int index = 0; index < size; index++) {
                reference.add(index % 17 == 0 ? null : Integer.valueOf(index));
            }
            final TreeList<Integer> actual = new TreeList<>(reference);
            assertSnapshot(reference, actual);
            assertThrows(IndexOutOfBoundsException.class, () -> actual.get(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> actual.get(actual.size()));
            final ListIterator<Integer> expectedIterator = reference.listIterator();
            final ListIterator<Integer> actualIterator = actual.listIterator();
            while (expectedIterator.hasNext()) {
                assertEquals(expectedIterator.next(), actualIterator.next());
            }
            while (expectedIterator.hasPrevious()) {
                assertEquals(expectedIterator.previous(), actualIterator.previous());
            }
        }
    }

    @Test
    void indexedLookupsSurviveRebalancingAndSlotReuse() {
        final Random random = new Random(0x5EEDAB11L);
        final List<Integer> reference = new ArrayList<>();
        final TreeList<Integer> actual = new TreeList<>();
        for (int step = 0; step < 12000; step++) {
            final int action = step % 7;
            if (reference.isEmpty() || action <= 2) {
                final int index = random.nextInt(reference.size() + 1);
                final Integer value = step % 11 == 0 ? null : Integer.valueOf(step);
                reference.add(index, value);
                actual.add(index, value);
            } else if (action == 3) {
                final int index = random.nextInt(reference.size());
                assertEquals(reference.remove(index), actual.remove(index));
            } else if (action == 4) {
                final int index = random.nextInt(reference.size());
                final Integer value = step % 13 == 0 ? null : Integer.valueOf(-step);
                assertEquals(reference.set(index, value), actual.set(index, value));
            } else if (action == 5) {
                final int index = random.nextInt(reference.size());
                assertEquals(reference.get(index), actual.get(index));
            } else {
                final int index = random.nextInt(reference.size());
                final ListIterator<Integer> expected = reference.listIterator(index);
                final ListIterator<Integer> observed = actual.listIterator(index);
                if (expected.hasNext()) {
                    assertEquals(expected.next(), observed.next());
                }
                if (expected.hasPrevious()) {
                    assertEquals(expected.previous(), observed.previous());
                }
            }
            if (step % 97 == 0) {
                assertSnapshot(reference, actual);
            }
        }
        assertSnapshot(reference, actual);
    }

    private static void assertSnapshot(List<Integer> expected, TreeList<Integer> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), "rank " + i);
        }
        assertArrayEquals(expected.toArray(), actual.toArray());
    }
}
