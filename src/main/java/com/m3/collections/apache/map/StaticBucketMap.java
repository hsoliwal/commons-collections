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
// Modified 2026 by Hitesh Soliwal and contributors: segmented array-owned mappings.
// Modified 2026-11-14: page-bounded entry projections and projection-free key snapshots.
package com.m3.collections.apache.map;

import java.lang.ref.WeakReference;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.m3.collections.apache.KeyValue;

/**
 * A StaticBucketMap is an efficient, thread-safe implementation of
 * {@link Map} that performs well in a highly
 * thread-contentious environment.
 * <p>
 * The map supports very efficient
 * {@link #get(Object) get}, {@link #put(Object,Object) put},
 * {@link #remove(Object) remove} and {@link #containsKey(Object) containsKey}
 * operations, assuming (approximate) uniform hashing and
 * that the number of entries does not exceed the number of buckets.  If the
 * number of entries exceeds the number of buckets or if the hash codes of the
 * objects are not uniformly distributed, these operations have a worst case
 * scenario that is proportional to the number of elements in the map
 * (<em>O(n)</em>).
 * </p>
 * <p>
 * Each bucket in the hash table has its own monitor, so two threads can
 * safely operate on the map at the same time, often without incurring any
 * monitor contention.  This means that you don't have to wrap instances
 * of this class with {@link java.util.Collections#synchronizedMap(Map)};
 * instances are already thread-safe.  Unfortunately, however, this means
 * that this map implementation behaves in ways you may find disconcerting.
 * Bulk operations, such as {@link #putAll(Map) putAll} or the
 * {@link Collection#retainAll(Collection) retainAll} operation in collection
 * views, are <em>not</em> atomic.  If two threads are simultaneously
 * executing
 * </p>
 *
 * <pre>
 *   staticBucketMapInstance.putAll(map);
 * </pre>
 *
 * and
 *
 * <pre>
 *   staticBucketMapInstance.entrySet().removeAll(map.entrySet());
 * </pre>
 *
 * <p>
 * then the results are generally random.  Those two statement could cancel
 * each other out, leaving {@code staticBucketMapInstance} essentially
 * unchanged, or they could leave some random subset of {@code map} in
 * {@code staticBucketMapInstance}.
 * </p>
 * <p>
 * Also, much like an encyclopedia, the results of {@link #size()} and
 * {@link #isEmpty()} are out-of-date as soon as they are produced.
 * </p>
 * <p>
 * The iterators returned by the collection views of this class are <em>not</em>
 * fail-fast.  They will <em>never</em> raise a
 * {@link ConcurrentModificationException}.  Keys and values
 * added to the map after the iterator is created do not necessarily appear
 * during iteration.  Similarly, the iterator does not necessarily fail to
 * return keys and values that were removed after the iterator was created.
 * </p>
 * <p>
 * Finally, unlike {@link HashMap}-style implementations, this
 * class <em>never</em> rehashes the map.  The number of buckets is fixed
 * at construction time and never altered.  Performance may degrade if
 * you do not allocate enough buckets upfront.
 * </p>
 * <p>
 * The {@link #atomic(Runnable)} method is provided to allow atomic iterations
 * and bulk operations; however, overuse of {@link #atomic(Runnable) atomic}
 * will basically result in a map that's slower than an ordinary synchronized
 * {@link HashMap}.
 * </p>
 * <p>
 * Use this class if you do not require reliable bulk operations and
 * iterations, or if you can make your own guarantees about how bulk
 * operations will affect the map.
 * </p>
 *
 * @param <K> The type of the keys in this map
 * @param <V> The type of the values in this map
 * @since 3.0 (previously in main package v2.1)
 */
public final class StaticBucketMap<K, V> extends AbstractIterableMap<K, V> {

    class BaseIterator {
        private final ArrayList<Map.Entry<K, V>> current = new ArrayList<>();
        private int bucket;
        private Map.Entry<K, V> last;

