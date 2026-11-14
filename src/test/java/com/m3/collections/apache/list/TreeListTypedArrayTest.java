/*
 * Copyright 2026 Hitesh Soliwal and contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.list;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

/** Native JUnit entry point for the independently executable export contract. */
class TreeListTypedArrayTest {
    @Test
    void typedExportPreservesOwnerAndSubclassContracts() throws Exception {
        // The regression also exercises Collection.toArray(IntFunction), added in Java 11.
        Assumptions.assumeTrue(Double.parseDouble(System.getProperty("java.specification.version")) >= 11);
        TreeListTypedArrayContract.verify();
    }
}
