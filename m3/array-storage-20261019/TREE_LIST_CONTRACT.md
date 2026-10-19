<!-- SPDX-License-Identifier: Apache-2.0 -->
# TreeList array-storage contract and recipe packet

Source: hsoliwal/commons-collections@13586e6bbbfed0f946c03ddaac62c8a1853ce12b,
`src/main/java/com/m3/collections/apache/list/TreeList.java`.
The source and its complete original tests were read before preparing this candidate.
The existing Apache ranked/threaded AVL algorithm is the donor. Preserve its ASF
license and attribution. Reusable recipe and postimage templates remain canonically
in hsoliwal/com.synexia. The receiving fork owns product verification.

## Scope and locked contracts

Apply only the declared TreeList source, new qualification tests, and these receipts.
Preserve every public/protected TreeList signature, null/equality direction, element
identity, indexing and exception messages, listIterator state, fail-fast timing,
backed subLists, and bulk append ordering. Preserve ranked AVL balancing and the
O(n + log m) bulk-tree construction/join algorithm. Dynamic array capacity growth
adds amortized allocation cost; no measured latency or heap speedup is claimed.

## Representation and meaningful atoms

Replace the private retained node graph with one parent-owned slot store: Object[]
payload; int[] child/thread indexes and relative positions; byte[] AVL heights and
per-slot LIVE/LEFT_PREVIOUS/RIGHT_NEXT masks. The root, iterator cursors, and temporary
join stacks contain integer indexes. Deleted payload is cleared and free slots are
reused by both individual insertions and balanced bulk builds. Bulk construction
uses one operation-local primitive slot journal to release only uncommitted slots
if a source iterator fails. Join scratch is allocated before subtree construction,
so the join does not introduce a post-build allocation failure. Clear releases the backing arrays. No Entry/Node object is retained per value.

Retain the donor behavioral atoms: locate by relative rank, threaded next/previous,
rotate/balance, insert, remove, balanced build, and height-matched tree join. The
recipe is the existing Maven/OpenRewrite hash-pinned snapshot family; generate from
sealed pre/post bytes, reject drift, and prove a no-change replay. Fix the template
and rerun when a test finds a failure; never patch receiver output directly.

## Qualification plan

Diff, syntax/lint, Java compilation, original Commons tests, then deterministic
original-vs-generated differential scenarios. Exercise insertion/removal rotations,
both AVL join directions, self-add and backed-source failures, next/previous/remove/
set/add iterator state, null and side-effect equality, free-slot reuse, and backing
payload release. Inspect retained structure independently of output equality.
No unrelated compiler/test/native gate is marked passed by these checks.

## Independent review correction

The first sealed template passed 72,000 behavior operations but failed a separate
bounded-capacity check: bulk construction ignored the free list. With peak live
size 33, 1,024 add/remove cycles raised capacity to 39,226. That template was never
received into the product fork. The successor journals acquired slot IDs, consumes
free slots before growing, and verifies 1,024 successful and failing bulk cycles
at constant capacity. The proof also independently checks that every acquired
slot is live or reachable through the free chain and that dead payloads are null.
This is a source-template correction; requalify all postimage seals before receipt.
