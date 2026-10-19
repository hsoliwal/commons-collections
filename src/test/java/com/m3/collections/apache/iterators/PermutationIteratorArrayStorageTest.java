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
package com.m3.collections.apache.iterators;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.m3.collections.apache.M3ArrayStorageOracle;

/** Original-source checks for direct ordinal payload storage without retained map entries. */
class PermutationIteratorArrayStorageTest {
    @TempDir
    static Path work;
    private static M3ArrayStorageOracle oracle;

    @BeforeAll
    static void originalSource() throws Exception {
        oracle = new M3ArrayStorageOracle(work);
        assertNotSame(PermutationIterator.class, oracle.originalClass("iterators.PermutationIterator"));
    }

    @AfterAll
    static void closeOracle() throws Exception {
        oracle.close();
    }

    private static void sameReferences(final List<?> original, final List<?> current) {
        assertEquals(original.size(), current.size());
        for (int index = 0; index < original.size(); index++) {
            assertSame(original.get(index), current.get(index));
        }
    }

    @Test
    void fullPermutationOrderAndIdentityMatchForEveryLengthThroughEight() throws Exception {
        for (int count = 0; count <= 8; count++) {
            for (int shape = 0; shape < 4; shape++) {
                final List<Object> values = new ArrayList<>();
                final Object shared = new Object();
                for (int index = 0; index < count; index++) {
                    values.add(shape == 0 ? new Object() : shape == 1 ? new String("equal")
                            : shape == 2 ? shared : index % 2 == 0 ? null : shared);
                }
                final Iterator<List<Object>> original = oracle.originalPermutations(values);
                final PermutationIterator<Object> current = new PermutationIterator<>(values);
                int permutations = 0;
                while (original.hasNext()) {
                    assertTrue(current.hasNext());
                    sameReferences(original.next(), current.next());
                    permutations++;
                }
                assertFalse(current.hasNext());
                int factorial = 1;
                for (int factor = 2; factor <= count; factor++) {
                    factorial *= factor;
                }
                assertEquals(factorial, permutations);
                assertThrows(NoSuchElementException.class, original::next);
                assertThrows(NoSuchElementException.class, current::next);
                assertThrows(UnsupportedOperationException.class, original::remove);
                assertThrows(UnsupportedOperationException.class, current::remove);
            }
        }
    }

    @Test
    void payloadOwnerHasOneDenseArrayAndNoRetainedMap() throws Exception {
        final List<Object> values = new ArrayList<>();
        for (int index = 0; index < 4096; index++) {
            values.add(new Object());
        }
        final PermutationIterator<Object> current = new PermutationIterator<>(values);
        final Field field = PermutationIterator.class.getDeclaredField("objects");
        field.setAccessible(true);
        final Object[] owned = (Object[]) field.get(current);
        assertArrayEquals(values.toArray(), owned);
        assertEquals(4096, owned.length);
        for (final Field retained : PermutationIterator.class.getDeclaredFields()) {
            if (!Modifier.isStatic(retained.getModifiers())) {
                assertFalse(Map.class.isAssignableFrom(retained.getType()), retained.getName());
            }
        }
        current.next();
        assertSame(owned, field.get(current));
    }

    @Test
    void returnedListsAndConstructorSnapshotKeepOriginalAliasingBoundaries() throws Exception {
        final List<Object> source = new ArrayList<>(Arrays.asList(new Object(), null, new Object()));
        final Iterator<List<Object>> original = oracle.originalPermutations(source);
        final PermutationIterator<Object> current = new PermutationIterator<>(source);
        source.clear();
        source.add(new Object());
        final List<Object> expectedFirst = original.next();
        final List<Object> actualFirst = current.next();
        sameReferences(expectedFirst, actualFirst);
        expectedFirst.clear();
        actualFirst.clear();
        while (original.hasNext()) {
            sameReferences(original.next(), current.next());
        }
        assertFalse(current.hasNext());
    }

    @Test
    void irregularSizeReportsKeepOriginalCallbacksFailuresAndZeroKeys() throws Exception {
        for (int firstSize = 0; firstSize <= 4; firstSize++) {
            for (int secondSize = 0; secondSize <= 4; secondSize++) {
                for (int count = 0; count <= 4; count++) {
                    final List<Object> values = new ArrayList<>();
                    for (int index = 0; index < count; index++) {
                        values.add(new Object());
                    }
                    final TracedCollection expectedSource = new TracedCollection(values, firstSize, secondSize);
                    final TracedCollection actualSource = new TracedCollection(values, firstSize, secondSize);
                    Iterator<List<Object>> original = null;
                    Iterator<List<Object>> current = null;
                    RuntimeException originalFailure = null;
                    RuntimeException currentFailure = null;
                    try {
                        original = oracle.originalPermutations(expectedSource);
                    } catch (final RuntimeException exception) {
                        originalFailure = exception;
                    }
                    try {
                        current = new PermutationIterator<>(actualSource);
                    } catch (final RuntimeException exception) {
                        currentFailure = exception;
                    }
                    sameFailure(originalFailure, currentFailure);
                    assertEquals(expectedSource.events, actualSource.events);
                    if (originalFailure == null) {
                        int attempts = 0;
                        while (original.hasNext()) {
                            assertTrue(current.hasNext());
                            List<Object> expected = null;
                            List<Object> actual = null;
                            try {
                                expected = original.next();
                            } catch (final RuntimeException exception) {
                                originalFailure = exception;
                            }
                            try {
                                actual = current.next();
                            } catch (final RuntimeException exception) {
                                currentFailure = exception;
                            }
                            sameFailure(originalFailure, currentFailure);
                            if (originalFailure != null) {
                                break;
                            }
                            sameReferences(expected, actual);
                            assertTrue(++attempts <= 24, "bounded original permutation sequence");
                        }
                        assertEquals(original.hasNext(), current.hasNext());
                    }
                }
            }
        }
    }

    @Test
    void nullAdmissionAndPublicProtectedSurfaceStayUnchanged() throws Exception {
        assertThrows(NullPointerException.class, () -> oracle.originalPermutations(null));
        assertThrows(NullPointerException.class, () -> new PermutationIterator<>(null));
        assertEquals(surface(oracle.originalClass("iterators.PermutationIterator")), surface(PermutationIterator.class));
    }

    private static void sameFailure(final RuntimeException original, final RuntimeException current) {
        assertEquals(original == null ? null : original.getClass(), current == null ? null : current.getClass());
        assertEquals(original == null ? null : original.getMessage(), current == null ? null : current.getMessage());
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

    private static final class TracedCollection extends AbstractCollection<Object> {
        private final List<Object> values;
        private final int firstSize;
        private final int secondSize;
        private final List<String> events = new ArrayList<>();
        private int sizes;

        TracedCollection(final List<Object> values, final int firstSize, final int secondSize) {
            this.values = values;
            this.firstSize = firstSize;
            this.secondSize = secondSize;
        }

        @Override
        public int size() {
            events.add("size");
            return sizes++ == 0 ? firstSize : secondSize;
        }

        @Override
        public Iterator<Object> iterator() {
            events.add("iterator");
            final Iterator<Object> delegate = values.iterator();
            return new Iterator<Object>() {
                @Override
                public boolean hasNext() {
                    events.add("hasNext");
                    return delegate.hasNext();
                }

                @Override
                public Object next() {
                    events.add("next");
                    return delegate.next();
                }
            };
        }

        @Override
        public Object[] toArray() {
            events.add("toArray");
            return values.toArray();
        }
    }
}
