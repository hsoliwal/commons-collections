/*
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.m3.collections.apache.queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.m3.collections.apache.M3ArrayStorageOracle;

/** Exhaustive original-source checks for contiguous spans, cursor state and retained storage. */
class CircularFifoQueueArrayStorageTest {
    @TempDir
    static Path work;
    private static M3ArrayStorageOracle oracle;
    private static Field originalElements;
    private static Field currentElements;
    private static Field originalStart;
    private static Field currentStart;
    private static Field originalEnd;
    private static Field currentEnd;
    private static Field originalFull;
    private static Field currentFull;

    @BeforeAll
    static void originalSource() throws Exception {
        oracle = new M3ArrayStorageOracle(work);
        final Class<?> original = oracle.originalClass("queue.CircularFifoQueue");
        assertNotSame(CircularFifoQueue.class, original);
        originalElements = field(original, "elements");
        currentElements = field(CircularFifoQueue.class, "elements");
        originalStart = field(original, "start");
        currentStart = field(CircularFifoQueue.class, "start");
        originalEnd = field(original, "end");
        currentEnd = field(CircularFifoQueue.class, "end");
        originalFull = field(original, "full");
        currentFull = field(CircularFifoQueue.class, "full");
    }

    @AfterAll
    static void closeOracle() throws Exception {
        oracle.close();
    }

