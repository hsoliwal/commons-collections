<!-- SPDX-License-Identifier: Apache-2.0 -->
# TreeList: reserve bulk fresh-slot demand once

This continuation optimizes the actual Commons fork's existing array-owned TreeList. It is not a sparse-set monitoring or M3JDK21 receiving change.

## Exact source custody

The inspected/tested original PR36 head was `40fccdab10cca7f8be240ba97d911c60f164e4b5`. Before publication, concurrent fork work advanced the branch to `5d7174bc9d709e9b795fb858fc244bdb62c8966c`; the TreeList preimage was independently re-read at that exact commit and is unchanged. The new commit is additive on that newer tree, retaining all concurrent source changes.

- Owner: `src/main/java/com/m3/collections/apache/list/TreeList.java`.
- Original Git blob: `ef4881eadee577ae82ef30d92fb59b3c8f04985d`; SHA-256 `1a7f9b8d001f13e0564bb347ce0f2d6581b8d3b475a4f73e923d6187d353e9fc`.
- Candidate Git blob: `572f624a81b3d7d1f8d89abf3132dd2f89d89221`; SHA-256 `ea5078019aa048e4a851c1b3eb80a141c8b931a3b058b3c618feba7ba0b7ff72`.
- Unchanged exact compile dependency `OrderedIterator.java`: `717a31655cf448e8803145a034cdd925c0a9c044`.
- Independent executable contract: `720299f56ceb4c9df63801cc2dac920cd198c620`; SHA-256 `b4ac95b51c606da7ba477b004a2b3adbe86effdfa7b18e23c05fe75d9ebf71a0`.
- Native JUnit entry: `c87ec2e0cc88ccbafdda8c407fb0d4961d855d9b`; SHA-256 `4cc112934f532be820e928255b98932a5528f14e585ddb842bca2c03d7bdff62`.

Original ASF license and existing Synexia modification notices remain intact. No challenge/editorial code was copied.

## Production change

The existing rollback journal already carries the count of unacquired build slots. Pass that count through `acquireSlot`/`freshSlot`. Recycled slots and unused existing capacity are consumed first. At the first fresh-capacity exhaustion, advance the original geometric growth policy arithmetically to the required capacity, then copy each of the six existing lanes only once. Single insertions pass demand 1.

No new retained arrays, fields, per-entry objects, public methods, wrapper, backend, dependency or JNI path. The existing proportional rollback journal remains. Successful final capacity follows the original geometric policy. Existing PR36 TreeList traversal/export edits are retained byte-for-byte outside the bounded growth/acquisition atoms and additive notice.

## Actually executed on exact real sources

Runtime: OpenJDK 21.0.12.1+1-1-deb13u1-Debian, Linux. Compile original/candidate and standalone contract with `javac --release 8 -proc:none -Xlint:all,-options -Werror`. Only obsolete options warnings are excluded. The candidate also compiles with `--release 21 -proc:none -Xlint:all -Werror` and passes the same full normal contract.

- Baseline RED: observed bulk append replaced all six lanes 16 times, failing the new at-most-once regression.
- Candidate GREEN: growth/reuse contract passes 86,170 assertion checks, fingerprint `ee73cb27bde08463`, both normal and `-Xint`.
- Baseline and candidate normal behavior: 21,199,395 assertion checks each, fingerprint `16907d3970574877`.
- Bounded interpreter behavior: 1,051,988 checks each, fingerprint `12c32b2b5e4fa1b8`. Covers all boundary/failure cases plus 2 x 200 random steps; normal run uses 12 x 2000. An initial full-size interpreter invocation reached the 20-second execution timeout and is NOT reported as passing.
- Cases include nulls/duplicates, ranks and forward/reverse exports, self-addition, clear, mixed fresh/recycled slots, failed sources at every prefix 0..64 across three layouts and RuntimeException/Error/short-iterator variants, exact exception identity, stale iterators, rollback ownership and subsequent reuse.
- Deliberate discarded-hint, skipped-reuse and lost-rollback-root mutants are all rejected.
- `javap -public -s` outputs match exactly; retained SlotStore field names/types match exactly.

These are assertion observations against actual compiled fork sources, not millions of separate JUnit tests or a substitute model.

## Bounded actual-source measurements

Three fresh JVMs per variant/workload; three retained samples after three warmups in each. `-Xbatch -Xms128m -Xmx512m`; input construction and receiver recycling outside measurement, actual addAll inside; consumed result and escaping receiver. Thread CPU and allocated-byte probes are scoped diagnostics, not JMH or representative application acceptance.

| Workload | Original bytes/op | Candidate bytes/op | Original median CPU ns/op | Candidate median CPU ns/op |
| --- | ---: | ---: | ---: | ---: |
| bulk 8 | 328 | 328 | 359.535 | 360.998 |
| bulk 8192 | 662648 | 242424 | 188389.583 | 164356.208 |
| bulk 65536 | 5030920 | 1851224 | 1075766.750 | 782169.450 |
| reuse 8192 | 33096 | 33096 | 112760.125 | 105814.417 |
| single insertion series 8192 | 629584 | 629584 | 1362996.417 | 1318476.083 |

The small case is slightly slower in these noisy measurements; no universal speedup is claimed. Reserving expected fresh capacity earlier can retain more storage after an exceptional/short source and can change memory-exhaustion timing. Observable successful behavior and tested rollback semantics match; out-of-memory equivalence is not claimed. This is reduced allocation churn, not a reduction in successful final retained capacity.

## Reproduction and draft boundary

The dependency-free contract has `main` modes `behavior`, `interpreted`, and `growth`. Compile it with TreeList and OrderedIterator and run with the same runtime options above. Native fork entry command: `mvn -B -ntp -Dtest=TreeListBulkGrowthTest test`.

Native JUnit/Surefire, full fork Maven/Checkstyle/RAT and hosted CI were not executed for this new change in this session. The separate existing FIFO public-surface test failure on PR36 is not repaired or relabeled by this TreeList change.

A pinned-source local Java materializer generated the owner. The existing private `m3-collections-contract-refinement-20261017` host dataset `commons-tree-bulk-growth-20261113` is authored locally with exact pre/post SHA-256 and ABSENT test rows; canonical Maven/OpenRewrite execution is NOT_RUN. The private materializer, dataset and preimages are not copied here. No canonical-execution success is inferred from these public scratch-fork outputs.

Keep this PR draft. Author self-review only; independent review pending. No master merge, M3jdk21 write, installed JDK/JMOD/JNI/GC/jtreg/Windows acceptance or all-forks completion is claimed.
