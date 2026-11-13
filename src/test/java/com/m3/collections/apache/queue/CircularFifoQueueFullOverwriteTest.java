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
// Array-operation contribution: Copyright (c) 2026 Hitesh Soliwal and Synexia contributors.
package com.m3.collections.apache.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Real fork-native differential test of the exact-class full-ring fast path. */
public final class CircularFifoQueueFullOverwriteTest {
    private static final Field ELEMENTS = field("elements");
    private static final Field START = field("start");
    private static final Field END = field("end");
    private static final Field FULL = field("full");
    private static int checks;

    private static final class Value implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private final int key;

        Value(int key) {
            this.key = key;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Value && key == ((Value) other).key;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(key);
        }
    }

    private static Field field(String name) {
        try {
            Field result = CircularFifoQueue.class.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Independent scalar model: keep the original clear-then-write physical geometry. */
    private static final class Geometry {
        final Object[] elements;
        int start;
        int end;
        boolean full;

        Geometry(int capacity) {
            elements = new Object[capacity];
        }

        int size() {
            if (start == end) return full ? elements.length : 0;
            return end < start ? elements.length - start + end : end - start;
        }

        void add(Value value) {
            if (full) {
                elements[start] = null;
                start = (start + 1) % elements.length;
                full = false;
            }
            elements[end] = value;
            end = (end + 1) % elements.length;
            if (end == start) full = true;
        }

        Value poll() {
            if (size() == 0) return null;
            Value result = (Value) elements[start];
            elements[start] = null;
            start = (start + 1) % elements.length;
            full = false;
            return result;
        }

        void clear() {
            Arrays.fill(elements, null);
            start = 0;
            end = 0;
            full = false;
        }
    }

    private static void state(CircularFifoQueue<Value> queue,
            ArrayDeque<Value> order, Geometry physical) throws Exception {
        check(queue.size() == order.size(), "size");
        check(queue.isEmpty() == order.isEmpty(), "empty");
        check(queue.isAtFullCapacity() == (order.size() == physical.elements.length), "full");
        check(Arrays.equals(queue.toArray(), order.toArray()), "FIFO order");
        check(Arrays.equals((Object[]) ELEMENTS.get(queue), physical.elements), "physical cells");
        check(START.getInt(queue) == physical.start, "physical start");
        check(END.getInt(queue) == physical.end, "physical end");
        check(FULL.getBoolean(queue) == physical.full, "physical full");
        check(queue.peek() == order.peekFirst(), "peek identity");
        Value[] contents = queue.toArray(new Value[0]);
        int i = 0;
        for (Value value : queue) {
            check(value == contents[i++], "iterator identity");
        }
        check(i == order.size(), "iterator count");
    }

    @Test
    void exactOwnedFullRingPreservesEverySlotAndIdentity() throws Exception {
        Random random = new Random(0x4d335f1fL);
        int transitions = 0;
        for (int capacity : new int[] {1, 2, 3, 5, 7, 8, 16, 31, 32, 33, 64, 127}) {
            CircularFifoQueue<Value> queue = new CircularFifoQueue<>(capacity);
            ArrayDeque<Value> order = new ArrayDeque<>();
            Geometry model = new Geometry(capacity);
            for (int i = 0; i < 7000; i++) {
                switch (random.nextInt(9)) {
                    case 0:
                    case 1:
                    case 2:
                    case 3:
                    case 4:
                    case 5: {
                        Value value = new Value(i + capacity * 10000);
                        check(queue.add(value), "add accepted");
                        if (order.size() == capacity) order.removeFirst();
                        order.addLast(value);
                        model.add(value);
                        break;
                    }
                    case 6: {
                        Value observed = queue.poll();
                        Value expected = order.pollFirst();
                        check(observed == expected, "poll identity");
                        check(model.poll() == expected, "model poll identity");
                        break;
                    }
                    case 7:
                        queue.clear();
                        order.clear();
                        model.clear();
                        break;
                    case 8: {
                        Value value = new Value(-i);
                        if (random.nextBoolean()) {
                            check(queue.offer(value), "offer accepted");
                            if (order.size() == capacity) order.removeFirst();
                            order.addLast(value);
                            model.add(value);
                        } else if (order.isEmpty()) {
                            assertThrows(NoSuchElementException.class, queue::remove);
                        } else {
                            check(queue.remove() == order.removeFirst(), "remove identity");
                            model.poll();
                        }
                        break;
                    }
                    default:
                        throw new AssertionError();
                }
                if (i % 97 == 0) {
                    Object[] snapshot = queue.toArray();
                    assertThrows(NullPointerException.class, () -> queue.add(null));
                    check(Arrays.equals(snapshot, queue.toArray()), "null preflight");
                }
                state(queue, order, model);
                transitions++;
            }
        }
        check(transitions == 84000 && checks > 500000, "oracle coverage");
    }

    private static final class HookedQueue extends CircularFifoQueue<Value> {
        private static final long serialVersionUID = 1L;
        int checks;
        int removed;

        HookedQueue(int capacity) {
            super(capacity);
        }
        @Override
        public boolean isAtFullCapacity() {
            checks++;
            return super.isAtFullCapacity();
        }
        @Override
        public Value remove() {
            removed++;
            return super.remove();
        }
    }

    @Test
    void subclassRetainsVirtualFullAndRemovalHooks() {
        HookedQueue queue = new HookedQueue(3);
        queue.add(new Value(1));
        queue.add(new Value(2));
        queue.add(new Value(3));
        int oldChecks = queue.checks;
        queue.add(new Value(4));
        check(queue.checks == oldChecks + 1, "subclass isAtFullCapacity hook");
        check(queue.removed == 1, "subclass remove hook");
        assertEquals(Arrays.asList(new Value(2), new Value(3), new Value(4)),
                Arrays.asList(queue.toArray()));
        queue.add(new Value(5));
        check(queue.removed == 2, "second subclass remove hook");
    }

    @Test
    void fullRingSerializationAndDeserialization() throws Exception {
        for (int capacity : new int[]{1, 2, 3, 32}) {
            CircularFifoQueue<Value> queue = new CircularFifoQueue<>(capacity);
            for (int i = 0; i < capacity * 6 + 1; i++) queue.add(new Value(i));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(queue);
            }
            Object copy;
            try (ObjectInputStream in = new ObjectInputStream(
                    new ByteArrayInputStream(bytes.toByteArray()))) {
                copy = in.readObject();
            }
            check(copy.getClass() == CircularFifoQueue.class, "serial class");
            @SuppressWarnings("unchecked")
            CircularFifoQueue<Value> restored = (CircularFifoQueue<Value>) copy;
            check(Arrays.equals(queue.toArray(), restored.toArray()), "serialized FIFO values");
            Value extra = new Value(12345);
            restored.add(extra);
            Value expectedOldest = capacity == 1 ? extra : queue.get(1);
            check(restored.peek().equals(expectedOldest), "post-read full overwrite");
            check(restored.get(restored.size() - 1) == extra, "post-read append identity");
        }
    }

    public static void main(String[] args) throws Exception {
        CircularFifoQueueFullOverwriteTest test = new CircularFifoQueueFullOverwriteTest();
        test.exactOwnedFullRingPreservesEverySlotAndIdentity();
        test.subclassRetainsVirtualFullAndRemovalHooks();
        test.fullRingSerializationAndDeserialization();
        System.out.println("CIRCULAR_FIFO_OVERWRITE_PASS checks=" + checks);
    }
}
