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
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** Independent tests covering shallow and deep nested-chain contract. */
public final class IteratorChainOwnedTraversalContract {
    private static long checks;
    private static long digest = 0x123456789abcdefL;

    private IteratorChainOwnedTraversalContract() { }

    private static void require(final boolean good, final String label) {
        checks++;
        if (!good) throw new AssertionError(label + " check=" + checks);
    }

    private static void record(final Object value) {
        digest = digest * 131 + (value == null ? 0 : value.hashCode());
    }

    private static void simple() {
        final List<Integer> a = new ArrayList<>(Arrays.asList(1, 2));
        final List<Integer> b = new ArrayList<>(Arrays.asList(3));
        final List<Integer> c = new ArrayList<>(Arrays.asList(4, 5));
        final IteratorChain<Integer> chain = new IteratorChain<>();
        chain.addIterator(a.iterator());
        chain.addIterator(b.iterator());
        chain.addIterator(c.iterator());
        for (int n = 1; n <= 5; n++) {
            require(chain.hasNext() && chain.hasNext(), "flat hasNext repeated");
            final int observed = chain.next();
            require(observed == n, "flat order");
            record(observed);
            if (n == 2 || n == 4) {
                chain.remove();
                try {
                    chain.remove();
                    throw new AssertionError("double removal permitted");
                } catch (final IllegalStateException expected) {
                    require(expected.getMessage().contains("without next"), "double removal message");
                }
            }
        }
        require(!chain.hasNext() && !chain.hasNext(), "flat exhausted");
        require(a.equals(Arrays.asList(1)) && b.equals(Arrays.asList(3)) && c.equals(Arrays.asList(5)),
                "flat removal side effect");
    }

    private static IteratorChain<Integer> nested(final int depth, final List<Integer> values) {
        IteratorChain<Integer> parent = new IteratorChain<>(values.iterator());
        for (int i = 0; i < depth; i++) {
            parent = new IteratorChain<>(parent);
        }
        return parent;
    }

    private static void deep() {
        for (final int depth : new int[] {0, 1, 2, 3, 8, 31, 32, 64, 1024, 20000}) {
            final ArrayList<Integer> values = new ArrayList<>(Arrays.asList(31, 37, 41));
            final IteratorChain<Integer> chain = nested(depth, values);
            require(chain.hasNext() && chain.hasNext(), "deep availability " + depth);
            require(chain.next() == 31, "deep first " + depth);
            chain.remove();
            require(values.equals(Arrays.asList(37, 41)), "deep remove " + depth);
            try {
                chain.remove();
                throw new AssertionError("deep second removal");
            } catch (final IllegalStateException expected) {
                require(expected.getMessage().contains("without next"), "deep second removal error");
            }
            require(chain.hasNext() && chain.next() == 37, "deep second element " + depth);
            record(depth);
            record(values.size());
        }
    }

    private static final class RetryIterator implements Iterator<Integer> {
        private boolean removed;
        private int reads;
        private RuntimeException exception = new IllegalStateException("retry failure");
        @Override public boolean hasNext() { return reads == 0; }
        @Override public Integer next() {
            if (reads++ != 0) throw new NoSuchElementException();
            return 19;
        }
        @Override public void remove() {
            if (exception != null) {
                final RuntimeException fail = exception;
                exception = null;
                throw fail;
            }
            removed = true;
        }
    }

    private static void retry() {
        final RetryIterator leaf = new RetryIterator();
        final IteratorChain<Integer> chain = new IteratorChain<>(new IteratorChain<>(leaf));
        require(chain.hasNext() && chain.next() == 19, "retry next");
        try {
            chain.remove();
            throw new AssertionError("missing injected remove failure");
        } catch (final IllegalStateException expected) {
            require("retry failure".equals(expected.getMessage()), "retry exact failure");
        }
        chain.remove();
        require(leaf.removed, "retry remove lost callback");
        record(leaf.removed);
    }

