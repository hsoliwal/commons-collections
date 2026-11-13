<!-- SPDX-License-Identifier: Apache-2.0 -->
# Commons Collections ArrayStack — direct-array search verification (2026-11-13)

Source receiver: `hsoliwal/commons-collections/master@1c3d291123d3e991e982005c6bc1ee91dc682fad`.

Protected source: `src/main/java/com/m3/collections/apache/ArrayStack.java`.
Baseline Git blob `0720f9092da9fe8600dc3163e7139dd86d563cc9`, SHA-256 `7c36f53558bf884d85c186b7756325799ec1841948928e8584673fff313c2be7`.
Candidate Git blob `0d64fdbdf17d24e7300e484aaab822303544fe69`, SHA-256 `a86f620ba2f9b822f4b4e45510874a86e17dc7be0c44c84bcad7bab91afa3db6`.

## Actual collection optimization

The original `search(Object)` performed a virtual `get(i)` call and bounds check for every reverse probe. The candidate uses the parent `ArrayList.lastIndexOf` (direct backing-array traversal) **only** for the exact `ArrayStack` runtime class, length `>=2048`, and stable-equality probes `null`, `String`, `Integer`, `Long`. For every subclass and opaque object with potentially side-effectful `equals`, the legacy virtual-get loop is retained. One-based depth, latest duplicate, missing/null behavior, inherited Java collection API and serialized representation are unchanged.

Only **one existing production class** was modified. The new JUnit source and opt-in benchmark are separate one-purpose files; neither expands the product class or JNI/native surface.

## Executed Java 21 proof

- Baseline and optimized source both compiled with `javac --release 21 -proc:none -Xlint:all -Werror`, exit zero, empty logs.
- A real-source standalone regression passed **1,649 checks** in each of mixed, `-Xint`, and `-Xcomp` execution; identical stdout SHA-256 `fcb0ddd49b1573f9e57b0759ecb3ba098fa1021a6fa324a61f57e195195ae373`. Checks cover seeded lengths 0 through 2048, immutable probes, null/duplicates/missing, virtual subclass `get(int)`, user-defined/mutating `equals`, and Java serialization. Baseline and candidate replay outputs matched.
- Five new JUnit 5 test method bodies compiled under strict `javac` and ran in all three JVM modes using a **local test-only annotation/assertion shim**. Identical stdout SHA-256 `45fde74ae3a9c683d3b6d3f2969e8e4feb94a742a9158ecb69d792218a210c99`. This is **NOT a real JUnit platform/Maven execution**. All five also passed against the unmodified baseline class.
- `javap -p` reports byte-identical baseline/candidate public and private declaration lists (`logs/api_diff.log` empty, SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`). Both retained `serialVersionUID=2130079159931574599L`.
- The seeded standalone proof does not use signatures or body emulation in place of `ArrayStack`. The production class itself is the exact original/candidate file, each independently Git-blob hashed.

## Exploratory measurements, not JMH or performance certification

JDK 21.0.12.1, `java -XX:-TieredCompilation -Xbatch`, same compiled benchmark class and workload in both processes, 2 warmup batches and 5 timed batches; 4,096-element stack:

| Workload | Baseline median ns/search | Candidate median ns/search | Observed difference |
| --- | ---: | ---: | ---: |
| Missing String query | 5,388.8 | 4,348.6 | ~19.3% lower |
| Bottom String hit | 5,369.6 | 4,402.2 | ~18.0% lower |

Across three preceding independent deep-miss runs the baseline/candidate medians were 5,048.4/4,224.5 ns, ~16.3% lower observed search latency. Source, JVM/flags, sizes, checksums, and raw logs are included; benchmarks are machine-specific and not JMH. The 2,048-element threshold is an explicit opt-in performance cutoff; no asymptotic complexity claim is made (both paths are O(n)).

## Archive and HOLD

Conversation proof bundle: `commons_collections_arraystack_direct_search_20261113.zip`, **47 members**, ZIP CRC and member SHA-256 manifest fully checked, archive SHA-256 `b04db3ec566edd5af84277e063fb178db0c8e3d0b29473ef1545f54bdca82fb3`. It contains exact baseline/candidate source, JUnit source, standalone actual-class proof, exploratory benchmark, C2/tiered logs, strict compiled class files, API-diff and serialization receipts. Local proof shims stay only in the archive, not in the fork.

**NOT EXECUTED:** the complete Commons Collections Maven/JUnit engine, hosted fork CI, JMH qualification, downstream `hsoliwal/M3JDK21` integration, ABI/JNI/native/runtime or wider class-by-class fork optimization. No donor-gate work, automatic merge, class relocation, public API change or product claim occurred in this optimization PR. **Disposition: REVIEWABLE FORK HOT-PATH OPTIMIZATION / FULL FORK-NATIVE AND M3JDK RECEIVER HOLD.**
