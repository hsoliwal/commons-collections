/*
 * Copyright 2026 Hitesh Soliwal and contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.bloomfilter;

import org.junit.jupiter.api.Test;

/** Runs the independent hot-path contracts in the fork's existing JUnit suite. */
class BitmapHotPathTest {
    @Test
    void preservesExtractionPairingAndCountingContracts() {
        BitmapHotPathContract.verify();
    }
}
