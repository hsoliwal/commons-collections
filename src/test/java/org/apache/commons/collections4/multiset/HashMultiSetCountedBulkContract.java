/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.collections4.multiset;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.HashSet;

/** Executable MultiSet-specific counted-bulk and legacy callback contract. */
public final class HashMultiSetCountedBulkContract {
    private static long checks;

    private HashMultiSetCountedBulkContract() { }

    private static void check(final boolean ok, final String label) {
        checks++;
        if (!ok) {
            throw new AssertionError(label + " at check " + checks);
        }
    }

    private static int mods(final HashMultiSet<?> multiset) {
        try {
            final Field field = AbstractMapMultiSet.class.getDeclaredField("modCount");
            field.setAccessible(true);
            return field.getInt(multiset);
        } catch (final ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static HashMultiSet<Object> make(final Object[] keys, final int count) {
        final HashMultiSet<Object> set = new HashMultiSet<>();
        for (final Object key : keys) {
            set.add(key, count);
        }
        return set;
    }

    private static void same(final HashMultiSet<?> a, final HashMultiSet<?> b,
            final Object[] keys, final String operation) {
        check(a.size() == b.size(), operation + " size");
        check(a.uniqueSet().equals(b.uniqueSet()), operation + " distinct");
        check(mods(a) == mods(b), operation + " modCount");
        for (final Object key : keys) {
            check(a.getCount(key) == b.getCount(key), operation + " occurrence");
        }
    }

    private static void randomizedCounts() {
        for (final int distinct : new int[] {1, 2, 7, 16, 64}) {
            for (final int copies : new int[] {1, 2, 8, 32, 256}) {
                for (int variant = 0; variant < 3; variant++) {
                    final Object[] keys = new Object[distinct];
                    for (int i = 0; i < distinct; i++) {
                        keys[i] = variant == 0 ? Integer.valueOf(i)
                                : variant == 1 ? (i == 0 ? null : "word" + i)
                                : Long.valueOf(i);
                    }
                    final HashMultiSet<Object> source = make(keys, copies);
                    final int sourceMods = mods(source);
                    final Object[] targetKeys = Arrays.copyOf(keys, distinct / 2);
                    final HashMultiSet<Object> original = make(targetKeys, 4);
                    final HashMultiSet<Object> candidate = make(targetKeys, 4);
                    boolean added = false;
                    for (final Object key : source) {
                        added |= original.add(key);
                    }
                    check(added == candidate.addAll(source), "MultiSet.addAll return");
                    same(original, candidate, keys, "addAll");
                    check(mods(source) == sourceMods, "source changed");

                    final HashMultiSet<Object> oldRemoval = make(keys, copies / 2 + 1);
                    final HashMultiSet<Object> newRemoval = make(keys, copies / 2 + 1);
                    boolean removed = false;
                    for (final Object key : source) {
                        removed |= oldRemoval.remove(key, oldRemoval.getCount(key)) != 0;
                    }
                    check(removed == newRemoval.removeAll(source), "MultiSet.removeAll return");
                    same(oldRemoval, newRemoval, keys, "removeAll");
                }
            }
        }
    }

    private static void saturationAndRemoveAll() {
        final HashMultiSet<Integer> source = new HashMultiSet<>();
        source.add(7, 1_000_000);
        final HashMultiSet<Integer> target = new HashMultiSet<>();
        target.add(7, Integer.MAX_VALUE - 9);
        // MultiSet.add(E) always returns true, unlike Bag.add(E).
        check(target.addAll(source), "addAll returns true for existing key");
        check(target.getCount(7) == Integer.MAX_VALUE, "count saturation");
        check(mods(target) == 1_000_001, "per-occurrence add counter");
        check(target.removeAll(source), "removeAll removes full matching key");
        check(target.getCount(7) == 0, "removeAll MultiSet cardinality");
        check(mods(target) == 1_000_002, "per-distinct remove counter");
        check(source.getCount(7) == 1_000_000, "source intact");
    }

    private static final class UserKey {
        private int calls;
        @Override public int hashCode() { return ++calls; }
    }

    private static final class Subclass extends HashMultiSet<String> {
        private int adds;
        private int removes;
        @Override public boolean add(final String key) {
            adds++;
            return super.add(key);
        }
        @Override public int remove(final Object key, final int copies) {
            removes++;
            return super.remove(key, copies);
        }
    }

    private static void protectedFallbacks() {
        final HashMultiSet<String> self = new HashMultiSet<>();
        self.add("self", 3);
        try {
            self.addAll(self);
            throw new AssertionError("self add should throw CME");
        } catch (final ConcurrentModificationException expected) {
            check(self.getCount("self") == 4, "self add prefix");
        }
        final HashMultiSet<String> selfRemove = new HashMultiSet<>();
        selfRemove.add("self", 3);
        try {
            selfRemove.removeAll(selfRemove);
            throw new AssertionError("self remove should throw CME");
        } catch (final ConcurrentModificationException expected) {
            check(selfRemove.getCount("self") == 0, "self remove prefix");
        }
        final UserKey key = new UserKey();
        final HashMultiSet<UserKey> source = new HashMultiSet<>();
        source.add(key, 256);
        final HashMultiSet<UserKey> receiver = new HashMultiSet<>();
        final int before = key.calls;
        check(receiver.addAll(source), "user key added");
        check(key.calls - before >= 256, "custom hash callback bypassed");

        final HashMultiSet<String> regular = new HashMultiSet<>();
        regular.add("v", 128);
        final Subclass subclass = new Subclass();
        check(subclass.addAll(regular), "subclass added");
        check(subclass.adds == 128, "subclass add callbacks");
        check(subclass.removeAll(regular), "subclass removed");
        check(subclass.removes == 128, "subclass remove callbacks");
        check(subclass.isEmpty(), "subclass empties");
    }

    public static void verify() {
        checks = 0;
        randomizedCounts();
        saturationAndRemoveAll();
        protectedFallbacks();
        System.out.println("HASH_MULTISET_COUNTED_BULK_PASS checks=" + checks);
    }

    public static void main(final String[] args) {
        verify();
    }
}