        public boolean hasNext() {
            if (!current.isEmpty()) {
                return true;
            }
            while (bucket < buckets.length) {
                synchronized (locks[bucket]) {
                    int n = buckets[bucket];
                    while (n != NONE) {
                        current.add(entryAt(n));
                        n = arena.next(n);
                    }
                    bucket++;
                    if (!current.isEmpty()) {
                        return true;
                    }
                }
            }
            return false;
        }

        protected Map.Entry<K, V> nextEntry() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            last = current.remove(current.size() - 1);
            return last;
        }

        public void remove() {
            if (last == null) {
                throw new IllegalStateException();
            }
            StaticBucketMap.this.remove(last.getKey());
            last = null;
        }
    }

    private final class EntryIterator extends BaseIterator implements Iterator<Map.Entry<K, V>> {

        @Override
        public Map.Entry<K, V> next() {
            return nextEntry();
        }

    }

    private final class EntrySet extends AbstractSet<Map.Entry<K, V>> {

        @Override
        public void clear() {
            StaticBucketMap.this.clear();
        }

        @Override
        public boolean contains(final Object obj) {
            final Map.Entry<?, ?> entry = (Map.Entry<?, ?>) obj;
            final int hash = getHash(entry.getKey());
            synchronized (locks[hash]) {
                final Lock lock = locks[hash];
                arena.enter(lock);
                try {
                    for (int n = buckets[hash]; n != NONE; n = arena.next(n)) {
                        if (entryEquals(n, entry)) {
                            return true;
                        }
                    }
                } finally {
                    arena.leave(lock, buckets[hash]);
                }
            }
            return false;
        }

        @Override
        public Iterator<Map.Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        @Override
        public boolean remove(final Object obj) {
            if (!(obj instanceof Map.Entry<?, ?>)) {
                return false;
            }
            final Map.Entry<?, ?> entry = (Map.Entry<?, ?>) obj;
            final int hash = getHash(entry.getKey());
            synchronized (locks[hash]) {
                final Lock lock = locks[hash];
                arena.enter(lock);
                try {
                    for (int n = buckets[hash]; n != NONE; n = arena.next(n)) {
                        if (entryEquals(n, entry)) {
                            StaticBucketMap.this.remove(arena.key(n));
                            return true;
                        }
                    }
                } finally {
                    arena.leave(lock, buckets[hash]);
                }
            }
            return false;
        }

        @Override
        public int size() {
            return StaticBucketMap.this.size();
        }

    }

    /** Key snapshots need neither live value projections nor entry identity. */
    private final class KeyIterator implements Iterator<K> {
        private final ArrayList<K> current = new ArrayList<>();
        private int bucket;
        private K last;
        private boolean removable;

        @Override
        @SuppressWarnings("unchecked")
        public boolean hasNext() {
            if (!current.isEmpty()) {
                return true;
            }
            while (bucket < buckets.length) {
                synchronized (locks[bucket]) {
                    for (int slot = buckets[bucket]; slot != NONE; slot = arena.next(slot)) {
                        current.add((K) arena.key(slot));
                    }
                    bucket++;
                    if (!current.isEmpty()) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public K next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            last = current.remove(current.size() - 1);
            removable = true;
            return last;
        }

        @Override
        public void remove() {
            if (!removable) {
                throw new IllegalStateException();
            }
            StaticBucketMap.this.remove(last);
            last = null;
            removable = false;
        }
    }

    private final class KeySet extends AbstractSet<K> {

        @Override
        public void clear() {
            StaticBucketMap.this.clear();
        }

        @Override
        public boolean contains(final Object obj) {
            return StaticBucketMap.this.containsKey(obj);
        }

        @Override
        public Iterator<K> iterator() {
            return new KeyIterator();
        }

        @Override
        public boolean remove(final Object obj) {
            final int hash = getHash(obj);
            synchronized (locks[hash]) {
                final Lock lock = locks[hash];
                arena.enter(lock);
                try {
                    for (int n = buckets[hash]; n != NONE; n = arena.next(n)) {
                        final Object k = arena.key(n);
                        if (Objects.equals(k, obj)) {
                            StaticBucketMap.this.remove(k);
                            return true;
                        }
                    }
                } finally {
                    arena.leave(lock, buckets[hash]);
                }
            }
            return false;
        }

        @Override
        public int size() {
            return StaticBucketMap.this.size();
        }

    }

    private static final int NONE = -1;
    private static final int LIVE = -2;

    /** The original bucket monitor also owns its reusable and retired coordinates. */
    private static final class Lock {
        int size;
        int free = NONE;
        int retired = NONE;
        int readers;
        boolean reentered;
    }

    /** Stable payload pages: growing the directory never moves live mappings. */
    private static final class Segment {
        final Object[] data = new Object[Arena.PAGE_SIZE * 2];
        final long[] links = new long[Arena.PAGE_SIZE];
        volatile WeakReference<Projection> projections;

        Projection projection() {
            final WeakReference<Projection> reference = projections;
            return reference == null ? null : reference.get();
        }
    }

    /**
     * One weak registration per exposed storage page, not per mapping.
     * A live public entry keeps its page's projections alive. Removed entries
     * are unlinked immediately and no longer retain this page or its siblings.
     */
    private static final class Projection {
        final Arena arena;
        final Node<?, ?>[] entries = new Node<?, ?>[Arena.PAGE_SIZE];

        Projection(final Arena arena) {
            this.arena = arena;
        }
    }

    /** No mapping object is allocated by normal insertion or replacement. */
    private static final class Arena {
        static final int PAGE_SHIFT = 6;
        static final int PAGE_SIZE = 1 << PAGE_SHIFT;
        static final int PAGE_MASK = PAGE_SIZE - 1;
        private final AtomicInteger used = new AtomicInteger();
        private volatile Segment[] pages = new Segment[0];

        private Segment page(final int slot) {
            return pages[slot >>> PAGE_SHIFT];
        }

        private synchronized void ensurePage(final int pageIndex) {
            Segment[] current = pages;
            if (pageIndex >= current.length) {
                current = Arrays.copyOf(current,
                        Math.max(pageIndex + 1, Math.max(4, current.length + (current.length >>> 1))));
            }
            if (current[pageIndex] == null) {
                current[pageIndex] = new Segment();
            }
            pages = current;
        }

        private int freshSlot() {
            for (;;) {
                final int slot = used.get();
                if (slot == Integer.MAX_VALUE) {
                    throw new OutOfMemoryError("StaticBucketMap slot capacity exhausted");
                }
                final int pageIndex = slot >>> PAGE_SHIFT;
                final Segment[] current = pages;
                if (pageIndex >= current.length || current[pageIndex] == null) {
                    ensurePage(pageIndex);
                }
                if (used.compareAndSet(slot, slot + 1)) {
                    return slot;
                }
            }
        }

        int acquire(final Lock lock, final Object key, final Object value) {
            final int slot;
            if (lock.free == NONE) {
                slot = freshSlot();
            } else {
                slot = lock.free;
                lock.free = upper(slot);
            }
            final Segment page = page(slot);
            final int offset = slot & PAGE_MASK;
            page.data[offset * 2] = key;
            page.data[offset * 2 + 1] = value;
            page.links[offset] = ((long) LIVE << 32) | 0xffffffffL;
            return slot;
        }

        int next(final int slot) {
            return (int) page(slot).links[slot & PAGE_MASK];
        }

        void next(final int slot, final int next) {
            final Segment page = page(slot);
            final int offset = slot & PAGE_MASK;
            page.links[offset] = (page.links[offset] & 0xffffffff00000000L) | (next & 0xffffffffL);
        }

        private int upper(final int slot) {
            return (int) (page(slot).links[slot & PAGE_MASK] >>> 32);
        }

        private void upper(final int slot, final int state) {
            final Segment page = page(slot);
            final int offset = slot & PAGE_MASK;
            page.links[offset] = ((long) state << 32) | (page.links[offset] & 0xffffffffL);
        }

        Object key(final int slot) {
            return page(slot).data[(slot & PAGE_MASK) * 2];
        }

        Object rawValue(final int slot) {
            return page(slot).data[(slot & PAGE_MASK) * 2 + 1];
        }

        Object value(final int slot) {
            final Node<?, ?> node = projected(slot);
            return node == null ? rawValue(slot) : node.value;
        }

        void value(final int slot, final Object value) {
            final Node<?, ?> node = projected(slot);
            if (node == null) {
                page(slot).data[(slot & PAGE_MASK) * 2 + 1] = value;
            } else {
                // No user callback is made under this internal publication lock.
                synchronized (this) {
                    node.detachedValue(value);
                    if (node.owner != null) {
                        page(slot).data[(slot & PAGE_MASK) * 2 + 1] = value;
                    }
                }
            }
        }

        Node<?, ?> projected(final int slot) {
            final Projection projection = page(slot).projection();
            return projection == null ? null : projection.entries[slot & PAGE_MASK];
        }

        Node<?, ?> entry(final int slot) {
            final Segment page = page(slot);
            synchronized (page) {
                Projection projection = page.projection();
                if (projection == null) {
                    // Allocate before publication; an exposed Node roots this
                    // group, but the map retains only its single weak reference.
                    projection = new Projection(this);
                    page.projections = new WeakReference<>(projection);
                }
                final int offset = slot & PAGE_MASK;
                Node<?, ?> node = projection.entries[offset];
                if (node == null) {
                    node = new Node<>(key(slot), rawValue(slot), projection, slot);
                    projection.entries[offset] = node;
                }
                return node;
            }
        }

        void enter(final Lock lock) {
            if (lock.readers++ != 0) {
                lock.reentered = true;
            }
        }

        void retire(final Lock lock, final int slot) {
            if (upper(slot) == LIVE) {
                // Zero encodes the end of this deferred list. The sign bit is
                // reserved for a reachable-again marker during outermost cleanup.
                upper(slot, lock.retired + 1);
                lock.retired = slot;
            }
        }

        void leave(final Lock lock, final int head) {
            if (--lock.readers != 0) {
                return;
            }
            if (lock.reentered && lock.retired != NONE) {
                // The original linked implementation can relink an old suffix
                // after a callback clears/removes it. Do not recycle that suffix.
                for (int slot = head; slot != NONE; slot = next(slot)) {
                    final int state = upper(slot);
                    if (state != LIVE) {
                        upper(slot, state | Integer.MIN_VALUE);
                    }
                }
            }
            while (lock.retired != NONE) {
                final int slot = lock.retired;
                final int state = upper(slot);
                lock.retired = (state & Integer.MAX_VALUE) - 1;
                if (state < 0) {
                    upper(slot, LIVE);
                    continue;
                }
                final Segment page = page(slot);
                final int offset = slot & PAGE_MASK;
                final Projection projection = page.projection();
                final Node<?, ?> node = projection == null ? null : projection.entries[offset];
                if (node != null) {
                    synchronized (this) {
                        node.detachedValue(rawValue(slot));
                        node.owner = null;
                    }
                }
                page.data[offset * 2] = null;
                page.data[offset * 2 + 1] = null;
                if (projection != null) {
                    synchronized (page) {
                        // Never let a held sibling retain a removed mapping.
                        projection.entries[offset] = null;
                    }
                }
                page.links[offset] = ((long) lock.free << 32) | 0xffffffffL;
                lock.free = slot;
            }
            lock.reentered = false;
        }
    }

    /** Lazy API projection, never the map's retained storage or successor link. */
    private static final class Node<K, V> implements Map.Entry<K, V>, KeyValue<K, V> {
        private final K key;
        private volatile V value;
        private volatile Projection owner;
        private final int slot;

        Node(final K key, final V value, final Projection owner, final int slot) {
            this.key = key;
            this.value = value;
            this.owner = owner;
            this.slot = slot;
        }

        @SuppressWarnings("unchecked")
        void detachedValue(final Object replacement) {
            value = (V) replacement;
        }

        @Override
        public boolean equals(final Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof Map.Entry<?, ?>)) {
                return false;
            }
            final Map.Entry<?, ?> other = (Map.Entry<?, ?>) obj;
            return Objects.equals(key, other.getKey()) && Objects.equals(getValue(), other.getValue());
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public int hashCode() {
            final int keyHash = key == null ? 0 : key.hashCode();
            final V current = getValue();
            return keyHash ^ (current == null ? 0 : current.hashCode());
        }

        @Override
        public V setValue(final V replacement) {
            final Projection current = owner;
            if (current == null) {
                final V previous = value;
                value = replacement;
                return previous;
            }
            synchronized (current.arena) {
                final V previous = value;
                value = replacement;
                if (owner != null) {
                    current.arena.page(slot).data[(slot & Arena.PAGE_MASK) * 2 + 1] = replacement;
                }
                return previous;
            }
        }
    }

    private final class ValueIterator extends BaseIterator implements Iterator<V> {

        @Override
        public V next() {
            return nextEntry().getValue();
        }

    }

    private final class Values extends AbstractCollection<V> {

        @Override
        public void clear() {
            StaticBucketMap.this.clear();
        }

        @Override
        public Iterator<V> iterator() {
            return new ValueIterator();
        }

        @Override
        public int size() {
            return StaticBucketMap.this.size();
        }

    }

    @SuppressWarnings("unchecked")
    private V valueAt(final int slot) {
        return (V) arena.value(slot);
    }

    @SuppressWarnings("unchecked")
    private Map.Entry<K, V> entryAt(final int slot) {
        return (Map.Entry<K, V>) arena.entry(slot);
    }

    private boolean entryEquals(final int slot, final Map.Entry<?, ?> entry) {
        final Node<?, ?> projection = arena.projected(slot);
        return projection == entry || Objects.equals(arena.key(slot), entry.getKey())
                && Objects.equals(arena.value(slot), entry.getValue());
    }

    /** The default number of buckets to use */
    private static final int DEFAULT_BUCKETS = 255;

    /** The array of buckets, where the actual data is held */
    private final int[] buckets;

    private final Arena arena = new Arena();

    /** The matching array of locks */
    private final Lock[] locks;

    /**
     * Initializes the map with the default number of buckets (255).
     */
    public StaticBucketMap() {
        this(DEFAULT_BUCKETS);
    }

    /**
     * Initializes the map with a specified number of buckets.  The number
     * of buckets is never below 17, and is always an odd number (StaticBucketMap
     * ensures this). The number of buckets is inversely proportional to the
     * chances for thread contention.  The fewer buckets, the more chances for
     * thread contention.  The more buckets the fewer chances for thread
     * contention.
     *
     * @param numBuckets  The number of buckets for this map
     */
    public StaticBucketMap(final int numBuckets) {
        int size = Math.max(17, numBuckets);

        // Ensure that bucketSize is never a power of 2 (to ensure maximal distribution)
        if (size % 2 == 0) {
            size--;
        }

        buckets = new int[size];
        Arrays.fill(buckets, NONE);
        locks = new Lock[size];

        for (int i = 0; i < size; i++) {
            locks[i] = new Lock();
        }
    }

    /**
     * Prevents any operations from occurring on this map while the given {@link Runnable} executes. This method can be used, for instance, to execute a bulk
     * operation atomically:
     * <pre>
     * staticBucketMapInstance.atomic(new Runnable() {
     *     public void run() {
     *         staticBucketMapInstance.putAll(map);
     *     }
     * });
     * </pre>
     * <p>
     * It can also be used if you need a reliable iterator:
     * </p>
     *
     * <pre>
     *    staticBucketMapInstance.atomic(new Runnable() {
     *        public void run() {
     *            Iterator iterator = staticBucketMapInstance.iterator();
     *            while (iterator.hasNext()) {
     *                foo(iterator.next();
     *            }
     *        }
     *    });
     * </pre>
     * <p>
     * <strong>Implementation note:</strong> This method requires a lot of time and a ton of stack space. Essentially a recursive algorithm is used to enter each bucket's
     * monitor. If you have twenty thousand buckets in your map, then the recursive method will be invoked twenty thousand times. You have been warned.
     * </p>
     *
     * @param runnable The code to execute atomically
     */
    public void atomic(final Runnable runnable) {
        atomic(Objects.requireNonNull(runnable, "runnable"), 0);
    }

    private void atomic(final Runnable r, final int bucket) {
        if (bucket >= buckets.length) {
            r.run();
            return;
        }
        synchronized (locks[bucket]) {
            atomic(r, bucket + 1);
        }
    }

    /**
     * Clears the map of all entries.
     */
    @Override
    public void clear() {
        for (int i = 0; i < buckets.length; i++) {
            final Lock lock = locks[i];
            synchronized (lock) {
                arena.enter(lock);
                try {
                    for (int n = buckets[i]; n != NONE; n = arena.next(n)) {
                        arena.retire(lock, n);
                    }
                    buckets[i] = NONE;
                    lock.size = 0;
                } finally {
                    arena.leave(lock, buckets[i]);
                }
            }
        }
    }

    /**
     * Checks if the map contains the specified key.
     *
     * @param key  The key to check
     * @return true if found
     */
    @Override
    public boolean containsKey(final Object key) {
        final int hash = getHash(key);
        final Lock lock = locks[hash];
        synchronized (lock) {
            arena.enter(lock);
            try {
                for (int n = buckets[hash]; n != NONE; n = arena.next(n)) {
                    if (Objects.equals(arena.key(n), key)) {
                        return true;
                    }
                }
            } finally {
                arena.leave(lock, buckets[hash]);
            }
        }
        return false;
    }

    /**
     * Checks if the map contains the specified value.
     *
     * @param value  The value to check
     * @return true if found
     */
    @Override
    public boolean containsValue(final Object value) {
        for (int i = 0; i < buckets.length; i++) {
            final Lock lock = locks[i];
            synchronized (lock) {
                arena.enter(lock);
                try {
                    for (int n = buckets[i]; n != NONE; n = arena.next(n)) {
                        if (Objects.equals(arena.value(n), value)) {
                            return true;
                        }
                    }
                } finally {
                    arena.leave(lock, buckets[i]);
                }
            }
        }
        return false;
    }

    /**
     * Gets the entry set.
     *
     * @return The entry set
     */
    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return new EntrySet();
    }

    /**
     * Compares this map to another, as per the Map specification.
     *
     * @param obj  The object to compare to
     * @return true if equal
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof Map<?, ?>)) {
            return false;
        }
        final Map<?, ?> other = (Map<?, ?>) obj;
        return entrySet().equals(other.entrySet());
    }

    /**
     * Gets the value associated with the key.
     *
     * @param key  The key to retrieve
     * @return The associated value
     */
    @Override
    public V get(final Object key) {
        final int hash = getHash(key);
        final Lock lock = locks[hash];
        synchronized (lock) {
            arena.enter(lock);
            try {
                for (int n = buckets[hash]; n != NONE; n = arena.next(n)) {
                    if (Objects.equals(arena.key(n), key)) {
                        return valueAt(n);
                    }
                }
            } finally {
                arena.leave(lock, buckets[hash]);
            }
        }
        return null;
    }

    /**
     * Determine the exact hash entry for the key.  The hash algorithm
     * is rather simplistic, but it does the job:
     *
     * <pre>
     *   He = |Hk mod n|
     * </pre>
     *
     * <p>
     *   He is the entry's hashCode, Hk is the key's hashCode, and n is
     *   the number of buckets.
     * </p>
     */
    private int getHash(final Object key) {
        if (key == null) {
            return 0;
        }
        int hash = key.hashCode();
        hash += ~(hash << 15);
        hash ^= hash >>> 10;
        hash += hash << 3;
        hash ^= hash >>> 6;
        hash += ~(hash << 11);
        hash ^= hash >>> 16;
        hash %= buckets.length;
        return hash < 0 ? hash * -1 : hash;
    }

    /**
     * Gets the hash code, as per the Map specification.
     *
     * @return The hash code
     */
    @Override
    public int hashCode() {
        int result = 0;
        for (int i = 0; i < buckets.length; i++) {
            final Lock lock = locks[i];
            synchronized (lock) {
                arena.enter(lock);
                try {
                    for (int n = buckets[i]; n != NONE; n = arena.next(n)) {
                        final Object key = arena.key(n);
                        final int keyHash = key == null ? 0 : key.hashCode();
                        final Object value = arena.value(n);
                        result += keyHash ^ (value == null ? 0 : value.hashCode());
                    }
                } finally {
                    arena.leave(lock, buckets[i]);
                }
            }
        }
        return result;
    }

    /**
     * Checks if the size is currently zero.
     *
     * @return true if empty
     */
    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Gets the key set.
     *
     * @return The key set
     */
    @Override
    public Set<K> keySet() {
        return new KeySet();
    }

    /**
     * Puts a new key value mapping into the map.
     *
     * @param key  The key to use
     * @param value  The value to use
     * @return The previous mapping for the key
     */
    @Override
    public V put(final K key, final V value) {
        final int hash = getHash(key);
        final Lock lock = locks[hash];
        synchronized (lock) {
            arena.enter(lock);
            try {
                int n = buckets[hash];
                if (n == NONE) {
                    buckets[hash] = arena.acquire(lock, key, value);
                    lock.size++;
                    return null;
                }
                for (int next = n; next != NONE; next = arena.next(next)) {
                    n = next;
                    if (Objects.equals(arena.key(n), key)) {
                        final V previous = valueAt(n);
                        arena.value(n, value);
                        return previous;
                    }
                }
                final int appended = arena.acquire(lock, key, value);
                arena.next(n, appended);
                if (arena.upper(n) != LIVE) {
                    arena.retire(lock, appended);
                }
                lock.size++;
            } finally {
                arena.leave(lock, buckets[hash]);
            }
        }
        return null;
    }

    /**
     * Puts all the entries from the specified map into this map.
     * This operation is <strong>not atomic</strong> and may have undesired effects.
     *
     * @param map  The map of entries to add
     */
    @Override
    public void putAll(final Map<? extends K, ? extends V> map) {
        for (final Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Removes the specified key from the map.
     *
     * @param key  The key to remove
     * @return The previous value at this key
     */
    @Override
    public V remove(final Object key) {
        final int hash = getHash(key);
        final Lock lock = locks[hash];
        synchronized (lock) {
            arena.enter(lock);
            try {
                int previous = NONE;
                for (int n = buckets[hash]; n != NONE; n = arena.next(n)) {
                    if (Objects.equals(arena.key(n), key)) {
                        if (previous == NONE) {
                            buckets[hash] = arena.next(n);
                        } else {
                            arena.next(previous, arena.next(n));
                        }
                        lock.size--;
                        final V result = valueAt(n);
                        arena.retire(lock, n);
                        return result;
                    }
                    previous = n;
                }
            } finally {
                arena.leave(lock, buckets[hash]);
            }
        }
        return null;
    }

    /**
     * Gets the current size of the map.
     * The value is computed fresh each time the method is called.
     *
     * @return The current size
     */
    @Override
    public int size() {
        int cnt = 0;

        for (int i = 0; i < buckets.length; i++) {
            synchronized (locks[i]) {
                cnt += locks[i].size;
            }
        }
        return cnt;
    }

    /**
     * Gets the values.
     *
     * @return The values
     */
    @Override
    public Collection<V> values() {
        return new Values();
    }

}
