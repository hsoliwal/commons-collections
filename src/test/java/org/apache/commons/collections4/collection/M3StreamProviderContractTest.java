/*
 * Copyright 2026 Hitesh Soliwal and contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.apache.commons.collections4.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Provider contract oracle for stream adapters around predicated Commons decorators. */
final class M3StreamProviderContractTest {
    @Test
    void predicatedViewKeepsValidationAtMutationAndPreservesEncounterOccurrences() {
        List<String> owner = new ArrayList<>();
        Collection<String> source =
                PredicatedCollection.predicatedCollection(owner, value -> value != null);
        source.addAll(Arrays.asList("same", "same", "last"));
        List<String> detached = new ArrayList<>(source);

        assertThrows(IllegalArgumentException.class, () -> source.add(null));
        source.add("later");

        assertEquals(Arrays.asList("same", "same", "last"), detached);
        assertEquals(Arrays.asList("same", "same", "last", "later"), new ArrayList<>(source));
        assertEquals(4L, source.stream().count());
    }
}
