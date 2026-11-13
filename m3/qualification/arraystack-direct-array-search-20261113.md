<!-- SPDX-License-Identifier: Apache-2.0 -->
# Commons Collections fork: ArrayStack direct backing-array search

**Branch:** `perf/arraystack-direct-backed-search-20261113` on `hsoliwal/commons-collections/master@1c3d291123d3e991e982005c6bc1ee91dc682fad`.

**Exact existing owner:** `src/main/java/com/m3/collections/apache/ArrayStack.java`, Git blob `0720f9092da9fe8600dc3163e7139dd86d563cc9`.

## Algorithm atom, contracts, and non-overlap

Problem category: reverse linear search in an **array-backed stack**. The existing `ArrayStack.search(Object)` scans backwards with `get(i)` for each element, performing one virtual getter and bounds check per probe. `ArrayList.lastIndexOf(Object)` (called as `super.lastIndexOf`) uses the same reverse-equality contract but scans the parent storage directly, without the repeated getter call.

One additive guarded fast path is eligible **only when the runtime class is exactly `ArrayStack`** and the search argument is `null`, `String`, `Integer`, or `Long`, whose equality operations cannot mutate the stack or invoke a user-defined overridden `equals`. All other argument types, and **all subclasses** even when given a String, retain the existing virtual-get/equals fallback **byte-for-byte**.

This intentionally does **not** generalize to arbitrary objects: an adversarial `equals(Object)` can mutate the list during traversal, affecting when `get(i)` throws or what it observes. Nor can the subclass path bypass a subclass's overridden `get(int)`. These are observable semantics, so the ordinary optimized `ArrayList.lastIndexOf` cannot safely replace the method unconditionally.

Public signature, package `com.m3.collections.apache`, serialization UID, null behavior, one-based depth, duplicate-last-match precedence, exception behavior, and all external contracts remain locked. No reflection, type widening, native/JNI code, class relocation or new memory storage is introduced.

## Measured threshold selection (exploratory, non-JMH)

The candidate is gated to `size() >= 2048` to avoid routing small stacks through a new dispatch path. The original `size()` is still read once and reused by the fallback; the existing virtual `get(int)` loop is untouched. Only exact-runtime `ArrayStack` with side-effect-free query equality uses `super.lastIndexOf`.

Local OpenJDK 21.0.12.1, 5 timed samples after two warmup batches, 8 million approximate element probes per sample, same benchmark class and process arguments for both variants; `java -XX:-TieredCompilation -Xbatch`, separate JVMs (not JMH). Three independent sample runs of a deep 4096-element miss yielded median **baseline 5,048.4 ns/search** and median **candidate 4,224.5 ns/search**, approximately **16.3% lower observed search latency**. At 1,024 elements, both variants retain the original traversal; their observed medians were **1,305.2 vs 1,277.2 ns/search** across runs (within ordinary noise). The isolated single-run tiered JVM result for a 4,096-element miss was **5,040.7 vs 3,995.7 ns/search**. Benchmarks are exploratory and machine-specific, not evidence of a universal improvement or full fork performance qualification.

Reproducible standalone benchmark source and raw logs are retained in the execution proof archive, and the opt-in fork qualification file `m3/qualification/arraystack-direct-array-search-20261113/ArrayStackSearchBenchmark.java` contains the identical microbenchmark. Production correctness, not microbenchmark timing, is the hard admission prerequisite.

## Expected patch and proof

The production diff changes only the body of `ArrayStack.search(Object)`; all other production source lines remain identical. Do not change `ArrayList` or public API and do not overlap existing TreeList, queue, Bloom filter, or Eclipse/Guava PR owners.

Required independent proof:
- Run exact unmodified source as a baseline and the candidate under Java 21 `javac --release 21 -proc:none -Xlint:all -Werror`.
- Differentially compare at least empty/null, duplicated and absent values across stack lengths including large arrays, and asymmetric/synthetic mutation-sensitive `equals`.
- Prove subclass virtual `get(int)` retains its original semantics; verify no extra public methods or fields, retained serial UID, and exact source Git blob before and after.
- Run a focused seeded benchmark of the direct-storage fast path against the original `get(i)` traversal; report measured timings with methodology and checksum (no invented improvement claim).
- Add the fork-native JUnit 5 tests alongside a bounded independent test runner. Maven reactor and hosted workflow results are separate mandatory gates.
- Record build, runtime, benchmark, and file hashes; do not merge without the requested contract and performance evidence.

**Status before patch:** PROPOSED; no compiler/benchmark claim. Converge validated algorithm into `hsoliwal/M3JDK21` through the existing collection receiver/recipe after independent class/fork acceptance, not by copying upstream source prematurely.
