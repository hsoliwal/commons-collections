<!-- SPDX-License-Identifier: Apache-2.0 -->
# Actual Commons SetUniqueList source change — bounded Java 21 verification

Date: 2026-11-13. Receiver `hsoliwal/commons-collections/master@1c3d291123d3e991e982005c6bc1ee91dc682fad`.
Production owner: `src/main/java/com/m3/collections/apache/list/SetUniqueList.java`.

## Executed source custody and single-purpose patch

Baseline Git blob: `a9d4a21591849b4d78a3b889e9ab3297c070e415`.
Postimage Git blob: `6144821ed4db28e4ccb8a7a3d88edb5686c19830` (read back from GitHub).
Only the `removeAll(Collection<?>)` implementation and one private
`isStableBulkValue(Object)` predicate were changed. The existing loop remains
unchanged as the fallback; no public method signatures, package, serialization
UID, API or JNI members were changed.

New fork JUnit 5 source:
`src/test/java/com/m3/collections/apache/list/SetUniqueListBulkRemovalTest.java`.
Six test methods cover 8K ordered removal, 8 seeded boundary lengths,
stale duplicate external backing state, linked-list/linked-set fallback,
subclass override dispatch at 16K scale, and opaque custom equality at 16K scale.

## Executed isolated Java 21 algorithm proof

An isolated *method-level* Java program implemented the original SetUniqueList
memberwise removal and the exact proposed HashSet/ArrayList gated compaction,
without claiming to compile the entire relocated Commons fork class.
`javac --release 21 -proc:none -Xlint:all -Werror` passed with no diagnostics.

- 4,321 seeded, deterministic original-vs-candidate differential checks: PASS.
- 73-check bounded replay: PASS in mixed, `-Xint`, and `-Xcomp` modes.
- The three bounded replay logs were byte-identical, SHA-256
  `c9821480cc4b5d1e02d41cb9459e9f711af66e75738b895ab2b54d3424068878`.
- Full 4,321-check log SHA-256
  `04a22ff043caf63bb08e10dcad4964aa114ba27ec6cdcda6fd49e3e380f5be4f`.
- Strict compiler log SHA-256
  `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

The first interpreted proof exceeded the bounded command limit and was NOT
treated as a pass; a correctly sliced 73-check replay completed in all three
execution modes. The final 4,321-check run completed in the regular JVM.
This proof does not replace the original `SetUniqueListTest` or real Maven.

## Performance correction was applied before admission

Exploratory JDK 21 `-Xbatch` median timing across three separate runs of
the *isolated method*:

| Array size | Removal set size | Original median ns | Candidate median ns | Path |
| ---: | ---: | ---: | ---: | --- |
| 2,048 | 512 | 228,569 | 240,306 | Legacy (no fast path) |
| 8,192 | 1,024 | 1,112,197 | 1,090,623 | Legacy (no fast path) |
| 8,192 | 2,048 | 3,702,068 | 292,393 | Admitted bulk |
| 16,384 | 1,024 | 1,530,114 | 262,208 | Admitted bulk |

Originally a `256/64` gate looked plausible; actual measurement showed
severe regressions, including `n=512,k=128` and `n=2048,k=512`.
Therefore the **committed production gate** was narrowed to exactly:
`size>=8192 && coll.size()>=1024 && (long)size*coll.size()>=12000000`.
This does not prove a universal speedup and the samples are **not JMH**.
Reproducible source for a *real-fork* benchmark is committed separately under
`m3/qualification/setuniquelist-bulk-array-compaction-20261113/SetUniqueBulkBenchmark.java`;
that benchmark is authored but **not executed against the full relocated fork**.

## Artifact and mandatory HOLD

The bounded method-level proof archive
`commons_setuniquelist_bulk_compaction_method_proof_20261113.zip`
was created under the conversation scratch namespace; SHA-256
`25ee62d624d316805b95d2443139be0fac1caf6714d417f08c33b981d408124e`.
It includes the exact isolated method model and benchmark programs, compiled
classes, strict compiler logs, original-vs-candidate replay logs, raw
three-run measurement CSV and SHA256SUMS. All ZIP CRCs and included member
SHA-256 checks passed. This ZIP is **not** a donor library or GitHub binary import.

**NOT EXECUTED:** full Commons Collections fork-native Maven/JUnit, complete
relocated `SetUniqueList.java` dependency closure compilation, JMH, fork CI,
M3JDK21 receiver build, JNI/HotSpot/jtreg and licence/ABI acceptance.
Disposition: **REVIEWABLE SOURCE OPTIMIZATION / FORK NATIVE AND RECEIVER HOLD**.
No automatic merge is authorized.
