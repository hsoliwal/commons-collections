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
/*
 * Modifications Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.
 * SPDX-License-Identifier: Apache-2.0
 * Parent-owned dual tree slots replace retained node graphs.
 */
package com.m3.collections.apache.bidimap;

import static com.m3.collections.apache.bidimap.TreeBidiMap.DataElement.KEY;
import static com.m3.collections.apache.bidimap.TreeBidiMap.DataElement.VALUE;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import com.m3.collections.apache.KeyValue;
import com.m3.collections.apache.MapIterator;
import com.m3.collections.apache.OrderedBidiMap;
import com.m3.collections.apache.OrderedIterator;
import com.m3.collections.apache.OrderedMapIterator;
import com.m3.collections.apache.iterators.EmptyOrderedMapIterator;
import com.m3.collections.apache.keyvalue.UnmodifiableMapEntry;

/**
 * Red-Black tree-based implementation of BidiMap where all objects added
 * implement the {@code Comparable} interface.
 * <p>
 * This class guarantees that the map will be in both ascending key order
 * and ascending value order, sorted according to the natural order for
 * the key's and value's classes.
 * </p>
 * <p>
 * This Map is intended for applications that need to be able to look
 * up a key-value pairing by either key or value, and need to do so
 * with equal efficiency.
 * </p>
 * <p>
 * While that goal could be accomplished by taking a pair of TreeMaps
 * and redirecting requests to the appropriate TreeMap (for example,
 * containsKey would be directed to the TreeMap that maps values to
 * keys, containsValue would be directed to the TreeMap that maps keys
 * to values), there are problems with that implementation.
 * If the data contained in the TreeMaps is large, the cost of redundant
 * storage becomes significant. The {@link DualTreeBidiMap} and
 * {@link DualHashBidiMap} implementations use this approach.
 * </p>
 * <p>
 * This solution keeps minimizes the data storage by holding data only once.
 * The red-black algorithm is based on {@link TreeMap}, but has been modified
 * to simultaneously map a tree node by key and by value. This doubles the
 * cost of put operations (but so does using two TreeMaps), and nearly doubles
 * the cost of remove operations (there is a savings in that the lookup of the
 * node to be removed only has to be performed once). And since only one node
 * contains the key and value, storage is significantly less than that
 * required by two TreeMaps.
 * </p>
 * <p>
 * The Map.Entry instances returned by the appropriate methods will
 * not allow setValue() and will throw an
 * UnsupportedOperationException on attempts to call that method.
 * </p>
 *
 * @param <K> The type of the keys in this map
 * @param <V> The type of the values in this map
 * @since 3.0 (previously DoubleOrderedMap v2.0)
 */
