<!-- Copyright 2026 Hitesh Soliwal and contributors; SPDX-License-Identifier: Apache-2.0 -->
# Continue PR32: direct peer merge and valid-empty export

This extends the existing fork optimization PR, not another collection backend or
receiving PR. The initial commons-bitmap-hotpaths receipt remains historical;
this file describes the successor to fork commit 3abc797fe3448b65a1c723263975cbd5a25f6b65.

## Actual source changes

SimpleBloomFilter.merge(BitMapExtractor) ORs existing long[] arrays directly for
its own final class, avoiding the counter array and capturing traversal lambda.
Other producers retain the original callback route. Source immutability, self-merge,
partial writes on oversize/excess-bit failure and failure-time cache state remain.

ArrayCountingBloomFilter.asBitMapArray uses existing state/cardinality to skip
cell scanning only when valid and empty. It still returns an independent correctly
sized zero array. All populated/invalid packing is the previous implementation;
negative cells remain enabled even when the positive-cell count is zero.

No new retained fields, public signatures, per-element objects or dependencies.
Original Apache headers/LICENSE/NOTICE remain; new tests/evidence are Apache-2.0.

## Exact identities

SimpleBloomFilter: d94b075b45791ebb3932925441e2e9bb32203ed0.
ArrayCountingBloomFilter: f34c353cf79a22f73b29459efd07807a6e2285b8.
BitmapMergeContract: a80647db100e4b59ffb3e8cdb12806e6561b4708.
BitmapHotPathTest: 77b67b70b612514f0148790e4f8f95714b819d97.
Private canonical candidate: hsoliwal/com.synexia@ec219e4f9b72f01502b9270929456571f6dc8596,
existing CollectionsContractRefinement20261017 dataset commons-bitmap-hotpaths-20261113.
Its six Java outputs include the extra contract and updated JUnit entry point.
These are authored candidate identities, NOT an actual OpenRewrite execution receipt.
No private recipe, source preimage or instrumentation is stored in this public branch.

## Executed checks

OpenJDK21.0.12.1 Linux, exact 14-file production source closure, no stubs.
Java8-source compile passes with -Xlint:all,-options,-overloads -Werror; known baseline
options/overload warnings are excluded, not repaired. Normal VM and -Xint each pass:
BitmapHotPathContract 4900947 checks/f266a4db064e25ff;
BitmapMergeContract 90337 checks/734fb78781dbcb82; combined 4991284 checks per mode.
The controlled full-cause-message run (-XX:-OmitStackTraceInFastThrow and
-Dm3.test.fullCauseMessages=true) passes, fingerprint f8b98055ee3f31e5. Ordinary
mode compares public messages and cause types without depending on HotSpot's
warmup-dependent omission of implicit-exception diagnostic messages.

Under -Xint the old peer-merge allocation test fails at 480000 bytes/10000 operations;
the new source passes at zero bytes. Three deliberate mutants (early failure cache
invalidation, silent oversized input, invalid emptiness) fail the behavioral tests.
Effective public shape and retained-field probe outputs match prior PR32 sources.
Native JUnit entry point is wired but has NOT run through JUnit/Surefire here.

## Measurements and open work

Three fresh JVM diagnostics (-Xbatch -Xms128m -Xmx512m) measured pooled median merge
CPU at 256 words: 150.767 -> 95.588 ns; 4096 words: 2355.243 -> 1241.937 ns.
One-word case slightly regressed, 2.826 -> 2.887 ns. Fixed repeated inputs, setup
excluded. These are bounded diagnostics, NOT JMH or a general performance pass.
Empty export default-tiered warmup was unstable. Separate C2-only diagnostics
(-XX:-TieredCompilation -XX:CompileThreshold=1000 plus same heap/-Xbatch, three JVMs,
4096-word valid-empty input) measured 59272.460 -> 1753.610 ns. Do not present this
as a default-tiered startup speedup. Result allocation remains necessary.

New sparse linear/grouped/branchless/callback replacements and an invalid-state
fallback were rejected because of random-input regressions/profile sensitivity.
They are absent here. The earlier populated sparse-export CPU tradeoff remains OPEN.
Raw accepted/rejected timings, exact closure and reproducers are in the conversation
continuation bundle. This receipt does not erase the earlier limitation.

Author self-review only. Maven could not start (mvn: command not found), and Maven
Central DNS is unavailable. Canonical Maven/OpenRewrite fixed-point/refusal/replay,
full fork lifecycle, hosted CI and independent review remain pending. No merge,
M3JDK21 edit, installed SDK/JNI/GC/jtreg/Windows or all-forks-complete claim.
