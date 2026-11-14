<!-- SPDX-License-Identifier: Apache-2.0 -->
# Combined PR54 TreeList bulk-join optimization

Continue `hsoliwal/commons-collections` PR #54 from
`c831c0392aff488e6000b1dbe33a181135a15ce6`. This composes the previously local
bulk-join optimization with the PR's already-published single-pass removal and
nonredundant height updates. It does not replace those improvements with an older
master snapshot; the permutation implementation and all other paths are retained.

## Implementation

Remove the per-call `int[64]` join stack. The existing left/right child-index lane
records the descended parent chain temporarily. Every link is restored before
its ancestor is balanced. No external callback or new allocation occurs during
this temporary reversal. All existing public signatures, storage fields, original
ASF header and prior attribution remain unchanged. A modification notice is additive.
No extra retained arrays or per-entry objects are introduced. Other builder
allocations and existing recursive call-stack use are not claimed removed.

## Exact source pins

| Source | Git blob | SHA-256 |
| --- | --- | --- |
| PR54 TreeList baseline | `f0e96a415585c5090595efd04b68d990e20c792a` | `a660620e9fdb9928552e4f27d71348f1e7d604122963a28d837d5eccd0a5327e` |
| Combined TreeList | `3010590530c57baa195575c941383fb760045c73` | `137b49262422d27c3d15b7b7c8a095770093847ea10e67bfad4476e75d4c6edd` |
| Join regression | `3377084f23d0d2a3a3dee315bb730a4f5b441d0c` | `d2bffe31d2e37004a5c2b869e53d848332973e9c370dab53946fc2a651fe806b` |

The exact OrderedIterator dependency remains blob
`717a31655cf448e8803145a034cdd925c0a9c044`. The unchanged permutation owner remains
`c5ed7c4fa8916082dfbe8e7f041d570c28520d71`. Source identities were independently
recomputed before testing; no substitute or normalized production dependency was used.

## Fresh combined-source execution

OpenJDK 21.0.12.1 Linux; exact production sources and standalone contracts compiled
with `--release 21 -proc:none -Xlint:all -Werror`. Both versions also compile with
`--release 8 -proc:none -Xlint:all,-options -Werror`; execution is on Java 21.

- Baseline and candidate: 14,352 complete physical states / 37,829,783 assertion
  observations each, matching digest `f1b933b7b13b4753`.
- Bounded interpreter: 3,252 states / 5,236,681 assertions each, matching digest
  `46267c86d2853455`. All fixed cases remain; random steps are 150 rather than 2,000.
- Candidate release-8 classes produce the same full normal result on Java 21.
- Unchanged permutation contract: 92,515 outputs / 1,373,334 assertions; normal
  and interpreter digest `97c014c32cba481e`.
- New integration cases: 126 nested-source removal/append combinations with
  successful completion, RuntimeException or Error, followed by refill.
- Existing cases check both join-height branches, complete backing-array state,
  free-chain order, ranks/heights/threads, nulls, self-addition, cursor changes,
  source callbacks and failure cleanup. Four faulty join variants are rejected.
- Public/protected descriptors match. All retained fields match. The one private
  join descriptor changes from `(III[I)I` to `(III)I`, removing its scratch argument.

These are assertion observations, not millions of separate JUnit test cases.
The included native JUnit adapter was not run through Surefire in this environment.

## Measured allocation regression

Under `-Xint`, with preallocated/recycled element slots, 1,000 warmups and 2,000
measured calls; input construction and removal are outside the measured interval:

| Appended elements | Baseline B/call | Combined B/call |
| ---: | ---: | ---: |
| 1 | 328 | 56 |
| 8 | 360 | 88 |
| 9 | 304 | 32 |
| 16 | 304 | 32 |
| 128 | 304 | 32 |

The bound fails on the current PR54 baseline and passes on the combined source:
272 fewer allocated bytes per measured call. Retained capacity is unchanged.
No fresh CPU-timing, universal throughput, GC or production benchmark claim is made.
Historical timings for a different source image are not relabeled as new results.

## Reproduce and outstanding scope

Compile OrderedIterator.java, TreeList.java and TreeListJoinContract.java using the
commands above; run `com.m3.collections.apache.list.TreeListJoinContract` normally
and with `-Xint` plus argument `150`. Native entry point:
`mvn -B -ntp -Dmaven.compiler.testRelease=21 -Dtest=TreeListTest,TreeListJoinTest test`.

This is a scoped real-source build, not the complete fork. Maven is absent and
Maven Central DNS resolution fails in this runtime. Native Maven/JUnit,
Checkstyle/RAT, hosted CI and independent review remain unverified for this commit.
Author self-review only. Local exact-source composition/replay is not canonical
Maven/OpenRewrite execution; that gate remains NOT_RUN.

Only product source, independent regression tests and this passive receipt are
published. No private authoring, recipe, fixtures, registry or instrumentation
are copied into the fork. No master merge or M3jdk21 write occurs. Keep PR #54 draft.
