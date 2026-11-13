/*
 * Copyright 2026 Hitesh Soliwal and contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.list;

import org.junit.jupiter.api.Test;

/** Native test entry point for bulk storage and rollback contracts. */
class TreeListBulkRollbackTest {
    @Test
    void preservesBulkStorageRollbackAndReentry() throws ReflectiveOperationException {
        TreeListBulkRollbackContract.verify();
    }
}
