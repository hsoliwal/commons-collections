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
package org.apache.commons.collections4.iterators;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;

import org.apache.commons.collections4.Transformer;

/** Executable contracts for the actual chain and graph owners. */
public final class IteratorTraversalContract {
    private static long checks;
    private static long digest = 1;
    private static int events;
    private static boolean printTrace;

    private IteratorTraversalContract() {
    }

    private static void check(final boolean condition, final String label) {
        checks++;
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static void event(final String value) {
        events++;
        for (int i = 0; i < value.length(); i++) {
            digest = 31 * digest + value.charAt(i);
        }
        digest = 31 * digest + '\n';
        if (printTrace) {
            System.out.println(value);
        }
    }

    private static final class Probe implements Iterator<Object> {
        private final String id;
        private final List<Object> contents;
        private final Iterator<Object> source;
        private Runnable hook;
        private RuntimeException failure;
        private int reads;

        Probe(final String id, final List<?> input) {
            this.id = id;
            contents = new ArrayList<>(input);
            source = contents.iterator();
        }

        @Override
        public boolean hasNext() {
            event(id + ":h:" + reads);
            if (hook != null) {
                final Runnable action = hook;
                hook = null;
                action.run();
            }
            if (failure != null) {
                final RuntimeException error = failure;
                failure = null;
                throw error;
            }
            return source.hasNext();
        }

        @Override
        public Object next() {
            event(id + ":n:" + reads++);
            return source.next();
        }

        @Override
        public void remove() {
            event(id + ":r:" + reads);
            source.remove();
        }
    }

    private static final class ChainHooks extends IteratorChain<Object> {
        @Override
        public void addIterator(final Iterator<? extends Object> it) {
            event("chain:add");
            super.addIterator(it);
        }

        @Override
        protected void updateCurrentIterator() {
            event("chain:update");
            super.updateCurrentIterator();
        }

        void prime() {
            updateCurrentIterator();
        }
    }

    private static final class GraphHooks extends ObjectGraphIterator<Object> {
        GraphHooks(final Object root, final Transformer<Object, Object> transform) {
            super(root, transform);
        }

        @Override
        protected void findNext(final Object value) {
            event("graph:find:" + (value instanceof Iterator ? "iterator" : value));
            super.findNext(value);
        }

        @Override
        protected void findNextByIterator(final Iterator<? extends Object> it) {
            event("graph:descend");
            super.findNextByIterator(it);
        }

        @Override
        protected void updateCurrentIterator() {
            event("graph:update");
            super.updateCurrentIterator();
        }
    }

    private static List<Object> graph(final Random random, final int depth) {
        final List<Object> result = new ArrayList<>();
        for (int i = 0, n = random.nextInt(5); i < n; i++) {
            result.add(depth != 0 && random.nextInt(3) == 0 ? graph(random, depth - 1)
                    : random.nextInt(7) == 0 ? null : random.nextInt(100));
        }
        return result;
    }

    private static void flatten(final Object input, final List<Object> output) {
        if (input instanceof List<?>) {
            for (final Object value : (List<?>) input) {
                flatten(value, output);
            }
        } else {
            output.add(input);
        }
    }

    private static void consume(final Iterator<?> iterator, final List<?> expected) {
        int count = 0;
        while (iterator.hasNext()) {
            check(iterator.hasNext(), "repeat hasNext");
            final Object value = iterator.next();
            event("result:" + value);
            check(count < expected.size() && java.util.Objects.equals(value, expected.get(count)), "order");
            count++;
            if (count % 3 == 0) {
                iterator.remove();
            }
        }
        check(count == expected.size(), "count");
        try {
            iterator.next();
            throw new AssertionError("exhaustion");
        } catch (final NoSuchElementException error) {
            event("end:" + error.getMessage());
        }
    }

    public static void behavior() {
        checks = 0;
        digest = 1;
        events = 0;
        for (int seed = 0; seed < 100; seed++) {
            final List<Object> root = graph(new Random(seed), 7);
            final List<Object> expected = new ArrayList<>();
            flatten(root, expected);
            for (final boolean subclass : new boolean[] {false, true}) {
                final int[] id = {0};
                final Transformer<Object, Object> transform = v -> {
                    event("transform:" + (v instanceof List ? "list" : v));
                    return v instanceof List ? new Probe("g" + id[0]++, (List<?>) v) : v;
                };
                final ObjectGraphIterator<Object> it = subclass ? new GraphHooks(root, transform)
                        : new ObjectGraphIterator<>(root, transform);
                consume(it, expected);
            }
            final ChainHooks chain = new ChainHooks();
            final List<Object> chainExpected = new ArrayList<>();
            final Random r = new Random(seed);
            for (int i = 0; i < 50; i++) {
                final List<Object> values = graph(r, 0);
                chainExpected.addAll(values);
                chain.addIterator(new Probe("c" + i, values));
                check(chain.size() == i + 1, "pending size");
            }
            consume(chain, chainExpected);
            check(chain.size() == 0 && chain.isLocked(), "exhausted chain state");
            try {
                chain.addIterator(null);
                throw new AssertionError("locked admission");
            } catch (final UnsupportedOperationException error) {
                event(error.getMessage());
            }
        }
        admissions();
        failuresAndReentry();
        integration();
        check(events == 62721 && digest == 0x4a70622f3c2c22faL, "frozen callback trace");
        System.out.println("TRAVERSAL_PASS checks=" + checks + " events=" + events
                + " digest=" + Long.toHexString(digest));
    }

    private static void admissions() {
        for (int n = 0; n < 80; n++) {
            final IteratorChain<Object> chain = new IteratorChain<>();
            for (int i = 0; i < n; i++) {
                chain.addIterator(Collections.emptyIterator());
            }
            chain.addIterator(chain);
            check(chain.size() == n * 2, "finite self-admission");
            try {
                chain.addIterator(UnmodifiableIterator.unmodifiableIterator(chain));
                check(n == 0, "self-unmodifiable missing refusal");
            } catch (final java.util.ConcurrentModificationException expected) {
                check(chain.size() == n * 2 + 1, "self-unmodifiable partial effect");
            }
            check(!chain.hasNext(), "empty children");
            check(chain.size() == 0, "released children");
        }
        final ChainHooks partial = new ChainHooks();
        partial.addIterator(new Probe("old", Arrays.asList(1)));
        partial.addIterator(new Probe("pending", Arrays.asList(2)));
        partial.prime(); // Protected entry does not itself lock admission.
        partial.addIterator(new Probe("new", Arrays.asList(3)));
        final IteratorChain<Object> flattened = new IteratorChain<>(partial);
        consume(flattened, Arrays.asList(2, 3));
        consume(partial, Arrays.asList(1)); // The pending iterator objects were shared.
        check(!partial.hasNext(), "nested pending ownership");
    }

    private static void failuresAndReentry() {
        for (int mode = 0; mode < 4; mode++) {
            final Probe leaf = new Probe("leaf", Arrays.asList(1, 2, 3, 4));
            final Probe mid = new Probe("mid", Arrays.asList(leaf, 5));
            final Probe root = new Probe("root", Arrays.asList(mid, 6));
            final ObjectGraphIterator<Object> graph = new ObjectGraphIterator<>(root);
            final RuntimeException failure = new IllegalArgumentException("injected");
            if (mode == 0) {
                leaf.failure = failure;
            } else if (mode == 1) {
                leaf.hook = () -> event("nested-next:" + graph.next());
            } else if (mode == 2) {
                mid.hook = () -> event("nested-has:" + graph.hasNext());
            } else {
                root.failure = failure;
            }
            for (int step = 0; step < 24; step++) {
                try {
                    if (graph.hasNext()) {
                        event("outer:" + graph.next());
                    } else {
                        break;
                    }
                } catch (final RuntimeException caught) {
                    check(caught == failure, "graph exception identity");
                    event("failure:graph");
                }
            }
        }
        final IteratorChain<Object> chain = new IteratorChain<>();
        final Probe first = new Probe("retry", Arrays.asList(7, 8));
        final RuntimeException failure = new IllegalStateException("retry");
        first.failure = failure;
        chain.addIterator(first);
        try {
            chain.next();
            throw new AssertionError("chain failure");
        } catch (final IllegalStateException caught) {
            check(caught == failure, "chain failure identity");
        }
        consume(chain, Arrays.asList(7, 8));
    }

    private static void integration() {
        final org.apache.commons.collections4.map.StaticBucketMap<Integer, Integer> map =
                new org.apache.commons.collections4.map.StaticBucketMap<>();
        for (int i = 0; i < 8192; i++) {
            map.put(i, i);
        }
        final Object[] values = map.keySet().toArray();
        final IteratorChain<Object> pending = new IteratorChain<>();
        for (int offset = 0; offset < values.length; offset += 73) {
            pending.addIterator(new ArrayIterator<Object>(values, offset, Math.min(values.length, offset + 73)));
        }
        final ObjectGraphIterator<Object> graph = new ObjectGraphIterator<>(
                Arrays.asList(Collections.emptyIterator(), pending, Collections.emptyIterator()).iterator());
        final ListIteratorWrapper<Object> replay = new ListIteratorWrapper<>(graph);
        final org.apache.commons.collections4.list.TreeList<Object> tree = new org.apache.commons.collections4.list.TreeList<>();
        for (int i = 0; i < values.length; i++) {
            check(replay.hasNext() && replay.next() == values[i], "integrated traversal order");
            tree.add(values[i]);
        }
        check(!replay.hasNext() && pending.size() == 0, "integrated exhaustion");
        tree.subList(1024, 7168).clear();
        check(tree.size() == 2048, "integrated range removal");
        for (int i = values.length - 1; i >= 0; i--) {
            check(replay.previous() == values[i], "cached replay after tree mutation");
        }
        final ArrayListIterator<Object> array = new ArrayListIterator<>(values);
        for (int i = 0; i < values.length; i++) {
            check(array.next() == replay.next(), "forward array/replay identity");
        }
        for (int i = values.length - 1; i >= 0; i--) {
            check(array.previous() == values[i], "backward reference-array path");
        }
        final List<Object> first = Arrays.asList(values[0], values[1], values[2], values[3]);
        final PermutationIterator<Object> permutations = new PermutationIterator<>(first);
        int count = 0;
        while (permutations.hasNext()) {
            tree.addAll(permutations.next());
            count++;
        }
        check(count == 24 && tree.size() == 2144, "permutation and recycled tree slots");
        event("integration:" + values.length + ":" + count + ":" + tree.size());
    }

    public static void deep() {
        Object root = 42;
        for (int i = 0; i < 50000; i++) {
            root = Collections.singletonList(root).iterator();
        }
        final ObjectGraphIterator<Object> it = new ObjectGraphIterator<>(root, null);
        check(it.hasNext() && it.next().equals(42) && !it.hasNext(), "deep traversal");
        final List<Object> wide = new ArrayList<>();
        for (int i = 0; i < 50000; i++) {
            wide.add(Collections.emptyIterator());
        }
        wide.add(99);
        final ObjectGraphIterator<Object> emptyBranches = new ObjectGraphIterator<>(wide.iterator());
        check(emptyBranches.hasNext() && emptyBranches.next().equals(99) && !emptyBranches.hasNext(),
                "wide empty branch traversal");
        System.out.println("DEEP_PASS depth=50000 emptyBranches=50000");
    }

    private static Object field(final Object instance, final String name) throws ReflectiveOperationException {
        final Field f = instance.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(instance);
    }

    public static void storage() throws ReflectiveOperationException {
        for (final Class<?> type : new Class<?>[] {IteratorChain.class, ObjectGraphIterator.class}) {
            for (final Field f : type.getDeclaredFields()) {
                check(Modifier.isStatic(f.getModifiers()) || !Collection.class.isAssignableFrom(f.getType()),
                        "owner still retains a Collection backend: " + type.getSimpleName());
            }
        }
        final IteratorChain<Object> chain = new IteratorChain<>();
        for (int i = 0; i < 10000; i++) {
            chain.addIterator(Collections.singletonList((Object) i).iterator());
        }
        int count = 0;
        while (chain.hasNext()) {
            check(chain.next().equals(count++), "chain growth order");
        }
        check(count == 10000, "chain growth count");
        for (final Object value : (Object[]) field(chain, "iteratorQueue")) {
            check(value == null, "consumed pending reference retained");
        }
        final ObjectGraphIterator<Object> graph = new ObjectGraphIterator<>(
                Arrays.asList(Arrays.asList(1, 2).iterator(), 3).iterator());
        while (graph.hasNext()) {
            graph.next();
        }
        for (final Object value : (Object[]) field(graph, "stack")) {
            check(value == null, "popped ancestor reference retained");
        }
        System.out.println("STORAGE_PASS");
    }

    public static void main(final String[] args) throws ReflectiveOperationException {
        final String mode = args.length == 0 ? "behavior" : args[0];
        printTrace = "trace".equals(mode);
        if ("deep".equals(mode)) {
            deep();
        } else if ("storage".equals(mode)) {
            storage();
        } else {
            behavior();
        }
    }
}
