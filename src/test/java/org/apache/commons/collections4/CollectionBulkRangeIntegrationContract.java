/*
 * Copyright 2026 Hitesh Soliwal and Synexia contributors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.collections4;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.collections4.iterators.ListIteratorWrapper;
import org.apache.commons.collections4.iterators.PermutationIterator;
import org.apache.commons.collections4.list.TreeList;
import org.apache.commons.collections4.map.StaticBucketMap;

/** Exercises bulk deletion with the merged map, replay and permutation owners. */
public final class CollectionBulkRangeIntegrationContract {
    private long checks;
    private void check(final boolean value, final String message) {
        checks++;
        if (!value) { throw new AssertionError(message); }
    }
    private CollectionBulkRangeIntegrationContract() { }
    private void run() {
        for (int pass = 0; pass < 8; pass++) {
            final StaticBucketMap<Integer, Integer> map = new StaticBucketMap<>(4095);
            for (int i = 0; i < 8192; i++) { map.put(i, i + pass); }
            final TreeList<Integer> tree = new TreeList<>(map.keySet());
            final List<Integer> reference = new ArrayList<>(tree);
            final Iterator<Integer> source = tree.iterator();
            // Deliberately hide ListIterator so replay uses its actual paged cache.
            final ListIteratorWrapper<Integer> replay = new ListIteratorWrapper<>(new Iterator<Integer>() {
                @Override public boolean hasNext() { return source.hasNext(); }
                @Override public Integer next() { return source.next(); }
            });
            final List<Integer> cached = new ArrayList<>();
            for (int i = 0; i < 2049; i++) { cached.add(replay.next()); }
            final Map.Entry<Integer, Integer> held = map.entrySet().iterator().next();
            final Integer heldKey = held.getKey();
            final List<Integer> parent = tree.subList(64, 8100);
            final List<Integer> child = parent.subList(32, 7000);
            child.clear();
            reference.subList(96, 7064).clear();
            check(tree.equals(reference) && child.isEmpty() && parent.size() == 1068, "large backed range");
            replay.reset();
            for (int i = 0; i < cached.size(); i++) { check(replay.next() == cached.get(i), "cached reference changed"); }
            try { replay.next(); throw new AssertionError("underlying stale tree cursor accepted"); }
            catch (final ConcurrentModificationException expected) { checks++; }
            final List<Integer> six = new ArrayList<>(tree.subList(0, 6));
            final PermutationIterator<Integer> permutations = new PermutationIterator<>(six);
            int count = 0;
            while (permutations.hasNext()) {
                final List<Integer> permutation = permutations.next();
                tree.addAll(permutation); reference.addAll(permutation);
                permutation.clear();
                count++;
            }
            check(count == 720 && tree.equals(reference), "permutation append aliases or order");
            map.put(heldKey, -77);
            check(held.getValue() == -77, "live entry lost update");
            map.remove(heldKey); map.put(heldKey, -88);
            check(held.getValue() == -77 && map.get(heldKey) == -88, "detached entry rebound");
            tree.subList(0, tree.size()).clear();
            check(tree.isEmpty(), "full range not cleared");
            tree.addAll(map.keySet());
            check(tree.size() == 8192 && tree.equals(new ArrayList<>(map.keySet())), "recycled bulk refill");
        }
    }
    public static void verify() {
        final CollectionBulkRangeIntegrationContract test = new CollectionBulkRangeIntegrationContract();
        test.run();
        System.out.println("BULK_RANGE_INTEGRATION_PASS checks=" + test.checks);
    }
    public static void main(final String[] args) { verify(); }
}
