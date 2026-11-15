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
package com.m3.collections.apache.queue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import com.m3.collections.apache.iterators.ArrayIterator;
import com.m3.collections.apache.list.TreeList;

/** Exercise filtering with the fork's merged array iterators and reusable tree slots. */
public final class CollectionRingFilterIntegration {
    private static long checks;
    private static long digest;

    private CollectionRingFilterIntegration() { }

    private static void equal(final Object actual, final Object expected) {
        checks++;
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError(actual + " != " + expected);
        }
        digest = 31 * digest + Objects.hashCode(expected);
    }

    public static void verify() {
        checks = 0;
        digest = 0;
        for (int seed = 0; seed < 24; seed++) {
            final int capacity = 64 + seed * 17;
            final CircularFifoQueue<Integer> queue = new CircularFifoQueue<>(capacity);
            final List<Integer> model = new ArrayList<>();
            final TreeList<Integer> source = new TreeList<>();
            for (int i = 0; i < capacity * 2; i++) { source.add(i % 257); }
            final ArrayIterator<Integer> input = new ArrayIterator<>(source.toArray(new Integer[0]));
            while (input.hasNext()) {
                final Integer value = input.next();
                queue.add(value);
                model.add(value);
                if (model.size() > capacity) { model.remove(0); }
            }
            final HashSet<Integer> membership = new HashSet<>();
            for (final Integer value : source) {
                if ((value + seed) % 3 != 0) { membership.add(value); }
            }
            final boolean retain = (seed & 1) == 0;
            equal(retain ? queue.retainAll(membership) : queue.removeAll(membership),
                    retain ? model.retainAll(membership) : model.removeAll(membership));
            equal(Arrays.asList(queue.toArray(new Integer[0])), model);
            final TreeList<Integer> received = new TreeList<>(queue);
            final List<Integer> combined = new ArrayList<>(model);
            received.addAll(received.size() / 2, new ArrayList<>(queue));
            combined.addAll(combined.size() / 2, model);
            equal(new ArrayList<>(received), combined);
            received.subList(0, received.size() / 2).clear();
            combined.subList(0, combined.size() / 2).clear();
            equal(new ArrayList<>(received), combined);
            queue.addAll(new ArrayList<>(received));
            model.addAll(combined);
            if (model.size() > capacity) { model.subList(0, model.size() - capacity).clear(); }
            equal(new ArrayList<>(queue), model);
            final Iterator<Integer> cursor = queue.iterator();
            final Iterator<Integer> expected = model.iterator();
            int index = 0;
            while (expected.hasNext()) {
                equal(cursor.next(), expected.next());
                if ((index++ & 3) == 0) { cursor.remove(); expected.remove(); }
            }
            equal(cursor.hasNext(), false);
            equal(new ArrayList<>(queue), model);
        }
        System.out.println("COLLECTION_RING_INTEGRATION_PASS checks=" + checks
                + " digest=" + Long.toHexString(digest));
    }

    public static void main(final String[] args) { verify(); }
}