    private static Field field(final Class<?> owner, final String name) throws Exception {
        final Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static <E> void moveEmptyStart(final Queue<E> original, final Queue<E> current,
            final int start, final E marker) {
        for (int index = 0; index < start; index++) {
            original.add(marker);
            current.add(marker);
            assertSame(original.remove(), current.remove());
        }
    }

    private static void sameRetainedState(final Queue<?> original, final CircularFifoQueue<?> current)
            throws Exception {
        assertEquals(original.size(), current.size());
        assertSame(original.peek(), current.peek());
        assertArrayEquals(original.toArray(), current.toArray());
        assertEquals(originalStart.getInt(original), currentStart.getInt(current));
        assertEquals(originalEnd.getInt(original), currentEnd.getInt(current));
        assertEquals(originalFull.getBoolean(original), currentFull.getBoolean(current));
        final Object[] expected = (Object[]) originalElements.get(original);
        final Object[] actual = (Object[]) currentElements.get(current);
        assertEquals(expected.length, actual.length);
        final int start = currentStart.getInt(current);
        for (int offset = 0; offset < actual.length; offset++) {
            final int index = (start + offset) % actual.length;
            assertSame(expected[index], actual[index], "exact slot identity");
            if (offset >= current.size()) {
                assertNull(actual[index], "inactive slots must release payload references");
            }
        }
    }

    @Test
    void everySmallRingLayoutAndRemovalMatchesTheOriginalOwner() throws Exception {
        int scenarios = 0;
        for (int capacity = 1; capacity <= 24; capacity++) {
            for (int start = 0; start < capacity; start++) {
                for (int count = 1; count <= capacity; count++) {
                    for (int removed = 0; removed < count; removed++) {
                        final Queue<Object> original = oracle.originalQueue(capacity);
                        final CircularFifoQueue<Object> current = new CircularFifoQueue<>(capacity);
                        moveEmptyStart(original, current, start, new Object());
                        for (int index = 0; index < count; index++) {
                            final Object value = new Object();
                            original.add(value);
                            current.add(value);
                        }
                        final Object ownedArray = currentElements.get(current);
                        final Iterator<Object> expected = original.iterator();
                        final Iterator<Object> actual = current.iterator();
                        assertThrows(IllegalStateException.class, expected::remove);
                        assertThrows(IllegalStateException.class, actual::remove);
                        for (int index = 0; index <= removed; index++) {
                            assertSame(expected.next(), actual.next());
                        }
                        expected.remove();
                        actual.remove();
                        assertThrows(IllegalStateException.class, expected::remove);
                        assertThrows(IllegalStateException.class, actual::remove);
                        assertSame(ownedArray, currentElements.get(current), "removal reuses the owner's array");
                        sameRetainedState(original, current);
                        while (expected.hasNext()) {
                            assertEquals(expected.hasNext(), actual.hasNext());
                            assertSame(expected.next(), actual.next());
                        }
                        assertFalse(actual.hasNext());
                        assertThrows(NoSuchElementException.class, expected::next);
                        assertThrows(NoSuchElementException.class, actual::next);
                        for (int index = 0; index <= capacity; index++) {
                            final Object value = new Object();
                            original.add(value);
                            current.add(value);
                        }
                        sameRetainedState(original, current);
                        while (!original.isEmpty()) {
                            assertSame(original.remove(), current.remove());
                        }
                        sameRetainedState(original, current);
                        scenarios++;
                    }
                }
            }
        }
        assertEquals(47450, scenarios);
    }

    @Test
    void everySmallRemovalSubsetPreservesOneLiveIteratorAcrossWraps() throws Exception {
        for (int capacity = 1; capacity <= 8; capacity++) {
            for (int start = 0; start < capacity; start++) {
                for (int count = 1; count <= capacity; count++) {
                    for (int mask = 0; mask < 1 << count; mask++) {
                        final Queue<Integer> original = oracle.originalQueue(capacity);
                        final CircularFifoQueue<Integer> current = new CircularFifoQueue<>(capacity);
                        moveEmptyStart(original, current, start, -1);
                        for (int value = 0; value < count; value++) {
                            original.add(value);
                            current.add(value);
                        }
                        final Iterator<Integer> expected = original.iterator();
                        final Iterator<Integer> actual = current.iterator();
                        for (int index = 0; index < count; index++) {
                            assertEquals(expected.hasNext(), actual.hasNext());
                            assertSame(expected.next(), actual.next());
                            if ((mask & 1 << index) != 0) {
                                expected.remove();
                                actual.remove();
                                sameRetainedState(original, current);
                            }
                        }
                        assertFalse(expected.hasNext());
                        assertFalse(actual.hasNext());
                        sameRetainedState(original, current);
                    }
                }
            }
        }
    }

    @Test
    void originalSerializationBytesRemainReadableAfterSpanRemoval() throws Exception {
        for (int capacity = 1; capacity <= 10; capacity++) {
            for (int start = 0; start < capacity; start++) {
                for (int removed = 0; removed < capacity; removed++) {
                    final Queue<Integer> original = oracle.originalQueue(capacity);
                    final CircularFifoQueue<Integer> current = new CircularFifoQueue<>(capacity);
                    moveEmptyStart(original, current, start, -1);
                    for (int value = 0; value < capacity; value++) {
                        original.add(value);
                        current.add(value);
                    }
                    final Iterator<Integer> expected = original.iterator();
                    final Iterator<Integer> actual = current.iterator();
                    for (int index = 0; index <= removed; index++) {
                        assertEquals(expected.next(), actual.next());
                    }
                    expected.remove();
                    actual.remove();
                    final byte[] originalBytes = serialize(original);
                    final byte[] currentBytes = serialize(current);
                    assertArrayEquals(originalBytes, currentBytes);
                    final Queue<?> restoredOriginal = deserialize(currentBytes, original.getClass().getClassLoader());
                    final Queue<?> restoredCurrent = deserialize(originalBytes, current.getClass().getClassLoader());
                    assertArrayEquals(original.toArray(), restoredCurrent.toArray());
                    assertArrayEquals(current.toArray(), restoredOriginal.toArray());
                    assertArrayEquals(serialize(restoredOriginal), serialize(restoredCurrent));
                }
            }
        }
    }

    @Test
    void headRemovalStillDispatchesToTheSubclassOwner() {
        final ObservingQueue queue = new ObservingQueue(4);
        queue.addAll(Arrays.asList(1, 2, 3));
        final Iterator<Integer> first = queue.iterator();
        first.next();
        first.remove();
        assertEquals(1, queue.removalCalls);
        final Iterator<Integer> later = queue.iterator();
        later.next();
        later.next();
        later.remove();
        assertEquals(1, queue.removalCalls);
        assertArrayEquals(new Object[] {2}, queue.toArray());
    }

    @Test
    void publicProtectedAndSerializedSurfacesAreUnchanged() throws Exception {
        final Class<?> original = oracle.originalClass("queue.CircularFifoQueue");
        assertEquals(surface(original), surface(CircularFifoQueue.class));
        assertEquals(ObjectStreamClass.lookup(original).getSerialVersionUID(),
                ObjectStreamClass.lookup(CircularFifoQueue.class).getSerialVersionUID());
        assertEquals(Arrays.toString(ObjectStreamClass.lookup(original).getFields()),
                Arrays.toString(ObjectStreamClass.lookup(CircularFifoQueue.class).getFields()));
    }

    private static byte[] serialize(final Object value) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static Queue<?> deserialize(final byte[] bytes, final ClassLoader owner) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            protected Class<?> resolveClass(final ObjectStreamClass descriptor) throws ClassNotFoundException {
                return Class.forName(descriptor.getName(), false, owner);
            }
        }) {
            return (Queue<?>) input.readObject();
        }
    }

    private static TreeSet<String> surface(final Class<?> type) {
        final TreeSet<String> result = new TreeSet<>();
        Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) || Modifier.isProtected(method.getModifiers()))
                .forEach(method -> result.add(method.toGenericString()));
        Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers())
                        || Modifier.isProtected(constructor.getModifiers()))
                .forEach(constructor -> result.add(constructor.toGenericString()));
        return result;
    }

    private static final class ObservingQueue extends CircularFifoQueue<Integer> {
        private static final long serialVersionUID = 1L;
        private int removalCalls;

        ObservingQueue(final int capacity) {
            super(capacity);
        }

        @Override
        public Integer remove() {
            removalCalls++;
            return super.remove();
        }
    }
}
