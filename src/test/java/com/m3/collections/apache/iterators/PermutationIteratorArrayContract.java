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
package com.m3.collections.apache.iterators;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** Independently executable contracts for the actual permutation owner. */
public final class PermutationIteratorArrayContract {
    private long checks;
    private long outputs;
    private long digest = 1;

    private static final class Token {
        private final int id;
        private Token(final int id) {
            this.id = id;
        }
        @Override
        public boolean equals(final Object other) {
            throw new AssertionError("payload equality must not be called");
        }
        @Override
        public int hashCode() {
            throw new AssertionError("payload hashing must not be called");
        }
    }

    private void check(final boolean value, final String message) {
        checks++;
        if (!value) {
            throw new AssertionError(message);
        }
    }

    /** An insertion recurrence, not the production mobile-key algorithm. */
    private static List<int[]> orders(final int count) {
        if (count == 0) {
            return Collections.singletonList(new int[0]);
        }
        final List<int[]> result = new ArrayList<>();
        boolean leftwards = true;
        for (final int[] smaller : orders(count - 1)) {
            for (int step = 0; step < count; step++) {
                final int position = leftwards ? count - 1 - step : step;
                final int[] order = new int[count];
                System.arraycopy(smaller, 0, order, 0, position);
                order[position] = count - 1;
                System.arraycopy(smaller, position, order, position + 1, smaller.length - position);
                result.add(order);
            }
            leftwards = !leftwards;
        }
        return result;
    }

    private void match(final List<?> actual, final Object[] input, final int[] order) {
        check(actual.getClass() == ArrayList.class, "retain concrete mutable result class");
        check(actual.size() == order.length, "result length");
        for (int i = 0; i < order.length; i++) {
            final Object expected = input[order[i]];
            check(actual.get(i) == expected, "permutation order or payload identity");
            digest = 31 * digest + (expected == null ? -1 : ((Token) expected).id);
        }
        outputs++;
    }

    private void allOrders(final int maximum) {
        for (int count = 0; count <= maximum; count++) {
            final List<int[]> expected = orders(count);
            for (int pattern = 0; pattern < 2; pattern++) {
                final Object[] input = new Object[count];
                for (int i = 0; i < count; i++) {
                    input[i] = pattern == 0 ? new Token(i)
                            : i % 3 == 0 ? null : input[i - 1] == null ? new Token(i) : input[i - 1];
                }
                final List<Object> source = new ArrayList<>(Arrays.asList(input));
                final PermutationIterator<Object> iterator = new PermutationIterator<>(source);
                source.clear();
                List<Object> previous = null;
                Object[] previousContents = null;
                for (final int[] order : expected) {
                    check(iterator.hasNext() && iterator.hasNext(), "hasNext is repeatable");
                    final List<Object> actual = iterator.next();
                    if (previous != null) {
                        check(previous != actual, "caller owns a separate result list");
                        check(previous.size() == previousContents.length, "previous result size mutated");
                        for (int i = 0; i < previousContents.length; i++) {
                            check(previous.get(i) == previousContents[i], "previous result content mutated");
                        }
                    }
                    match(actual, input, order);
                    // Mutating any returned result must not corrupt the iterator's next snapshot.
                    actual.clear();
                    actual.add(null);
                    actual.add(new Token(999));
                    previous = actual;
                    previousContents = actual.toArray();
                }
                check(!iterator.hasNext(), "factorial exhaustion");
                for (int repeat = 0; repeat < 2; repeat++) {
                    try {
                        iterator.next();
                        throw new AssertionError("missing exhaustion exception");
                    } catch (final NoSuchElementException expectedFailure) {
                        check(expectedFailure.getMessage() == null, "exhaustion exception message");
                    }
                }
                try {
                    iterator.remove();
                    throw new AssertionError("remove unexpectedly supported");
                } catch (final UnsupportedOperationException expectedFailure) {
                    check("remove() is not supported".equals(expectedFailure.getMessage()), "remove message");
                }
            }
        }
    }

    private void unusualConstructor() {
        final Object[] input = {new Token(0), new Token(1), new Token(2), new Token(3)};
        final Object special = new Token(100);
        final int[] sizeCalls = {0};
        final Collection<Object> inconsistent = new AbstractCollection<Object>() {
            @Override
            public int size() {
                sizeCalls[0]++;
                return 4;
            }
            @Override
            public Iterator<Object> iterator() {
                return Arrays.asList(input).iterator();
            }
            @Override
            public Object[] toArray() {
                return new Object[] {special};
            }
        };
        final PermutationIterator<Object> iterator = new PermutationIterator<>(inconsistent);
        final List<Object> first = iterator.next();
        check(first.size() == 1 && first.get(0) == special, "initial toArray image is honored");
        first.clear();
        final List<int[]> expected = orders(4);
        for (int i = 1; i < expected.size(); i++) {
            match(iterator.next(), input, expected.get(i));
        }
        check(!iterator.hasNext() && sizeCalls[0] == 2, "constructor callback count");

        final Collection<Object> shortSource = new AbstractCollection<Object>() {
            @Override
            public int size() {
                return 3;
            }
            @Override
            public Iterator<Object> iterator() {
                return Collections.singletonList(input[0]).iterator();
            }
        };
        final PermutationIterator<Object> shortIterator = new PermutationIterator<>(shortSource);
        check(shortIterator.next().equals(Collections.singletonList(input[0])), "short-source first snapshot");
        check(!shortIterator.hasNext(), "unfilled zero ordinals preserve incumbent exhaustion");
        try {
            new PermutationIterator<Object>(null);
            throw new AssertionError("null source accepted");
        } catch (final NullPointerException expectedFailure) {
            check("collection".equals(expectedFailure.getMessage()), "null source message");
        }
    }

    private static final class ReentrantIterator extends PermutationIterator<Object> {
        private boolean armed;
        private List<Object> nested;
        private RuntimeException failure;
        private ReentrantIterator(final Collection<Object> source) {
            super(source);
        }
        @Override
        public boolean hasNext() {
            if (failure != null) {
                final RuntimeException thrown = failure;
                failure = null;
                throw thrown;
            }
            if (armed && super.hasNext()) {
                armed = false;
                nested = super.next();
            }
            return super.hasNext();
        }
    }

    private void subclassDispatch() {
        final Object[] input = {new Token(0), new Token(1), new Token(2), new Token(3)};
        final List<int[]> expected = orders(4);
        final ReentrantIterator iterator = new ReentrantIterator(Arrays.asList(input));
        int position = 0;
        while (position < expected.size()) {
            final RuntimeException failure = new IllegalStateException("user hasNext");
            iterator.failure = failure;
            try {
                iterator.next();
                throw new AssertionError("subclass failure was bypassed");
            } catch (final IllegalStateException actual) {
                check(actual == failure, "subclass exception identity");
            }
            iterator.armed = true;
            final List<Object> outer = iterator.next();
            match(iterator.nested, input, expected.get(position++));
            match(outer, input, expected.get(position++));
            iterator.nested.clear();
            outer.clear();
        }
        check(!iterator.hasNext(), "reentry exhaustion");
    }

    public static void verify(final int maximum) {
        final PermutationIteratorArrayContract contract = new PermutationIteratorArrayContract();
        contract.allOrders(maximum);
        contract.unusualConstructor();
        contract.subclassDispatch();
        System.out.println("PERMUTATION_CONTRACT_PASS outputs=" + contract.outputs
                + " checks=" + contract.checks + " digest=" + Long.toHexString(contract.digest));
    }

    public static void main(final String[] args) {
        verify(args.length == 0 ? 8 : Integer.parseInt(args[0]));
    }
}
