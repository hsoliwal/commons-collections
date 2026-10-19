<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Array-operation candidate

CircularFifoQueue.clear scans all retained capacity even when only a few positions are occupied. This candidate uses existing start/end/full fields to clear occupied spans; full queues still use a single full-array fill. No virtual size/isEmpty calls, storage fields or per-entry objects are added.

6,849,617 checks per version against an independent FIFO oracle, including wrapped storage, iterator removals, serialization, subclass hooks and repeated reuse. Sparse large-capacity clear is much faster in the initial JMH run; full 8-slot refill+clear was 17.18 -> 19.58 ns/op in the focused run, with overlapping intervals. Backing storage is unchanged.

HELD: tiny dense nonregression and strict improvements to both space and time are unproven. Native Maven compile stops at existing RAT unapproved files. The previously rejected iterator bulk-shift candidate is not included.

Canonical source: [Synexia PR 12484](https://github.com/hsoliwal/com.synexia/pull/12484), commit `da475085ca326d91e20314c2fd7f895accc2b6cc`.

[Recipe, exact pins and replay instructions](https://github.com/hsoliwal/com.synexia/blob/da475085ca326d91e20314c2fd7f895accc2b6cc/synexia-openrewrite-recipes/crates/m3-array-fork-operations-20261019/README.md), [all timings and confidence intervals](https://github.com/hsoliwal/com.synexia/blob/da475085ca326d91e20314c2fd7f895accc2b6cc/synexia-openrewrite-recipes/crates/m3-array-fork-operations-20261019/benchmark-summary.json), and [raw evidence archive](https://github.com/hsoliwal/com.synexia/blob/da475085ca326d91e20314c2fd7f895accc2b6cc/synexia-openrewrite-recipes/crates/m3-array-fork-operations-20261019/evidence.tar.gz). The test-only Maven recipes materialized these production files exactly; their hashes are in `source-pins.json`.

All field/method descriptors match the baseline. The standalone Java contract and JMH benchmark are copied unchanged from that canonical packet. Compile against the actual relocated baseline class directory and run with either baseline or candidate-first classpaths. The TopK candidate retention assertion uses `-Dm3.candidate=true`. These witnesses do not stand in for the owning product native Maven suites.

This draft covers the listed owner only. All collection families in all hsoliwal forks remain in scope and incomplete. No default branch is changed and no merge is performed. Upstream source notices and licences remain intact; the new first-party witnesses use Apache-2.0.
