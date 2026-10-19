/*
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.m3.collections.apache.list;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

class M3TreeListArrayStorageTest {
    @Test
    void randomizedListAndIteratorContractsWithSlotReuse() throws ReflectiveOperationException {
        for (int seed = 0; seed < 4; seed++) {
            M3TreeListArrayStorageProof.exercise(new ArrayList<>(), new TreeList<>(), 87121L + seed, 3000, false);
        }
    }

    @Test
    void bulkAppendAndFailureReuseStorageAtBoundedLiveSize() throws ReflectiveOperationException {
        M3TreeListArrayStorageProof.boundedBulkReuse(new TreeList<>(), new TreeList<>());
    }

    @Test
    void failedBalancedBuildReleasesUncommittedPayloadAndCanGrowAgain() throws ReflectiveOperationException {
        // TreeList intentionally builds the complete source before attaching its subtree.
        M3TreeListArrayStorageProof.failedBulkBuild(new TreeList<>(), new TreeList<>());
    }
}
