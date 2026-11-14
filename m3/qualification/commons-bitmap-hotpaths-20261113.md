<!-- Copyright 2026 Hitesh Soliwal and contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Five bitmap hot paths in the actual Commons collection fork

This scratch-fork draft changes three existing production owners, not an M3JDK21
receiver or a new storage backend. It does not touch the separate TreeList typed
export PR. Existing Apache LICENSE, NOTICE and source headers are preserved, with
additive source modification notices. New tests and this receipt are Apache-2.0.

## Implementation

- IndexExtractor.fromBitMapExtractor visits enabled bits with trailing-zero scans
  and clears the lowest enabled bit. Preserve the current word and its original
  index base across callback reentry; a refused word does not advance its base.
- SimpleBloomFilter cardinality and index traversal use the original owned long[]
  directly, retaining lazy cardinality caching and callback/exception semantics.
- SimpleBloomFilter pairing uses direct arrays only for its final peer class.
  Unequal lengths retain zero padding; arbitrary producers retain the old dispatch.
- ArrayCountingBloomFilter bitmap export allocates one exact-size long[] and
  packs existing int[] cells. Negative invalid-state cells remain nonzero bits.

No retained fields, per-entry Node/Entry objects, new runtime dependencies, JNI
symbols or provider renames. Effective public descriptors and retained fields
match. One declaration overrides inherited ArrayCountingBloomFilter.asBitMapArray;
declared-method reflection is intentionally different. No serialization guarantee
beyond the unchanged existing type contracts is introduced.

## Source identities

Baseline: 1c3d291123d3e991e982005c6bc1ee91dc682fad.

| Owner | Before Git blob | After Git blob |
| --- | --- | --- |
| IndexExtractor | d9ed49697019cc6480b946afd63f6fd67add8964 | b8c31dbc2ccd9ae27be0705324589303026968de |
| SimpleBloomFilter | 772234c9bbd04d6de40ff0970f2728a3f4083857 | 9624ebd479d3807241e61f472016c7d5d5e9bfc8 |
| ArrayCountingBloomFilter | 569ef46f8bbcb2123c0bd69c743ec453c205e630 | 93dd189978b16ae8ac491edc3d2e864793badfd6 |

Private canonical candidate: hsoliwal/com.synexia@48cc8b20e2043b591f150e488d8b62347cc5707e,
existing CollectionsContractRefinement20261017 host, dataset
commons-bitmap-hotpaths-20261113. Its five Java templates seal all three owners
and both test files. This receipt is passive, not an execution claim or recipe.

## Actually executed local checks

OpenJDK 21.0.12.1 Linux. Fourteen complete exact source files, without stubs,
compile with --release 8 -Xlint:all,-options,-overloads -Werror. The excluded
options warning concerns the obsolete target; five inherited overload-ambiguity
warnings in unchanged interfaces remain recorded, not repaired or called clean.

The independent BitmapHotPathContract passes 4,900,947 checks on each original
and final source revision in normal VM execution and separately under -Xint,
fingerprint f266a4db064e25ff. Serial source-stage tests also pass. Coverage includes
BitSet oracle/order, callback mutation and reentry, null/exception behavior,
stop prefixes, unequal pair lengths, producer fallbacks, negative/overflowed
cells, partial final words and detached export/copy ownership. Five deliberately
faulty source variants are rejected. The native JUnit wrapper is provided for the
existing suite but has not been executed here with real JUnit/Surefire dependencies.

Interpreter allocations (129-bit owners, callbacks preallocated): indices
56 -> 0 B/op, peer pairs 24 -> 0 B/op, dirty cardinality 40 -> 0 B/op,
counting bitmap export 224 -> 40 B/op (only the long[3] result remains).
JIT allocation behavior varies; these interpreter values are not universal claims.

Three fresh-JVM bounded diagnostics (-Xbatch -Xms128m -Xmx512m) at 256 words:

| Operation / input | Original ns/op | Candidate ns/op |
| --- | ---: | ---: |
| Generic extraction, one high bit per word | 10159.172 | 293.139 |
| Simple index traversal, one high bit per word | 7303.623 | 288.787 |
| Simple peer pairing, one high bit per word | 178.705 | 145.795 |
| Dirty cardinality, including reflective invalidation | 644.557 | 79.959 |
| Counting export, all bits set | 28553.762 | 14011.934 |

The 256-word counting export allocates 4088 -> 2064 bytes. At 4096 sparse words,
export allocates 65552 -> 32784 bytes but CPU regresses 65425.080 -> 77368.800 ns.
Tiny cases and earlier runs are mixed. These are standalone diagnostics, not JMH,
matched production workloads, retained-heap/native-memory or general non-regression.

## Draft / pending

Maven is absent locally and container DNS resolution is unavailable. Actual
canonical Maven/OpenRewrite execution, fixed-point/refusal receipts, the full
fork Maven/JUnit/style/license lifecycle, hosted CI and independent review remain
pending. Local tests do not substitute for them. No M3JDK21 source, installed SDK,
JNI/GC/jtreg/Windows or release qualification is claimed, and no merge is requested.
Recipes, source preimages and proof instrumentation remain private in Synexia;
this public branch contains implementation/test source and passive evidence only.
