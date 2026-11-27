/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.collections4.list;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ListIterator;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Verifies exact-owner slot traversal against the inherited List methods. */
class TreeListSlotOperationsTest {

    private static void check(final boolean matches, final String detail) {
        if (!matches) {
            throw new AssertionError(detail);
        }
    }

    @Test
    void replaceAllPreservesValuesAndNonStructuralIteratorState() {
        final Random random = new Random(0x7A155EEDL);
        for (final int n : new int[] {0, 1, 127, 1024, 8192}) {
            final ArrayList<Integer> values = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                values.add(random.nextInt(29) == 0 ? null : random.nextInt(400));
            }
            final TreeList<Integer> reference = new TreeList<>(values);
            final TreeList<Integer> actual = new TreeList<>(values);
            final ListIterator<Integer> iterator = reference.listIterator();
            while (iterator.hasNext()) {
                final Integer old = iterator.next();
                iterator.set(old == null ? null : old + 1);
            }
            actual.replaceAll(old -> old == null ? null : old + 1);
            check(Arrays.equals(reference.toArray(), actual.toArray()), "replaceAll values n=" + n);
            check(actual.size() == n, "replaceAll size n=" + n);
        }
    }

    private static boolean inheritedEqual(final TreeList<?> left, final TreeList<?> right) {
        final ListIterator<?> first = left.listIterator();
        final ListIterator<?> second = right.listIterator();
        while (first.hasNext() && second.hasNext()) {
            final Object a = first.next();
            final Object b = second.next();
            if (!(a == null ? b == null : a.equals(b))) {
                return false;
            }
        }
        return !first.hasNext() && !second.hasNext();
    }

    @Test
    void equalsPreservesOrderedComparisonsAndMismatches() {
        final Random random = new Random(0x555A41L);
        for (final int n : new int[] {0, 1, 128, 1024, 16384}) {
            final ArrayList<Integer> values = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                values.add(random.nextInt(20) == 0 ? null : random.nextInt(400));
            }
            final TreeList<Integer> left = new TreeList<>(values);
            final TreeList<Integer> right = new TreeList<>(values);
            check(inheritedEqual(left, right) == left.equals(right), "exact equals n=" + n);
            if (n != 0) {
                right.set(n - 1, 9999);
                check(inheritedEqual(left, right) == left.equals(right), "trailing mismatch n=" + n);
                right.set(0, 9998);
                check(inheritedEqual(left, right) == left.equals(right), "early mismatch n=" + n);
            }
        }
    }

    public static void main(final String[] args) {
        final TreeListSlotOperationsTest tests = new TreeListSlotOperationsTest();
        tests.replaceAllPreservesValuesAndNonStructuralIteratorState();
        tests.equalsPreservesOrderedComparisonsAndMismatches();
        System.out.println("TREE_SLOT_OPERATIONS_PASS");
    }
}
