/* Copyright 2026 Hitesh Soliwal and contributors. SPDX-License-Identifier: Apache-2.0 */
package com.m3.collections.apache.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Entry getters must not introduce a cross-bucket monitor cycle in equality callbacks. */
public final class StaticBucketEntryConcurrency {
    private StaticBucketEntryConcurrency() { }
    private static final class Key {
        final int id;
        Runnable action;
        Key(int id) { this.id = id; }
        @Override public int hashCode() { return id; }
        @Override public boolean equals(Object other) {
            Runnable once = action; action = null;
            if (once != null) { once.run(); }
            return other instanceof Key && ((Key) other).id == id;
        }
    }
    public static void main(String[] args) throws Exception {
        for (int round = 0; round < 100; round++) {
            StaticBucketMap<Key, Integer> map = new StaticBucketMap<>(17);
            Key a = new Key(1), b = new Key(2);
            map.put(a, 11); map.put(b, 22);
            List<Map.Entry<Key, Integer>> entries = new ArrayList<>(map.entrySet());
            Map.Entry<Key, Integer> ea = entries.get(0).getKey() == a ? entries.get(0) : entries.get(1);
            Map.Entry<Key, Integer> eb = entries.get(0).getKey() == b ? entries.get(0) : entries.get(1);
            CountDownLatch both = new CountDownLatch(2);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            a.action = () -> callback(both, eb, 22);
            b.action = () -> callback(both, ea, 11);
            Thread one = new Thread(() -> run(map, 1, 11, failure));
            Thread two = new Thread(() -> run(map, 2, 22, failure));
            one.setDaemon(true); two.setDaemon(true); one.start(); two.start();
            one.join(4000); two.join(4000);
            if (one.isAlive() || two.isAlive()) { throw new AssertionError("new entry getter lock cycle"); }
            if (failure.get() != null) { throw new AssertionError("callback failed", failure.get()); }
        }
        System.out.println("ENTRY_CONCURRENCY_PASS rounds=100");
    }
    private static void callback(CountDownLatch both, Map.Entry<Key, Integer> entry, int value) {
        both.countDown();
        try {
            if (!both.await(2, TimeUnit.SECONDS)) { throw new AssertionError("callbacks not concurrent"); }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        if (entry.getValue() != value) { throw new AssertionError("wrong entry value"); }
    }
    private static void run(StaticBucketMap<Key, Integer> map, int key, int value,
            AtomicReference<Throwable> failure) {
        try {
            if (map.get(new Key(key)) != value) { throw new AssertionError("wrong map value"); }
        } catch (Throwable error) { failure.compareAndSet(null, error); }
    }
}
