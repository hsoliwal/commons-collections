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
package org.apache.commons.collections4.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.apache.commons.collections4.iterators.EmptyIterator;
import org.apache.commons.collections4.iterators.IteratorChain;
import org.apache.commons.collections4.iterators.UnmodifiableIterator;
import org.apache.commons.collections4.list.TreeList;
import org.junit.jupiter.api.Test;

/** Tests nested composite paths without materializing the contained values. */
class CompositeCollectionNestedIteratorTest {

    private static Collection<Integer> callback(final String name, final List<String> calls,
            final Supplier<Iterator<Integer>> factory) {
        return new AbstractCollection<Integer>() {
            @Override
            public Iterator<Integer> iterator() {
                calls.add(name);
                return factory.get();
            }

            @Override
            public int size() {
                throw new AssertionError("Iterator composition must not query a leaf's size");
            }
        };
    }

    @SafeVarargs
    private static CompositeCollection<Integer> composite(final Collection<Integer>... children) {
        final CompositeCollection<Integer> result = new CompositeCollection<>();
        for (final Collection<Integer> child : children) {
            result.addComposited(child);
        }
        return result;
    }

    private static ArrayList<Integer> list(final Integer... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static void insertRawChild(final CompositeCollection<Integer> composite, final Object child) {
        composite.setMutator(new CompositeCollection.CollectionMutator<Integer>() {
            private static final long serialVersionUID = 1L;

            @Override
            @SuppressWarnings({ "rawtypes", "unchecked" })
            public boolean add(final CompositeCollection<Integer> owner,
                    final List<Collection<Integer>> children, final Integer value) {
                ((List) children).add(child);
                return true;
            }

            @Override
            public boolean addAll(final CompositeCollection<Integer> owner,
                    final List<Collection<Integer>> children, final Collection<? extends Integer> values) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean remove(final CompositeCollection<Integer> owner,
                    final List<Collection<Integer>> children, final Object value) {
                throw new UnsupportedOperationException();
            }
        });
        composite.add(null);
    }

    private static List<Integer> read(final Iterator<Integer> iterator) {
        final List<Integer> result = new ArrayList<>();
        while (iterator.hasNext()) {
            result.add(iterator.next());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static CompositeCollection<Integer> roundTrip(final CompositeCollection<Integer> source) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(source);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (CompositeCollection<Integer>) input.readObject();
        }
    }

    @Test
    void testDeepCompositePathDoesNotUseTheJavaCallStack() {
        CompositeCollection<Integer> root = composite(list(42));
        for (int depth = 0; depth < 20_000; depth++) {
            root = composite(root);
        }
        final Iterator<Integer> iterator = root.iterator();
        assertTrue(iterator instanceof IteratorChain);
        assertEquals(1, ((IteratorChain<?>) iterator).size());
        assertEquals(Integer.valueOf(42), iterator.next());
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    @Test
    void testBalancedTreeOfArrayLinkedAndRankedListsKeepsEncounterOrder() {
        List<Collection<Integer>> level = new ArrayList<>();
        final List<Integer> expected = new ArrayList<>();
        for (int index = 0; index < 128; index++) {
            final List<Integer> values = list(index, null, -index);
            expected.addAll(values);
            switch (index % 3) {
            case 0:
                level.add(new ArrayList<>(values));
                break;
            case 1:
                level.add(new LinkedList<>(values));
                break;
            default:
                level.add(new TreeList<>(values));
                break;
            }
        }
        while (level.size() > 1) {
            final List<Collection<Integer>> parents = new ArrayList<>();
            for (int index = 0; index < level.size(); index += 2) {
                parents.add(composite(level.get(index), level.get(index + 1)));
            }
            level = parents;
        }
        final Iterator<Integer> iterator = level.get(0).iterator();
        assertEquals(2, ((IteratorChain<?>) iterator).size());
        assertEquals(expected, read(iterator));
    }

    @Test
    void testSharedSubtreeIsVisitedOncePerOccurrence() {
        final CompositeCollection<Integer> shared = composite(list(1), new TreeList<>(list(2)));
        final CompositeCollection<Integer> root = composite(shared, composite(shared, list(3)), shared);
        final Iterator<Integer> iterator = root.iterator();
        assertEquals(3, ((IteratorChain<?>) iterator).size());
        assertEquals(list(1, 2, 1, 2, 3, 1, 2), read(iterator));
        assertSame(shared, root.getCollections().get(0));
        assertSame(shared, root.getCollections().get(2));
    }

    @Test
    void testNestedEmptyCompositesRemainPendingIteratorOccurrences() {
        final CompositeCollection<Integer> empty = composite();
        assertSame(EmptyIterator.emptyIterator(), empty.iterator());
        final CompositeCollection<Integer> root = composite(composite(empty, list()), empty,
                new LinkedList<Integer>(), composite(empty));
        final Iterator<Integer> iterator = root.iterator();
        assertTrue(iterator instanceof IteratorChain);
        assertEquals(4, ((IteratorChain<?>) iterator).size());
        assertFalse(iterator.hasNext());
        assertEquals(0, ((IteratorChain<?>) iterator).size());
    }

    @Test
    void testLeafValuesAreLiveAndNotCopiedDuringComposition() {
        final ArrayList<Integer> array = list(1, 2);
        final LinkedList<Integer> linked = new LinkedList<>(list(3));
        final TreeList<Integer> tree = new TreeList<>(list(4));
        final CompositeCollection<Integer> root = composite(composite(array, composite(linked)), tree);
        final Iterator<Integer> iterator = root.iterator();
        array.set(0, 10);
        linked.set(0, 30);
        tree.set(0, 40);
        assertEquals(list(10, 2, 30, 40), read(iterator));
    }

    @Test
    void testHierarchyChangesAfterCreationDoNotAppendToExistingIterator() {
        final CompositeCollection<Integer> branch = composite(list(1));
        final CompositeCollection<Integer> root = composite(branch);
        final List<Collection<Integer>> liveChildren = branch.getCollections();
        final Iterator<Integer> iterator = root.iterator();
        final ArrayList<Integer> added = list(2);
        branch.addComposited(added);
        assertSame(added, liveChildren.get(1));
        assertEquals(list(1), read(iterator));
        assertEquals(list(1, 2), read(root.iterator()));
    }

    @Test
    void testLeafIteratorsAreCreatedBeforeTraversalStarts() {
        final ArrayList<Integer> first = list(1);
        final TreeList<Integer> later = new TreeList<>(list(2));
        final Iterator<Integer> iterator = composite(composite(first), composite(later)).iterator();
        later.add(3);
        assertEquals(Integer.valueOf(1), iterator.next());
        assertThrows(ConcurrentModificationException.class, iterator::next);
    }

    @Test
    void testRemoveStillTargetsTheLastLeafAfterHasNextMovesToAnother() {
        final ArrayList<Integer> first = list(1);
        final LinkedList<Integer> second = new LinkedList<>(list(2));
        final TreeList<Integer> third = new TreeList<>(list(3));
        final Iterator<Integer> iterator = composite(composite(first, second), third).iterator();
        assertThrows(IllegalStateException.class, iterator::remove);
        assertEquals(Integer.valueOf(1), iterator.next());
        assertTrue(iterator.hasNext());
        iterator.remove();
        assertTrue(first.isEmpty());
        assertEquals(list(2), second);
        assertThrows(IllegalStateException.class, iterator::remove);
        assertEquals(Integer.valueOf(2), iterator.next());
        iterator.remove();
        assertTrue(second.isEmpty());
        assertEquals(Integer.valueOf(3), iterator.next());
        iterator.remove();
        assertTrue(third.isEmpty());
        assertFalse(iterator.hasNext());
    }

    @Test
    void testCustomLeafConstructionRemainsEagerAndOrdered() {
        final List<String> calls = new ArrayList<>();
        final Collection<Integer> first = callback("first", calls, () -> list(1).iterator());
        final Collection<Integer> second = callback("second", calls, () -> list(2).iterator());
        final Collection<Integer> third = callback("third", calls, () -> list(3).iterator());
        final Iterator<Integer> iterator = composite(composite(first, second), third).iterator();
        assertEquals(Arrays.asList("first", "second", "third"), calls);
        assertEquals(list(1, 2, 3), read(iterator));
        assertEquals(Arrays.asList("first", "second", "third"), calls);
    }

    @Test
    void testLateCustomCallbackSeesPreviouslyConstructedLeafIterators() {
        final List<String> calls = new ArrayList<>();
        final ArrayList<Integer> first = list(1);
        final Collection<Integer> later = callback("later", calls, () -> {
            first.add(2);
            return list(3).iterator();
        });
        final Iterator<Integer> iterator = composite(composite(first), later).iterator();
        assertEquals(Arrays.asList("later"), calls);
        assertThrows(ConcurrentModificationException.class, iterator::next);
    }

    @Test
    void testCompositeSubclassIteratorOverrideIsNotFlattenedAway() {
        final List<String> calls = new ArrayList<>();
        final CompositeCollection<Integer> subclass = new CompositeCollection<Integer>(list(1)) {
            private static final long serialVersionUID = 1L;

            @Override
            public Iterator<Integer> iterator() {
                calls.add("subclass");
                return list(99).iterator();
            }
        };
        final Iterator<Integer> iterator = composite(composite(list(0)), subclass).iterator();
        assertEquals(Arrays.asList("subclass"), calls);
        assertEquals(list(0, 99), read(iterator));
    }

    @Test
    void testListSubclassIteratorOverridesArePreserved() {
        final List<String> calls = new ArrayList<>();
        final ArrayList<Integer> array = new ArrayList<Integer>() {
            private static final long serialVersionUID = 1L;

            @Override
            public Iterator<Integer> iterator() {
                calls.add("array");
                return list(7).iterator();
            }
        };
        array.add(1);
        final TreeList<Integer> tree = new TreeList<Integer>(list(2)) {
            @Override
            public Iterator<Integer> iterator() {
                calls.add("tree");
                return list(8).iterator();
            }
        };
        final Iterator<Integer> iterator = composite(composite(array, tree)).iterator();
        assertEquals(Arrays.asList("array", "tree"), calls);
        assertEquals(list(7, 8), read(iterator));
    }

    @Test
    void testAncestorMutationIsDetectedAfterItsNestedChildFinishes() {
        final List<String> calls = new ArrayList<>();
        final CompositeCollection<Integer> root = composite();
        final Collection<Integer> mutating = callback("mutating", calls, () -> {
            root.addComposited(list(4));
            return list(1).iterator();
        });
        final Collection<Integer> nestedTail = callback("nested-tail", calls, () -> list(2).iterator());
        final Collection<Integer> rootTail = callback("root-tail", calls, () -> list(3).iterator());
        root.addComposited(composite(mutating, nestedTail), rootTail);
        assertThrows(ConcurrentModificationException.class, root::iterator);
        assertEquals(Arrays.asList("mutating", "nested-tail"), calls);
    }

    @Test
    void testChildMutationStopsBeforeItsFollowingLeafCallback() {
        final List<String> calls = new ArrayList<>();
        final CompositeCollection<Integer> child = composite();
        final Collection<Integer> mutating = callback("mutating", calls, () -> {
            child.addComposited(list(4));
            return list(1).iterator();
        });
        child.addComposited(mutating, callback("tail", calls, () -> list(2).iterator()));
        assertThrows(ConcurrentModificationException.class, composite(child)::iterator);
        assertEquals(Arrays.asList("mutating"), calls);
    }

    @Test
    void testCallbackFailurePrecedesAncestorModificationFailure() {
        final List<String> calls = new ArrayList<>();
        final CompositeCollection<Integer> root = composite(composite(list(0)));
        final IllegalArgumentException failure = new IllegalArgumentException("leaf failure");
        root.addComposited(callback("failing", calls, () -> {
            root.addComposited(list(4));
            throw failure;
        }));
        assertSame(failure, assertThrows(IllegalArgumentException.class, root::iterator));
        assertEquals(Arrays.asList("failing"), calls);
    }

    @Test
    void testNullIteratorFailurePrecedesAncestorModificationFailure() {
        final List<String> calls = new ArrayList<>();
        final CompositeCollection<Integer> root = composite(composite(list(0)));
        root.addComposited(callback("null-iterator", calls, () -> {
            root.addComposited(list(4));
            return null;
        }));
        assertThrows(NullPointerException.class, root::iterator);
        assertEquals(Arrays.asList("null-iterator"), calls);
    }

    @Test
    void testNullChildFailureStillFollowsEarlierLeafConstruction() {
        final List<String> calls = new ArrayList<>();
        final CompositeCollection<Integer> root = composite(callback("first", calls, () -> list(1).iterator()));
        insertRawChild(root, null);
        root.addComposited(composite(list(2)));
        assertThrows(NullPointerException.class, root::iterator);
        assertEquals(Arrays.asList("first"), calls);
    }

    @Test
    void testRawNonCollectionChildFailureStillFollowsEarlierLeafConstruction() {
        final List<String> calls = new ArrayList<>();
        final CompositeCollection<Integer> root = composite(callback("first", calls, () -> list(1).iterator()));
        insertRawChild(root, "not a collection");
        root.addComposited(composite(list(2)));
        assertThrows(ClassCastException.class, root::iterator);
        assertEquals(Arrays.asList("first"), calls);
    }

    @Test
    void testEarlierCallbackFailurePrecedesMalformedChildInspection() {
        for (final Object malformed : new Object[] { null, "not a collection" }) {
            final List<String> calls = new ArrayList<>();
            final IllegalStateException failure = new IllegalStateException("earlier leaf failed");
            final CompositeCollection<Integer> root = composite(callback("failing", calls, () -> {
                throw failure;
            }));
            insertRawChild(root, malformed);
            root.addComposited(composite(list(2)));
            assertSame(failure, assertThrows(IllegalStateException.class, root::iterator));
            assertEquals(Arrays.asList("failing"), calls);
        }
    }

    @Test
    void testEmptyIteratorChainReturnedByCustomLeafRemainsAnImmediateChild() {
        final List<String> calls = new ArrayList<>();
        final Collection<Integer> noPending = callback("empty-chain", calls, IteratorChain<Integer>::new);
        final CompositeCollection<Integer> empty = composite();
        final Iterator<Integer> iterator = composite(composite(empty, noPending), list(9)).iterator();
        assertEquals(Arrays.asList("empty-chain"), calls);
        assertEquals(2, ((IteratorChain<?>) iterator).size());
        assertEquals(list(9), read(iterator));
    }

    @Test
    void testSerializedSharedHierarchyStillUsesIndependentLeafIterators() throws Exception {
        final CompositeCollection<Integer> shared = composite(list(1), new LinkedList<>(list(2)));
        final CompositeCollection<Integer> restored = roundTrip(composite(shared, shared));
        assertSame(restored.getCollections().get(0), restored.getCollections().get(1));
        final Iterator<Integer> iterator = restored.iterator();
        assertEquals(2, ((IteratorChain<?>) iterator).size());
        assertEquals(list(1, 2, 1, 2), read(iterator));
    }

    @Test
    void testRootPendingCountAndLockFollowImmediateChildBoundaries() {
        final CompositeCollection<Integer> collection = composite(
                composite(list(1), composite(list(2))), composite(), composite(list(3), list(4)));
        final IteratorChain<Integer> root = (IteratorChain<Integer>) collection.iterator();
        assertEquals(3, root.size());
        assertFalse(root.isLocked());
        root.addIterator(list(5).iterator());
        assertEquals(4, root.size());
        assertFalse(root.isLocked());

        assertTrue(root.hasNext());
        assertTrue(root.isLocked());
        assertEquals(3, root.size());
        assertThrows(UnsupportedOperationException.class, () -> root.addIterator(null));
        assertEquals(Integer.valueOf(1), root.next());
        assertTrue(root.hasNext());
        assertEquals(3, root.size(), "moving inside the first composite does not consume another root child");
        assertEquals(Integer.valueOf(2), root.next());
        assertEquals(3, root.size());

        assertTrue(root.hasNext());
        assertEquals(1, root.size(), "the exhausted first child and empty second child precede the third child");
        assertEquals(Integer.valueOf(3), root.next());
        assertTrue(root.hasNext());
        assertEquals(1, root.size());
        assertEquals(Integer.valueOf(4), root.next());
        assertTrue(root.hasNext());
        assertEquals(0, root.size());
        assertEquals(Integer.valueOf(5), root.next());
        assertFalse(root.hasNext());
        assertEquals(0, root.size());
        assertTrue(root.isLocked());
    }

    @Test
    void testCustomChildChainAcceptsLateAdditionsUntilReached() {
        for (final boolean unmodifiable : new boolean[] { false, true }) {
            final List<String> calls = new ArrayList<>();
            final IteratorChain<Integer> source = new IteratorChain<>();
            final Iterator<Integer> child = unmodifiable ? UnmodifiableIterator.unmodifiableIterator(source) : source;
            final Collection<Integer> provider = callback("source", calls, () -> child);
            final IteratorChain<Integer> root = (IteratorChain<Integer>) composite(
                    composite(list(0, 1)), composite(provider), list(9)).iterator();
            assertEquals(Arrays.asList("source"), calls);
            assertEquals(3, root.size());
            assertFalse(source.isLocked());
            assertTrue(root.hasNext());
            assertTrue(root.isLocked());
            assertEquals(2, root.size());
            assertFalse(source.isLocked());
            source.addIterator(list(2).iterator());
            assertEquals(Integer.valueOf(0), root.next());
            source.addIterator(list(3).iterator());
            assertEquals(Integer.valueOf(1), root.next());
            assertFalse(source.isLocked());
            assertTrue(root.hasNext());
            assertTrue(source.isLocked());
            assertEquals(1, root.size());
            assertThrows(UnsupportedOperationException.class, () -> source.addIterator(list(4).iterator()));
            assertEquals(list(2, 3, 9), read(root));
            assertFalse(source.hasNext());
            assertEquals(Arrays.asList("source"), calls);
        }
    }

    @Test
    void testCustomChildChainRetainsSharedConsumptionAndRemovalState() {
        final List<String> calls = new ArrayList<>();
        final ArrayList<Integer> values = list(1, 2, 3);
        final IteratorChain<Integer> source = new IteratorChain<>(values.iterator());
        final Collection<Integer> provider = callback("source", calls, () -> source);
        final Iterator<Integer> first = composite(composite(provider)).iterator();
        final Iterator<Integer> second = composite(composite(provider)).iterator();
        assertEquals(Arrays.asList("source", "source"), calls);
        assertEquals(Integer.valueOf(1), source.next());
        assertEquals(Integer.valueOf(2), first.next());
        source.remove();
        assertThrows(IllegalStateException.class, first::remove);
        assertEquals(Integer.valueOf(3), second.next());
        second.remove();
        assertThrows(IllegalStateException.class, source::remove);
        assertEquals(list(1), values);
        assertFalse(first.hasNext());
        assertFalse(second.hasNext());
        assertFalse(source.hasNext());
    }

    @Test
    void testCustomChildChainHasNextOverrideKeepsItsMeaning() {
        final List<String> calls = new ArrayList<>();
        final AtomicInteger hasNextCalls = new AtomicInteger();
        final IteratorChain<Integer> source = new IteratorChain<Integer>(list(1).iterator()) {
            @Override
            public boolean hasNext() {
                hasNextCalls.incrementAndGet();
                return false;
            }
        };
        final Collection<Integer> provider = callback("source", calls, () -> source);
        assertEquals(list(0, 2), read(composite(composite(list(0)), composite(provider), list(2)).iterator()));
        assertEquals(1, hasNextCalls.get());
        assertEquals(1, source.size());
        assertFalse(source.isLocked());
    }

    @Test
    void testCustomChildChainNextOverrideKeepsItsMeaning() {
        final List<String> calls = new ArrayList<>();
        final AtomicInteger nextCalls = new AtomicInteger();
        final IteratorChain<Integer> source = new IteratorChain<Integer>(list(1, 2).iterator()) {
            @Override
            public Integer next() {
                nextCalls.incrementAndGet();
                return super.next() * 10;
            }
        };
        final Collection<Integer> provider = callback("source", calls, () -> source);
        assertEquals(list(10, 20, 3), read(composite(composite(provider), list(3)).iterator()));
        assertEquals(2, nextCalls.get());
        assertEquals(Arrays.asList("source"), calls);
    }

    @Test
    void testCustomChildChainRemoveOverrideSurvivesRootLookAhead() {
        final List<String> calls = new ArrayList<>();
        final ArrayList<Integer> values = list(1);
        final AtomicInteger removeCalls = new AtomicInteger();
        final IteratorChain<Integer> source = new IteratorChain<Integer>(values.iterator()) {
            @Override
            public void remove() {
                removeCalls.incrementAndGet();
                throw new UnsupportedOperationException("Read-only child chain");
            }
        };
        final Collection<Integer> provider = callback("source", calls, () -> source);
        final IteratorChain<Integer> root = (IteratorChain<Integer>) composite(composite(provider), list(2)).iterator();
        assertEquals(Integer.valueOf(1), root.next());
        assertTrue(root.hasNext());
        assertEquals(0, root.size());
        assertThrows(UnsupportedOperationException.class, root::remove);
        assertThrows(UnsupportedOperationException.class, root::remove);
        assertEquals(2, removeCalls.get());
        assertEquals(list(1), values);
        assertEquals(list(2), read(root));
    }

    @Test
    void testCustomChildChainUpdateOverrideCanRetryAfterFailure() {
        final List<String> calls = new ArrayList<>();
        final AtomicInteger updates = new AtomicInteger();
        final IllegalStateException failure = new IllegalStateException("Retry child traversal");
        final IteratorChain<Integer> source = new IteratorChain<Integer>(list(7).iterator()) {
            @Override
            protected void updateCurrentIterator() {
                if (updates.incrementAndGet() == 1) {
                    throw failure;
                }
                super.updateCurrentIterator();
            }
        };
        final Collection<Integer> provider = callback("source", calls, () -> source);
        final IteratorChain<Integer> root = (IteratorChain<Integer>) composite(
                composite(list(0)), composite(composite(provider))).iterator();
        assertEquals(Integer.valueOf(0), root.next());
        assertSame(failure, assertThrows(IllegalStateException.class, root::hasNext));
        assertTrue(root.isLocked());
        assertEquals(0, root.size());
        assertEquals(Integer.valueOf(7), root.next());
        assertEquals(2, updates.get());
        assertFalse(root.hasNext());
    }

    @Test
    void testReturnedExactRootKeepsSharedConsumptionAndRemovalState() {
        final ArrayList<Integer> values = list(1, 2, 3);
        final IteratorChain<Integer> root = (IteratorChain<Integer>) composite(
                composite(composite(values))).iterator();
        final IteratorChain<Integer> first = new IteratorChain<>(root);
        final IteratorChain<Integer> second = new IteratorChain<>(root);
        assertEquals(1, root.size());
        assertEquals(Integer.valueOf(1), root.next());
        assertEquals(0, root.size());
        assertEquals(Integer.valueOf(2), first.next());
        root.remove();
        assertThrows(IllegalStateException.class, first::remove);
        assertEquals(Integer.valueOf(3), second.next());
        second.remove();
        assertThrows(IllegalStateException.class, root::remove);
        assertEquals(list(1), values);
        assertFalse(first.hasNext());
        assertFalse(second.hasNext());
        assertFalse(root.hasNext());
    }

    @Test
    void testCyclicCompositeRetainsItsLegacyFailureRoute() {
        final CompositeCollection<Integer> root = composite();
        root.addComposited(root);
        assertThrows(StackOverflowError.class, root::iterator);
    }
}
