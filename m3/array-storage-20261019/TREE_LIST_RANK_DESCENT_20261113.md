<!-- SPDX-License-Identifier: Apache-2.0 -->
# Commons TreeList: iterative rank descent over existing array slots (2026-11-13)

This is a **direct optimization candidate in the original hsoliwal/commons-collections fork**, not a wrapper, new implementation or M3JDK receiver. Preserve the original ASF license/attribution and every public/protected TreeList signature, iterator and subList contract. This fork is a public scratch donor; reusable executable transformation recipes and final product admission remain under canonical Synexia custody.

## Exact source identity and one operation

- GitHub fork baseline: `hsoliwal/commons-collections@3df539acc3258628ffb17d6064b204a61a79eb3c`.
- Existing implementation: `src/main/java/com/m3/collections/apache/list/TreeList.java`, exact Git blob `ea33cb5bc43e6939cecd30bacdc0e64bcab2d1a0`; full UTF-8 SHA-256 `2c1f47e3736f1366105258db91eb563780322956462460ffdb403d4f66a96f65`.
- Proposed full-file postimage SHA-256 `18093a81b6dc9b3f6f81b61a680516026b16249ea10b13fa62b3bf18775b362d`.
- Atom: private `TreeList.SlotStore.get(int,int)` rank-index lookup; retain `slot`/`index` parameters, `relativePosition` arithmetic, left/right thread-state tests and the `NONE` sentinel. Replace the recursive self-call with a loop carrying the child-relative index. Do **not** rewrite balancing, insertion, removal, min/max, iterator callbacks, tree-shape metadata or array ownership.
- Complexity O(log n) as before; O(1) iterative stack usage versus recursive stack depth. Zero additional retained arrays, nodes, objects, API types, or native symbols.

## Proof actually executed

Standalone Java 21 exact-atom baseline/candidate oracle: `RankedSlotDescentProbe.java`, SHA-256 recorded in the attached workspace; **412,206 deterministic checks passed** across balanced ranked/threaded trees of 1–131,071 elements, including in-range lookup, absent ranks, and original/candidate min/max checks. Strict `javac -Xlint:all -Werror` passed. Five independently launched JVMs each ran 11 alternating baseline/candidate warmed samples, 196,608 indexed gets per sample on a 131,071-element balanced tree. Candidate / incumbent sampled median ratios: **0.9336, 0.9221, 0.9197, 0.9419, 0.9381**. No actual-fork end-to-end test suite or full JMH was executed in this environment; do not treat the standalone atom probe as product qualification or a universal speedup.

## Product verification and release refusal

A direct fork-owned JUnit regression test is added to exercise random indexed access, mutations and iterator transitions against `ArrayList`. Run `mvn -B -ntp -Dtest=TreeListRankedDescentTest test` and the complete Apache Commons suite on an authorized runner, using the original JDK target. Check `javap -p -s` public/protected and serialized shape against the original baseline. Diff and compile gates precede test and runtime. Preserve failed CI as FAIL/PENDING; a queued or zero-job run is not proof. Do not promote this scratch result into M3JDK until the canonical Synexia Maven/OpenRewrite recipe has executed and the target JDK image gates pass.

Source change is **proposal / review branch only**; no default-branch or runtime shipping claims.
