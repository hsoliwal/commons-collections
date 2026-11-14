/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Copyright 2026 Hitesh Soliwal and contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.list;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sun.management.ThreadMXBean;

/** Native test entry point for bulk storage and rollback contracts. */
class TreeListBulkRollbackTest {
    @Test
    void preservesBulkStorageRollbackAndReentry() throws ReflectiveOperationException {
        TreeListBulkRollbackContract.verify();
    }

    @Test
    void reusedLargeBatchNeedsOnlyBoundedTemporaryStorage() {
        assumeTrue(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean);
        final ThreadMXBean allocation = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(allocation.isThreadAllocatedMemorySupported());
        if (!allocation.isThreadAllocatedMemoryEnabled()) {
            allocation.setThreadAllocatedMemoryEnabled(true);
        }
        final int count = 32768;
        final List<Integer> input = Collections.nCopies(count, 7);
        final TreeList<Integer> list = new TreeList<>(Collections.nCopies(count + 1, 7));
        for (int warmup = 0; warmup < 4; warmup++) {
            while (list.size() > 1) {
                list.remove(list.size() - 1);
            }
            list.addAll(input);
        }
        while (list.size() > 1) {
            list.remove(list.size() - 1);
        }
        final long thread = Thread.currentThread().threadId();
        final long before = allocation.getThreadAllocatedBytes(thread);
        list.addAll(input);
        final long allocated = allocation.getThreadAllocatedBytes(thread) - before;
        assertEquals(count + 1, list.size());
        assertEquals(Integer.valueOf(7), list.get(count));
        assertTrue(allocated <= 4096, "bulk build must not allocate a batch-sized rollback journal: " + allocated);
    }
}