    /** A leaf callback may reenter its parent and advance the same chains. */
    private static void reentrantRemoval() {
        for (final int depth : new int[] {1, 2, 3, 9, 33}) {
            final int[] observed = {-1};
            final IteratorChain<Integer>[] holder = new IteratorChain[1];
            final Iterator<Integer> leaf = new Iterator<Integer>() {
                private int index;
                @Override public boolean hasNext() { return index < 2; }
                @Override public Integer next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    return ++index;
                }
                @Override public void remove() {
                    observed[0] = holder[0].next();
                }
            };
            IteratorChain<Integer> parent = new IteratorChain<>(leaf);
            for (int n = 0; n < depth; n++) parent = new IteratorChain<>(parent);
            holder[0] = parent;
            require(parent.next() == 1, "reentrant first");
            parent.remove();
            require(observed[0] == 2, "reentrant second");
            try {
                parent.remove();
                throw new AssertionError("reentrant remove state retained");
            } catch (final IllegalStateException expected) {
                require(expected.getMessage().contains("without next"), "reentrant removal error");
            }
            require(!parent.hasNext(), "reentrant exhausted");
            record(depth * 101 + observed[0]);
        }
    }

    private static void cycles() {
        final IteratorChain<Integer> self = new IteratorChain<>();
        self.addIterator(self);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                self.hasNext();
                throw new AssertionError("direct self-cycle admitted");
            } catch (final IllegalStateException expected) {
                require("Cyclic IteratorChain".equals(expected.getMessage()), "self-cycle error");
            }
        }
        final IteratorChain<Integer> a = new IteratorChain<>();
        final IteratorChain<Integer> b = new IteratorChain<>();
        final IteratorChain<Integer> c = new IteratorChain<>();
        a.addIterator(b); b.addIterator(c); c.addIterator(a);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                a.hasNext();
                throw new AssertionError("multi-cycle admitted");
            } catch (final IllegalStateException expected) {
                require("Cyclic IteratorChain".equals(expected.getMessage()), "multi-cycle error");
            }
        }
        // The current entry graph may be modified via protected/private state
        // only after next(); test the remove path's cycle guard directly.
        try {
            final IteratorChain<Integer> root = new IteratorChain<>(Arrays.asList(5).iterator());
            root.next();
            final Field used = IteratorChain.class.getDeclaredField("lastUsedIterator");
            used.setAccessible(true);
            used.set(root, root);
            try {
                root.remove();
                throw new AssertionError("cyclic last-used permitted");
            } catch (final IllegalStateException expected) {
                require("Cyclic IteratorChain".equals(expected.getMessage()), "remove cycle");
            }
        } catch (final ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static final class HookedChain extends IteratorChain<Integer> {
        private int removeCalls;
        @Override public void remove() {
            removeCalls++;
            super.remove();
        }
    }

    private static void subclasses() {
        final HookedChain inner = new HookedChain();
        final List<Integer> values = new ArrayList<>(Arrays.asList(5, 6));
        inner.addIterator(values.iterator());
        final IteratorChain<Integer> outer = new IteratorChain<>(inner);
        require(outer.hasNext() && outer.next() == 5, "subclass next");
        outer.remove();
        require(inner.removeCalls == 1 && values.equals(Arrays.asList(6)), "subclass override dispatch");
        record(inner.removeCalls);
    }

    private static void exhaustion() {
        final IteratorChain<Integer> empty = new IteratorChain<>();
        require(!empty.hasNext(), "empty chain");
        try {
            empty.next();
            throw new AssertionError("empty next permitted");
        } catch (final NoSuchElementException expected) {
            require(true, "empty next exception");
        }
        final IteratorChain<Integer> chain = new IteratorChain<>();
        for (int index = 0; index < 2000; index++) {
            chain.addIterator(new ArrayList<Integer>().iterator());
        }
        require(!chain.hasNext(), "many empty sibling chains");
        record(chain.size());
    }

    public static void main(final String[] args) {
        checks = 0;
        digest = 0x123456789abcdefL;
        simple();
        deep();
        retry();
        reentrantRemoval();
        cycles();
        subclasses();
        exhaustion();
        System.out.println("ITERATOR_CHAIN_OWNED_PASS checks=" + checks + " digest=" + Long.toHexString(digest));
    }
}
