<!-- Copyright 2026 Hitesh Soliwal and contributors; SPDX-License-Identifier: Apache-2.0 -->
# PR43 continuation: bounded bulk rollback

Preserves capacity reservation/freeCount and every existing PR43 file. For batches
above eight, initialized uncommitted child links replace the linear int[] acquisition
journal. Right/left/root cleanup reverses acquisition preorder, restoring free-chain
order. Tiny builds keep their original journal, bounded to nine ints. No new retained
fields or per-entry objects. Balanced build/cleanup uses O(log n) stack, not O(1).
PR31/32 and M3JDK21 are untouched; no merge is requested.

Parent: 0ec7fec75daa959e11c28220e5fa2c56c83f9d8e.
Before blob: c7978e34050a130ceb19450e223333aa7233f88c.
After blob: f62dc5fd4e6b61c60c6418b24941cedcf28a107d.
After SHA256: fe478ff6a693405b0410bec2b5f1948dbaf346ae7bb899de88f445b5af8a792b.
Contract blob: e1cdd4ea05915b6f235101fe5b3a7c8d6a1c6522.
JUnit adapter: a2c2f1cc007c4649e949ec82134629c70a18ca09.
Original Apache header/LICENSE/NOTICE retained; additive change notice. New tests
and receipt are Apache-2.0. No new third-party source or private recipe is imported.

## Executed locally

OpenJDK21.0.12.1 Linux; actual TreeList/OrderedIterator, no stubs.
javac --release 8 -Xlint:all,-options -Werror passes (obsolete-target options only).
Predecessor and candidate each pass 2,322,135 behavior/storage assertions normally
and under -Xint, identical digest 178f8eecbe77fb0b. Covers 40,000 random updates,
100,000-element construction, AVL ranks/threads, free-chain order/reference clearing,
nulls, self-add, sublists, iterator edits, input failures, reentrant add/remove and
nested bulk success/failure. Additional typed-array contract: 392,447 checks pass.
Candidate also passes that contract under -Xint.

Each version passes 326 disposable-source acquisition fault injections and a real
-Xmx32m allocation-failure/recovery case with all six original lane identities intact.
No injection is in the product source. Public/protected javap descriptors match.
Missing cleanup and missing child registration mutants fail invariants; reversed
cleanup is rejected by differential storage digest, not public-sequence assertions.

## Allocation observation

ThreadMXBean, -Xint, reused slots, input/setup excluded; final of three trials:

| Batch | Previous bytes | New bytes |
| --- | ---: | ---: |
| 8 | 360 | 360 |
| 9 | 360 | 304 |
| 128 | 840 | 304 |
| 8192 | 33096 | 304 |
| 100000 | 400328 | 304 |

Baseline fails the bounded-allocation regression; candidate passes. This is not
CPU/JMH, GC, retained-heap or native-process evidence. Stack-exhaustion recovery
is not qualified. Prior capacity-reservation timing/capacity tradeoffs still apply.

## Reproduce / pending

Compile OrderedIterator.java, TreeList.java and TreeListBulkRollbackContract.java
with the command above. Run com.m3.collections.apache.list.TreeListBulkRollbackContract
normally and under -Xint. TreeListBulkRollbackTest is its native JUnit entry point.
The attempt mvn -B verify could not start: mvn: command not found. The real JUnit
adapter and full fork tests/lint/license/CI were NOT run here; no stubs or skip
settings replace them. Canonical Maven/OpenRewrite execution/fixed-point/refusal
and independent review remain pending. Private recipe fixtures are not executed
receipts. Initial README describes the prior source; this receipt describes its
successor. Author self-review only. No installed SDK/JMOD/JNI/GC/jtreg/Windows or
all-forks completion claim. Keep this PR draft.
