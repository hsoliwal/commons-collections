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
package com.m3.collections.apache.map;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Independent checks of snapshot order, ownership, entry lifetime and remove retry. */
public final class StaticBucketSnapshotContract {
    private long checks;
    private long digest = 1;

    private StaticBucketSnapshotContract() {
    }

    private static final class Key {
        private final int id;
        private RuntimeException failure;

        Key(final int id) {
            this.id = id;
        }

        @Override
        public int hashCode() {
            if (failure != null) {
                throw failure;
            }
            return 7;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof Key && ((Key) other).id == id;
        }
    }

    private void check(final boolean condition, final String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void record(final int value) {
        digest = 31 * digest + value;
    }

    private static Object field(final Object owner, final String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (final NoSuchFieldException missing) {
                // Continue through the entry/value iterator's shared base.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static StaticBucketMap<Key, Integer> map(final int count, final List<Key> keys) {
        final StaticBucketMap<Key, Integer> map = new StaticBucketMap<>();
        for (int index = 0; index < count; index++) {
            final Key key = new Key(index);
            keys.add(key);
            map.put(key, index);
        }
        return map;
    }

    private void behavioral() {
        for (final int count : new int[] {0, 1, 2, 8, 9, 10, 11, 16, 17, 63, 64, 65, 512, 2048}) {
            final List<Key> keys = new ArrayList<>();
            final StaticBucketMap<Key, Integer> map = map(count, keys);
            final Iterator<Key> keyIterator = map.keySet().iterator();
            final Iterator<Map.Entry<Key, Integer>> entryIterator = map.entrySet().iterator();
            final Iterator<Integer> valueIterator = map.values().iterator();
            for (final Iterator<?> iterator : new Iterator<?>[] {keyIterator, entryIterator, valueIterator}) {
                try {
                    iterator.remove();
                    throw new AssertionError("remove before next");
                } catch (final IllegalStateException expected) {
                    checks++;
                }
                check(iterator.hasNext() == (count != 0), "initial availability");
                check(iterator.hasNext() == (count != 0), "repeat availability");
            }
            // All three snapshots have now captured the same bucket. Exposed entries
            // remain live through updates but retain their final value after removal.
            for (int index = 0; index < count; index++) {
                map.put(keys.get(index), -index);
                if ((index & 1) == 0) {
                    map.remove(keys.get(index));
                    map.put(new Key(index), 100000 + index);
                }
            }
            for (int index = count - 1; index >= 0; index--) {
                check(keyIterator.hasNext() && entryIterator.hasNext() && valueIterator.hasNext(), "snapshot count");
                check(keyIterator.next() == keys.get(index), "frozen key identity/order");
                final Map.Entry<Key, Integer> entry = entryIterator.next();
                check(entry.getKey() == keys.get(index), "entry key identity");
                check(entry.getValue() == -index, "live or detached entry value");
                check(valueIterator.next() == -index, "value snapshot projection");
                final Integer previous = entry.setValue(-100000 - index);
                check(previous == -index, "entry set return");
                check(map.get(keys.get(index)) == ((index & 1) == 0 ? 100000 + index : -100000 - index),
                        "entry update must not rebind a removed mapping");
                record(index);
            }
            for (final Iterator<?> iterator : new Iterator<?>[] {keyIterator, entryIterator, valueIterator}) {
                check(!iterator.hasNext(), "exhausted");
                try {
                    iterator.next();
                    throw new AssertionError("missing exhaustion exception");
                } catch (final NoSuchElementException expected) {
                    check(expected.getMessage() == null, "exhaustion message");
                }
            }
        }
        inheritedIteratorHooks();
        retryRemoval();
        nullKeyAndRepeatedSnapshots();
        System.out.println("MAP_SNAPSHOT_BEHAVIOR_PASS checks=" + checks + " digest=" + Long.toHexString(digest));
    }

    private void inheritedIteratorHooks() {
        final StaticBucketMap<Key, Integer> map = new StaticBucketMap<>();
        final boolean[] forceAvailable = {true};
        final StaticBucketMap<Key, Integer>.BaseIterator iterator = map.new BaseIterator() {
            @Override
            public boolean hasNext() {
                return forceAvailable[0] || super.hasNext();
            }
        };
        IndexOutOfBoundsException expectedFailure = null;
        try {
            new ArrayList<Object>().remove(-1);
        } catch (final IndexOutOfBoundsException failure) {
            expectedFailure = failure;
        }
        for (int repeat = 0; repeat < 2; repeat++) {
            try {
                iterator.nextEntry();
                throw new AssertionError("invalid hook must fail without consuming state");
            } catch (final IndexOutOfBoundsException failure) {
                check(failure.getClass() == expectedFailure.getClass(), "hook failure class");
                check(Objects.equals(failure.getMessage(), expectedFailure.getMessage()), "hook failure message");
            }
        }
        forceAvailable[0] = false;
        final Key key = new Key(100);
        map.put(key, 123);
        check(iterator.hasNext() && iterator.nextEntry().getKey() == key, "hook error corrupted snapshot state");
        iterator.remove();
        check(map.isEmpty(), "hook recovery removal");
    }

    private void retryRemoval() {
        for (int view = 0; view < 3; view++) {
            final List<Key> keys = new ArrayList<>();
            final StaticBucketMap<Key, Integer> map = map(17, keys);
            final Iterator<?> iterator = view == 0 ? map.keySet().iterator()
                    : view == 1 ? map.entrySet().iterator() : map.values().iterator();
            iterator.next();
            final Key key = keys.get(16);
            final RuntimeException failure = new IllegalStateException("key hash failure");
            key.failure = failure;
            try {
                iterator.remove();
                throw new AssertionError("missing child failure");
            } catch (final IllegalStateException caught) {
                check(caught == failure, "exact child failure identity");
            }
            key.failure = null;
            iterator.remove();
            check(map.size() == 16 && !map.containsKey(key), "remove retry lost last key");
            try {
                iterator.remove();
                throw new AssertionError("double removal");
            } catch (final IllegalStateException expected) {
                checks++;
            }
            int remaining = 0;
            while (iterator.hasNext()) {
                iterator.next();
                remaining++;
            }
            check(remaining == 16, "snapshot survives map removal");
        }
    }

    private void nullKeyAndRepeatedSnapshots() {
        final StaticBucketMap<Integer, Integer> map = new StaticBucketMap<>();
        map.put(null, null);
        final Iterator<Integer> emptyKey = map.keySet().iterator();
        check(emptyKey.hasNext() && emptyKey.next() == null, "null key");
        emptyKey.remove();
        check(map.isEmpty(), "null-key removal");
        for (int index = 0; index < 8192; index++) {
            map.put(index, index % 13 == 0 ? null : index);
        }
        final List<Integer> expectedKeys = new ArrayList<>(map.keySet());
        final List<Map.Entry<Integer, Integer>> held = new ArrayList<>(map.entrySet());
        for (int pass = 0; pass < 4; pass++) {
            final Iterator<Integer> keys = map.keySet().iterator();
            final Iterator<Map.Entry<Integer, Integer>> entries = map.entrySet().iterator();
            final Iterator<Integer> values = map.values().iterator();
            for (int index = 0; index < expectedKeys.size(); index++) {
                final Integer key = expectedKeys.get(index);
                check(Objects.equals(keys.next(), key), "multi-bucket key order");
                check(entries.next() == held.get(index), "entry projection identity");
                check(Objects.equals(values.next(), map.get(key)), "multi-bucket value order");
            }
            check(!keys.hasNext() && !entries.hasNext() && !values.hasNext(), "multi-bucket exhaustion");
        }
    }

    private void storage() throws ReflectiveOperationException {
        final List<Key> keys = new ArrayList<>();
        final StaticBucketMap<Key, Integer> map = map(2048, keys);
        for (final Collection<?> view : new Collection<?>[] {map.keySet(), map.entrySet(), map.values()}) {
            final Iterator<?> iterator = view.iterator();
            for (Class<?> type = iterator.getClass(); type != Object.class; type = type.getSuperclass()) {
                for (final Field field : type.getDeclaredFields()) {
                    check(Modifier.isStatic(field.getModifiers())
                            || !Collection.class.isAssignableFrom(field.getType()), "snapshot retains a Collection helper");
                }
            }
            check(((Object[]) field(iterator, "current")).length == 0, "eager empty snapshot allocation");
            check(iterator.hasNext(), "snapshot available");
            final Object[] snapshot = (Object[]) field(iterator, "current");
            check(snapshot.length == 2048, "first bucket must reserve its known count exactly");
            for (int count = 2048; count > 0; count--) {
                check(iterator.hasNext() && iterator.hasNext(), "idempotent snapshot access");
                check(field(iterator, "current") == snapshot, "unexpected buffer replacement");
                iterator.next();
                check(snapshot[count - 1] == null, "consumed reference retained");
            }
            check(!iterator.hasNext(), "snapshot exhausted");
            for (final Object remaining : snapshot) {
                check(remaining == null, "buffer retains a mapping after consumption");
            }
        }
        System.out.println("MAP_SNAPSHOT_STORAGE_PASS checks=" + checks);
    }

    public static void verifyBehavior() {
        new StaticBucketSnapshotContract().behavioral();
    }

    public static void verifyStorage() throws ReflectiveOperationException {
        new StaticBucketSnapshotContract().storage();
    }

    public static void main(final String[] args) throws ReflectiveOperationException {
        if (args.length != 0 && "storage".equals(args[0])) {
            verifyStorage();
        } else {
            verifyBehavior();
        }
    }
}
