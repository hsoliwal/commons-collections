// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
package org.apache.commons.collections4.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** First-party one-pass captured-input regression for dynamic-array bulk admission. */
final class M3PredicatedBulkCaptureTest {
    @Test void exactlyTheValidatedOccurrencesAreWritten() {
        List<String> owner = new ArrayList<>();
        Collection<String> view = PredicatedCollection.predicatedCollection(owner, value -> value.startsWith("ok"));
        AtomicInteger opens = new AtomicInteger();
        AbstractCollection<String> input = new AbstractCollection<String>() {
            @Override public int size() { return 2; }
            @Override public Iterator<String> iterator() {
                return opens.incrementAndGet() == 1
                        ? Arrays.asList("ok-one", "ok-two").iterator()
                        : Arrays.asList("rejected-on-replay").iterator();
            }
        };
        assertTrue(view.addAll(input));
        assertEquals(Arrays.asList("ok-one", "ok-two"), owner);
        assertEquals(1, opens.get(), "input must not be reopened for a second bulk write");
    }

    @Test void invalidBatchPerformsNoWrite() {
        List<String> owner = new ArrayList<>(Arrays.asList("ok-original"));
        Collection<String> view = PredicatedCollection.predicatedCollection(owner, value -> value.startsWith("ok"));
        assertThrows(IllegalArgumentException.class,
                () -> view.addAll(Arrays.asList("ok-accepted", "invalid", "ok-later")));
        assertEquals(Arrays.asList("ok-original"), owner);
    }

    @Test void aliasInputDoesNotLoseOrDuplicateWrongOccurrences() {
        List<String> owner = new ArrayList<>(Arrays.asList("ok-a", "ok-b"));
        Collection<String> view = PredicatedCollection.predicatedCollection(owner, value -> value.startsWith("ok"));
        assertTrue(view.addAll(view));
        assertEquals(Arrays.asList("ok-a", "ok-b", "ok-a", "ok-b"), owner);
        assertFalse(view.addAll(Collections.<String>emptyList()));
    }
}
