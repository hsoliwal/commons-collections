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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.m3.collections.apache.M3ArrayStorageOracle;

/** Exact original-source callback traces qualify the owned value lane and presence mask. */
class M3CollatingIteratorArrayStorageTest {
    @TempDir
    static Path work;

    private static M3ArrayStorageOracle oracle;

    @BeforeAll
    static void compileOriginal() throws Exception {
        oracle = new M3ArrayStorageOracle(work);
    }

    @AfterAll
    static void closeOriginal() throws Exception {
        if (oracle != null) {
            oracle.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Iterator<Integer> create(final boolean original, final Comparator<Integer> comparator) throws Exception {
        return original ? (Iterator<Integer>) oracle.originalClass("iterators.CollatingIterator")
                .getConstructor(Comparator.class).newInstance(comparator) : new CollatingIterator<>(comparator);
    }

    private static Object call(final Object target, final String method, final Class<?> type, final Object value) throws Exception {
        return M3ArrayStorageOracle.invoke(target, method, new Class<?>[] {type}, value);
    }

    private static Iterator<Integer> tracing(final int id, final List<Integer> values, final List<String> trace,
            final int failureKind) {
        final Iterator<Integer> delegate = values.iterator();
        return new Iterator<Integer>() {
            private boolean failed;
            private int index;

            @Override
            public boolean hasNext() {
                trace.add("has:" + id + ":" + index);
                if (failureKind == 1 && !failed) {
                    failed = true;
                    throw new IllegalArgumentException("child-has");
                }
                return delegate.hasNext();
            }

            @Override
            public Integer next() {
                trace.add("next:" + id + ":" + index);
                if (failureKind == 2 && !failed) {
                    failed = true;
                    throw new IllegalArgumentException("child-next");
                }
                index++;
                return delegate.next();
            }

            @Override
            public void remove() {
                trace.add("remove:" + id + ":" + index);
                delegate.remove();
            }
        };
    }

    @Test
    void mergeAndCallbacksMatchOriginalAcrossSparseAndDenseMaskWords() throws Exception {
        final Random random = new Random(0x6d335f6d65726765L);
        for (int scenario = 0; scenario < 180; scenario++) {
            final List<List<Integer>> children = new ArrayList<>();
            final int count = scenario % 7 == 0 ? 129 : scenario % 40;
            for (int child = 0; child < count; child++) {
                final List<Integer> values = new ArrayList<>();
                final int length = random.nextInt(6);
                for (int index = 0; index < length; index++) {
                    values.add(random.nextInt(9) == 0 ? null : random.nextInt(13) - 6);
                }
                values.sort(Comparator.nullsFirst(Integer::compareTo));
                children.add(values);
            }
            assertEquals(trace(true, children, 0, false), trace(false, children, 0, false), "scenario " + scenario);
        }
    }

    @Test
    void comparatorAndChildExceptionsLeaveExactlyTheOriginalCachedState() throws Exception {
        final List<List<Integer>> children = Arrays.asList(Arrays.asList(1, 4, 6), Arrays.asList(2, 3, 5), Arrays.asList(0, 8));
        for (int failure = 1; failure <= 3; failure++) {
            assertEquals(trace(true, children, failure, false), trace(false, children, failure, false), "failure " + failure);
        }
    }

    @Test
    void reentrantComparatorHasNextAndRemoveDelegationMatchOriginal() throws Exception {
        final List<List<Integer>> children = Arrays.asList(Arrays.asList(1, 3, 5), Arrays.asList(1, 2, 6), Arrays.asList(1, 4, 7));
        assertEquals(trace(true, children, 0, true), trace(false, children, 0, true));
    }

    private static List<String> trace(final boolean original, final List<List<Integer>> sources,
            final int failureKind, final boolean reentrant) throws Exception {
        final List<String> result = new ArrayList<>();
        final AtomicInteger comparisons = new AtomicInteger();
        final AtomicReference<Iterator<Integer>> reference = new AtomicReference<>();
        final Iterator<Integer> merged = create(original, (left, right) -> {
            result.add("compare:" + left + ":" + right);
            if (failureKind == 3 && comparisons.getAndIncrement() == 1) {
                throw new IllegalArgumentException("comparator");
            }
            if (reentrant) {
                result.add("reentrant:" + reference.get().hasNext());
            }
            return Comparator.nullsFirst(Integer::compareTo).compare(left, right);
        });
        reference.set(merged);
        int sourceIndex = 0;
        final List<List<Integer>> mutableSources = new ArrayList<>();
        for (final List<Integer> values : sources) {
            final List<Integer> mutable = new ArrayList<>(values);
            mutableSources.add(mutable);
            final int fault = sourceIndex == 1 ? failureKind : 0;
            call(merged, "addIterator", Iterator.class, tracing(sourceIndex++, mutable, result, fault));
        }
        int ordinal = 0;
        int attempts = 0;
        while (true) {
            assertTrue(attempts++ < 2000, "Merge did not terminate");
            try {
                final boolean available = merged.hasNext();
                result.add("available:" + available);
                if (!available) {
                    break;
                }
                result.add("value:" + merged.next());
                result.add("source:" + M3ArrayStorageOracle.invoke(merged, "getIteratorIndex", new Class<?>[0]));
                if (++ordinal % 4 == 0) {
                    merged.remove();
                }
            } catch (final IllegalArgumentException failure) {
                result.add("failure:" + failure.getMessage());
            }
        }
        assertThrows(NoSuchElementException.class, merged::next);
        result.add("remaining:" + mutableSources);
        return result;
    }

    @Test
    void lazyNullComparatorAndRegistrationLockKeepTheirOriginalOrder() throws Exception {
        for (final boolean original : new boolean[] {true, false}) {
            final Iterator<Integer> empty = create(original, null);
            assertEquals(false, empty.hasNext());
            assertThrows(IllegalStateException.class, () -> call(empty, "addIterator", Iterator.class, null));
            assertThrows(IllegalStateException.class, () -> call(empty, "setComparator", Comparator.class, null));
            final Iterator<Integer> single = create(original, null);
            call(single, "addIterator", Iterator.class, Arrays.asList(1, 2).iterator());
            assertEquals(Integer.valueOf(1), single.next());
            assertEquals(Integer.valueOf(2), single.next());
            final Iterator<Integer> multiple = create(original, null);
            call(multiple, "addIterator", Iterator.class, Arrays.asList(1, 2).iterator());
            call(multiple, "addIterator", Iterator.class, Arrays.asList(3, 4).iterator());
            assertTrue(multiple.hasNext());
            assertThrows(NullPointerException.class, multiple::next);
            assertThrows(NullPointerException.class, multiple::next);
            assertThrows(IllegalStateException.class, () -> call(multiple, "setComparator", Comparator.class, Comparator.naturalOrder()));
        }
    }

    @Test
    void cachedValuesAreParentOwnedAndReleasedAsTheyAreConsumed() throws Exception {
        final CollatingIterator<Integer> iterator = new CollatingIterator<>(Comparator.naturalOrder());
        iterator.addIterator(Arrays.asList(1, 3, 5).iterator());
        iterator.addIterator(Arrays.asList(2, 4, 6).iterator());
        final Field field = CollatingIterator.class.getDeclaredField("values");
        field.setAccessible(true);
        assertEquals(Object[].class, field.getType());
        assertNull(field.get(iterator));
        iterator.hasNext();
        final Object[] values = (Object[]) field.get(iterator);
        assertEquals(2, values.length);
        assertEquals(Integer.valueOf(1), iterator.next());
        assertNull(values[0]);
        assertNotNull(values[1]);
        while (iterator.hasNext()) {
            iterator.next();
        }
        for (final Object value : values) {
            assertNull(value);
        }
    }
}
