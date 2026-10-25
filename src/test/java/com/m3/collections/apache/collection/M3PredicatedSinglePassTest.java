/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Synexia contributors.
 */
package com.m3.collections.apache.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** Contract tests for single-pass predicate validation before delegate mutation. */
class M3PredicatedSinglePassTest {

    @Test
    void doesNotReopenOneShotInputAndRetainsDuplicateOccurrences() {
        final List<String> backing = new ArrayList<>();
        final Collection<String> view = PredicatedCollection.predicatedCollection(
                backing, value -> value.startsWith("ok:"));
        final AtomicInteger iteratorOpens = new AtomicInteger();
        final Collection<String> oneShot = new AbstractCollection<String>() {
            @Override
            public int size() {
                return 3;
            }

            @Override
            public Iterator<String> iterator() {
                if (iteratorOpens.incrementAndGet() != 1) {
                    throw new IllegalStateException("input was reopened");
                }
                return Arrays.asList("ok:a", "ok:a", "ok:b").iterator();
            }
        };

        assertTrue(view.addAll(oneShot));
        assertEquals(1, iteratorOpens.get());
        assertEquals(Arrays.asList("ok:a", "ok:a", "ok:b"), backing);
    }

    @Test
    void rejectsEntireInputBeforeAnyDelegateWrite() {
        final List<String> backing = new ArrayList<>();
        final Collection<String> view = PredicatedCollection.predicatedCollection(
                backing, value -> value.startsWith("ok:"));

        assertThrows(IllegalArgumentException.class,
                () -> view.addAll(Arrays.asList("ok:allowed", "bad:reject")));
        assertTrue(backing.isEmpty());
    }

    @Test
    void supportsAliasedInputUsingStableValidatedSnapshot() {
        final List<String> backing = new ArrayList<>(Arrays.asList("ok:a", "ok:b"));
        final Collection<String> view = PredicatedCollection.predicatedCollection(
                backing, value -> value.startsWith("ok:"));

        assertTrue(view.addAll(view));
        assertEquals(Arrays.asList("ok:a", "ok:b", "ok:a", "ok:b"), backing);
    }
}
