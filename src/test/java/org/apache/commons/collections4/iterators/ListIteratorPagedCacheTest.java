/*
 * Copyright 2026 Hitesh Soliwal and contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.apache.commons.collections4.iterators;

import org.junit.jupiter.api.Test;

/** Native JUnit entry points for the independently executable replay regressions. */
class ListIteratorPagedCacheTest {
    @Test
    void preservesReplayRemovalReentryAndNativeDelegation() {
        ListIteratorPagedCacheContract.verify();
    }

    @Test
    void growthDoesNotCopyCompletedPayloadPages() throws ReflectiveOperationException {
        ListIteratorPagedCacheContract.storage();
    }
}
