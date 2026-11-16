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
package org.apache.commons.collections4.iterators;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** Live/rechained, wrapped, shared-graph and failure-prefix contract. */
public final class IteratorChainLiveGraphContract {
    private static long checks;
    private static long digest = 1;

    private IteratorChainLiveGraphContract() { }

    private static void require(final boolean good, final String where) {
        checks++;
        if (!good) throw new AssertionError(where + " at " + checks);
    }

    private static void record(final int value) {
        digest = 31 * digest + value;
    }

    private static void expectCycle(final Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("expected cyclic IteratorChain");
        } catch (final IllegalStateException exception) {
            require("Cyclic IteratorChain".equals(exception.getMessage()), "cycle message");
        }
    }

    @SuppressWarnings("unchecked")
    private static Iterator<Integer> wrap(final Iterator<Integer> iterator, final boolean readonly) {
        return readonly ? UnmodifiableIterator.unmodifiableIterator(iterator) : iterator;
    }

    private static IteratorChain<Integer> cycle(final int shape, final int prefix) {
        final IteratorChain<Integer> a = new IteratorChain<>();
        if (prefix == 1) a.addIterator(Collections.<Integer>emptyIterator());
        if (prefix == 2) a.addIterator(Collections.singletonList(9).iterator());
        final IteratorChain<Integer> b = new IteratorChain<>();
        if (shape == 0) a.addIterator(a);
        else if (shape == 1) { a.addIterator(b); b.addIterator(a); }
        else if (shape == 2) a.addIterator(wrap(a, true));
        else if (shape == 3) { a.addIterator(wrap(b, true)); b.addIterator(wrap(a, true)); }
        else {
            final IteratorChain<Integer> c = new IteratorChain<>();
            a.addIterator(b); b.addIterator(c); c.addIterator(a);
        }
        return a;
    }

    private static void cycles() {
        for (int shape = 0; shape < 5; shape++) {
            for (int prefix = 0; prefix < 3; prefix++) {
                for (int first = 0; first < 2; first++) {
                    final IteratorChain<Integer> a = cycle(shape, prefix);
                    if (prefix == 2) require(a.next() == 9, "prefix value");
                    if (first == 0) expectCycle(a::hasNext);
                    else expectCycle(a::next);
                    expectCycle(a::hasNext);
                    expectCycle(a::next);
                    require(a.isLocked(), "cycle must lock");
                    try {
                        a.addIterator(Collections.<Integer>emptyIterator());
                        throw new AssertionError("added after locked");
                    } catch (final UnsupportedOperationException expected) {
                        checks++;
                    }
                    record(shape * 10 + prefix);
                }
            }
        }
    }

    private static void sharedChild() {
        for (int wrapping = 0; wrapping < 2; wrapping++) {
            final IteratorChain<Integer> shared = new IteratorChain<>(Arrays.asList(1, 2).iterator());
            final IteratorChain<Integer> root = new IteratorChain<>();
            root.addIterator(wrap(shared, wrapping != 0));
            root.addIterator(wrap(shared, wrapping != 0));
            require(root.next() == 1 && root.next() == 2, "shared child values");
            require(!root.hasNext(), "shared child exhausted");
            record(wrapping);
        }
    }

    private static void lateChildAdditions() {
        for (int wrapped = 0; wrapped < 2; wrapped++) {
            for (int prefixed = 0; prefixed < 2; prefixed++) {
                final IteratorChain<Integer> child = new IteratorChain<>();
                final IteratorChain<Integer> root = new IteratorChain<>();
                if (prefixed != 0) root.addIterator(Arrays.asList(5, 6).iterator());
                root.addIterator(wrap(child, wrapped != 0));
                child.addIterator(Collections.singletonList(7).iterator());
                if (prefixed != 0) {
                    require(root.next() == 5, "prefix before child");
                    child.addIterator(Collections.singletonList(8).iterator());
                    require(root.next() == 6, "prefix second");
                }
                require(root.next() == 7, "late child first");
                require(root.hasNext() == (prefixed != 0), "late child second availability");
                if (prefixed != 0) require(root.next() == 8, "late child second");
                require(!root.hasNext(), "late child exhaustion");
                record(wrapped + prefixed * 2);
            }
        }
    }

    private static void rechaining() {
        for (int consumed = 0; consumed < 7; consumed++) {
            for (int wrapped = 0; wrapped < 2; wrapped++) {
                for (int lookahead = 0; lookahead < 2; lookahead++) {
                    final IteratorChain<Integer> source = new IteratorChain<>();
                    source.addIterator(Arrays.asList(1,2).iterator());
                    source.addIterator(Arrays.asList(3,4).iterator());
                    source.addIterator(Arrays.asList(5,6).iterator());
                    for (int k = 1; k <= consumed; k++) require(source.next() == k, "source consumed");
                    if (lookahead != 0) require(source.hasNext() == (consumed < 6), "lookahead");
                    final IteratorChain<Integer> destination = new IteratorChain<>();
                    destination.addIterator(wrap(source, wrapped != 0));
                    destination.addIterator(Arrays.asList(7,8).iterator());
                    for (int k = consumed + 1; k <= 8; k++) {
                        require(destination.next() == k, "rechain next " + k);
                        record(k);
                    }
                    require(!destination.hasNext(), "rechain exhausted");
                }
            }
        }
    }

    private static void removalCycle() {
        try {
            final Field field = IteratorChain.class.getDeclaredField("lastUsedIterator");
            field.setAccessible(true);
            for (int depth : new int[] {1, 2, 3, 7, 32}) {
                final IteratorChain<Integer>[] chains = (IteratorChain<Integer>[]) new IteratorChain<?>[depth];
                for (int i = 0; i < depth; i++) chains[i] = new IteratorChain<>(Collections.singletonList(i).iterator());
                for (int i = 0; i < depth - 1; i++) field.set(chains[i], chains[i+1]);
                field.set(chains[depth - 1], chains[0]);
                final IteratorChain<Integer> root = chains[0];
                expectCycle(root::remove);
                record(depth);
            }
        } catch (final ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    public static void main(final String[] args) {
        checks = 0;
        digest = 1;
        cycles();
        sharedChild();
        lateChildAdditions();
        rechaining();
        removalCycle();
        System.out.println("ITERATOR_CHAIN_LIVE_GRAPH_PASS checks=" + checks + " digest=" + Long.toHexString(digest));
    }
}
