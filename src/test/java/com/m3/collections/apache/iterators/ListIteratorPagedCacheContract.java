/*
 * Copyright 2026 Hitesh Soliwal and contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.iterators;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;

/** Standalone regression body, also invoked by the fork's native JUnit suite. */
public final class ListIteratorPagedCacheContract {
    private static long checks;
    private static long digest;

    private ListIteratorPagedCacheContract() {
    }

    private static void check(final boolean condition, final String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(final Object actual, final Object expected) {
        check(Objects.equals(actual, expected), actual + " != " + expected);
        digest = digest * 31 + Objects.hashCode(expected);
    }

    private static Integer value(final int index) {
        return index % 13 == 0 ? null : Integer.valueOf(index & 127);
    }

    private static final class Source implements Iterator<Integer> {
        private final int limit;
        private int cursor;
        private int hasCalls;
        private int nextCalls;
        private int removeCalls;
        private RuntimeException nextFailure;
        private RuntimeException removeFailure;
        private Runnable onRemove;
        private Runnable onNext;

        Source(final int limit) {
            this.limit = limit;
        }

        @Override
        public boolean hasNext() {
            hasCalls++;
            return cursor < limit;
        }

        @Override
        public Integer next() {
            nextCalls++;
            if (nextFailure != null) {
                final RuntimeException failure = nextFailure;
                nextFailure = null;
                throw failure;
            }
            if (onNext != null) {
                final Runnable callback = onNext;
                onNext = null;
                callback.run();
            }
            if (cursor == limit) {
                throw new NoSuchElementException("source exhausted");
            }
            return value(cursor++);
        }

        @Override
        public void remove() {
            removeCalls++;
            if (onRemove != null) {
                final Runnable callback = onRemove;
                onRemove = null;
                callback.run();
            }
            if (removeFailure != null) {
                final RuntimeException failure = removeFailure;
                removeFailure = null;
                throw failure;
            }
        }
    }

    public static void boundaries() {
        for (final int size : new int[] {0, 1, 9, 10, 11, 15, 16, 63, 64, 65, 127, 128, 129,
                1023, 1024, 1025, 65537, 131073}) {
            final Source source = new Source(size);
            final ListIteratorWrapper<Integer> iterator = new ListIteratorWrapper<>(source);
            equal(iterator.nextIndex(), 0);
            equal(iterator.previousIndex(), -1);
            for (int i = 0; i < size; i++) {
                equal(iterator.hasNext(), true);
                equal(iterator.next(), value(i));
                equal(iterator.nextIndex(), i + 1);
            }
            equal(iterator.hasNext(), false);
            equal(source.nextCalls, size);
            final int hasCalls = source.hasCalls;
            for (int i = size - 1; i >= 0; i--) {
                equal(iterator.hasPrevious(), true);
                equal(iterator.previous(), value(i));
            }
            equal(iterator.hasPrevious(), false);
            iterator.reset();
            for (int i = 0; i < size; i++) {
                equal(iterator.hasNext(), true);
                equal(iterator.next(), value(i));
            }
            equal(source.nextCalls, size);
            equal(source.hasCalls, hasCalls);
            iterator.reset();
            equal(iterator.nextIndex(), 0);
        }
    }

    private static void failureIdentityAndRemoval() {
        final Source source = new Source(200);
        final ListIteratorWrapper<Integer> iterator = new ListIteratorWrapper<>(source);
        final RuntimeException sentinel = new IllegalArgumentException("child sentinel");
        for (int i = 0; i < 130; i++) {
            source.nextFailure = sentinel;
            try {
                iterator.next();
                throw new AssertionError("child next failure swallowed");
            } catch (final RuntimeException failure) {
                check(failure == sentinel, "next exception identity");
            }
            equal(iterator.nextIndex(), 0);
            equal(iterator.next(), value(i));
            source.removeFailure = sentinel;
            try {
                iterator.remove();
                throw new AssertionError("child remove failure swallowed");
            } catch (final RuntimeException failure) {
                check(failure == sentinel, "remove exception identity");
            }
            equal(iterator.nextIndex(), 1);
            iterator.remove();
            equal(iterator.nextIndex(), 0);
            equal(iterator.hasPrevious(), false);
        }
        equal(source.removeCalls, 260);
        equal(source.cursor, 130);
    }

    private static void reentrantRemoval() {
        for (final int initial : new int[] {1, 9, 10, 63, 64, 65, 127, 128, 129, 1023, 1024, 1025}) {
            for (final int nested : new int[] {1, 2, 64, 130, 1024, 2050}) {
                final Source source = new Source(initial + nested + 1);
                final ListIteratorWrapper<Integer> iterator = new ListIteratorWrapper<>(source);
                final List<Integer> expected = new ArrayList<>();
                for (int i = 0; i < initial; i++) {
                    expected.add(iterator.next());
                }
                source.onRemove = () -> {
                    for (int i = 0; i < nested; i++) {
                        expected.add(iterator.next());
                    }
                };
                iterator.remove();
                expected.remove(initial - 1);
                equal(iterator.nextIndex(), initial - 1);
                iterator.reset();
                for (final Integer element : expected) {
                    equal(iterator.next(), element);
                }
                equal(iterator.next(), value(initial + nested));
                equal(iterator.hasNext(), false);
            }
        }
    }

    private static void reentrantTrace() {
        for (final int initial : new int[] {0, 1, 8, 63, 64, 65, 127, 128, 129}) {
            for (int action = 0; action < 7; action++) {
                for (final boolean duringRemove : new boolean[] {false, true}) {
                    final Source source = new Source(initial + 136);
                    final ListIteratorWrapper<Integer> iterator = new ListIteratorWrapper<>(source);
                    for (int i = 0; i < initial; i++) {
                        iterator.next();
                    }
                    final int selected = action;
                    final Runnable callback = () -> {
                        switch (selected) {
                        case 0: iterator.reset(); break;
                        case 1: iterator.previous(); break;
                        case 2: iterator.remove(); break;
                        case 3: iterator.next(); break;
                        case 4:
                            for (int i = 0; i < 130; i++) {
                                iterator.next();
                            }
                            break;
                        case 5:
                            for (int i = 0; i < 65; i++) {
                                iterator.next();
                            }
                            throw new IllegalStateException("reentrant child failure");
                        default: iterator.reset(); iterator.next(); break;
                        }
                    };
                    if (duringRemove) {
                        source.onRemove = callback;
                    } else {
                        source.onNext = callback;
                    }
                    final String first = operation(iterator, duringRemove ? 2 : 0, null);
                    digest = digest * 31 + first.hashCode();
                    iterator.reset();
                    for (int i = 0; i < initial + 139; i++) {
                        final String observation = operation(iterator, 0, null);
                        digest = digest * 31 + observation.hashCode();
                        digest = digest * 31 + iterator.nextIndex();
                        checks++;
                    }
                    digest = digest * 31 + source.nextCalls;
                    digest = digest * 31 + source.removeCalls;
                }
            }
        }
    }

    private static String operation(final ListIteratorWrapper<Integer> iterator,
            final int operation, final Integer value) {
        try {
            switch (operation) {
            case 0: return "next:" + iterator.next();
            case 1: return "previous:" + iterator.previous();
            case 2: iterator.remove(); return "remove";
            case 3: iterator.reset(); return "reset";
            case 4: iterator.add(value); return "add";
            case 5: iterator.set(value); return "set";
            case 6: return "hasNext:" + iterator.hasNext();
            case 7: return "hasPrevious:" + iterator.hasPrevious();
            default: return "index:" + iterator.nextIndex() + ":" + iterator.previousIndex();
            }
        } catch (final RuntimeException failure) {
            return failure.getClass().getName() + ":" + failure.getMessage();
        }
    }

    private static void trace() {
        for (final boolean nativeList : new boolean[] {false, true}) {
            for (int seed = 0; seed < 12; seed++) {
                final List<Integer> backing = new ArrayList<>();
                for (int i = 0; i < 513; i++) {
                    backing.add(value(i));
                }
                final Iterator<Integer> child = nativeList ? backing.listIterator(seed) : backing.iterator();
                final ListIteratorWrapper<Integer> iterator = new ListIteratorWrapper<>(child);
                final Random random = new Random(0x50414745444cL + seed);
                for (int step = 0; step < 10000; step++) {
                    final String observation = operation(iterator, random.nextInt(9), value(step));
                    digest = digest * 31 + observation.hashCode();
                    digest = digest * 31 + iterator.nextIndex();
                    digest = digest * 31 + iterator.previousIndex();
                    checks++;
                }
                digest = digest * 31 + backing.hashCode();
            }
        }
    }

    private static Object[][] pages(final ListIteratorWrapper<?> iterator) throws ReflectiveOperationException {
        for (final Field field : ListIteratorWrapper.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == Object[][].class) {
                field.setAccessible(true);
                return (Object[][]) field.get(iterator);
            }
        }
        throw new AssertionError("Replay payload still uses a flat grow-and-copy collection, not indexed pages");
    }

