<!--
Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.
SPDX-License-Identifier: Apache-2.0

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->
# Wordwise counting Bloom containment: receiving receipt

Source base: `hsoliwal/commons-collections@9364705b7fd39ff9112a886de4d932e6aba92d65`.
Canonical recipe/evidence: [com.synexia@c9655cb34081751fd285b356dc5b633f0fdbe1d5](https://github.com/hsoliwal/com.synexia/tree/c9655cb34081751fd285b356dc5b633f0fdbe1d5/synexia-openrewrite-recipes/recipe-crates/m3-collections-contract-refinement-20261017/authoring/commons-bloom-contains-20261114).
The publication replaces the commit placeholder with the immutable canonical commit.

The private canonical owner started from Synexia develop
`f2ce596a39f9a9a4ba6f48d40eb62db0a1af2757`. The public fork receives qualified
outputs; this receipt contains no private recipe implementation.

## Qualified operation and source custody

The counting Bloom bitmap overload compares incoming words with its existing
occupancy mask while preserving miss/bounds ordering, live callback state and
short-circuit behavior. Public/protected descriptors are unchanged. Explicit
history resolutions remove 11 malformed merge lines from AbstractMapBag and a
57-line duplicate-member block from CircularFifoQueue, retaining the coherent
parent owners and both branches' tests. Two existing first-party metadata files
receive missing Apache notices.

Recipe: `com.synexia.m3jdk.CollectionsContractRefinement20261017`, existing Maven
host `m3-collections-contract-refinement-20261017`.

| Dataset | SDK / sealing / receiver result |
| --- | --- |
| commons-bag-owners-20261114 (canonical bootstrap) | 5 / 23 / 9 passed on frozen starting owner |
| commons-bag-recovery-20261114 | 5 / 24 / 9 passed |
| commons-fifo-recovery-20261114 | 5 / 24 / 9 passed |
| commons-bloom-contains-20261114 | 5 / 24 / 9 passed |
| commons-recipe-notice-20261114 | 5 / 24 / 9 passed |

Every listed product output matches an actual SDK Result and pinned preimage.
All five receivers were replayed to zero changes. The exact bag recovery gate
also passed nine near-miss refusals; the queue uses the ordinary Java lane.

## Product verification

Maven 3.9.6 and Temurin 21.0.13+9 completed offline `clean verify` with
`-Dmaven.compiler.testRelease=21`: **22,458 tests, 22,376 passed, 82 skipped,
zero failures/errors** across 295 reports. Production remains at Java release 8;
tests compile at release 21. RAT passed, and all configured JaCoCo coverage
checks passed across 569 classes. The eight new differential tests include
65,536 exhaustive pairs, 5,280 seeded scenarios and signed cursor wraparound.
A separate eight-test run with `-XX:-OmitStackTraceInFastThrow` passed.

A final comment-only correction adds two spaces to the test's ASF header. It was
resealed and SDK emitted. Recompilation proved all 1,235 production/test class
SHA-256 values identical to the complete passed run. The incremental final
compile/RAT/package/coverage check explicitly skipped tests, and fresh-cache
Checkstyle found zero violations on the four changed Java files. The canonical
packet retains both source epochs, exact seals and historical failure witnesses.

The offline build skipped CycloneDX `makeAggregateBom` and SPDX `createSPDX`
because those goals require online mode. This receipt does not claim completed
SBOM/SPDX generation or release/distribution certification.

## Bounded measurements and donor provenance

Six fresh JVMs supplied 1,080 checksummed CPU/allocation samples. Dense 128- and
2,048-word hits used about 0.023 and 0.025 times the original CPU time. One-bit
sparse hits can regress (about 2.10 times at one word, 1.53 times at 2,048 words);
early misses commonly add 1–3 ns. No universal speed or allocation win is claimed.
Full observations and reproducible source are in the canonical evidence packet.

Source/category records cover the incumbent Apache wordwise identity, pinned
Apache-2.0 RoaringBitmap comparator, LeetCode 2166, HackerRank Java BitSet and
GeeksforGeeks BitSet AND. No donor challenge/tutorial/bitmap implementation source
is copied into these outputs. Existing Apache notices and fixture attribution
are retained.

## Six generated output identities

The receiving receipt itself is publication metadata and is not one of the six
source outputs below. Each digest is SHA-256 of the exact final file bytes.

| Path | SHA-256 |
| --- | --- |
| `m3/recipes/zipping-iterator-constructor-20261114/README.md` | `ea81d5e500653b13a7126b9acb31067c66958bcf09dafb1761fe02899b042399` |
| `m3/recipes/zipping-iterator-constructor-20261114/SOURCE_SEALS.tsv` | `393b35bbde34a9740c6ca3fd0432ecd00a1ff7ef3047a7150469de50ee9a4756` |
| `src/main/java/com/m3/collections/apache/bag/AbstractMapBag.java` | `5693612ae0904a5a322d698a660c17c8e0c4abf64f137561b28a12907c62aa98` |
| `src/main/java/com/m3/collections/apache/bloomfilter/ArrayCountingBloomFilter.java` | `8ef2f974ff15e788430c7e7c02487d85838e56d77d1b3cdeb1ddb564dc6396cc` |
| `src/main/java/com/m3/collections/apache/queue/CircularFifoQueue.java` | `680028128301661914479c1090bb2001691a5560ddad036118801eada545645d` |
| `src/test/java/com/m3/collections/apache/bloomfilter/ArrayCountingBloomFilterContainsTest.java` | `7563676f4b83effa523c5363cb11bbd86336a9c14c61b9d4751c71340b0efc07` |
