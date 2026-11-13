/*
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.queue;

import org.junit.jupiter.api.Test;

/** Contract proof shared by the native lifecycle and standalone qualification. */
class M3CircularFifoQueueArrayExportTest {
    @Test
    void arrayExportsRetainEncounterOrderAndInheritedContracts() throws Exception {
        M3CircularFifoQueueArrayExportProof.run();
    }
}
