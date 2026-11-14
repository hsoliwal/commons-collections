/*
 * Copyright 2026 Hitesh Soliwal and contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.m3.collections.apache;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.m3.collections.apache.iterators.ListIteratorPagedCacheContract;
import com.m3.collections.apache.iterators.ListIteratorWrapper;
import com.m3.collections.apache.iterators.PermutationIterator;
import com.m3.collections.apache.list.TreeList;
import com.m3.collections.apache.map.StaticBucketMap;
import com.m3.collections.apache.map.StaticBucketProjectionContract;

/** Exercises the optimized owners together, against independent JDK collection state. */
public final class CollectionForkIntegrationContract {
    private static long checks;
    private static long fingerprint;

    private CollectionForkIntegrationContract() {
    }

    private static void equal(final Object actual, final Object expected) {
        checks++;
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError("check " + checks + ": " + actual + " != " + expected);
        }
        fingerprint = fingerprint * 31 + Objects.hashCode(expected);
    }

    private static void replayAndRemove(final TreeList<Integer> owner, final List<Integer> model) {
        final ListIteratorWrapper<Integer> replay = new ListIteratorWrapper<>(owner.iterator());
        for (final Integer expected : model) {
            equal(replay.next(), expected);
        }
        equal(replay.hasNext(), false);
        if (!model.isEmpty()) {
            equal(replay.previous(), model.get(model.size() - 1));
            equal(replay.next(), model.get(model.size() - 1));
            replay.remove();
            model.remove(model.size() - 1);
        }
        replay.reset();
        for (final Integer expected : model) {
            equal(replay.next(), expected);
        }
        equal(replay.hasNext(), false);
        equal(new ArrayList<>(owner), model);
        for (int i = model.size() - 1; i >= 0; i--) {
            equal(replay.previous(), model.get(i));
        }
        equal(replay.hasPrevious(), false);
    }

    private static void permutationToMapOfTrees(final int seed) {
        final StaticBucketMap<Integer, TreeList<Integer>> owners = new StaticBucketMap<>(17);
        final Map<Integer, List<Integer>> model = new HashMap<>();
        final List<Integer> keys = Arrays.asList(null, seed * 10 + 1, seed * 10 + 2,
                seed * 10 + 3, seed * 10 + 4);
        for (final Integer key : keys) {
            owners.put(key, new TreeList<>());
            model.put(key, new ArrayList<>());
        }
        // TreeList consumes the real map's key view, then the real permutation owner.
        final TreeList<Integer> keySnapshot = new TreeList<>(owners.keySet());
        equal(new HashSet<>(keySnapshot), new HashSet<>(keys));
        final PermutationIterator<Integer> permutations = new PermutationIterator<>(keySnapshot);
        final Set<List<Integer>> seen = new HashSet<>();
        List<Integer> previousOutput = null;
        int count = 0;
        while (permutations.hasNext()) {
            final List<Integer> order = permutations.next();
            if (previousOutput != null) {
                previousOutput.set(0, Integer.MIN_VALUE);
            }
            equal(new HashSet<>(order), new HashSet<>(keys));
            equal(order.size(), keys.size());
            equal(seen.add(new ArrayList<>(order)), true);
            final Integer key = order.get(count % keys.size());
            final TreeList<Integer> owner = owners.get(key);
            final List<Integer> expected = model.get(key);
            equal(owner.addAll(order), expected.addAll(order));
            if (expected.size() > 80) {
                owner.subList(0, expected.size() - 80).clear();
                expected.subList(0, expected.size() - 80).clear();
            }
            if ((count & 7) == 0) {
                replayAndRemove(owner, expected);
            }
            equal(Arrays.asList(owner.toArray(new Integer[0])), expected);
            equal(owner.hashCode(), expected.hashCode());
            previousOutput = order;
            count++;
        }
        equal(count, 120);
        for (final Integer key : keys) {
            equal(new ArrayList<>(owners.get(key)), model.get(key));
        }
        // A live entry must follow map replacement, but never a recycled mapping.
        final Map.Entry<Integer, TreeList<Integer>> held = owners.entrySet().iterator().next();
        final Integer key = held.getKey();
        final TreeList<Integer> replacement = new TreeList<>(Arrays.asList(seed, null));
        owners.put(key, replacement);
        equal(held.getValue() == replacement, true);
        owners.remove(key);
        final TreeList<Integer> recycled = new TreeList<>(Arrays.asList(seed + 1, null));
        owners.put(key, recycled);
        held.setValue(new TreeList<>());
        equal(owners.get(key) == recycled, true);
        final Iterator<Integer> staleKeys = owners.keySet().iterator();
        equal(staleKeys.hasNext(), true);
        owners.clear();
        // At least the first captured bucket survives; later buckets stay weakly consistent.
        final Integer stale = staleKeys.next();
        equal(keys.contains(stale), true);
        staleKeys.remove();
        equal(owners.isEmpty(), true);
    }

    private static void cachedSourceFailureRollsBackBulkTree() {
        final TreeList<Integer> target = new TreeList<>(Arrays.asList(7, null, 8));
        final RuntimeException sentinel = new IllegalArgumentException("cached bulk source failure");
        final Iterator<Integer> failing = new Iterator<Integer>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < 31;
            }

            @Override
            public Integer next() {
                if (index == 15) {
                    throw sentinel;
                }
                return index++;
            }
        };
        final ListIteratorWrapper<Integer> replay = new ListIteratorWrapper<>(failing);
        final AbstractCollection<Integer> source = new AbstractCollection<Integer>() {
            @Override
            public int size() {
                return 31;
            }

            @Override
            public Iterator<Integer> iterator() {
                return replay;
            }
        };
        try {
            target.addAll(source);
            throw new AssertionError("source failure was swallowed");
        } catch (final RuntimeException failure) {
            equal(failure == sentinel, true);
        }
        equal(new ArrayList<>(target), Arrays.asList(7, null, 8));
        replay.reset();
        final List<Integer> recovered = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            recovered.add(replay.next());
            equal(recovered.get(i), i);
        }
        target.addAll(recovered);
        final List<Integer> expected = new ArrayList<>(Arrays.asList(7, null, 8));
        expected.addAll(recovered);
        replayAndRemove(target, expected);
    }

    private static void largeReplayOverReusedTreeSlots() {
        final TreeList<Integer> owner = new TreeList<>();
        final List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 8193; i++) {
            final Integer value = i % 17 == 0 ? null : i;
            owner.add(value);
            expected.add(value);
        }
        for (int i = 0; i < 2048; i++) {
            final int index = (i * 31) % expected.size();
            equal(owner.remove(index), expected.remove(index));
        }
        final List<Integer> tail = new ArrayList<>(expected.subList(0, 2048));
        owner.addAll(tail);
        expected.addAll(tail);
        replayAndRemove(owner, expected);
    }

    public static void verifyBehavior() {
        checks = 0;
        fingerprint = 0;
        for (int seed = 0; seed < 32; seed++) {
            permutationToMapOfTrees(seed);
        }
        cachedSourceFailureRollsBackBulkTree();
        largeReplayOverReusedTreeSlots();
        System.out.println("INTEGRATION_PASS checks=" + checks + " fingerprint="
                + Long.toHexString(fingerprint));
    }

    public static void verifyOptimizedStorage() throws ReflectiveOperationException {
        StaticBucketProjectionContract.keyTraversalDoesNotExposeEntries();
        StaticBucketProjectionContract.registrationsArePageBounded();
        ListIteratorPagedCacheContract.storage();
    }

    public static void main(final String[] args) throws ReflectiveOperationException {
        if (args.length != 0 && "storage".equals(args[0])) {
            verifyOptimizedStorage();
        } else {
            verifyBehavior();
        }
    }
}
