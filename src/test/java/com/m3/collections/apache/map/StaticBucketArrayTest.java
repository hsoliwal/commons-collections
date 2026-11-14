/*
 * Copyright 2026 Hitesh Soliwal and contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.map;

import org.junit.jupiter.api.Test;

/** Native test entry points; the standalone bodies use the actual fork sources. */
class StaticBucketArrayTest {
    @Test
    void preservesMappingViewsAndConcurrentOperations() throws Exception {
        StaticBucketArrayContract.verify();
    }

    @Test
    void storesMappingsWithoutRetainedNodes() throws Exception {
        StaticBucketArrayContract.verifyArrayStorage();
    }

    @Test
    void entryCallbacksDoNotIntroduceCrossBucketDeadlocks() throws Exception {
        StaticBucketEntryConcurrency.main(new String[0]);
    }
}