public class TreeBidiMap<K extends Comparable<K>, V extends Comparable<V>>
    implements OrderedBidiMap<K, V>, Serializable {

    /**
     * A view of this map.
     */
    abstract class AbstractView<E> extends AbstractSet<E> {

        /** Whether to return KEY or VALUE order. */
        final DataElement orderType;

        /**
         * Constructs a new instance.
         *
         * @param orderType  The KEY or VALUE int for the order
         */
        AbstractView(final DataElement orderType) {
            this.orderType = orderType;
        }

        @Override
        public void clear() {
            TreeBidiMap.this.clear();
        }

        @Override
        public int size() {
            return TreeBidiMap.this.size();
        }
    }

    /**
     * An iterator over the map.
     */
    abstract class AbstractViewIterator {

        /** Whether to return KEY or VALUE order. */
        private final DataElement orderType;

        /** The last node returned by the iterator. */
        int lastReturnedNode;
        K lastReturnedKey;
        V lastReturnedValue;
        private int retiredSlot;
        private K retiredKey;
        private V retiredValue;
        private HashPage retiredHashPage;
        private int retiredHashOffset;

        /** The next node to be returned by the iterator. */
        private int nextNode;

        /** The previous node in the sequence returned by the iterator. */
        private int previousNode;

        /** The modification count. */
        private int expectedModifications;

        /**
         * Constructs a new instance.
         *
         * @param orderType  The KEY or VALUE int for the order
         */
        AbstractViewIterator(final DataElement orderType) {
            this.orderType = orderType;
            expectedModifications = modifications;
            nextNode = leastNode(rootNode[orderType.ordinal()], orderType);
            lastReturnedNode = 0;
            previousNode = 0;
        }

        public final boolean hasNext() {
            return nextNode != 0;
        }

        public boolean hasPrevious() {
            return previousNode != 0;
        }

        protected int navigateNext() {
            if (nextNode == 0) {
                throw new NoSuchElementException();
            }
            if (modifications != expectedModifications) {
                throw new ConcurrentModificationException();
            }
            lastReturnedNode = nextNode;
            rememberLast();
            previousNode = nextNode;
            nextNode = nextNode == retiredSlot ? 0 : nextGreater(nextNode, orderType);
            return lastReturnedNode;
        }

        protected int navigatePrevious() {
            if (previousNode == 0) {
                throw new NoSuchElementException();
            }
            if (modifications != expectedModifications) {
                throw new ConcurrentModificationException();
            }
            nextNode = lastReturnedNode;
            if (nextNode == 0) {
                nextNode = previousNode == retiredSlot ? 0 : nextGreater(previousNode, orderType);
            }
            lastReturnedNode = previousNode;
            rememberLast();
            previousNode = previousNode == retiredSlot ? 0 : nextSmaller(previousNode, orderType);
            return lastReturnedNode;
        }

        private void rememberLast() {
            if (lastReturnedNode == retiredSlot) {
                lastReturnedKey = retiredKey;
                lastReturnedValue = retiredValue;
            } else {
                lastReturnedKey = keyAt(lastReturnedNode);
                lastReturnedValue = valueAt(lastReturnedNode);
            }
        }

        final Node<K, V> projectLast() {
            return lastReturnedNode == retiredSlot
                    ? new Node<>(lastReturnedKey, lastReturnedValue, retiredHashPage, retiredHashOffset)
                    : exposeEntry(lastReturnedNode, lastReturnedKey, lastReturnedValue);
        }

        public final void remove() {
            if (lastReturnedNode == 0) {
                throw new IllegalStateException();
            }
            if (modifications != expectedModifications) {
                throw new ConcurrentModificationException();
            }
            if (lastReturnedNode == retiredSlot) {
                // The original detached-node removal clears both roots again.
                rootNode[KEY.ordinal()] = 0;
                rootNode[VALUE.ordinal()] = 0;
                resetStorage();
                shrink();
            } else {
                if (nextNode == lastReturnedNode) {
                    retiredSlot = lastReturnedNode;
                    retiredKey = lastReturnedKey;
                    retiredValue = lastReturnedValue;
                    retiredHashPage = entryHashPages == null ? null : entryHashPages[lastReturnedNode];
                    retiredHashOffset = colors[lastReturnedNode] >> 2;
                }
                doRedBlackDelete(lastReturnedNode);
            }
            expectedModifications++;
            lastReturnedNode = 0;
            lastReturnedKey = null;
            lastReturnedValue = null;
            if (nextNode == 0) {
                previousNode = greatestNode(rootNode[orderType.ordinal()], orderType);
            } else {
                previousNode = nextNode == retiredSlot ? 0 : nextSmaller(nextNode, orderType);
            }
        }
    }

    enum DataElement {
        KEY("key"), VALUE("value");

        private final String description;

        /**
         * Creates a new TreeBidiMap.DataElement.
         *
         * @param description  The description for the element
         */
        DataElement(final String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    /**
     * A view of this map.
     */
    final class EntryView extends AbstractView<Map.Entry<K, V>> {

        EntryView() {
            super(KEY);
        }

        @Override
        public boolean contains(final Object obj) {
            if (!(obj instanceof Map.Entry)) {
                return false;
            }
            final Map.Entry<?, ?> entry = (Map.Entry<?, ?>) obj;
            final Object value = entry.getValue();
            final int node = lookupKey(entry.getKey());
            return node != 0 && Objects.equals(valueAt(node), value);
        }

        @Override
        public Iterator<Map.Entry<K, V>> iterator() {
            return new ViewMapEntryIterator();
        }

        @Override
        public boolean remove(final Object obj) {
            if (!(obj instanceof Map.Entry)) {
                return false;
            }
            final Map.Entry<?, ?> entry = (Map.Entry<?, ?>) obj;
            final Object value = entry.getValue();
            final int node = lookupKey(entry.getKey());
            if (node != 0 && Objects.equals(valueAt(node), value)) {
                doRedBlackDelete(node);
                return true;
            }
            return false;
        }
    }

    /**
     * The inverse map implementation.
     */
    final class Inverse implements OrderedBidiMap<V, K> {

        /** Store the keySet once created. */
        private Set<V> inverseKeySet;

        /** Store the valuesSet once created. */
        private Set<K> inverseValuesSet;

        /** Store the entrySet once created. */
        private Set<Map.Entry<V, K>> inverseEntrySet;

        @Override
        public void clear() {
            TreeBidiMap.this.clear();
        }

        @Override
        public boolean containsKey(final Object key) {
            return TreeBidiMap.this.containsValue(key);
        }

        @Override
        public boolean containsValue(final Object value) {
            return TreeBidiMap.this.containsKey(value);
        }

        @Override
        public Set<Map.Entry<V, K>> entrySet() {
            if (inverseEntrySet == null) {
                inverseEntrySet = new InverseEntryView();
            }
            return inverseEntrySet;
        }

        @Override
        public boolean equals(final Object obj) {
            return TreeBidiMap.this.doEquals(obj, VALUE);
        }

        @Override
        public V firstKey() {
            if (TreeBidiMap.this.nodeCount == 0) {
                throw new NoSuchElementException("Map is empty");
            }
            return valueAt(leastNode(TreeBidiMap.this.rootNode[VALUE.ordinal()], VALUE));
        }

        @Override
        public K get(final Object key) {
            return TreeBidiMap.this.getKey(key);
        }

        @Override
        public V getKey(final Object value) {
            return TreeBidiMap.this.get(value);
        }

        @Override
        public int hashCode() {
            return TreeBidiMap.this.doHashCode(VALUE);
        }

        @Override
        public OrderedBidiMap<K, V> inverseBidiMap() {
            return TreeBidiMap.this;
        }

        @Override
        public boolean isEmpty() {
            return TreeBidiMap.this.isEmpty();
        }

        @Override
        public Set<V> keySet() {
            if (inverseKeySet == null) {
                inverseKeySet = new ValueView(VALUE);
            }
            return inverseKeySet;
        }

        @Override
        public V lastKey() {
            if (TreeBidiMap.this.nodeCount == 0) {
                throw new NoSuchElementException("Map is empty");
            }
            return valueAt(greatestNode(TreeBidiMap.this.rootNode[VALUE.ordinal()], VALUE));
        }

        @Override
        public OrderedMapIterator<V, K> mapIterator() {
            if (isEmpty()) {
                return EmptyOrderedMapIterator.<V, K>emptyOrderedMapIterator();
            }
            return new InverseViewMapIterator(VALUE);
        }

        @Override
        public V nextKey(final V key) {
            checkKey(key);
            final int node = nextGreater(TreeBidiMap.this.<V>lookup(key, VALUE), VALUE);
            return node == 0 ? null : valueAt(node);
        }

        @Override
        public V previousKey(final V key) {
            checkKey(key);
            final int node = TreeBidiMap.this.nextSmaller(TreeBidiMap.this.<V>lookup(key, VALUE), VALUE);
            return node == 0 ? null : valueAt(node);
        }

        @Override
        public K put(final V key, final K value) {
            final K result = get(key);
            TreeBidiMap.this.doPut(value, key);
            return result;
        }

        @Override
        public void putAll(final Map<? extends V, ? extends K> map) {
            for (final Map.Entry<? extends V, ? extends K> e : map.entrySet()) {
                put(e.getKey(), e.getValue());
            }
        }

        @Override
        public K remove(final Object key) {
            return TreeBidiMap.this.removeValue(key);
        }

        @Override
        public V removeValue(final Object value) {
            return TreeBidiMap.this.remove(value);
        }

        @Override
        public int size() {
            return TreeBidiMap.this.size();
        }

        @Override
        public String toString() {
            return TreeBidiMap.this.doToString(VALUE);
        }

        @Override
        public Set<K> values() {
            if (inverseValuesSet == null) {
                inverseValuesSet = new KeyView(VALUE);
            }
            return inverseValuesSet;
        }
    }

    /**
     * A view of this map.
     */
    final class InverseEntryView extends AbstractView<Map.Entry<V, K>> {

        InverseEntryView() {
            super(VALUE);
        }

        @Override
        public boolean contains(final Object obj) {
            if (!(obj instanceof Map.Entry)) {
                return false;
            }
            final Map.Entry<?, ?> entry = (Map.Entry<?, ?>) obj;
            final Object value = entry.getValue();
            final int node = lookupValue(entry.getKey());
            return node != 0 && Objects.equals(keyAt(node), value);
        }

        @Override
        public Iterator<Map.Entry<V, K>> iterator() {
            return new InverseViewMapEntryIterator();
        }

        @Override
        public boolean remove(final Object obj) {
            if (!(obj instanceof Map.Entry)) {
                return false;
            }
            final Map.Entry<?, ?> entry = (Map.Entry<?, ?>) obj;
            final Object value = entry.getValue();
            final int node = lookupValue(entry.getKey());
            if (node != 0 && Objects.equals(keyAt(node), value)) {
                doRedBlackDelete(node);
                return true;
            }
            return false;
        }
    }

    /**
     * An iterator over the inverse map entries.
     */
    final class InverseViewMapEntryIterator extends AbstractViewIterator implements OrderedIterator<Map.Entry<V, K>> {

        /**
         * Constructs a new instance.
         */
        InverseViewMapEntryIterator() {
            super(VALUE);
        }

        private Map.Entry<V, K> createEntry() {
            return new UnmodifiableMapEntry<>(lastReturnedValue, lastReturnedKey);
        }

        @Override
        public Map.Entry<V, K> next() {
            navigateNext();
            return createEntry();
        }

        @Override
        public Map.Entry<V, K> previous() {
            navigatePrevious();
            return createEntry();
        }
    }

    /**
     * An iterator over the map.
     */
    final class InverseViewMapIterator extends AbstractViewIterator implements OrderedMapIterator<V, K> {

        /**
         * Creates a new TreeBidiMap.InverseViewMapIterator.
         */
        InverseViewMapIterator(final DataElement orderType) {
            super(orderType);
        }

        @Override
        public V getKey() {
            if (lastReturnedNode == 0) {
                throw new IllegalStateException(
                        "Iterator getKey() can only be called after next() and before remove()");
            }
            return lastReturnedValue;
        }

        @Override
        public K getValue() {
            if (lastReturnedNode == 0) {
                throw new IllegalStateException(
                        "Iterator getValue() can only be called after next() and before remove()");
            }
            return lastReturnedKey;
        }

        @Override
        public V next() {
            navigateNext();
            return lastReturnedValue;
        }

        @Override
        public V previous() {
            navigatePrevious();
            return lastReturnedValue;
        }

        /**
         * Always throws {@link UnsupportedOperationException}.
         *
         * @param value Ignored.
         * @throws UnsupportedOperationException Always thrown.
         */
        @Override
        public K setValue(final K value) {
            throw new UnsupportedOperationException();
        }
    }
    final class KeyView extends AbstractView<K> {

        /**
         * Creates a new TreeBidiMap.KeyView.
         */
        KeyView(final DataElement orderType) {
            super(orderType);
        }

        @Override
        public boolean contains(final Object obj) {
            checkNonNullComparable(obj, KEY);
            return lookupKey(obj) != 0;
        }

        @Override
        public Iterator<K> iterator() {
            return new ViewMapIterator(orderType);
        }

        @Override
        public boolean remove(final Object o) {
            return doRemoveKey(o) != null;
        }

    }

    /**
     * An immutable entry created only when the entry view exposes a mapping.
     * Tree links and colors are stored in the owning map, not in this entry.
     */
    static class Node<K extends Comparable<K>, V extends Comparable<V>> implements Map.Entry<K, V>, KeyValue<K, V> {

        private final K key;
        private final V value;
        private int hashCodeValue;
        private volatile boolean calculatedHashCode;
        private final HashPage hashPage;
        private final int hashOffset;

        /**
         * Makes an immutable exposed entry with the given key and value.
         *
         * @param key The key of this node
         * @param value The value of this node
         */
        Node(final K key, final V value) {
            this(key, value, null, 0);
        }

        private Node(final K key, final V value, final HashPage hashPage, final int hashOffset) {
            this.key = key;
            this.value = value;
            this.hashPage = hashPage;
            this.hashOffset = hashOffset;
            calculatedHashCode = false;
        }

        /**
         * Compares the specified object with this entry for equality.
         * Returns true if the given object is also a map entry and
         * the two entries represent the same mapping.
         *
         * @param obj  The object to be compared for equality with this entry.
         * @return true if the specified object is equal to this entry.
         */
        @Override
        public boolean equals(final Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof Map.Entry)) {
                return false;
            }
            final Map.Entry<?, ?> e = (Map.Entry<?, ?>) obj;
            return Objects.equals(getKey(), e.getKey()) && Objects.equals(getValue(), e.getValue());
        }

        /**
         * Gets the key.
         *
         * @return The key corresponding to this entry.
         */
        @Override
        public K getKey() {
            return key;
        }

        /**
         * Gets the value.
         *
         * @return The value corresponding to this entry.
         */
        @Override
        public V getValue() {
            return value;
        }

        /**
         * @return The hash code value for this map entry.
         */
        @Override
        public int hashCode() {
            if (hashPage != null) {
                // A later reentrant completion must reach every existing projection.
                return hashPage.hash(hashOffset, getKey(), getValue());
            }
            if (!calculatedHashCode) {
                hashCodeValue = getKey().hashCode() ^ getValue().hashCode();
                calculatedHashCode = true;
            }
            return hashCodeValue;
        }

        /**
         * Optional operation that is not permitted in this implementation.
         *
         * @param ignored this parameter is ignored.
         * @return does not return
         * @throws UnsupportedOperationException always
         */
        @Override
        public V setValue(final V ignored) throws UnsupportedOperationException {
            throw new UnsupportedOperationException("Map.Entry.setValue is not supported");
        }

    }

    final class ValueView extends AbstractView<V> {

        /**
         * Creates a new TreeBidiMap.ValueView.
         */
        ValueView(final DataElement orderType) {
            super(orderType);
        }

        @Override
        public boolean contains(final Object obj) {
            checkNonNullComparable(obj, VALUE);
            return lookupValue(obj) != 0;
        }

        @Override
        public Iterator<V> iterator() {
            return new InverseViewMapIterator(orderType);
        }

        @Override
        public boolean remove(final Object o) {
            return doRemoveValue(o) != null;
        }

    }

    /**
     * An iterator over the map entries.
     */
    final class ViewMapEntryIterator extends AbstractViewIterator implements OrderedIterator<Map.Entry<K, V>> {

        /**
         * Constructs a new instance.
         */
        ViewMapEntryIterator() {
            super(KEY);
        }

        @Override
        public Map.Entry<K, V> next() {
            navigateNext();
            return projectLast();
        }

        @Override
        public Map.Entry<K, V> previous() {
            navigatePrevious();
            return projectLast();
        }
    }

    /**
     * An iterator over the map.
     */
    final class ViewMapIterator extends AbstractViewIterator implements OrderedMapIterator<K, V> {

        /**
         * Constructs a new instance.
         */
        ViewMapIterator(final DataElement orderType) {
            super(orderType);
        }

        @Override
        public K getKey() {
            if (lastReturnedNode == 0) {
                throw new IllegalStateException(
                        "Iterator getKey() can only be called after next() and before remove()");
            }
            return lastReturnedKey;
        }

        @Override
        public V getValue() {
            if (lastReturnedNode == 0) {
                throw new IllegalStateException(
                        "Iterator getValue() can only be called after next() and before remove()");
            }
            return lastReturnedValue;
        }

        @Override
        public K next() {
            navigateNext();
            return lastReturnedKey;
        }

        @Override
        public K previous() {
            navigatePrevious();
            return lastReturnedKey;
        }

        /**
         * Always throws {@link UnsupportedOperationException}.
         *
         * @param value Ignored.
         * @throws UnsupportedOperationException Always thrown.
         */
        @Override
        public V setValue(final V value) {
            throw new UnsupportedOperationException();
        }
    }


    /** Releases all backing arrays; exposed immutable entries remain valid. */
    private void resetStorage() {
        keys = EMPTY_PAYLOAD;
        values = EMPTY_PAYLOAD;
        leftKey = EMPTY_LINKS;
        rightKey = EMPTY_LINKS;
        parentKey = EMPTY_LINKS;
        leftValue = EMPTY_LINKS;
        rightValue = EMPTY_LINKS;
        parentValue = EMPTY_LINKS;
        colors = EMPTY_COLORS;
        entryHashPages = null;
        currentHashPage = null;
        nextSlot = 1;
        freeSlot = 0;
    }

    /** Grows all lanes before publishing any of them. Slot zero denotes null. */
    private void ensureCapacity(final int required) {
        if (required <= keys.length) {
            return;
        }
        final int capacity = (int) Math.min(Integer.MAX_VALUE,
                Math.max(8L, Math.max(required, keys.length + (long) (keys.length >> 1) + 1)));
        final Object[] newKeys = Arrays.copyOf(keys, capacity);
        final Object[] newValues = Arrays.copyOf(values, capacity);
        final int[] newLeftKey = Arrays.copyOf(leftKey, capacity);
        final int[] newRightKey = Arrays.copyOf(rightKey, capacity);
        final int[] newParentKey = Arrays.copyOf(parentKey, capacity);
        final int[] newLeftValue = Arrays.copyOf(leftValue, capacity);
        final int[] newRightValue = Arrays.copyOf(rightValue, capacity);
        final int[] newParentValue = Arrays.copyOf(parentValue, capacity);
        final byte[] newColors = Arrays.copyOf(colors, capacity);
        final HashPage[] newPages = entryHashPages == null ? null : Arrays.copyOf(entryHashPages, capacity);
        keys = newKeys;
        values = newValues;
        leftKey = newLeftKey;
        rightKey = newRightKey;
        parentKey = newParentKey;
        leftValue = newLeftValue;
        rightValue = newRightValue;
        parentValue = newParentValue;
        colors = newColors;
        entryHashPages = newPages;
    }

    private int allocateSlot(final K key, final V value) {
        final int slot;
        if (freeSlot != 0) {
            slot = freeSlot;
            freeSlot = leftKey[slot];
            leftKey[slot] = 0;
        } else {
            if (nextSlot == Integer.MAX_VALUE) {
                throw new OutOfMemoryError("Required array size too large");
            }
            ensureCapacity(nextSlot + 1);
            slot = nextSlot++;
        }
        keys[slot] = key;
        values[slot] = value;
        colors[slot] = 3;
        return slot;
    }

    private void releaseSlot(final int slot) {
        keys[slot] = null;
        values[slot] = null;
        rightKey[slot] = 0;
        parentKey[slot] = 0;
        leftValue[slot] = 0;
        rightValue[slot] = 0;
        parentValue[slot] = 0;
        colors[slot] = 0;
        if (entryHashPages != null && entryHashPages[slot] != null) {
            final HashPage page = entryHashPages[slot];
            entryHashPages[slot] = null;
            if (--page.liveSlots == 0 && page == currentHashPage) {
                currentHashPage = null;
            }
        }
        leftKey[slot] = freeSlot;
        freeSlot = slot;
    }

    @SuppressWarnings("unchecked")
    private K keyAt(final int slot) {
        if (slot == 0) {
            throw new NullPointerException();
        }
        return (K) keys[slot];
    }

    @SuppressWarnings("unchecked")
    private V valueAt(final int slot) {
        if (slot == 0) {
            throw new NullPointerException();
        }
        return (V) values[slot];
    }

    private Object dataAt(final int slot, final DataElement dataElement) {
        return dataElement == KEY ? keyAt(slot) : valueAt(slot);
    }

    /**
     * A small primitive cache with stable cells. Exposed entries own their cell's
     * lifetime; no cell is reassigned, and pages never link to one another or to
     * the map or payloads. A removed entry retains at most this bounded page.
     */
    private static final class HashPage {
        private final int[] hashes = new int[8];
        private int valid;
        private int allocated;
        private int liveSlots;

        private int hash(final int offset, final Object key, final Object value) {
            final int bit = 1 << offset;
            synchronized (this) {
                if ((valid & bit) != 0) {
                    return hashes[offset];
                }
            }
            // User callbacks run outside the page monitor, including reentrant callbacks.
            final int computed = key.hashCode() ^ value.hashCode();
            synchronized (this) {
                // A reentrant calculation finishes before this one, as in the original entry.
                hashes[offset] = computed;
                valid |= bit;
                return computed;
            }
        }
    }

    /** Shares a primitive hash cell, never a caller-visible Entry object. */
    private synchronized Node<K, V> exposeEntry(final int slot, final K key, final V value) {
        final HashPage[] pages = entryHashPages == null ? new HashPage[keys.length] : entryHashPages;
        HashPage page = pages[slot];
        if (page == null) {
            page = currentHashPage;
            if (page == null || page.allocated == 8) {
                page = new HashPage();
            }
            final int offset = page.allocated++;
            page.liveSlots++;
            colors[slot] = (byte) (colors[slot] | offset << 2);
            pages[slot] = page;
            currentHashPage = page;
            entryHashPages = pages;
        }
        return new Node<>(key, value, page, colors[slot] >> 2);
    }

    private void setLeft(final int slot, final int child, final DataElement dataElement) {
        (dataElement == KEY ? leftKey : leftValue)[slot] = child;
    }

    private void setRight(final int slot, final int child, final DataElement dataElement) {
        (dataElement == KEY ? rightKey : rightValue)[slot] = child;
    }

    private void setParent(final int slot, final int parent, final DataElement dataElement) {
        (dataElement == KEY ? parentKey : parentValue)[slot] = parent;
    }

    private boolean isLeftChild(final int slot, final DataElement dataElement) {
        final int parent = getParent(slot, dataElement);
        return parent != 0 && getLeftChild(parent, dataElement) == slot;
    }

    private boolean isRightChild(final int slot, final DataElement dataElement) {
        final int parent = getParent(slot, dataElement);
        return parent != 0 && getRightChild(parent, dataElement) == slot;
    }

    private void swapColors(final int first, final int second, final DataElement dataElement) {
        final boolean firstBlack = isBlack(first, dataElement);
        copyColor(second, first, dataElement);
        if (firstBlack) {
            makeBlack(second, dataElement);
        } else {
            makeRed(second, dataElement);
        }
    }

    private static final long serialVersionUID = 721969328361807L;

    /**
     * Checks a key for validity (non-null and implements Comparable)
     *
     * @param key The key to be checked
     * @throws NullPointerException if key is null
     * @throws ClassCastException if key is not Comparable
     */
    private static void checkKey(final Object key) {
        checkNonNullComparable(key, KEY);
    }

    /**
     * Checks a key and a value for validity (non-null and implements
     * Comparable)
     *
     * @param key The key to be checked
     * @param value The value to be checked
     * @throws NullPointerException if key or value is null
     * @throws ClassCastException if key or value is not Comparable
     */
    private static void checkKeyAndValue(final Object key, final Object value) {
        checkKey(key);
        checkValue(value);
    }

    /**
     * Checks if an object is fit to be proper input ... has to be
     * Comparable and non-null.
     *
     * @param obj The object being checked
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     *
     * @throws NullPointerException if o is null
     * @throws ClassCastException if o is not Comparable
     */
    private static void checkNonNullComparable(final Object obj, final DataElement dataElement) {
        Objects.requireNonNull(obj, Objects.toString(dataElement));
        if (!(obj instanceof Comparable)) {
            throw new ClassCastException(dataElement + " must be Comparable");
        }
    }

    /**
     * Checks a value for validity (non-null and implements Comparable)
     *
     * @param value The value to be checked
     * @throws NullPointerException if value is null
     * @throws ClassCastException if value is not Comparable
     */
    private static void checkValue(final Object value) {
        checkNonNullComparable(value, VALUE);
    }

    /**
     * Compares two objects.
     *
     * @param o1  The first object
     * @param o2  The second object
     * @return negative value if o1 &lt; o2; 0 if o1 == o2; positive
     *         value if o1 &gt; o2
     */
    private static <T extends Comparable<T>> int compare(final T o1, final T o2) {
        return o1.compareTo(o2);
    }

    /**
     * Is the specified black red? If the node does not exist, sure,
     * it's black, thank you.
     *
     * @param node The node (may be null) in question
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     */
    private boolean isBlack(final int node, final DataElement dataElement) {
        return node == 0 || (colors[node] & 1 << dataElement.ordinal()) != 0;
    }

    /**
     * Is the specified node red? If the node does not exist, no, it's
     * black, thank you.
     *
     * @param node The node (may be null) in question
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     */
    private boolean isRed(final int node, final DataElement dataElement) {
        return node != 0 && (colors[node] & 1 << dataElement.ordinal()) == 0;
    }

    /**
     * Forces a node (if it exists) black.
     *
     * @param node The node (may be null) in question
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     */
    private void makeBlack(final int node, final DataElement dataElement) {
        if (node != 0) {
            colors[node] = (byte) (colors[node] | 1 << dataElement.ordinal());
        }
    }

    /**
     * Forces a node (if it exists) red.
     *
     * @param node The node (may be null) in question
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     */
    private void makeRed(final int node, final DataElement dataElement) {
        if (node != 0) {
            colors[node] = (byte) (colors[node] & ~(1 << dataElement.ordinal()));
        }
    }

    private static final Object[] EMPTY_PAYLOAD = new Object[0];
    private static final int[] EMPTY_LINKS = new int[0];
    private static final byte[] EMPTY_COLORS = new byte[0];

    private transient int[] rootNode;
    private transient Object[] keys;
    private transient Object[] values;
    private transient int[] leftKey;
    private transient int[] rightKey;
    private transient int[] parentKey;
    private transient int[] leftValue;
    private transient int[] rightValue;
    private transient int[] parentValue;
    private transient byte[] colors;
    private transient HashPage[] entryHashPages;
    private transient HashPage currentHashPage;
    private transient int nextSlot;
    private transient int freeSlot;

    private transient int nodeCount;

    private transient int modifications;

    private transient Set<K> keySet;

    private transient Set<V> valuesSet;

    private transient Set<Map.Entry<K, V>> entrySet;

    private transient Inverse inverse;

    /**
     * Constructs a new empty TreeBidiMap.
     */
    public TreeBidiMap() {
        rootNode = new int[2];
        resetStorage();
    }

    /**
     * Constructs a new TreeBidiMap by copying an existing Map.
     *
     * @param map  The map to copy
     * @throws ClassCastException if the keys/values in the map are
     *  not Comparable or are not mutually comparable
     * @throws NullPointerException if any key or value in the map is null
     */
    public TreeBidiMap(final Map<? extends K, ? extends V> map) {
        this();
        putAll(map);
    }

    /**
     * Removes all mappings from this map.
     */
    @Override
    public void clear() {
        modify();

        nodeCount = 0;
        rootNode[KEY.ordinal()] = 0;
        rootNode[VALUE.ordinal()] = 0;
        resetStorage();
    }

    /**
     * Checks whether this map contains a mapping for the specified key.
     * <p>
     * The key must implement {@code Comparable}.
     *
     * @param key  key whose presence in this map is to be tested
     * @return true if this map contains a mapping for the specified key
     * @throws ClassCastException if the key is of an inappropriate type
     * @throws NullPointerException if the key is null
     */
    @Override
    public boolean containsKey(final Object key) {
        checkKey(key);
        return lookupKey(key) != 0;
    }

    /**
     * Checks whether this map contains a mapping for the specified value.
     * <p>
     * The value must implement {@code Comparable}.
     *
     * @param value  value whose presence in this map is to be tested
     * @return true if this map contains a mapping for the specified value
     * @throws ClassCastException if the value is of an inappropriate type
     * @throws NullPointerException if the value is null
     */
    @Override
    public boolean containsValue(final Object value) {
        checkValue(value);
        return lookupValue(value) != 0;
    }

    /**
     * Copies the color from one node to another, dealing with the fact
     * that one or both nodes may, in fact, be null.
     *
     * @param from The node whose color we're copying; may be null
     * @param to The node whose color we're changing; may be null
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     */
    private void copyColor(final int from, final int to, final DataElement dataElement) {
        if (isBlack(from, dataElement)) {
            makeBlack(to, dataElement);
        } else {
            makeRed(to, dataElement);
        }
    }

    /**
     * Compares for equals as per the API.
     *
     * @param obj  The object to compare to
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     * @return true if equal
     */
    private boolean doEquals(final Object obj, final DataElement dataElement) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof Map)) {
            return false;
        }
        final Map<?, ?> other = (Map<?, ?>) obj;
        if (other.size() != size()) {
            return false;
        }

        if (nodeCount > 0) {
            try {
                for (final MapIterator<?, ?> it = getMapIterator(dataElement); it.hasNext(); ) {
                    final Object key = it.next();
                    final Object value = it.getValue();
                    if (!value.equals(other.get(key))) {
                        return false;
                    }
                }
            } catch (final ClassCastException | NullPointerException ex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Gets the hash code value for this map as per the API.
     *
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     * @return The hash code value for this map
     */
    private int doHashCode(final DataElement dataElement) {
        int total = 0;
        if (nodeCount > 0) {
            for (final MapIterator<?, ?> it = getMapIterator(dataElement); it.hasNext(); ) {
                final Object key = it.next();
                final Object value = it.getValue();
                total += key.hashCode() ^ value.hashCode();
            }
        }
        return total;
    }

    /**
     * Puts logic.
     *
     * @param key  The key, always the main map key
     * @param value  The value, always the main map value
     */
    private void doPut(final K key, final V value) {
        checkKeyAndValue(key, value);

        // store previous and remove previous mappings
        doRemoveKey(key);
        doRemoveValue(value);

        int node = rootNode[KEY.ordinal()];
        if (node == 0) {
            // map is empty
            final int root = allocateSlot(key, value);
            rootNode[KEY.ordinal()] = root;
            rootNode[VALUE.ordinal()] = root;
            grow();

        } else {
            // add new mapping
            while (true) {
                final int cmp = compare(key, keyAt(node));

                if (cmp == 0) {
                    // shouldn't happen
                    throw new IllegalArgumentException("Cannot store a duplicate key (\"" + key + "\") in this Map");
                }
                if (cmp < 0) {
                    if (getLeftChild(node, KEY) == 0) {
                        final int newNode = allocateSlot(key, value);

                        try {
                            insertValue(newNode);
                        } catch (final RuntimeException | Error ex) {
                            releaseSlot(newNode);
                            throw ex;
                        }
                        setLeft(node, newNode, KEY);
                        setParent(newNode, node, KEY);
                        doRedBlackInsert(newNode, KEY);
                        grow();

                        break;
                    }
                    node = getLeftChild(node, KEY);
                } else { // cmp > 0
                    if (getRightChild(node, KEY) == 0) {
                        final int newNode = allocateSlot(key, value);

                        try {
                            insertValue(newNode);
                        } catch (final RuntimeException | Error ex) {
                            releaseSlot(newNode);
                            throw ex;
                        }
                        setRight(node, newNode, KEY);
                        setParent(newNode, node, KEY);
                        doRedBlackInsert(newNode, KEY);
                        grow();

                        break;
                    }
                    node = getRightChild(node, KEY);
                }
            }
        }
    }

    /**
     * Complicated red-black delete stuff. Based on Sun's TreeMap
     * implementation, though it's barely recognizable anymore.
     *
     * @param deletedNode The node to be deleted
     */
    private void doRedBlackDelete(final int deletedNode) {
        for (final DataElement dataElement : DataElement.values()) {
            // if deleted node has both left and children, swap with
            // the next greater node
            if (getLeftChild(deletedNode, dataElement) != 0 && getRightChild(deletedNode, dataElement) != 0) {
                swapPosition(nextGreater(deletedNode, dataElement), deletedNode, dataElement);
            }
            final int replacement = getLeftChild(deletedNode, dataElement) != 0 ? getLeftChild(deletedNode, dataElement) : getRightChild(deletedNode, dataElement);
            if (replacement != 0) {
                setParent(replacement, getParent(deletedNode, dataElement), dataElement);
                if (getParent(deletedNode, dataElement) == 0) {
                    rootNode[dataElement.ordinal()] = replacement;
                } else if (deletedNode == getLeftChild(getParent(deletedNode, dataElement), dataElement)) {
                    setLeft(getParent(deletedNode, dataElement), replacement, dataElement);
                } else {
                    setRight(getParent(deletedNode, dataElement), replacement, dataElement);
                }
                setLeft(deletedNode, 0, dataElement);
                setRight(deletedNode, 0, dataElement);
                setParent(deletedNode, 0, dataElement);
                if (isBlack(deletedNode, dataElement)) {
                    doRedBlackDeleteFixup(replacement, dataElement);
                }
            } else if (getParent(deletedNode, dataElement) == 0) {
                // replacement is 0
                // empty tree
                rootNode[dataElement.ordinal()] = 0;
            } else {
                // deleted node had no children
                if (isBlack(deletedNode, dataElement)) {
                    doRedBlackDeleteFixup(deletedNode, dataElement);
                }
                if (getParent(deletedNode, dataElement) != 0) {
                    if (deletedNode == getLeftChild(getParent(deletedNode, dataElement), dataElement)) {
                        setLeft(getParent(deletedNode, dataElement), 0, dataElement);
                    } else {
                        setRight(getParent(deletedNode, dataElement), 0, dataElement);
                    }
                    setParent(deletedNode, 0, dataElement);
                }
            }
        }
        shrink();
        releaseSlot(deletedNode);
    }

    /**
     * Complicated red-black delete stuff. Based on Sun's TreeMap
     * implementation, though it's barely recognizable anymore. This
     * rebalances the tree (somewhat, as red-black trees are not
     * perfectly balanced -- perfect balancing takes longer)
     *
     * @param replacementNode The node being replaced
     * @param dataElement  The KEY or VALUE int
     */
    private void doRedBlackDeleteFixup(final int replacementNode, final DataElement dataElement) {
        int currentNode = replacementNode;

        while (currentNode != rootNode[dataElement.ordinal()] && isBlack(currentNode, dataElement)) {
            if (isLeftChild(currentNode, dataElement)) {
                int siblingNode = getRightChild(getParent(currentNode, dataElement), dataElement);

                if (isRed(siblingNode, dataElement)) {
                    makeBlack(siblingNode, dataElement);
                    makeRed(getParent(currentNode, dataElement), dataElement);
                    rotateLeft(getParent(currentNode, dataElement), dataElement);

                    siblingNode = getRightChild(getParent(currentNode, dataElement), dataElement);
                }

                if (isBlack(getLeftChild(siblingNode, dataElement), dataElement)
                    && isBlack(getRightChild(siblingNode, dataElement), dataElement)) {
                    makeRed(siblingNode, dataElement);

                    currentNode = getParent(currentNode, dataElement);
                } else {
                    if (isBlack(getRightChild(siblingNode, dataElement), dataElement)) {
                        makeBlack(getLeftChild(siblingNode, dataElement), dataElement);
                        makeRed(siblingNode, dataElement);
                        rotateRight(siblingNode, dataElement);

                        siblingNode = getRightChild(getParent(currentNode, dataElement), dataElement);
                    }

                    copyColor(getParent(currentNode, dataElement), siblingNode, dataElement);
                    makeBlack(getParent(currentNode, dataElement), dataElement);
                    makeBlack(getRightChild(siblingNode, dataElement), dataElement);
                    rotateLeft(getParent(currentNode, dataElement), dataElement);

                    currentNode = rootNode[dataElement.ordinal()];
                }
            } else {
                int siblingNode = getLeftChild(getParent(currentNode, dataElement), dataElement);

                if (isRed(siblingNode, dataElement)) {
                    makeBlack(siblingNode, dataElement);
                    makeRed(getParent(currentNode, dataElement), dataElement);
                    rotateRight(getParent(currentNode, dataElement), dataElement);

                    siblingNode = getLeftChild(getParent(currentNode, dataElement), dataElement);
                }

                if (isBlack(getRightChild(siblingNode, dataElement), dataElement)
                    && isBlack(getLeftChild(siblingNode, dataElement), dataElement)) {
                    makeRed(siblingNode, dataElement);

                    currentNode = getParent(currentNode, dataElement);
                } else {
                    if (isBlack(getLeftChild(siblingNode, dataElement), dataElement)) {
                        makeBlack(getRightChild(siblingNode, dataElement), dataElement);
                        makeRed(siblingNode, dataElement);
                        rotateLeft(siblingNode, dataElement);

                        siblingNode = getLeftChild(getParent(currentNode, dataElement), dataElement);
                    }

                    copyColor(getParent(currentNode, dataElement), siblingNode, dataElement);
                    makeBlack(getParent(currentNode, dataElement), dataElement);
                    makeBlack(getLeftChild(siblingNode, dataElement), dataElement);
                    rotateRight(getParent(currentNode, dataElement), dataElement);

                    currentNode = rootNode[dataElement.ordinal()];
                }
            }
        }

        makeBlack(currentNode, dataElement);
    }

    /**
     * Complicated red-black insert stuff. Based on Sun's TreeMap
     * implementation, though it's barely recognizable anymore.
     *
     * @param insertedNode The node to be inserted
     * @param dataElement  The KEY or VALUE int
     */
    private void doRedBlackInsert(final int insertedNode, final DataElement dataElement) {
        int currentNode = insertedNode;
        makeRed(currentNode, dataElement);

        while (currentNode != 0
            && currentNode != rootNode[dataElement.ordinal()]
            && isRed(getParent(currentNode, dataElement), dataElement)) {
            if (isLeftChild(currentNode, dataElement)) {
                final int y = getRightChild(getGrandParent(currentNode, dataElement), dataElement);

                if (isRed(y, dataElement)) {
                    makeBlack(getParent(currentNode, dataElement), dataElement);
                    makeBlack(y, dataElement);
                    makeRed(getGrandParent(currentNode, dataElement), dataElement);

                    currentNode = getGrandParent(currentNode, dataElement);
                } else {
                    //dead code?
                    if (isRightChild(currentNode, dataElement)) {
                        currentNode = getParent(currentNode, dataElement);

                        rotateLeft(currentNode, dataElement);
                    }

                    makeBlack(getParent(currentNode, dataElement), dataElement);
                    makeRed(getGrandParent(currentNode, dataElement), dataElement);

                    if (getGrandParent(currentNode, dataElement) != 0) {
                        rotateRight(getGrandParent(currentNode, dataElement), dataElement);
                    }
                }
            } else {

                // just like clause above, except swap left for right
                final int y = getLeftChild(getGrandParent(currentNode, dataElement), dataElement);

                if (isRed(y, dataElement)) {
                    makeBlack(getParent(currentNode, dataElement), dataElement);
                    makeBlack(y, dataElement);
                    makeRed(getGrandParent(currentNode, dataElement), dataElement);

                    currentNode = getGrandParent(currentNode, dataElement);
                } else {
                    //dead code?
                    if (isLeftChild(currentNode, dataElement)) {
                        currentNode = getParent(currentNode, dataElement);

                        rotateRight(currentNode, dataElement);
                    }

                    makeBlack(getParent(currentNode, dataElement), dataElement);
                    makeRed(getGrandParent(currentNode, dataElement), dataElement);

                    if (getGrandParent(currentNode, dataElement) != 0) {
                        rotateLeft(getGrandParent(currentNode, dataElement), dataElement);
                    }
                }
            }
        }

        makeBlack(rootNode[dataElement.ordinal()], dataElement);
    }

    private V doRemoveKey(final Object key) {
        final int node = lookupKey(key);
        if (node == 0) {
            return null;
        }
        final V result = valueAt(node);
        doRedBlackDelete(node);
        return result;
    }

    private K doRemoveValue(final Object value) {
        final int node = lookupValue(value);
        if (node == 0) {
            return null;
        }
        final K result = keyAt(node);
        doRedBlackDelete(node);
        return result;
    }

    /**
     * Gets the string form of this map as per AbstractMap.
     *
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     * @return The string form of this map
     */
    private String doToString(final DataElement dataElement) {
        if (nodeCount == 0) {
            return "{}";
        }
        final StringBuilder buf = new StringBuilder(nodeCount * 32);
        buf.append('{');
        final MapIterator<?, ?> it = getMapIterator(dataElement);
        boolean hasNext = it.hasNext();
        while (hasNext) {
            final Object key = it.next();
            final Object value = it.getValue();
            buf.append(key == this ? "(this Map)" : key)
                .append('=')
                .append(value == this ? "(this Map)" : value);

            hasNext = it.hasNext();
            if (hasNext) {
                buf.append(", ");
            }
        }

        buf.append('}');
        return buf.toString();
    }

    /**
     * Returns a set view of the entries contained in this map in key order.
     * For simple iteration through the map, the MapIterator is quicker.
     * <p>
     * The set is backed by the map, so changes to the map are reflected in
     * the set, and vice-versa. If the map is modified while an iteration over
     * the set is in progress, the results of the iteration are undefined.
     * <p>
     * The set supports element removal, which removes the corresponding mapping
     * from the map. It does not support the add or addAll operations.
     * The returned MapEntry objects do not support setValue.
     *
     * @return A set view of the values contained in this map.
     */
    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        if (entrySet == null) {
            entrySet = new EntryView();
        }
        return entrySet;
    }

    /**
     * Compares for equals as per the API.
     *
     * @param obj  The object to compare to
     * @return true if equal
     */
    @Override
    public boolean equals(final Object obj) {
        return this.doEquals(obj, KEY);
    }

    /**
     * Gets the first (lowest) key currently in this map.
     *
     * @return The first (lowest) key currently in this sorted map
     * @throws NoSuchElementException if this map is empty
     */
    @Override
    public K firstKey() {
        if (nodeCount == 0) {
            throw new NoSuchElementException("Map is empty");
        }
        return keyAt(leastNode(rootNode[KEY.ordinal()], KEY));
    }

    /**
     * Gets the value to which this map maps the specified key.
     * Returns null if the map contains no mapping for this key.
     * <p>
     * The key must implement {@code Comparable}.
     *
     * @param key  key whose associated value is to be returned
     * @return The value to which this map maps the specified key,
     *  or null if the map contains no mapping for this key
     * @throws ClassCastException if the key is of an inappropriate type
     * @throws NullPointerException if the key is null
     */
    @Override
    public V get(final Object key) {
        checkKey(key);
        final int node = lookupKey(key);
        return node == 0 ? null : valueAt(node);
    }

    /**
     * Gets a node's grandparent. mind you, the node, its parent, or
     * its grandparent may not exist. No problem.
     *
     * @param node The node (may be null) in question
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     */
    private int getGrandParent(final int node, final DataElement dataElement) {
        return getParent(getParent(node, dataElement), dataElement);
    }

    /**
     * Gets the key to which this map maps the specified value.
     * Returns null if the map contains no mapping for this value.
     * <p>
     * The value must implement {@code Comparable}.
     *
     * @param value  value whose associated key is to be returned.
     * @return The key to which this map maps the specified value,
     *  or null if the map contains no mapping for this value.
     * @throws ClassCastException if the value is of an inappropriate type
     * @throws NullPointerException if the value is null
     */
    @Override
    public K getKey(final Object value) {
        checkValue(value);
        final int node = lookupValue(value);
        return node == 0 ? null : keyAt(node);
    }

    /**
     * Gets a node's left child. mind you, the node may not exist. no
     * problem.
     *
     * @param node The node (may be null) in question
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     */
    private int getLeftChild(final int node, final DataElement dataElement) {
        return node == 0 ? 0 : (dataElement == KEY ? leftKey : leftValue)[node];
    }

    private MapIterator<?, ?> getMapIterator(final DataElement dataElement) {
        switch (dataElement) {
        case KEY:
            return new ViewMapIterator(KEY);
        case VALUE:
            return new InverseViewMapIterator(VALUE);
        default:
            throw new IllegalArgumentException();
        }
    }

    /**
     * Gets a node's parent. mind you, the node, or its parent, may not
     * exist. no problem.
     *
     * @param node The node (may be null) in question
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     */
    private int getParent(final int node, final DataElement dataElement) {
        return node == 0 ? 0 : (dataElement == KEY ? parentKey : parentValue)[node];
    }

    /**
     * Gets a node's right child. mind you, the node may not exist. no
     * problem.
     *
     * @param node The node (may be null) in question
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     */
    private int getRightChild(final int node, final DataElement dataElement) {
        return node == 0 ? 0 : (dataElement == KEY ? rightKey : rightValue)[node];
    }

    /**
     * Finds the greatest node from a given node.
     *
     * @param node  The node from which we will start searching
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     * @return The greatest node, from the specified node
     */
    private int greatestNode(final int node, final DataElement dataElement) {
        int rval = node;
        if (rval != 0) {
            while (getRightChild(rval, dataElement) != 0) {
                rval = getRightChild(rval, dataElement);
            }
        }
        return rval;
    }

    /**
     * Bumps up the size and note that the map has changed.
     */
    private void grow() {
        modify();
        nodeCount++;
    }

    /**
     * Gets the hash code value for this map as per the API.
     *
     * @return The hash code value for this map
     */
    @Override
    public int hashCode() {
        return this.doHashCode(KEY);
    }

    /**
     * Inserts a node by its value.
     *
     * @param newNode The node to be inserted
     * @throws IllegalArgumentException if the node already exists
     *                                     in the value mapping
     */
    private void insertValue(final int newNode) throws IllegalArgumentException {
        int node = rootNode[VALUE.ordinal()];

        while (true) {
            final int cmp = compare(valueAt(newNode), valueAt(node));

            if (cmp == 0) {
                throw new IllegalArgumentException(
                    "Cannot store a duplicate value (\"" + dataAt(newNode, VALUE) + "\") in this Map");
            }
            if (cmp < 0) {
                if (getLeftChild(node, VALUE) == 0) {
                    setLeft(node, newNode, VALUE);
                    setParent(newNode, node, VALUE);
                    doRedBlackInsert(newNode, VALUE);

                    break;
                }
                node = getLeftChild(node, VALUE);
            } else { // cmp > 0
                if (getRightChild(node, VALUE) == 0) {
                    setRight(node, newNode, VALUE);
                    setParent(newNode, node, VALUE);
                    doRedBlackInsert(newNode, VALUE);

                    break;
                }
                node = getRightChild(node, VALUE);
            }
        }
    }

    /**
     * Gets the inverse map for comparison.
     *
     * @return The inverse map
     */
    @Override
    public OrderedBidiMap<V, K> inverseBidiMap() {
        if (inverse == null) {
            inverse = new Inverse();
        }
        return inverse;
    }

    /**
     * Checks whether the map is empty or not.
     *
     * @return true if the map is empty
     */
    @Override
    public boolean isEmpty() {
        return nodeCount == 0;
    }

    /**
     * Returns a set view of the keys contained in this map in key order.
     * <p>
     * The set is backed by the map, so changes to the map are reflected in
     * the set, and vice-versa. If the map is modified while an iteration over
     * the set is in progress, the results of the iteration are undefined.
     * <p>
     * The set supports element removal, which removes the corresponding mapping
     * from the map. It does not support the add or addAll operations.
     *
     * @return A set view of the keys contained in this map.
     */
    @Override
    public Set<K> keySet() {
        if (keySet == null) {
            keySet = new KeyView(KEY);
        }
        return keySet;
    }

    /**
     * Gets the last (highest) key currently in this map.
     *
     * @return The last (highest) key currently in this sorted map
     * @throws NoSuchElementException if this map is empty
     */
    @Override
    public K lastKey() {
        if (nodeCount == 0) {
            throw new NoSuchElementException("Map is empty");
        }
        return keyAt(greatestNode(rootNode[KEY.ordinal()], KEY));
    }

    /**
     * Finds the least node from a given node.
     *
     * @param node  The node from which we will start searching
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     * @return The smallest node, from the specified node, in the
     *         specified mapping
     */
    private int leastNode(final int node, final DataElement dataElement) {
        int rval = node;
        if (rval != 0) {
            while (getLeftChild(rval, dataElement) != 0) {
                rval = getLeftChild(rval, dataElement);
            }
        }
        return rval;
    }

    /**
     * Does the actual lookup of a piece of data.
     *
     * @param data The key or value to be looked up
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     * @return The desired Node, or null if there is no mapping of the
     *         specified data
     */
    @SuppressWarnings("unchecked")
    private <T extends Comparable<T>> int lookup(final Object data, final DataElement dataElement) {
        int rval = 0;
        int node = rootNode[dataElement.ordinal()];

        while (node != 0) {
            final int cmp = compare((T) data, (T) dataAt(node, dataElement));
            if (cmp == 0) {
                rval = node;
                break;
            }
            node = cmp < 0 ? getLeftChild(node, dataElement) : getRightChild(node, dataElement);
        }

        return rval;
    }

    private int lookupKey(final Object key) {
        return this.<K>lookup(key, KEY);
    }

    private int lookupValue(final Object value) {
        return this.<V>lookup(value, VALUE);
    }

    @Override
    public OrderedMapIterator<K, V> mapIterator() {
        if (isEmpty()) {
            return EmptyOrderedMapIterator.<K, V>emptyOrderedMapIterator();
        }
        return new ViewMapIterator(KEY);
    }

    /**
     * Increments the modification count -- used to check for
     * concurrent modification of the map through the map and through
     * an Iterator from one of its Set or Collection views.
     */
    private void modify() {
        modifications++;
    }

    /**
     * Gets the next larger node from the specified node.
     *
     * @param node The node to be searched from
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     * @return The specified node
     */
    private int nextGreater(final int node, final DataElement dataElement) {
        final int rval;
        if (node == 0) {
            rval = 0;
        } else if (getRightChild(node, dataElement) != 0) {
            // everything to the node's right is larger. The least of
            // the right node's descendants is the next larger node
            rval = leastNode(getRightChild(node, dataElement), dataElement);
        } else {
            // traverse up our ancestry until we find an ancestor that
            // is 0 or one whose left child is our ancestor. If we
            // find a 0, then this node IS the largest node in the
            // tree, and there is no greater node. Otherwise, we are
            // the largest node in the subtree on that ancestor's left
            // ... and that ancestor is the next greatest node
            int parent = getParent(node, dataElement);
            int child = node;

            while (parent != 0 && child == getRightChild(parent, dataElement)) {
                child = parent;
                parent = getParent(parent, dataElement);
            }
            rval = parent;
        }
        return rval;
    }

    /**
     * Gets the next key after the one specified.
     * <p>
     * The key must implement {@code Comparable}.
     *
     * @param key The key to search for next from
     * @return The next key, null if no match or at end
     */
    @Override
    public K nextKey(final K key) {
        checkKey(key);
        final int node = nextGreater(lookupKey(key), KEY);
        return node == 0 ? null : keyAt(node);
    }

    /**
     * Gets the next smaller node from the specified node.
     *
     * @param node The node to be searched from
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     * @return The specified node
     */
    private int nextSmaller(final int node, final DataElement dataElement) {
        final int rval;
        if (node == 0) {
            rval = 0;
        } else if (getLeftChild(node, dataElement) != 0) {
            // everything to the node's left is smaller. The greatest of
            // the left node's descendants is the next smaller node
            rval = greatestNode(getLeftChild(node, dataElement), dataElement);
        } else {
            // traverse up our ancestry until we find an ancestor that
            // is 0 or one whose right child is our ancestor. If we
            // find a 0, then this node IS the largest node in the
            // tree, and there is no greater node. Otherwise, we are
            // the largest node in the subtree on that ancestor's right
            // ... and that ancestor is the next greatest node
            int parent = getParent(node, dataElement);
            int child = node;

            while (parent != 0 && child == getLeftChild(parent, dataElement)) {
                child = parent;
                parent = getParent(parent, dataElement);
            }
            rval = parent;
        }
        return rval;
    }

    /**
     * Gets the previous key before the one specified.
     * <p>
     * The key must implement {@code Comparable}.
     *
     * @param key The key to search for previous from
     * @return The previous key, null if no match or at start
     */
    @Override
    public K previousKey(final K key) {
        checkKey(key);
        final int node = nextSmaller(lookupKey(key), KEY);
        return node == 0 ? null : keyAt(node);
    }

    /**
     * Puts the key-value pair into the map, replacing any previous pair.
     * <p>
     * When adding a key-value pair, the value may already exist in the map
     * against a different key. That mapping is removed, to ensure that the
     * value only occurs once in the inverse map.
     * <pre>
     *  BidiMap map1 = new TreeBidiMap();
     *  map.put("A","B");  // contains A mapped to B, as per Map
     *  map.put("A","C");  // contains A mapped to C, as per Map
     *
     *  BidiMap map2 = new TreeBidiMap();
     *  map.put("A","B");  // contains A mapped to B, as per Map
     *  map.put("C","B");  // contains C mapped to B, key A is removed
     * </pre>
     * <p>
     * Both key and value must implement {@code Comparable}.
     *
     * @param key  key with which the specified value is to be  associated
     * @param value  value to be associated with the specified key
     * @return The previous value for the key
     * @throws ClassCastException if the key is of an inappropriate type
     * @throws NullPointerException if the key is null
     */
    @Override
    public V put(final K key, final V value) {
        final V result = get(key);
        doPut(key, value);
        return result;
    }

    /**
     * Puts all the mappings from the specified map into this map.
     * <p>
     * All keys and values must implement {@code Comparable}.
     *
     * @param map  The map to copy from
     */
    @Override
    public void putAll(final Map<? extends K, ? extends V> map) {
        for (final Map.Entry<? extends K, ? extends V> e : map.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    /**
     * Deserializes the content of the stream.
     *
     * @param stream The input stream
     * @throws IOException Thrown if an error occurs while reading from the stream
     * @throws ClassNotFoundException if an object read from the stream cannot be loaded
     */
    @SuppressWarnings("unchecked") // This will fail at runtime if the stream is incorrect
    private void readObject(final ObjectInputStream stream) throws IOException, ClassNotFoundException {
        stream.defaultReadObject();
        rootNode = new int[2];
        resetStorage();
        final int size = stream.readInt();
        for (int i = 0; i < size; i++) {
            final K k = (K) stream.readObject();
            final V v = (V) stream.readObject();
            put(k, v);
        }
    }

    /**
     * Removes the mapping for this key from this map if present.
     * <p>
     * The key must implement {@code Comparable}.
     *
     * @param key  key whose mapping is to be removed from the map.
     * @return previous value associated with specified key,
     *  or null if there was no mapping for key.
     * @throws ClassCastException if the key is of an inappropriate type
     * @throws NullPointerException if the key is null
     */
    @Override
    public V remove(final Object key) {
        return doRemoveKey(key);
    }

    /**
     * Removes the mapping for this value from this map if present.
     * <p>
     * The value must implement {@code Comparable}.
     *
     * @param value  value whose mapping is to be removed from the map
     * @return previous key associated with specified value,
     *  or null if there was no mapping for value.
     * @throws ClassCastException if the value is of an inappropriate type
     * @throws NullPointerException if the value is null
     */
    @Override
    public K removeValue(final Object value) {
        return doRemoveValue(value);
    }

    /**
     * Does a rotate left. standard fare in the world of balanced trees.
     *
     * @param node The node to be rotated
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     */
    private void rotateLeft(final int node, final DataElement dataElement) {
        final int rightChild = getRightChild(node, dataElement);
        setRight(node, getLeftChild(rightChild, dataElement), dataElement);

        if (getLeftChild(rightChild, dataElement) != 0) {
            setParent(getLeftChild(rightChild, dataElement), node, dataElement);
        }
        setParent(rightChild, getParent(node, dataElement), dataElement);

        if (getParent(node, dataElement) == 0) {
            // node was the root ... now its right child is the root
            rootNode[dataElement.ordinal()] = rightChild;
        } else if (getLeftChild(getParent(node, dataElement), dataElement) == node) {
            setLeft(getParent(node, dataElement), rightChild, dataElement);
        } else {
            setRight(getParent(node, dataElement), rightChild, dataElement);
        }

        setLeft(rightChild, node, dataElement);
        setParent(node, rightChild, dataElement);
    }

    /**
     * Does a rotate right. standard fare in the world of balanced trees.
     *
     * @param node The node to be rotated
     * @param dataElement  either the {@link DataElement#KEY key}
     *                     or the {@link DataElement#VALUE value}.
     */
    private void rotateRight(final int node, final DataElement dataElement) {
        final int leftChild = getLeftChild(node, dataElement);
        setLeft(node, getRightChild(leftChild, dataElement), dataElement);
        if (getRightChild(leftChild, dataElement) != 0) {
            setParent(getRightChild(leftChild, dataElement), node, dataElement);
        }
        setParent(leftChild, getParent(node, dataElement), dataElement);

        if (getParent(node, dataElement) == 0) {
            // node was the root ... now its left child is the root
            rootNode[dataElement.ordinal()] = leftChild;
        } else if (getRightChild(getParent(node, dataElement), dataElement) == node) {
            setRight(getParent(node, dataElement), leftChild, dataElement);
        } else {
            setLeft(getParent(node, dataElement), leftChild, dataElement);
        }

        setRight(leftChild, node, dataElement);
        setParent(node, leftChild, dataElement);
    }

    /**
     * Decrements the size and note that the map has changed.
     */
    private void shrink() {
        modify();
        nodeCount--;
    }

    /**
     * Returns the number of key-value mappings in this map.
     *
     * @return The number of key-value mappings in this map
     */
    @Override
    public int size() {
        return nodeCount;
    }

    /**
     * Swaps two nodes (except for their content), taking care of
     * special cases where one is the other's parent ... hey, it
     * happens.
     *
     * @param x one node
     * @param y another node
     * @param dataElement  The KEY or VALUE int
     */
    private void swapPosition(final int x, final int y, final DataElement dataElement) {
        // Save initial values.
        final int xFormerParent = getParent(x, dataElement);
        final int xFormerLeftChild = getLeftChild(x, dataElement);
        final int xFormerRightChild = getRightChild(x, dataElement);
        final int yFormerParent = getParent(y, dataElement);
        final int yFormerLeftChild = getLeftChild(y, dataElement);
        final int yFormerRightChild = getRightChild(y, dataElement);
        final boolean xWasLeftChild =
                getParent(x, dataElement) != 0 && x == getLeftChild(getParent(x, dataElement), dataElement);
        final boolean yWasLeftChild =
                getParent(y, dataElement) != 0 && y == getLeftChild(getParent(y, dataElement), dataElement);

        // Swap, handling special cases of one being the other's parent.
        if (x == yFormerParent) { // x was y's parent
            setParent(x, y, dataElement);

            if (yWasLeftChild) {
                setLeft(y, x, dataElement);
                setRight(y, xFormerRightChild, dataElement);
            } else {
                setRight(y, x, dataElement);
                setLeft(y, xFormerLeftChild, dataElement);
            }
        } else {
            setParent(x, yFormerParent, dataElement);

            if (yFormerParent != 0) {
                if (yWasLeftChild) {
                    setLeft(yFormerParent, x, dataElement);
                } else {
                    setRight(yFormerParent, x, dataElement);
                }
            }

            setLeft(y, xFormerLeftChild, dataElement);
            setRight(y, xFormerRightChild, dataElement);
        }

        if (y == xFormerParent) { // y was x's parent
            setParent(y, x, dataElement);

            if (xWasLeftChild) {
                setLeft(x, y, dataElement);
                setRight(x, yFormerRightChild, dataElement);
            } else {
                setRight(x, y, dataElement);
                setLeft(x, yFormerLeftChild, dataElement);
            }
        } else {
            setParent(y, xFormerParent, dataElement);

            if (xFormerParent != 0) {
                if (xWasLeftChild) {
                    setLeft(xFormerParent, y, dataElement);
                } else {
                    setRight(xFormerParent, y, dataElement);
                }
            }

            setLeft(x, yFormerLeftChild, dataElement);
            setRight(x, yFormerRightChild, dataElement);
        }

        // Fix children's parent pointers
        if (getLeftChild(x, dataElement) != 0) {
            setParent(getLeftChild(x, dataElement), x, dataElement);
        }

        if (getRightChild(x, dataElement) != 0) {
            setParent(getRightChild(x, dataElement), x, dataElement);
        }

        if (getLeftChild(y, dataElement) != 0) {
            setParent(getLeftChild(y, dataElement), y, dataElement);
        }

        if (getRightChild(y, dataElement) != 0) {
            setParent(getRightChild(y, dataElement), y, dataElement);
        }

        swapColors(x, y, dataElement);

        // Check if root changed
        if (rootNode[dataElement.ordinal()] == x) {
            rootNode[dataElement.ordinal()] = y;
        } else if (rootNode[dataElement.ordinal()] == y) {
            rootNode[dataElement.ordinal()] = x;
        }
    }

    /**
     * Returns a string version of this Map in standard format.
     *
     * @return A standard format string version of the map
     */
    @Override
    public String toString() {
        return this.doToString(KEY);
    }

    /**
     * Returns a set view of the values contained in this map in key order.
     * The returned object can be cast to a Set.
     * <p>
     * The set is backed by the map, so changes to the map are reflected in
     * the set, and vice-versa. If the map is modified while an iteration over
     * the set is in progress, the results of the iteration are undefined.
     * <p>
     * The set supports element removal, which removes the corresponding mapping
     * from the map. It does not support the add or addAll operations.
     *
     * @return A set view of the values contained in this map.
     */
    @Override
    public Set<V> values() {
        if (valuesSet == null) {
            valuesSet = new ValueView(KEY);
        }
        return valuesSet;
    }

    /**
     * Serializes this object to an ObjectOutputStream.
     *
     * @param out The target ObjectOutputStream.
     * @throws IOException thrown when an I/O errors occur writing to the target stream.
     */
    private void writeObject(final ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeInt(this.size());
        for (final Entry<K, V> entry : entrySet()) {
            out.writeObject(entry.getKey());
            out.writeObject(entry.getValue());
        }
    }

}
