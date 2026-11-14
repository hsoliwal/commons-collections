/* Copyright 2026 Hitesh Soliwal and contributors. SPDX-License-Identifier: Apache-2.0 */
package com.m3.collections.apache.map;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.ref.Reference;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.m3.collections.apache.MapIterator;
import com.m3.collections.apache.KeyValue;

/** Independent real-source behavioral and storage regression. */
public final class StaticBucketArrayContract {
    private static long checks;
    private static long digest;
    private StaticBucketArrayContract() { }
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) { throw new AssertionError(message); }
    }
    private static void same(Object a, Object b) {
        check(Objects.equals(a, b), "actual=" + a + " expected=" + b);
        digest = digest * 31 + Objects.hashCode(b);
    }
    private static void state(StaticBucketMap<Integer, Integer> map, Map<Integer, Integer> model) {
        same(map.size(), model.size());
        same(map.isEmpty(), model.isEmpty());
        same(new HashMap<>(map), model);
        same(map.hashCode(), model.hashCode());
        check(map.equals(model) && model.equals(map), "map equality");
        // Record original encounter order, not HashMap order, for separate-JVM comparison.
        digest = digest * 31 + new ArrayList<>(map.keySet()).hashCode();
        digest = digest * 31 + new ArrayList<>(map.values()).hashCode();
        for (int key = -1; key < 160; key++) {
            same(map.containsKey(key), model.containsKey(key));
            same(map.get(key), model.get(key));
        }
        same(map.get(null), model.get(null));
    }
    private static void randomized() {
        for (int seed = 0; seed < 8; seed++) {
            Random random = new Random(0x736c6f744d6170L + seed);
            StaticBucketMap<Integer, Integer> map = new StaticBucketMap<>(seed % 2 == 0 ? 17 : 255);
            Map<Integer, Integer> model = new HashMap<>();
            for (int step = 0; step < 12000; step++) {
                Integer key = step % 31 == 0 ? null : random.nextInt(160);
                Integer value = step % 17 == 0 ? null : random.nextInt(200);
                switch (random.nextInt(11)) {
                case 0: case 1: case 2: same(map.put(key, value), model.put(key, value)); break;
                case 3: same(map.remove(key), model.remove(key)); break;
                case 4: same(map.get(key), model.get(key)); break;
                case 5: same(map.keySet().remove(key), model.keySet().remove(key)); break;
                case 6:
                    Map.Entry<Integer, Integer> entry = new AbstractMap.SimpleEntry<>(key, value);
                    same(map.entrySet().remove(entry), model.entrySet().remove(entry)); break;
                case 7:
                    if (!map.isEmpty()) {
                        Iterator<Map.Entry<Integer, Integer>> it = map.entrySet().iterator();
                        Map.Entry<Integer, Integer> current = it.next();
                        same(current.setValue(value), model.put(current.getKey(), value));
                        if (step % 2 == 0) { it.remove(); model.remove(current.getKey()); }
                    }
                    break;
                case 8: same(map.containsValue(value), model.containsValue(value)); break;
                case 9:
                    if (step % 97 == 0) { map.clear(); model.clear(); }
                    break;
                default:
                    same(map.putIfAbsent(key, value), model.putIfAbsent(key, value)); break;
                }
                if ((step & 63) == 0) { state(map, model); }
            }
            state(map, model);
        }
    }
    private static final class Key {
        final int id;
        Runnable action;
        Key(int id) { this.id = id; }
        @Override public int hashCode() { return 0; }
        @Override public boolean equals(Object object) {
            Runnable once = action;
            action = null;
            if (once != null) { once.run(); }
            return object instanceof Key && ((Key) object).id == id;
        }
        @Override public String toString() { return "K" + id; }
    }
    private static void boundaryEntries() {
        StaticBucketMap<Key, Integer> map = new StaticBucketMap<>(17);
        Key a = new Key(1), b = new Key(2), c = new Key(3);
        map.put(a, 1); map.put(b, null); map.put(c, 3);
        Iterator<Map.Entry<Key, Integer>> first = map.entrySet().iterator();
        check(first.hasNext(), "snapshot starts");
        Map.Entry<Key, Integer> e = first.next();
        same(e.getKey(), c);
        check(e == map.entrySet().iterator().next(), "live entry identity");
        check(e instanceof KeyValue<?, ?>, "KeyValue compatibility");
        map.put(c, 30); same(e.getValue(), 30);
        same(e.setValue(31), 30); same(map.get(c), 31);
        map.remove(c); map.put(new Key(3), 300);
        same(e.getValue(), 31); same(e.setValue(32), 31); same(map.get(c), 300);
        // Bucket snapshot still contains the original null-valued entry after removal.
        map.remove(b);
        Map.Entry<Key, Integer> detached = first.next();
        same(detached.getKey(), b); same(detached.getValue(), null);
        same(detached.setValue(20), null); same(map.get(b), null);
        first.remove();
        map.clear();
        same(first.next().getKey(), a); check(!first.hasNext(), "snapshot exhausted");
        same(e.getValue(), 32);
        MapIterator<Key, Integer> m = map.mapIterator();
        check(!m.hasNext(), "empty map iterator");
        for (int i = 0; i < 2000; i++) { map.put(new Key(i), i); }
        m = map.mapIterator();
        int visited = 0;
        while (m.hasNext()) {
            Key k = m.next(); same(m.getKey(), k); same(m.setValue(10), k.id); m.remove(); visited++;
        }
        same(visited, 2000); same(map.size(), 0);
    }
    private static void callbacks() {
        for (int mode = 0; mode < 5; mode++) {
            StaticBucketMap<Key, Integer> map = new StaticBucketMap<>(17);
            Key a = new Key(1), b = new Key(2), c = new Key(3);
            map.put(a, 1); map.put(b, 2); map.put(c, 3);
            final int selected = mode;
            a.action = () -> {
                if (selected == 0) { map.remove(a); map.put(new Key(1), 100); }
                if (selected == 1) { map.remove(b); map.put(new Key(4), 4); }
                if (selected == 2) { map.clear(); map.put(new Key(5), 5); }
                if (selected == 3) { map.put(a, 11); }
                if (selected == 4) { map.remove(c); }
            };
            Integer observed = map.get(new Key(1));
            digest = digest * 31 + Objects.hashCode(observed);
            digest = digest * 31 + map.hashCode();
            digest = digest * 31 + map.size();
            digest = digest * 31 + new ArrayList<>(map.values()).hashCode();
        }
        StaticBucketMap<Key, Integer> map = new StaticBucketMap<>(17);
        Key key = new Key(1);
        map.put(key, 1);
        RuntimeException sentinel = new IllegalArgumentException("sentinel");
        key.action = () -> { throw sentinel; };
        try { map.get(new Key(1)); throw new AssertionError("missing exception"); }
        catch (RuntimeException error) { check(error == sentinel, "exception identity"); }
        same(map.get(new Key(1)), 1);
        try { map.entrySet().contains("bad"); throw new AssertionError("bad entry accepted"); }
        catch (ClassCastException expected) { checks++; }
        try { map.entrySet().contains(null); throw new AssertionError("null entry accepted"); }
        catch (NullPointerException expected) { checks++; }
        same(map.entrySet().remove("bad"), false);
        try { map.atomic(null); throw new AssertionError("null atomic callback"); }
        catch (NullPointerException expected) { same(expected.getMessage(), "runnable"); }
    }
    private static void reentryRetiredSuffix() {
        for (int mode = 0; mode < 3; mode++) {
            StaticBucketMap<Key, Integer> map = new StaticBucketMap<>(17);
            Key a = new Key(1), b = new Key(2), c = new Key(3);
            map.put(a, 1); map.put(b, 2); map.put(c, 3);
            final int selected = mode;
            a.action = () -> {
                if (selected == 0) {
                    map.clear();
                } else {
                    map.remove(a);
                    if (selected == 2) { map.put(new Key(1), 100); }
                }
            };
            // Preserve the incumbent's observable suffix, including its existing
            // count inconsistency under these reentrant removals. This is not a
            // claim that the old behavior is desirable or newly specified.
            same(map.remove(new Key(mode == 0 ? 1 : 2)), mode == 0 ? 1 : 2);
            List<Integer> observed = new ArrayList<>();
            for (Integer item : map.values()) { observed.add(item); }
            same(observed, mode == 0 ? Arrays.asList(3, 2)
                    : mode == 1 ? Arrays.asList(3, 2) : Arrays.asList(100, 3, 2));
            same(map.size(), mode == 0 ? -1 : mode == 1 ? 1 : 2);
        }
    }

    private static void concurrent() throws Exception {
        final StaticBucketMap<Integer, Integer> map = new StaticBucketMap<>(63);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch ready = new CountDownLatch(6), start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            final int worker = t;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int round = 0; round < 12; round++) {
                        for (int i = 0; i < 1000; i++) { map.put(worker * 1000 + i, round); }
                        for (int i = 0; i < 1000; i++) {
                            if (!Objects.equals(map.get(worker * 1000 + i), round)) {
                                throw new AssertionError("lost cross-bucket write");
                            }
                        }
                        for (int i = 0; i < 1000; i++) { map.remove(worker * 1000 + i); }
                    }
                    for (int i = 0; i < 1000; i++) { map.put(worker * 1000 + i, i); }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            });
            thread.setDaemon(true); threads.add(thread); thread.start();
        }
        check(ready.await(10, TimeUnit.SECONDS), "worker startup"); start.countDown();
        for (Thread thread : threads) { thread.join(30000); check(!thread.isAlive(), "worker deadlock"); }
        if (failure.get() != null) { throw new AssertionError("worker failure", failure.get()); }
        same(map.size(), 6000);
        for (int i = 0; i < 6000; i++) { same(map.get(i), i % 1000); }
        map.atomic(() -> {
            int sum = 0;
            for (int value : map.values()) { sum += value; }
            same(sum, 6 * 999 * 1000 / 2);
            map.clear(); same(map.size(), 0);
        });
    }
    private static int countNodes(Object object, IdentityHashMap<Object, Boolean> seen) throws Exception {
        if (object == null || object instanceof Reference<?> || seen.put(object, Boolean.TRUE) != null) { return 0; }
        Class<?> type = object.getClass();
        if (type.getName().equals(StaticBucketMap.class.getName() + "$Node")) { return 1; }
        int nodes = 0;
        if (type.isArray()) {
            if (!type.getComponentType().isPrimitive()) {
                for (int i = 0; i < Array.getLength(object); i++) { nodes += countNodes(Array.get(object, i), seen); }
            }
        } else if (type.getName().startsWith(StaticBucketMap.class.getName())) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                    field.setAccessible(true); nodes += countNodes(field.get(object), seen);
                }
            }
        }
        return nodes;
    }
    public static void verifyArrayStorage() throws Exception {
        StaticBucketMap<Integer, Integer> map = new StaticBucketMap<>(17);
        for (int i = 0; i < 10000; i++) { map.put(i, i); }
        int nodes = countNodes(map, new IdentityHashMap<>());
        check(nodes == 0, "retained per-mapping Node storage remains: " + nodes);
        same(map.size(), 10000);
        System.out.println("ARRAY_STORAGE_PASS");
    }
    private static void noStaleProjectionPayload() throws Exception {
        StaticBucketMap<Object, Object> map = new StaticBucketMap<>(17);
        Object key = new Object(), oldValue = new Object(), newValue = new Object();
        map.put(key, oldValue);
        Map.Entry<Object, Object> entry = map.entrySet().iterator().next();
        map.put(key, newValue);
        check(entry.getValue() == newValue, "entry did not observe replacement");
        for (Field field : entry.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                field.setAccessible(true);
                check(field.get(entry) != oldValue, "projection retains overwritten payload");
            }
        }
        map.clear();
        check(entry.getValue() == newValue, "detached value lost");
    }

    public static void verify() throws Exception {
        checks = 0; digest = 0;
        randomized(); boundaryEntries(); callbacks(); reentryRetiredSuffix(); concurrent(); noStaleProjectionPayload();
        System.out.println("CONTRACT_PASS checks=" + checks + " digest=" + Long.toHexString(digest));
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("retention")) { noStaleProjectionPayload(); return; }
        if (args.length > 0 && args[0].equals("storage")) { verifyArrayStorage(); } else { verify(); }
    }
}
