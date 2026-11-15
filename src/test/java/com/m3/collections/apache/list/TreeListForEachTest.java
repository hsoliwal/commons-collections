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
// Copyright (c) 2026 Hitesh Soliwal and Synexia contributors.
package com.m3.collections.apache.list;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/** Preserves iterator callback, identity, mutation and failure contracts. */
class TreeListForEachTest {

    private static final class ProjectedList extends TreeList<String> {
        private int iteratorCalls;

        @Override
        public Iterator<String> iterator() {
            iteratorCalls++;
            return Arrays.asList("projected-one", "projected-two").iterator();
        }
    }

    private static Class<?> traverse(final TreeList<Integer> list,
            final Consumer<Integer> action, final boolean direct) {
        try {
            if (direct) {
                list.forEach(action);
            } else {
                for (final Integer value : list) {
                    action.accept(value);
                }
            }
            return null;
        } catch (final RuntimeException failure) {
            return failure.getClass();
        }
    }

    private static void mutate(final TreeList<Integer> list, final int operation) {
        switch (operation) {
        case 0:
            list.add(100);
            break;
        case 1:
            list.remove(0);
            break;
        case 2:
            list.clear();
            break;
        case 3:
            list.set(list.size() - 1, 100);
            break;
        case 4:
            list.subList(1, list.size()).clear();
            break;
        default:
            list.clear();
            list.add(100);
            break;
        }
    }

    @Test
    void randomizedSlotReuseRetainsEncounterOrderAndNulls() {
        final Random random = new Random(0x44504f4fL);
        final TreeList<Integer> list = new TreeList<>();
        final List<Integer> reference = new ArrayList<>();
        for (int step = 0; step < 2500; step++) {
            if (reference.isEmpty() || random.nextBoolean()) {
                final int index = random.nextInt(reference.size() + 1);
                final Integer value = step % 11 == 0 ? null : step;
                reference.add(index, value);
                list.add(index, value);
            } else {
                final int index = random.nextInt(reference.size());
                assertEquals(reference.remove(index), list.remove(index));
            }
            final List<Integer> trace = new ArrayList<>();
            list.forEach(trace::add);
            assertEquals(reference, trace, "step=" + step);
        }
    }

    @Test
    void reentrantChangesPreserveIteratorFailureTiming() {
        for (int size = 1; size <= 24; size++) {
            for (int trigger = 1; trigger <= size; trigger++) {
                for (int operation = 0; operation < 6; operation++) {
                    final TreeList<Integer> expected = new TreeList<>();
                    final TreeList<Integer> actual = new TreeList<>();
                    for (int i = 0; i < size; i++) {
                        expected.add(i);
                        actual.add(i);
                    }
                    final List<Integer> expectedTrace = new ArrayList<>();
                    final List<Integer> actualTrace = new ArrayList<>();
                    final int mutation = operation;
                    final int call = trigger;
                    final Class<?> expectedFailure = traverse(expected, value -> {
                        expectedTrace.add(value);
                        if (expectedTrace.size() == call) {
                            mutate(expected, mutation);
                        }
                    }, false);
                    final Class<?> actualFailure = traverse(actual, value -> {
                        actualTrace.add(value);
                        if (actualTrace.size() == call) {
                            mutate(actual, mutation);
                        }
                    }, true);
                    final String context = "size=" + size + " trigger=" + trigger + " mutation=" + mutation;
                    assertEquals(expectedFailure, actualFailure, context);
                    assertEquals(expectedTrace, actualTrace, context);
                    assertArrayEquals(expected.toArray(), actual.toArray(), context);
                }
            }
        }
    }

    @Test
    void callbackFailurePreservesIdentityAndVisitedPrefix() {
        final TreeList<Object> list = new TreeList<>();
        final Object first = new Object();
        final Object second = new Object();
        list.add(first);
        list.add(second);
        final RuntimeException failure = new RuntimeException("callback");
        final List<Object> trace = new ArrayList<>();
        assertSame(failure, assertThrows(RuntimeException.class, () -> list.forEach(value -> {
            trace.add(value);
            if (value == second) {
                throw failure;
            }
        })));
        assertEquals(2, trace.size());
        assertSame(first, trace.get(0));
        assertSame(second, trace.get(1));
        assertSame(first, list.get(0));
        assertSame(second, list.get(1));
    }

    @Test
    void subclassIteratorProjectionAndNullValidationRemainObservable() {
        final ProjectedList list = new ProjectedList();
        list.add("stored");
        assertThrows(NullPointerException.class, () -> list.forEach(null));
        assertEquals(0, list.iteratorCalls);
        final List<String> trace = new ArrayList<>();
        list.forEach(trace::add);
        assertEquals(Arrays.asList("projected-one", "projected-two"), trace);
        assertEquals(1, list.iteratorCalls);
        assertThrows(NullPointerException.class, () -> new TreeList<>().forEach(null));
    }
}
