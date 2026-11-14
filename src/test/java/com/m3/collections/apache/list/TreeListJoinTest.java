/*
 * Copyright 2026 Hitesh Soliwal and Synexia contributors.
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

import org.junit.jupiter.api.Test;

/** Fork-native entry point for the standalone join contract. */
class TreeListJoinTest {
    @Test
    void preservesRanksThreadsSlotsCallbacksAndFailureCleanup() {
        TreeListJoinContract.verify(2000);
    }
}
