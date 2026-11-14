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
package com.m3.collections.apache.bidimap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.Test;

/** Entry hash publication contracts exercised through independently exposed entries. */
class TreeBidiMapHashPublicationTest {

    private static final int[] HASHES = {
        0, Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 1, 0x55555555, 0xaaaaaaaa, 0x40000000
    };

    private static final class Value implements Comparable<Value> {
        private final int order;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile int hash;
        private volatile IntSupplier onHash;

        Value(final int order, final int hash) {
            this.order = order;
            this.hash = hash;
        }

        @Override
        public int compareTo(final Value other) {
            return Integer.compare(order, other.order);
        }

        @Override
        public boolean equals(final Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            calls.incrementAndGet();
            final IntSupplier action = onHash;
            return action == null ? hash : action.getAsInt();
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Hash callback rendezvous timed out");
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static TreeBidiMap<Value, Value> populated(final int count) {
        final TreeBidiMap<Value, Value> map = new TreeBidiMap<>();
        for (int index = 0; index < count; index++) {
            map.put(new Value(index, HASHES[index % HASHES.length]), new Value(index + 1000, 0));
        }
        return map;
    }

    private static List<Map.Entry<Value, Value>> project(final TreeBidiMap<Value, Value> map) {
        return new ArrayList<>(map.entrySet());
    }

    private static void shutdown(final ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void allIntHashValuesRemainSharedAfterDetachment() {
        for (int scenario = 0; scenario < 16; scenario++) {
            final boolean initiallyComputed = (scenario & 1) != 0;
            final int rotation = scenario / 2;
            final TreeBidiMap<Value, Value> map = populated(24);
            final List<Map.Entry<Value, Value>> first = project(map);
            final List<Map.Entry<Value, Value>> second = project(map);
            for (int index = 0; index < first.size(); index++) {
                first.get(index).getKey().hash = HASHES[(index + rotation) % HASHES.length];
            }
            if (initiallyComputed) {
                for (int index = 0; index < first.size(); index++) {
                    assertEquals(HASHES[(index + rotation) % HASHES.length], first.get(index).hashCode());
                    first.get(index).getKey().hash ^= 0x55aa55aa;
                }
            }
            map.clear();
            for (int index = 0; index < 24; index++) {
                map.put(new Value(index + 100, index + 9), new Value(index + 2000, 13));
            }
            for (final Map.Entry<Value, Value> replacement : map.entrySet()) {
                assertEquals(replacement.getKey().hash ^ 13, replacement.hashCode());
            }
            for (int repeat = 0; repeat < 16; repeat++) {
                for (int index = 0; index < first.size(); index++) {
                    final int expected = HASHES[(index + rotation) % HASHES.length];
                    assertEquals(expected, first.get(index).hashCode());
                    assertEquals(expected, second.get(index).hashCode());
                    assertEquals(1, first.get(index).getKey().calls.get());
                    assertEquals(1, first.get(index).getValue().calls.get());
                }
            }
        }
    }

    @Test
    void coldConcurrentReadersObserveCompleteHashesAcrossAllCells() throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            final TreeBidiMap<Value, Value> map = populated(24);
            final List<List<Map.Entry<Value, Value>>> projections = new ArrayList<>();
            for (int reader = 0; reader < 8; reader++) {
                projections.add(project(map));
            }
            for (int phase = 0; phase < 2; phase++) {
                if (phase != 0) {
                    map.clear();
                    map.put(new Value(100, 101), new Value(200, 201));
                }
                final CountDownLatch start = new CountDownLatch(1);
                final List<Future<?>> futures = new ArrayList<>();
                for (int reader = 0; reader < projections.size(); reader++) {
                    final int readerIndex = reader;
                    final List<Map.Entry<Value, Value>> entries = projections.get(reader);
                    futures.add(executor.submit(() -> {
                        await(start);
                        for (int repeat = 0; repeat < 4096; repeat++) {
                            final int index = (repeat + readerIndex) % entries.size();
                            assertEquals(HASHES[index % HASHES.length], entries.get(index).hashCode());
                        }
                    }));
                }
                start.countDown();
                for (final Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
            }
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void laterConcurrentCompletionReachesEveryExistingProjection() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            for (final boolean detached : new boolean[] {false, true}) {
                for (int offset = 0; offset < HASHES.length; offset++) {
                    final TreeBidiMap<Value, Value> map = populated(8);
                    final Map.Entry<Value, Value> outer = project(map).get(offset);
                    final Map.Entry<Value, Value> nested = project(map).get(offset);
                    final Map.Entry<Value, Value> observer = project(map).get(offset);
                    if (detached) {
                        map.remove(outer.getKey());
                        map.put(new Value(100, 101), new Value(200, 201));
                        map.clear();
                    }
                    final CountDownLatch entered = new CountDownLatch(1);
                    final CountDownLatch release = new CountDownLatch(1);
                    final AtomicInteger turns = new AtomicInteger();
                    outer.getKey().onHash = () -> {
                        if (turns.getAndIncrement() == 0) {
                            entered.countDown();
                            await(release);
                            return 11;
                        }
                        return 7;
                    };
                    final Future<Integer> first = executor.submit(outer::hashCode);
                    try {
                        await(entered);
                        assertEquals(7, nested.hashCode());
                        assertEquals(7, observer.hashCode());
                    } finally {
                        release.countDown();
                    }
                    assertEquals(Integer.valueOf(11), first.get(5, TimeUnit.SECONDS));
                    assertEquals(11, outer.hashCode());
                    assertEquals(11, nested.hashCode());
                    assertEquals(11, observer.hashCode());
                    assertEquals(2, turns.get());
                }
            }
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void nestedPublicationSurvivesAnOuterCallbackFailure() {
        for (int offset = 0; offset < HASHES.length; offset++) {
            final TreeBidiMap<Value, Value> map = populated(8);
            final Map.Entry<Value, Value> outer = project(map).get(offset);
            final Map.Entry<Value, Value> nested = project(map).get(offset);
            final Map.Entry<Value, Value> observer = project(map).get(offset);
            final AtomicInteger turns = new AtomicInteger();
            outer.getKey().onHash = () -> {
                if (turns.getAndIncrement() == 0) {
                    assertEquals(7, nested.hashCode());
                    throw new IllegalStateException("outer hash failed");
                }
                return 7;
            };
            assertEquals("outer hash failed", assertThrows(IllegalStateException.class, outer::hashCode).getMessage());
            assertEquals(7, outer.hashCode());
            assertEquals(7, nested.hashCode());
            assertEquals(7, observer.hashCode());
            assertEquals(2, turns.get());
        }
    }
}