    public static void storage() throws ReflectiveOperationException {
        final Source source = new Source(100001);
        final ListIteratorWrapper<Integer> iterator = new ListIteratorWrapper<>(source);
        for (int i = 0; i < 1024; i++) {
            iterator.next();
        }
        final Object[] first = pages(iterator)[0];
        final Object[] before = first.clone();
        for (int i = 1024; i < 100001; i++) {
            iterator.next();
        }
        check(first == pages(iterator)[0], "Appending replaced a populated payload page");
        check(Arrays.equals(first, before), "Appending copied or changed existing payloads");
        iterator.remove();
        final Object[][] all = pages(iterator);
        final Object[] last = all[100000 >>> 10];
        check(last[100000 & 1023] == null, "Removed payload remains referenced");
        for (final Object[] page : all) {
            check(page == null || page.length <= 1024, "Unbounded payload page");
        }
        final ListIteratorWrapper<Integer> nativeIterator = new ListIteratorWrapper<>(
                Arrays.asList(1, 2, 3).listIterator());
        nativeIterator.next();
        nativeIterator.previous();
        nativeIterator.reset();
        check(pages(nativeIterator).length == 0, "Native delegation allocated replay pages");
        System.out.println("PAGED_STORAGE_PASS cached=100000 max_page=1024");
    }

    public static void verify() {
        checks = 0;
        digest = 0;
        boundaries();
        failureIdentityAndRemoval();
        reentrantRemoval();
        reentrantTrace();
        trace();
        System.out.println("REPLAY_CONTRACT_PASS checks=" + checks + " digest=" + Long.toHexString(digest));
    }

    public static void main(final String[] args) throws ReflectiveOperationException {
        if (args.length != 0 && "storage".equals(args[0])) {
            storage();
        } else {
            verify();
        }
    }
}
