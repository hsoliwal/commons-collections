/*
 * Copyright 2026 Hitesh Soliwal and contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.map;

import java.lang.ref.Reference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Existing owner contracts plus bounded boundary-projection storage. */
public final class StaticBucketProjectionContract {
    private static long checks;

    private StaticBucketProjectionContract() {
    }

    private static void check(final boolean condition, final String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Key {
        private final int id;
        private boolean throwHash;

        Key(final int id) {
            this.id = id;
        }

        @Override
        public int hashCode() {
            if (throwHash) {
                throw new IllegalStateException("key-hash sentinel");
            }
            return 0;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof Key && ((Key) other).id == id;
        }
    }

    private static List<Object> graph(final Object root) throws ReflectiveOperationException {
        final List<Object> pending = new ArrayList<>();
        final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        pending.add(root);
        for (int i = 0; i < pending.size(); i++) {
            final Object object = pending.get(i);
            if (object == null || seen.put(object, Boolean.TRUE) != null) {
                continue;
            }
            final Class<?> type = object.getClass();
            // Count Reference objects themselves, not the weakly referenced entries.
            if (object instanceof Reference<?>) {
                continue;
            }
            if (type.isArray() && !type.getComponentType().isPrimitive()) {
                for (int j = 0; j < Array.getLength(object); j++) {
                    pending.add(Array.get(object, j));
                }
            } else if (type.getName().startsWith(StaticBucketMap.class.getName())) {
                for (final Field field : type.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                        field.setAccessible(true);
                        pending.add(field.get(object));
                    }
                }
            }
        }
        return new ArrayList<>(seen.keySet());
    }

    private static int registrations(final Object owner) throws ReflectiveOperationException {
        int count = 0;
        for (final Object object : graph(owner)) {
            if (object instanceof Reference<?>) {
                count++;
            }
        }
        return count;
    }

    public static void keyTraversalDoesNotExposeEntries() throws ReflectiveOperationException {
        final StaticBucketMap<Integer, Integer> map = new StaticBucketMap<>(17);
        for (int i = 0; i < 4096; i++) {
            map.put(i, i);
        }
        long sum = 0;
        for (final Integer key : map.keySet()) {
            sum += key;
        }
        check(sum == 4095L * 4096 / 2, "missing keys");
        final int references = registrations(map);
        check(references == 0, "key traversal allocated entry registrations: " + references);
        System.out.println("KEY_PROJECTION_PASS entries=4096 registrations=" + references);
    }

    public static void registrationsArePageBounded() throws ReflectiveOperationException {
        final StaticBucketMap<Integer, Integer> map = new StaticBucketMap<>(17);
        for (int i = 0; i < 4096; i++) {
            map.put(i, i);
        }
        final List<Map.Entry<Integer, Integer>> held = new ArrayList<>(map.entrySet());
        final int references = registrations(map);
        check(references <= 64, "per-entry weak registrations remain: " + references);
        for (final Map.Entry<Integer, Integer> entry : held) {
            check(Objects.equals(entry.getKey(), entry.getValue()), "entry corrupted");
        }
        System.out.println("PAGE_PROJECTION_PASS entries=4096 registrations=" + references);
    }

    private static void keySnapshots() {
        final StaticBucketMap<Key, Integer> map = new StaticBucketMap<>(17);
        final Key a = new Key(1);
        final Key b = new Key(2);
        map.put(null, 0);
        map.put(a, 1);
        map.put(b, 2);
        final Iterator<Key> iterator = map.keySet().iterator();
        try {
            iterator.remove();
            throw new AssertionError("remove before next");
        } catch (final IllegalStateException expected) {
            checks++;
        }
        check(iterator.hasNext(), "snapshot empty");
        map.remove(b);
        final Key replacement = new Key(2);
        map.put(replacement, 20);
        check(iterator.next() == b, "snapshot replaced original key identity");
        b.throwHash = true;
        try {
            iterator.remove();
            throw new AssertionError("hash failure swallowed");
        } catch (final IllegalStateException expected) {
            check("key-hash sentinel".equals(expected.getMessage()), "wrong failure");
        }
        b.throwHash = false;
        iterator.remove();
        check(!map.containsKey(replacement), "failed remove not retryable");
        try {
            iterator.remove();
            throw new AssertionError("duplicate remove accepted");
        } catch (final IllegalStateException expected) {
            checks++;
        }
        map.clear();
        check(iterator.next() == a, "cleared snapshot key lost");
        check(iterator.next() == null, "null snapshot key lost");
        iterator.remove();
        check(!iterator.hasNext(), "snapshot not exhausted");
        try {
            iterator.next();
            throw new AssertionError("missing exhaustion exception");
        } catch (final java.util.NoSuchElementException expected) {
            checks++;
        }
    }

