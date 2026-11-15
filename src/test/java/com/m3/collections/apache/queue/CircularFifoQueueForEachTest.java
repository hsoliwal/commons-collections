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
package com.m3.collections.apache.queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/** Verifies direct traversal against the established iterator under callback mutation. */
class CircularFifoQueueForEachTest {

    private static final class ProjectedQueue extends CircularFifoQueue<String> {
        private static final long serialVersionUID = 1L;
        private int iteratorCalls;

        ProjectedQueue() {
            super(3);
        }

        @Override
        public Iterator<String> iterator() {
            iteratorCalls++;
            return Arrays.asList("projected-one", "projected-two").iterator();
        }
    }

    private static CircularFifoQueue<Integer> queue(final int capacity, final int rotation, final int count) {
        final CircularFifoQueue<Integer> queue = new CircularFifoQueue<>(capacity);
        for (int i = 0; i < rotation; i++) {
            queue.add(-1);
            queue.remove();
        }
        for (int i = 0; i < count; i++) {
            queue.add(i);
        }
        return queue;
    }

    private static void traverse(final CircularFifoQueue<Integer> queue,
            final Consumer<Integer> action, final boolean direct) {
        if (direct) {
            queue.forEach(action);
        } else {
            for (final Integer value : queue) {
                action.accept(value);
            }
        }
    }

    private static void mutate(final CircularFifoQueue<Integer> queue, final int operation) {
        switch (operation) {
        case 0:
            queue.remove();
            break;
        case 1:
            queue.add(100);
            break;
        case 2:
            queue.clear();
            break;
        case 3:
            queue.clear();
            queue.add(100);
            break;
        case 4:
            queue.remove();
            queue.add(100);
            break;
        default:
            queue.remove(Integer.valueOf(2));
            break;
        }
    }

    private static byte[] serialize(final Object value) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    @Test
    void everyRingLayoutMatchesIteratorEncounterOrder() {
        for (int capacity = 1; capacity <= 16; capacity++) {
            for (int rotation = 0; rotation < capacity; rotation++) {
                for (int count = 0; count <= capacity; count++) {
                    final CircularFifoQueue<Integer> queue = queue(capacity, rotation, count);
                    final List<Integer> expected = new ArrayList<>();
                    final List<Integer> actual = new ArrayList<>();
                    traverse(queue, expected::add, false);
                    traverse(queue, actual::add, true);
                    assertEquals(expected, actual,
                            "capacity=" + capacity + " rotation=" + rotation + " count=" + count);
                }
            }
        }
    }

    @Test
    void callbackMutationsKeepIteratorDynamicEndRules() {
        for (int capacity = 1; capacity <= 12; capacity++) {
            for (int rotation = 0; rotation < capacity; rotation++) {
                for (int count = 1; count <= capacity; count++) {
                    for (int operation = 0; operation < 6; operation++) {
                        final CircularFifoQueue<Integer> expected = queue(capacity, rotation, count);
                        final CircularFifoQueue<Integer> actual = queue(capacity, rotation, count);
                        final List<Integer> expectedTrace = new ArrayList<>();
                        final List<Integer> actualTrace = new ArrayList<>();
                        final int mutation = operation;
                        traverse(expected, value -> {
                            expectedTrace.add(value);
                            if (expectedTrace.size() == 1) {
                                mutate(expected, mutation);
                            }
                        }, false);
                        traverse(actual, value -> {
                            actualTrace.add(value);
                            if (actualTrace.size() == 1) {
                                mutate(actual, mutation);
                            }
                        }, true);
                        assertEquals(expectedTrace, actualTrace,
                                "capacity=" + capacity + " rotation=" + rotation
                                        + " count=" + count + " mutation=" + mutation);
                        assertArrayEquals(expected.toArray(), actual.toArray());
                    }
                }
            }
        }
    }

    @Test
    void callbackFailurePreservesIdentityAndVisitedPrefix() {
        final CircularFifoQueue<Object> queue = new CircularFifoQueue<>(4);
        final Object first = new Object();
        final Object second = new Object();
        queue.add(first);
        queue.add(second);
        final RuntimeException failure = new RuntimeException("callback");
        final List<Object> trace = new ArrayList<>();
        assertSame(failure, assertThrows(RuntimeException.class, () -> queue.forEach(value -> {
            trace.add(value);
            if (value == second) {
                throw failure;
            }
        })));
        assertEquals(2, trace.size());
        assertSame(first, trace.get(0));
        assertSame(second, trace.get(1));
        assertArrayEquals(new Object[] {first, second}, queue.toArray());
    }

    @Test
    void subclassIteratorProjectionAndNullValidationRemainObservable() {
        final ProjectedQueue queue = new ProjectedQueue();
        queue.add("stored");
        assertThrows(NullPointerException.class, () -> queue.forEach(null));
        assertEquals(0, queue.iteratorCalls);
        final List<String> trace = new ArrayList<>();
        queue.forEach(trace::add);
        assertEquals(Arrays.asList("projected-one", "projected-two"), trace);
        assertEquals(1, queue.iteratorCalls);
        assertThrows(NullPointerException.class, () -> new CircularFifoQueue<>().forEach(null));
    }

    @Test
    void traversalDoesNotChangeSerializedRingState() throws Exception {
        final CircularFifoQueue<Integer> queue = queue(7, 5, 6);
        final byte[] before = serialize(queue);
        final List<Integer> trace = new ArrayList<>();
        queue.forEach(trace::add);
        assertArrayEquals(before, serialize(queue));
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(before))) {
            final CircularFifoQueue<?> restored = (CircularFifoQueue<?>) in.readObject();
            final List<Object> restoredTrace = new ArrayList<>();
            restored.forEach(restoredTrace::add);
            assertEquals(trace, restoredTrace);
        }
    }
}
