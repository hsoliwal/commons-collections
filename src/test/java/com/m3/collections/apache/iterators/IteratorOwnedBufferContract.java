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
package com.m3.collections.apache.iterators;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

/** Independent behavior and storage checks for the two owned iterator buffers. */
public final class IteratorOwnedBufferContract {
    private static long checks;
    private static long digest;

    private IteratorOwnedBufferContract() {
    }

    private static void equal(final Object actual, final Object expected) {
        checks++;
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError("check=" + checks + " actual=" + actual + " expected=" + expected);
        }
        digest = digest * 31 + (expected instanceof Class<?> ? ((Class<?>) expected).getName().hashCode()
                : Objects.hashCode(expected));
    }

    private static void same(final Object actual, final Object expected) {
        checks++;
        if (actual != expected) {
            throw new AssertionError("identity check=" + checks);
        }
        digest = digest * 31 + 1;
    }

    private static String observation(final Supplier<?> action, final RuntimeException marker) {
        try {
            final Object result = action.get();
            return result == null ? "null" : result.getClass().getName() + ":" + result;
        } catch (final RuntimeException failure) {
            return failure == marker ? "marker" : failure.getClass().getName() + ":" + failure.getMessage();
        }
    }

    private static final class Trace {
        private final StringBuilder events = new StringBuilder();
        private final RuntimeException marker = new IllegalStateException("callback marker");
        private final int failAt;
        private int calls;

        Trace(final int failAt) {
            this.failAt = failAt;
        }

        void event(final String event) {
            events.append(event).append(';');
            if (++calls == failAt) {
                throw marker;
            }
        }
    }

    private static final class RecordingIterable implements Iterable<Object> {
        private final Trace trace;
        private final String name;
        private final Object[] values;
        private int generations;

        RecordingIterable(final Trace trace, final String name, final Object... values) {
            this.trace = trace;
            this.name = name;
            this.values = values;
        }

        @Override
        public Iterator<Object> iterator() {
            final int generation = generations++;
            trace.event(name + ".iterator:" + generation);
            return new Iterator<Object>() {
                private int index;

                @Override
                public boolean hasNext() {
                    trace.event(name + ".has:" + generation + ":" + index);
                    return index < values.length;
                }

                @Override
                public Object next() {
                    trace.event(name + ".next:" + generation + ":" + index);
                    if (index == values.length) {
                        throw new NoSuchElementException("source exhausted");
                    }
                    return values[index++];
                }
            };
        }
    }

    private static void pushbackBehavior() {
        final Object[] tokens = {"alpha", null, Integer.valueOf(19), new String("identity payload"), "omega"};
        for (int seed = 0; seed < 24; seed++) {
            final Trace actualTrace = new Trace(0);
            final Trace expectedTrace = new Trace(0);
            final Iterator<Object> expectedSource = new RecordingIterable(expectedTrace, "source", tokens).iterator();
            final PushbackIterator<Object> actual = new PushbackIterator<>(
                    new RecordingIterable(actualTrace, "source", tokens).iterator());
            final Deque<Object> pending = new ArrayDeque<>();
            final Random random = new Random(seed);
            for (int step = 0; step < 2048; step++) {
                switch (random.nextInt(6)) {
                case 0:
                case 1:
                    final Object item = tokens[random.nextInt(tokens.length)];
                    equal(observation(() -> { actual.pushback(item); return null; }, null),
                            observation(() -> { pending.push(item); return null; }, null));
                    break;
                case 2:
                    equal(actual.hasNext(), !pending.isEmpty() || expectedSource.hasNext());
                    break;
                case 3:
                    if (!pending.isEmpty()) {
                        same(actual.next(), pending.pop());
                    } else {
                        equal(observation(actual::next, null), observation(expectedSource::next, null));
                    }
                    break;
                case 4:
                    equal(observation(() -> { actual.remove(); return null; }, null),
                            "java.lang.UnsupportedOperationException:null");
                    break;
                default:
                    same(PushbackIterator.pushbackIterator(actual), actual);
                    break;
                }
                equal(actualTrace.events.toString(), expectedTrace.events.toString());
            }
            while (!pending.isEmpty()) {
                same(actual.next(), pending.pop());
            }
        }
        equal(observation(() -> PushbackIterator.pushbackIterator(null), null),
                "java.lang.NullPointerException:iterator");
        final PushbackIterator<Object> noSource = new PushbackIterator<>(null);
        final Object token = new Object();
        noSource.pushback(token);
        equal(noSource.hasNext(), true);
        same(noSource.next(), token);
        equal(observation(() -> { noSource.pushback(null); return null; }, null),
                "java.lang.NullPointerException:null");
        final class Derived extends PushbackIterator<Object> {
            Derived() {
                super(Collections.emptyIterator());
            }
        }
        final Derived derived = new Derived();
        same(PushbackIterator.pushbackIterator(derived), derived);
        for (int failAt = 1; failAt <= 8; failAt++) {
            final Trace trace = new Trace(failAt);
            PushbackIterator<Object> iterator = null;
            try {
                iterator = new PushbackIterator<>(new RecordingIterable(trace, "fail", tokens).iterator());
            } catch (final RuntimeException failure) {
                same(failure, trace.marker);
            }
            if (iterator != null) {
                final PushbackIterator<Object> current = iterator;
                int failures = 0;
                for (int i = 0; i < 10; i++) {
                    failures += "marker".equals(observation(current::hasNext, trace.marker)) ? 1 : 0;
                    failures += "marker".equals(observation(current::next, trace.marker)) ? 1 : 0;
                }
                equal(failures, 1);
                current.pushback(token);
                same(current.next(), token);
            }
        }
        final List<PushbackIterator<Object>> reentrant = new ArrayList<>();
        final Iterator<Object> source = new Iterator<Object>() {
            @Override
            public boolean hasNext() {
                reentrant.get(0).pushback(token);
                return true;
            }

            @Override
            public Object next() {
                reentrant.get(0).pushback(token);
                return tokens[0];
            }
        };
        final PushbackIterator<Object> outer = new PushbackIterator<>(source);
        reentrant.add(outer);
        equal(outer.hasNext(), true);
        same(outer.next(), token);
        same(outer.next(), tokens[0]);
        same(outer.next(), token);
        final PushbackIterator<Object> remaining = new PushbackIterator<>(Arrays.asList(tokens).iterator());
        remaining.pushback(token);
        final RuntimeException stop = new IllegalArgumentException("consumer marker");
        equal(observation(() -> { remaining.forEachRemaining(value -> { throw stop; }); return null; }, stop), "marker");
        same(remaining.next(), tokens[0]);
        final List<Object> tail = new ArrayList<>();
        remaining.forEachRemaining(tail::add);
        equal(tail, Arrays.asList(tokens).subList(1, tokens.length));
    }

    /** Preserves the incumbent list-based state machine as a callback oracle. */
    private static final class CartesianReference implements Iterator<List<Object>> {
        private final List<Iterable<Object>> iterables = new ArrayList<>();
        private final List<Iterator<Object>> iterators = new ArrayList<>();
        private List<Object> previousTuple;

        CartesianReference(final Iterable<Object>[] inputs) {
            Objects.requireNonNull(inputs, "iterables");
            for (final Iterable<Object> iterable : inputs) {
                Objects.requireNonNull(iterable, "iterable");
                iterables.add(iterable);
                final Iterator<Object> iterator = iterable.iterator();
                if (!iterator.hasNext()) {
                    iterators.clear();
                    break;
                }
                iterators.add(iterator);
            }
        }

        @Override
        public boolean hasNext() {
            return iterators.stream().anyMatch(Iterator::hasNext);
        }

        @Override
        public List<Object> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            if (previousTuple == null) {
                previousTuple = new ArrayList<>(iterables.size());
                for (final Iterator<Object> iterator : iterators) {
                    previousTuple.add(iterator.next());
                }
                return new ArrayList<>(previousTuple);
            }
            for (int i = iterators.size() - 1; i >= 0; i--) {
                Iterator<Object> iterator = iterators.get(i);
                if (iterator.hasNext()) {
                    previousTuple.set(i, iterator.next());
                    return new ArrayList<>(previousTuple);
                }
                iterator = iterables.get(i).iterator();
                iterators.set(i, iterator);
                previousTuple.set(i, iterator.next());
            }
            throw new IllegalStateException("reached unreachable code");
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("remove");
        }
    }

    @SuppressWarnings("unchecked")
    private static Iterable<Object>[] inputs(final Trace trace, final int emptyAt) {
        final Iterable<Object>[] inputs = (Iterable<Object>[]) new Iterable<?>[3];
        inputs[0] = new RecordingIterable(trace, "A", emptyAt == 0 ? new Object[0] : new Object[] {"a", "b"});
        inputs[1] = new RecordingIterable(trace, "B", emptyAt == 1 ? new Object[0] : new Object[] {null, "c", "d"});
        inputs[2] = new RecordingIterable(trace, "C", emptyAt == 2 ? new Object[0] : new Object[] {"x", "y"});
        return inputs;
    }

    private static String cartesianScenario(final boolean reference, final int failAt, final int emptyAt) {
        final Trace trace = new Trace(failAt);
        final StringBuilder results = new StringBuilder();
        final Iterable<Object>[] sources = inputs(trace, emptyAt);
        final Iterator<List<Object>> iterator;
        try {
            iterator = reference ? new CartesianReference(sources) : new CartesianProductIterator<>(sources);
        } catch (final RuntimeException failure) {
            return observation(() -> { throw failure; }, trace.marker) + "|" + trace.events;
        }
        // The caller's varargs container is not retained, including for later restarts.
        Arrays.fill(sources, Collections.emptyList());
        final List<List<Object>> retained = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            results.append(observation(iterator::hasNext, trace.marker)).append(';');
            results.append(observation(() -> {
                final List<Object> tuple = iterator.next();
                equal(tuple.getClass(), ArrayList.class);
                final List<Object> snapshot = new ArrayList<>(tuple);
                for (final List<Object> previous : retained) {
                    equal(tuple == previous, false);
                }
                retained.add(tuple);
                // A detached, writable result must not change the iterator's cached tuple.
                tuple.clear();
                tuple.add("caller mutation");
                return snapshot;
            }, trace.marker)).append(';');
            if (i % 7 == 0) {
                results.append(observation(() -> { iterator.remove(); return null; }, trace.marker)).append(';');
            }
        }
        return results + "|" + trace.events;
    }

    @SuppressWarnings("unchecked")
    private static String reentrantCartesian(final boolean reference) {
        final StringBuilder events = new StringBuilder();
        final List<Iterator<List<Object>>> owner = new ArrayList<>();
        final Iterable<Object> input = () -> new Iterator<Object>() {
            private int index;
            private boolean entered;

            @Override
            public boolean hasNext() {
                events.append("has:").append(index).append(';');
                if (!entered && !owner.isEmpty()) {
                    entered = true;
                    events.append("nested:").append(owner.get(0).next()).append(';');
                }
                return index < 3;
            }

            @Override
            public Object next() {
                events.append("next:").append(index).append(';');
                if (index == 3) {
                    throw new NoSuchElementException();
                }
                return Integer.valueOf(index++);
            }
        };
        final Iterable<Object>[] inputs = (Iterable<Object>[]) new Iterable<?>[] {
            input, Arrays.asList("left", "right")
        };
        final Iterator<List<Object>> iterator = reference ? new CartesianReference(inputs)
                : new CartesianProductIterator<>(inputs);
        owner.add(iterator);
        for (int i = 0; i < 8; i++) {
            events.append(observation(iterator::next, null)).append(';');
        }
        return events.toString();
    }

    @SuppressWarnings("unchecked")
    private static void cartesianBehavior() {
        for (int emptyAt = -1; emptyAt < 3; emptyAt++) {
            for (int failAt = 0; failAt <= 90; failAt++) {
                equal(cartesianScenario(false, failAt, emptyAt), cartesianScenario(true, failAt, emptyAt));
            }
        }
        equal(reentrantCartesian(false), reentrantCartesian(true));
        equal(observation(() -> new CartesianProductIterator<Object>((Iterable<Object>[]) null), null),
                "java.lang.NullPointerException:iterables");
        equal(observation(() -> new CartesianProductIterator<Object>((Iterable<Object>) null), null),
                "java.lang.NullPointerException:iterable");
        final CartesianProductIterator<Object> empty = new CartesianProductIterator<>();
        equal(empty.hasNext(), false);
        equal(observation(empty::next, null), "java.util.NoSuchElementException:null");
        final CartesianProductIterator<Object> shortCircuit = new CartesianProductIterator<>(Collections.emptyList(), null);
        equal(shortCircuit.hasNext(), false);
        final Object first = new Object();
        final Object second = new Object();
        final CartesianProductIterator<Object> identities = new CartesianProductIterator<>(
                Arrays.asList(first, second), Arrays.asList(null, first));
        final List<List<Object>> tuples = new ArrayList<>();
        identities.forEachRemaining(tuples::add);
        equal(tuples.size(), 4);
        same(tuples.get(0).get(0), first);
        same(tuples.get(1).get(1), first);
        same(tuples.get(2).get(0), second);
        same(tuples.get(3).get(1), first);
        final List<String> callbackOrder = new ArrayList<>();
        final Iterable<Object>[] changing = (Iterable<Object>[]) new Iterable<?>[2];
        changing[0] = () -> {
            callbackOrder.add("first");
            changing[1] = () -> {
                callbackOrder.add("replacement");
                return Collections.singleton(first).iterator();
            };
            return Collections.singleton(second).iterator();
        };
        changing[1] = () -> { throw new AssertionError("constructor copied the input too early"); };
        final CartesianProductIterator<Object> changed = new CartesianProductIterator<>(changing);
        equal(callbackOrder, Arrays.asList("first", "replacement"));
        same(changed.next().get(1), first);
        final class EmptyDerived extends CartesianProductIterator<Object> {
            @Override
            public boolean hasNext() {
                return true;
            }
        }
        final EmptyDerived derived = new EmptyDerived();
        equal(derived.next(), Collections.emptyList());
        equal(observation(derived::next, null), "java.lang.IllegalStateException:reached unreachable code");
        final CartesianProductIterator<Object> consumer = new CartesianProductIterator<>(
                Arrays.asList(first, second), Arrays.asList(first, second));
        final RuntimeException stop = new IllegalStateException("consumer marker");
        equal(observation(() -> { consumer.forEachRemaining(value -> { throw stop; }); return null; }, stop), "marker");
        same(consumer.next().get(1), second);
    }

    /** Runs the callback, identity, exception and detached-result checks. */
    public static void verify() {
        checks = 0;
        digest = 0;
        pushbackBehavior();
        cartesianBehavior();
        System.out.println("OWNED_BUFFER_BEHAVIOR_PASS checks=" + checks + " digest=" + Long.toHexString(digest));
    }

    private static Object field(final Object owner, final String name) throws ReflectiveOperationException {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    /** Runs the storage regression independently of the behavior oracle. */
    public static void verifyStorage() throws ReflectiveOperationException {
        checks = 0;
        digest = 0;
        for (final Field field : PushbackIterator.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                equal(Collection.class.isAssignableFrom(field.getType()), false);
            }
        }
        final PushbackIterator<Object> first = new PushbackIterator<>(Collections.emptyIterator());
        final PushbackIterator<Object> second = new PushbackIterator<>(Collections.emptyIterator());
        final Object[] empty = (Object[]) field(first, "items");
        equal(empty.length, 0);
        same(field(second, "items"), empty);
        equal(observation(() -> { first.pushback(null); return null; }, null), "java.lang.NullPointerException:null");
        same(field(first, "items"), empty);
        final Object token = new Object();
        for (int i = 0; i < 8193; i++) {
            first.pushback(token);
        }
        final Object[] allocated = (Object[]) field(first, "items");
        for (int i = 0; i < 8193; i++) {
            same(first.next(), token);
        }
        for (final Object value : allocated) {
            same(value, null);
        }
        first.pushback(token);
        same(field(first, "items"), allocated);
        same(first.next(), token);
        final CartesianProductIterator<Object> cartesian = new CartesianProductIterator<>(
                Collections.singleton(first), Collections.singleton(second));
        equal(field(cartesian, "iterables").getClass().isArray(), true);
        equal(field(cartesian, "iterators").getClass().isArray(), true);
        final CartesianProductIterator<Object> stopped = new CartesianProductIterator<>(
                Collections.singleton(first), Collections.emptyList(), Collections.singleton(second));
        for (final Object value : (Object[]) field(stopped, "iterators")) {
            same(value, null);
        }
        System.out.println("OWNED_BUFFER_STORAGE_PASS checks=" + checks);
    }

    /** Command-line entry point for the independently executable checks. */
    public static void main(final String[] args) throws ReflectiveOperationException {
        if (args.length != 0 && "storage".equals(args[0])) {
            verifyStorage();
        } else {
            verify();
        }
    }
}
