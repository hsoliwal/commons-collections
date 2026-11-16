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
package org.apache.commons.collections4.bag;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.HashSet;

/** Executable count-based transfer and legacy Bag behavior regression. */
@SuppressWarnings("deprecation")
public final class HashBagCountedBulkContract {
    private static int checks;

    private HashBagCountedBulkContract() { }

    private static void check(final boolean value, final String label) {
        checks++;
        if (!value) {
            throw new AssertionError(label + " at check " + checks);
        }
    }

    private static int mods(final HashBag<?> bag) {
        try {
            final Field field = AbstractMapBag.class.getDeclaredField("modCount");
            field.setAccessible(true);
            return field.getInt(bag);
        } catch (final ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void same(final HashBag<?> a, final HashBag<?> b,
            final Object[] keys, final String label) {
        check(a.size() == b.size(), label + " total");
        check(a.uniqueSet().equals(b.uniqueSet()), label + " unique");
        check(mods(a) == mods(b), label + " modCount");
        for (final Object key : keys) {
            check(a.getCount(key) == b.getCount(key), label + " count");
        }
    }

    private static HashBag<Object> populated(final Object[] keys, final int count) {
        final HashBag<Object> bag = new HashBag<>();
        for (final Object key : keys) {
            bag.add(key, count);
        }
        return bag;
    }

    private static void basic() {
        for (final int distinct : new int[] {1, 2, 7, 16, 64}) {
            for (final int count : new int[] {1, 2, 8, 64, 256}) {
                for (int mode = 0; mode < 3; mode++) {
                    final Object[] keys = new Object[distinct];
                    for (int i = 0; i < keys.length; i++) {
                        keys[i] = mode == 0 ? Integer.valueOf(i)
                                : mode == 1 ? (i == 0 ? null : "word" + i)
                                : Long.valueOf(i);
                    }
                    final HashBag<Object> source = populated(keys, count);
                    final Object[] targetKeys = Arrays.copyOf(keys, distinct / 2);
                    final HashBag<Object> originalAdd = populated(targetKeys, 3);
                    final HashBag<Object> optimizedAdd = populated(targetKeys, 3);
                    final int sourceMods = mods(source);
                    boolean changed = false;
                    for (final Object key : source) {
                        changed |= originalAdd.add(key);
                    }
                    check(changed == optimizedAdd.addAll(source), "add changed");
                    same(originalAdd, optimizedAdd, keys, "add");
                    check(sourceMods == mods(source), "source modifications");

                    final HashBag<Object> originalRemove = populated(keys, count / 2 + 1);
                    final HashBag<Object> optimizedRemove = populated(keys, count / 2 + 1);
                    changed = false;
                    for (final Object key : source) {
                        changed |= originalRemove.remove(key, 1);
                    }
                    check(changed == optimizedRemove.removeAll(source), "remove changed");
                    same(originalRemove, optimizedRemove, keys, "remove");
                }
            }
        }
    }

    private static void largeAndRetain() {
        final HashBag<Integer> many = new HashBag<>();
        many.add(7, 1_000_000);
        final HashBag<Integer> receiver = new HashBag<>();
        receiver.add(7, Integer.MAX_VALUE - 9);
        check(!receiver.addAll(many), "saturated key reported as new");
        check(receiver.getCount(7) == Integer.MAX_VALUE, "saturation count");
        check(mods(receiver) == 1_000_001, "bulk add modification count");
        check(receiver.removeAll(many), "bulk remove changed");
        check(receiver.getCount(7) == Integer.MAX_VALUE - 1_000_000, "large remove count");
        check(mods(receiver) == 2_000_001, "bulk remove modification count");

        final HashBag<String> original = new HashBag<>();
        final HashBag<String> candidate = new HashBag<>();
        final HashBag<String> keep = new HashBag<>();
        for (final HashBag<String> bag : Arrays.asList(original, candidate)) {
            bag.add("a", 8192);
            bag.add("b", 4096);
            bag.add("c", 128);
        }
        keep.add("a", 3072);
        keep.add("c", 256);
        final HashBag<String> excess = new HashBag<>();
        excess.add("a", 5120);
        excess.add("b", 4096);
        for (final String key : excess) {
            original.remove(key, 1);
        }
        check(candidate.retainAll(keep), "retain changed");
        check(original.size() == candidate.size(), "retain size");
        check(mods(original) == mods(candidate), "retain modCount");
        for (final String key : new String[] {"a", "b", "c"}) {
            check(original.getCount(key) == candidate.getCount(key), "retain count");
        }
    }

    private static final class CallbackKey {
        private int calls;
        @Override public int hashCode() { return ++calls; }
    }

    private static final class CallbackBag extends HashBag<String> {
        private int adds;
        private int removes;
        @Override public boolean add(final String value) {
            adds++;
            return super.add(value);
        }
        @Override public boolean remove(final Object value, final int copies) {
            removes++;
            return super.remove(value, copies);
        }
    }

    private static void fallbacks() {
        final HashBag<String> same = new HashBag<>();
        same.add("self", 3);
        try {
            same.addAll(same);
            throw new AssertionError("self add must fail fast");
        } catch (final ConcurrentModificationException expected) {
            check(same.getCount("self") == 4, "self add successful prefix");
        }
        final HashBag<String> selfRemove = new HashBag<>();
        selfRemove.add("self", 3);
        try {
            selfRemove.removeAll(selfRemove);
            throw new AssertionError("self remove must fail fast");
        } catch (final ConcurrentModificationException expected) {
            check(selfRemove.getCount("self") == 2, "self remove successful prefix");
        }

        // No fast path may suppress the source key's hashCode callbacks.
        final CallbackKey callback = new CallbackKey();
        final HashBag<CallbackKey> source = new HashBag<>();
        source.add(callback, 256);
        final HashBag<CallbackKey> target = new HashBag<>();
        final int before = callback.calls;
        check(target.addAll(source), "callback key added");
        check(callback.calls - before >= 256, "custom hash callback count");

        final HashBag<String> regular = new HashBag<>();
        regular.add("value", 128);
        final CallbackBag subclass = new CallbackBag();
        check(subclass.addAll(regular), "subclass add");
        check(subclass.adds == 128, "subclass virtual add hook");
        check(subclass.removeAll(regular), "subclass remove");
        check(subclass.removes == 128, "subclass virtual remove hook");
        check(subclass.isEmpty(), "subclass emptied");
    }

    public static void verify() {
        checks = 0;
        basic();
        largeAndRetain();
        fallbacks();
        System.out.println("HASHBAG_COUNTED_BULK_PASS checks=" + checks);
    }

    public static void main(final String[] args) {
        verify();
    }
}
