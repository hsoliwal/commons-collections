<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Commons retained array storage and span atoms

Receiving baseline: `hsoliwal/commons-collections@13586e6bbbfed0f946c03ddaac62c8a1853ce12b`.
Canonical task: `hsoliwal/com.synexia`,
`synexia-openrewrite-recipes/recipe-crates/m3-collections-contract-refinement-20261017`,
final clean-delivery dataset `commons-arrays-final`. The first received packet
was `commons-arrays`; `commons-arrays-gates` supplies the ordered workflow/receipt
successor. The final dataset replays directly from the original baseline.

This packet changes three actual production owners. `IteratorChain` retains its
pending children in the existing JDK `ArrayDeque`; `PermutationIterator` retains
payloads in one parent-owned `Object[]` beside its existing `int[]` keys and
`boolean[]` directions; `CircularFifoQueue` retains its original ring array and
moves an iterator-removal suffix through at most two contiguous array-copy spans.
The packet adds no runtime dependency, collection hierarchy or per-element facade.

## Operations and contract boundaries

| Owner | Selected operation | Preserved behavior | Cost and storage |
| --- | --- | --- | --- |
| `IteratorChain` | Pending-child admission and dequeue | Non-null child admission; null payloads; lock before null validation; nested flattening without consuming the source queue; last-used child removal; direct self admission snapshots all pending children; wrapped self admission appends one child and then throws `ConcurrentModificationException` | Existing ArrayDeque owns pending references without a linked node per child. Queue operations remain amortized constant time. Only direct self admission needs a temporary ArrayList snapshot. |
| `PermutationIterator` | Dense ordinal-to-payload lookup | Original Steinhaus-Johnson-Trotter order; repeated/equal/null payload identities; empty input returns one empty list; independent mutable output lists; constructor callback/copy order; absent zero keys still decode to null for irregular collection-size input; unsupported remove | Dense Object[] replaces HashMap<Integer,E> and its retained entries. Key lookup is constant time and does not box keys. Existing permutation algorithm and required result-list allocation remain. |
| `CircularFifoQueue` | Iterator removal after the head | Original virtual head removal; iteration cursors; wrap geometry; overwrite behavior; exact active/inactive slots; released removed references; public/protected API; serial UID/fields and original byte stream | Reuses the same owned ring array. At most two System.arraycopy calls and one boundary reference assignment move the suffix. The asymptotic linear bound is unchanged; no throughput claim is made. |

The admission and key-decoding helpers express separate observable contracts.
The queue span helper changes actual data movement. These are not wrappers around
the former linked owners. The original `CircularFifoQueue.get` implementation is
outside this packet; Synexia already has the separate
`com.synexia.m3.M3CircularFifoIndexWrap20261018` donor for that operation.

## Original-source proofs

Four immutable production preimages are retained under
`src/test/resources/m3/array-storage-20261019/original`: the three changed owners
and `UnmodifiableIterator`, which shares package-private behavior with the
original `IteratorChain`. Each preimage is SHA-256 checked before compilation.
`M3ArrayStorageOracle` compiles those exact bytes and loads the original owner
classes child first at their original binary names. The receiver cannot serve as
its own behavioral oracle.

The added tests specify the following finite qualification:

- Every start offset, nonempty occupancy and removal ordinal for capacities
  1 through 24: 47,450 ring states, followed by future wrap/overwrite and drain.
- Every removal subset through capacity 8 while one iterator remains live;
  exact physical slots, cursor/full flags and payload release are compared.
- Original and current serialization bytes, cross-reading in both loaders,
  and serialized/public/protected surfaces are compared.
- Iterator chain direct and wrapped aliases cross ArrayDeque growth boundaries;
  payload identity/nulls, nesting, locks and removal-after-lookahead are compared.
- Permutation order and payload identity are compared for every input length
  0 through 8 across unique, equal, identical and null-bearing payload shapes;
  irregular size-report traces preserve original failures and zero-key behavior.
- Reflection verifies the physical retained ArrayDeque and Object[] owners and
  the absence of a retained map in the permutation owner.

These tests supplement the original owning suites. Actual SDK and runtime run
reports are separate evidence; this source receipt is not itself a passing test
claim. Original tests, manifests, templates and upstream sources are retained.

## Complete baseline accounting and remaining owners

`BASELINE_SOURCES.tsv` seals every production Java source in the inspected
baseline. `STORAGE_OWNER_LEDGER.tsv` records all 41 source files with actual JDK
HashMap/HashSet/LinkedHashMap/LinkedHashSet/TreeMap/TreeSet/LinkedList construction,
plus the intrinsic storage families and selected existing array owners.
Unselected source seals are expressly not operation qualification.

Only `IteratorChain` has an unconditional private pending queue eligible for the
existing ArrayDeque. `LayerManager` passes its mutable LinkedList to arbitrary
cleanup callbacks and supports positional access; callbacks can admit null or
observe List behavior. `PredicatedCollection` can construct a queue whose
predicate permits null, and its delegate participates in serialized state.
`QueueUtils.EMPTY_QUEUE` retains zero element nodes. Those contracts are not
silently narrowed by this packet.

Hash/map, bag/multiset count, bidirectional tree, Patricia trie, linked-list
subclass, concurrent and reference owners remain explicit ledger rows.
Bucket/segment arrays containing linked entry objects are not counted as fully
array-backed. ArrayList-based child lists and primitive bitmaps already using
arrays are retained. Utilities producing public JDK collections and temporary
ordered copies are distinguished from hidden retained storage.

The intrinsic TreeList lowering is owned by a separate `commons-tree` packet;
this dataset neither edits that source nor claims its qualification. The ledger
is an accounting boundary, not a claim that all remaining node owners have been
converted.

## Source custody, licensing and replay

`SOURCE_SHA256.tsv` binds the selected production owners, added proofs and
immutable originals to exact before/after bytes. The existing circular queue
workflow retains its original package-only parity check against the pinned
predecessor and checks its historic manifests/templates. Its current owner is
admitted by the new exact postimage seal and still runs against the genuine
original-source behavioral oracle. A strict Apache RAT step retains its failing
status if legacy metadata is unapproved. The following behavior step uses
`if: always()` and an explicit `-Drat.skip=true` so that behavioral evidence is
still produced; it does not turn the license gate or full workflow green.

The canonical `prepare.py`, Maven/OpenRewrite SDK harness and `receive.py` are
unchanged. Candidate files are templates until the actual SDK result is retained
and the strict receiver verifies its seals. Missing, duplicate, drifting and
wrong-representation inputs are refused; mixed pre/post states and fixed point
are tested. The effective recipe-owner hash is sealed separately from immutable
engine origins. Root publication binds the canonical SDK evidence to this
receiver; no GitHub merge or external publication is implied by this packet.

Apache upstream headers and notices are retained in copied/modified sources.
First-party modifications and added proof sources are attributed to Hitesh
Soliwal and Contributors to the Synexia Project under Apache-2.0. Existing JDK
ArrayDeque/ArrayList/System.arraycopy APIs are called without copying JDK
implementation source. Original licenses are not replaced.
