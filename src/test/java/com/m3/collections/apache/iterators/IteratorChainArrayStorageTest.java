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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.m3.collections.apache.M3ArrayStorageOracle;

/** Original-oracle checks for array-owned pending-child storage and admission atoms. */
class IteratorChainArrayStorageTest {
    @TempDir
    static Path work;
    private static M3ArrayStorageOracle oracle;

    @BeforeAll
    static void originalSource() throws Exception {
        oracle = new M3ArrayStorageOracle(work);
        assertNotSame(IteratorChain.class, oracle.originalClass("iterators.IteratorChain"));
    }

    @AfterAll
    static void closeOracle() throws Exception {
        oracle.close();
    }

    private static void add(final Iterator<?> chain, final Iterator<?> child) throws Exception {
        M3ArrayStorageOracle.invoke(chain, "addIterator", new Class<?>[] {Iterator.class}, child);
    }

    private static int size(final Iterator<?> chain) throws Exception {
        return (Integer) M3ArrayStorageOracle.invoke(chain, "size", new Class<?>[0]);
    }

    private static List<Object> drain(final Iterator<?> chain) {
        final List<Object> result = new ArrayList<>();
        chain.forEachRemaining(result::add);
        return result;
    }

    @Test
    void pendingChildrenUseExistingArrayDequeWithoutPerChildLinks() throws Exception {
        final IteratorChain<Object> chain = new IteratorChain<>();
        final Iterator<Object> sharedChild = Collections.emptyIterator();
        for (int index = 0; index < 16385; index++) {
            chain.addIterator(sharedChild);
        }
        final Field field = IteratorChain.class.getDeclaredField("iteratorQueue");
        field.setAccessible(true);
        final Queue<?> pending = assertInstanceOf(ArrayDeque.class, field.get(chain));
        assertEquals(16385, pending.size());
        pending.forEach(child -> assertSame(sharedChild, child));
        assertFalse(chain.hasNext());
        assertTrue(pending.isEmpty(), "consumed child references leave the canonical queue");
    }

    @Test
    void selfAdmissionSnapshotsExactlyTheOriginalPendingChildren() throws Exception {
        for (final int count : new int[] {0, 1, 2, 15, 16, 17, 63, 64, 65}) {
            final Iterator<Object> original = oracle.originalChain();
            final IteratorChain<Object> received = new IteratorChain<>();
            for (int index = 0; index < count; index++) {
                final Object value = new Object();
                add(original, Collections.singleton(value).iterator());
                received.addIterator(Collections.singleton(value).iterator());
            }
            add(original, original);
            received.addIterator(received);
            assertEquals(size(original), received.size());
            assertEquals(count * 2, received.size());
            assertEquals(drain(original), drain(received));
            assertEquals(size(original), received.size());
        }
    }

    @Test
    void wrappedSelfAdmissionKeepsOriginalPartialMutationAndException() throws Exception {
        for (final int count : new int[] {0, 1, 2, 15, 16, 17, 63, 64, 65}) {
            final Iterator<Object> original = oracle.originalChain();
            final IteratorChain<Object> received = new IteratorChain<>();
            for (int index = 0; index < count; index++) {
                final Object value = new Object();
                add(original, Collections.singleton(value).iterator());
                received.addIterator(Collections.singleton(value).iterator());
            }
            final Iterator<Object> originalWrapper = oracle.originalUnmodifiable(original);
            final Iterator<Object> receivedWrapper = UnmodifiableIterator.unmodifiableIterator(received);
            if (count == 0) {
                add(original, originalWrapper);
                received.addIterator(receivedWrapper);
            } else {
                assertThrows(ConcurrentModificationException.class, () -> add(original, originalWrapper));
                assertThrows(ConcurrentModificationException.class, () -> received.addIterator(receivedWrapper));
            }
            assertEquals(size(original), received.size());
            assertEquals(count == 0 ? 0 : count + 1, received.size());
            assertEquals(drain(original), drain(received));
        }
    }

    @Test
    void nestedFlatteningPreservesPayloadIdentityNullValuesAndSourceQueues() throws Exception {
        for (final boolean unmodifiable : new boolean[] {false, true}) {
            final Iterator<Object> originalChild = oracle.originalChain();
            final IteratorChain<Object> receivedChild = new IteratorChain<>();
            final Object first = new Object();
            final Object last = new Object();
            for (final List<Object> values : Arrays.asList(Collections.<Object>emptyList(), Arrays.asList(first, null),
                    Collections.singletonList(last))) {
                add(originalChild, new ArrayList<>(values).iterator());
                receivedChild.addIterator(new ArrayList<>(values).iterator());
            }
            final Iterator<Object> original = oracle.originalChain();
            final IteratorChain<Object> received = new IteratorChain<>();
            add(original, unmodifiable ? oracle.originalUnmodifiable(originalChild) : originalChild);
            received.addIterator(unmodifiable ? UnmodifiableIterator.unmodifiableIterator(receivedChild) : receivedChild);
            assertEquals(size(originalChild), receivedChild.size());
            final List<Object> expected = drain(original);
            final List<Object> actual = drain(received);
            assertEquals(expected, actual);
            assertSame(first, actual.get(0));
            assertSame(last, actual.get(2));
        }
    }

    @Test
    void removalAfterLookAheadKeepsLastUsedChildAndLockPrecedence() throws Exception {
        final List<Integer> expectedFirst = new ArrayList<>(Collections.singletonList(1));
        final List<Integer> actualFirst = new ArrayList<>(expectedFirst);
        final Iterator<Integer> original = oracle.originalChain();
        final IteratorChain<Integer> received = new IteratorChain<>();
        add(original, expectedFirst.iterator());
        received.addIterator(actualFirst.iterator());
        add(original, Collections.singletonList(2).iterator());
        received.addIterator(Collections.singletonList(2).iterator());
        assertThrows(NullPointerException.class, () -> add(original, null));
        assertThrows(NullPointerException.class, () -> received.addIterator(null));
        assertEquals(original.next(), received.next());
        assertEquals(original.hasNext(), received.hasNext());
        original.remove();
        received.remove();
        assertEquals(expectedFirst, actualFirst);
        assertTrue(actualFirst.isEmpty());
        assertThrows(IllegalStateException.class, original::remove);
        assertThrows(IllegalStateException.class, received::remove);
        assertThrows(UnsupportedOperationException.class, () -> add(original, null));
        assertThrows(UnsupportedOperationException.class, () -> received.addIterator(null));
        assertEquals(drain(original), drain(received));
    }

    @Test
    void partiallyConsumedNestedChainCopiesOnlyPendingChildren() throws Exception {
        final Iterator<Integer> originalChild = oracle.originalChain();
        final IteratorChain<Integer> receivedChild = new IteratorChain<>();
        add(originalChild, Arrays.asList(1, 2).iterator());
        receivedChild.addIterator(Arrays.asList(1, 2).iterator());
        add(originalChild, Arrays.asList(3, 4).iterator());
        receivedChild.addIterator(Arrays.asList(3, 4).iterator());
        assertEquals(originalChild.next(), receivedChild.next());
        final Iterator<Integer> original = oracle.originalChain();
        final IteratorChain<Integer> received = new IteratorChain<>();
        add(original, originalChild);
        received.addIterator(receivedChild);
        assertEquals(drain(original), drain(received));
        assertEquals(drain(originalChild), drain(receivedChild));
    }

    @Test
    void publicAndProtectedDeclaredSurfaceIsUnchanged() throws Exception {
        assertEquals(surface(oracle.originalClass("iterators.IteratorChain")), surface(IteratorChain.class));
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
}
