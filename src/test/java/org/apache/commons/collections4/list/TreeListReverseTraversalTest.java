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
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the exact-owner direct-slot reverse search and hashing optimizations. */
class TreeListReverseTraversalTest {
    private static final class Stored {
        private final int key;
        Stored(final int key) { this.key = key; }
        @Override public boolean equals(final Object other) {
            return other instanceof Stored && ((Stored) other).key == key;
        }
        @Override public int hashCode() { return key; }
    }
    private static final class Search {
        private final int key;
        private int calls;
        Search(final int key) { this.key = key; }
        @Override public boolean equals(final Object other) {
            calls++;
            return other instanceof Stored && ((Stored) other).key == key;
        }
        @Override public int hashCode() { return key; }
    }
    private static final class Hooked extends TreeList<Object> {
        private int iteratorCalls;
        private int listIteratorCalls;
        @Override public Iterator<Object> iterator() {
            iteratorCalls++;
            return super.iterator();
        }
        @Override public ListIterator<Object> listIterator(final int index) {
            listIteratorCalls++;
            return super.listIterator(index);
        }
    }
    private static int inheritedReverse(final List<?> collection, final Object value) {
        final ListIterator<?> iterator = collection.listIterator(collection.size());
        while (iterator.hasPrevious()) {
            final Object current = iterator.previous();
            if (value == null ? current == null : value.equals(current)) {
                return iterator.nextIndex();
            }
        }
        return -1;
    }
    private static int inheritedHash(final List<?> collection) {
        int hash = 1;
        for (final Object value : collection) {
            hash = 31 * hash + (value == null ? 0 : value.hashCode());
        }
        return hash;
    }
    private static final class MutatingEquals {
        private final TreeList<Object> target;
        MutatingEquals(final TreeList<Object> target) { this.target = target; }
        @Override public boolean equals(final Object other) {
            target.add("injected");
            return false;
        }
        @Override public int hashCode() { return 1; }
    }
    private static final class MutatingHash {
        private final TreeList<Object> target;
        MutatingHash(final TreeList<Object> target) { this.target = target; }
        @Override public int hashCode() {
            target.add("injected");
            return 123;
        }
    }

    @Test
    void randomizedValuesAndNullsMatchInheritedListBehavior() {
        final Random random = new Random(0x1414abcdL);
        for (int sample = 0; sample < 250; sample++) {
            final List<Object> values = new ArrayList<>();
            final int length = sample < 4 ? sample : 1 + random.nextInt(1024);
            for (int index = 0; index < length; index++) {
                final int key = random.nextInt(33);
                values.add(key == 0 ? null : key < 5 ? "word" + key : Integer.valueOf(key));
            }
            final TreeList<Object> tree = new TreeList<>(values);
            assertEquals(values.hashCode(), tree.hashCode());
            assertEquals(inheritedHash(tree), tree.hashCode());
            for (int key = -1; key < 36; key++) {
                final Object wanted = key < 0 ? null : key < 5 ? "word" + key : Integer.valueOf(key);
                assertEquals(values.lastIndexOf(wanted), tree.lastIndexOf(wanted));
                assertEquals(inheritedReverse(tree, wanted), tree.lastIndexOf(wanted));
            }
            if (!tree.isEmpty()) {
                tree.remove(tree.size() / 2);
                values.remove(values.size() / 2);
                assertEquals(values.hashCode(), tree.hashCode());
                assertEquals(values.lastIndexOf(null), tree.lastIndexOf(null));
            }
        }
    }

    @Test
    void asymmetricEqualsSearchDirectionIsUnchanged() {
        final TreeList<Object> tree = new TreeList<>(Arrays.asList(new Stored(1), new Stored(2), new Stored(1)));
        final Search found = new Search(1);
        assertEquals(2, tree.lastIndexOf(found));
        assertEquals(1, found.calls);
        final Search missing = new Search(99);
        assertEquals(-1, tree.lastIndexOf(missing));
        assertEquals(3, missing.calls);
    }

    @Test
    void subclassesKeepTheirOriginalIteratorCallbacks() {
        final Hooked subclass = new Hooked();
        subclass.addAll(Arrays.asList((Object) "a", "b", "a"));
        assertEquals(2, subclass.lastIndexOf("a"));
        assertTrue(subclass.listIteratorCalls > 0);
        assertEquals(inheritedHash(subclass), subclass.hashCode());
        assertTrue(subclass.iteratorCalls > 0);
    }

    @Test
    void callbacksPreserveFailFastAndLastElementBehavior() {
        final TreeList<Object> reverse = new TreeList<>(Arrays.asList((Object) "first", "last"));
        assertThrows(ConcurrentModificationException.class, () -> reverse.lastIndexOf(new MutatingEquals(reverse)));
        final TreeList<Object> hash = new TreeList<>();
        hash.add("first");
        hash.add(new MutatingHash(hash));
        assertThrows(ConcurrentModificationException.class, hash::hashCode);
        final TreeList<Object> singleton = new TreeList<>(Arrays.asList((Object) "single"));
        assertEquals(-1, singleton.lastIndexOf(new MutatingEquals(singleton)));
    }
}
