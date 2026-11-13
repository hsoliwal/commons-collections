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
// Modified 2026 by Hitesh Soliwal and Synexia contributors:
// qualify nonrecursive traversal of array-owned threaded AVL slots.
package com.m3.collections.apache.list;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** In-order, callback and recycled-slot contracts for the primitive AVL store. */
class TreeListThreadedIndexLookupTest {

    private static final class Tracked {
        private final int id;
        private final int ordinal;
        private final List<Integer> trace;

        Tracked(int id, int ordinal, List<Integer> trace) {
            this.id = id;
            this.ordinal = ordinal;
            this.trace = trace;
        }

        @Override
        public boolean equals(Object other) {
            trace.add(ordinal);
            return other instanceof Tracked && ((Tracked) other).id == id;
        }

        @Override
        public int hashCode() {
            return id;
        }
    }

    private static int firstStoredEqual(List<?> values, Object candidate) {
        for (int index = 0; index < values.size(); index++) {
            if (Objects.equals(values.get(index), candidate)) {
                return index;
            }
        }
        return -1;
    }

    @Test
    void callbackOrderAndFirstDuplicateMatchExistingRecursiveLookup() {
        for (int size : new int[] {0, 1, 2, 3, 7, 31, 64, 127, 1023}) {
            ArrayList<Integer> calls = new ArrayList<>();
            List<Tracked> reference = new ArrayList<>();
            TreeList<Tracked> actual = new TreeList<>();
            for (int index = 0; index < size; index++) {
                Tracked value = index % 29 == 0
                        ? null : new Tracked(index % 11, index, calls);
                reference.add(value);
                actual.add(value);
            }
            for (int query = -1; query < 13; query++) {
                Tracked candidate = query == -1 ? null : new Tracked(query, -1, calls);
                calls.clear();
                int expected = firstStoredEqual(reference, candidate);
                List<Integer> expectedTrace = new ArrayList<>(calls);
                calls.clear();
                assertEquals(expected, actual.indexOf(candidate),
                        "size=" + size + " query=" + query);
                assertEquals(expectedTrace, calls,
                        "stored-value equals order size=" + size + " query=" + query);
            }
        }
    }

    @Test
    void randomizedSlotReuseAndIndexedMutationMatchArrayList() {
        Random random = new Random(0x4d33545245454cL);
        for (int seed = 0; seed < 12; seed++) {
            TreeList<Integer> actual = new TreeList<>();
            List<Integer> expected = new ArrayList<>();
            for (int step = 0; step < 3500; step++) {
                Integer value = random.nextInt(17) == 0
                        ? null : random.nextInt(73) - 36;
                int operation = random.nextInt(9);
                if ((operation < 4 || expected.isEmpty()) && expected.size() < 256) {
                    int index = random.nextInt(expected.size() + 1);
                    actual.add(index, value);
                    expected.add(index, value);
                }
                else if (operation == 4 && !expected.isEmpty()) {
                    int index = random.nextInt(expected.size());
                    assertEquals(expected.remove(index), actual.remove(index));
                }
                else if (operation == 5 && !expected.isEmpty()) {
                    int index = random.nextInt(expected.size());
                    assertEquals(expected.set(index, value), actual.set(index, value));
                }
                else if (operation == 6 && (step & 127) == 0) {
                    actual.clear();
                    expected.clear();
                }
                else if (operation == 7 && !expected.isEmpty()) {
                    ListIterator<Integer> old = expected.listIterator();
                    ListIterator<Integer> candidate = actual.listIterator();
                    assertEquals(old.next(), candidate.next());
                    old.remove();
                    candidate.remove();
                }
                else if (operation == 8 && expected.size() < 253) {
                    List<Integer> appended = Arrays.asList(value, value, null);
                    assertTrue(actual.addAll(appended));
                    expected.addAll(appended);
                }
                assertEquals(expected.size(), actual.size());
                if ((step & 31) == 0) {
                    for (Integer needle : new Integer[] {null, -36, -1, 0, 1, 36, 71}) {
                        assertEquals(expected.indexOf(needle), actual.indexOf(needle),
                                "seed=" + seed + " step=" + step);
                    }
                }
            }
            assertEquals(expected, actual);
        }
    }

    @Test
    void missingElementsAndSubclassesRetainNullAndBoundaries() {
        TreeList<Integer> actual = new TreeList<>();
        assertEquals(-1, actual.indexOf(null));
        for (int index = 0; index < 8192; index++) {
            actual.add(index % 113);
        }
        assertEquals(112, actual.indexOf(112));
        assertEquals(-1, actual.indexOf(998877));
        actual.add(0, null);
        assertEquals(0, actual.indexOf(null));
        TreeList<Integer> derived = new TreeList<Integer>() { };
        derived.addAll(actual);
        assertEquals(actual.indexOf(112), derived.indexOf(112));
        assertEquals(actual.indexOf(998877), derived.indexOf(998877));
    }

    @Test
    void exceptionalStoredEqualityPropagatesWithoutChangingOwner() {
        final class ThrowsOnEquals {
            @Override
            public boolean equals(Object other) {
                throw new IllegalStateException("stored equals failed");
            }
        }
        TreeList<Object> actual = new TreeList<>();
        Object first = new Object();
        actual.add(first);
        actual.add(new ThrowsOnEquals());
        actual.add(new Object());
        assertThrows(IllegalStateException.class, () -> actual.indexOf(new Object()));
        assertEquals(3, actual.size());
        assertTrue(actual.get(0) == first);
    }
}