    private static void entryLifetimeAndRecycling() throws ReflectiveOperationException {
        for (int round = 0; round < 200; round++) {
            final StaticBucketMap<Key, Object> map = new StaticBucketMap<>(17);
            final Key[] keys = new Key[65];
            final Object[] values = new Object[keys.length];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = new Key(i);
                values[i] = new Object();
                map.put(keys[i], values[i]);
            }
            final List<Map.Entry<Key, Object>> held = new ArrayList<>(map.entrySet());
            final List<Map.Entry<Key, Object>> again = new ArrayList<>(map.entrySet());
            for (int i = 0; i < held.size(); i++) {
                check(held.get(i) == again.get(i), "live entry identity changed");
            }
            // Reverse per-bucket order: adjacent entries lie in the same projection page.
            final Map.Entry<Key, Object> removed = held.get(10);
            final Map.Entry<Key, Object> survivor = held.get(11);
            final Key key = removed.getKey();
            final Object oldValue = removed.getValue();
            final Object replacement = new Object();
            check(map.remove(key) == oldValue, "remove result");
            map.put(new Key(key.id), replacement);
            removed.setValue(new Object());
            check(map.get(key) == replacement, "detached entry rebound to recycled slot");
            final List<Object> liveGraph = graph(survivor);
            check(!liveGraph.contains(removed), "surviving projection retains removed entry");
            check(!liveGraph.contains(oldValue), "surviving projection retains removed payload");
            // Traverse by identity: Key.equals intentionally aliases a replacement key.
            check(liveGraph.stream().noneMatch(object -> object == key), "removed key retained");
            map.clear();
            check(removed.getKey() == key, "detached key changed");
            check(graph(removed).stream().noneMatch(object ->
                    object != removed && object instanceof Map.Entry<?, ?>),
                    "detached entry retains sibling projections");
            final Object updated = new Object();
            survivor.setValue(updated);
            check(survivor.getValue() == updated, "detached setValue failed");
            check(map.isEmpty(), "detached write changed map");
        }
    }

    private static void concurrentExposure() throws Exception {
        final StaticBucketMap<Integer, Integer> map = new StaticBucketMap<>(255);
        for (int i = 0; i < 2048; i++) {
            map.put(i, i);
        }
        final List<Map.Entry<Integer, Integer>> held = new ArrayList<>(map.entrySet());
        final Map<Integer, Map.Entry<Integer, Integer>> identities = new java.util.HashMap<>();
        for (final Map.Entry<Integer, Integer> entry : held) {
            identities.put(entry.getKey(), entry);
        }
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch start = new CountDownLatch(1);
        final Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            final int worker = t;
            threads[t] = new Thread(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 80; round++) {
                        for (final Map.Entry<Integer, Integer> entry : map.entrySet()) {
                            if (entry != identities.get(entry.getKey())) {
                                throw new AssertionError("concurrent projection identity split");
                            }
                            if ((entry.getKey() & 3) == worker) {
                                entry.setValue(round);
                                if (map.get(entry.getKey()) != round) {
                                    throw new AssertionError("exposed write not visible in map");
                                }
                            }
                        }
                    }
                } catch (final Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
            threads[t].setDaemon(true);
            threads[t].start();
        }
        start.countDown();
        for (final Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(30));
            check(!thread.isAlive(), "projection thread did not terminate");
        }
        if (failure.get() != null) {
            throw new AssertionError("concurrent projection failure", failure.get());
        }
        for (final Map.Entry<Integer, Integer> entry : held) {
            check(entry.getValue() == 79, "last projected value lost");
        }
    }

    public static void verifyBehavior() throws Exception {
        checks = 0;
        keySnapshots();
        entryLifetimeAndRecycling();
        concurrentExposure();
        System.out.println("PROJECTION_BEHAVIOR_PASS checks=" + checks);
    }

    public static void main(final String[] args) throws Exception {
        if (args.length == 0 || "behavior".equals(args[0])) {
            verifyBehavior();
        } else if ("keys".equals(args[0])) {
            keyTraversalDoesNotExposeEntries();
        } else if ("pages".equals(args[0])) {
            registrationsArePageBounded();
        } else {
            throw new IllegalArgumentException(Arrays.toString(args));
        }
    }
}
