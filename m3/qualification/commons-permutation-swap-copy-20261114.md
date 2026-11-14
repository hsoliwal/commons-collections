<!-- SPDX-License-Identifier: Apache-2.0 -->
# PermutationIterator: cached ordinal movement and bulk snapshot copying

## Actual collection-fork change

Optimize the existing `com.m3.collections.apache.iterators.PermutationIterator`.
The original primitive keys/directions and parent-owned payload array remain.
One private int packs the largest-key position and a canonical-result flag. When
the largest key is mobile, avoid scanning all keys. Copy the unexposed canonical
ArrayList snapshot in bulk and rewrite the two adjacent changed positions, rather
than decoding every payload. The first build still decodes keys: a collection's
iterator and toArray can expose different images. The public next method retains
the incumbent small-input algorithm for fewer than four keys.

Each returned list remains a separate mutable ArrayList. No result-array allocation
is removed: complete output materialization remains O(n). This is not a claim of
O(1) total time per permutation. No extra retained arrays, per-element nodes,
wrappers, dependencies or native code are introduced. The new int fitted existing
object padding on the tested JVM; both owners measured 32 shallow bytes. Other
JVM layouts may differ. Original ASF licensing and contributor notices remain.

Problem category: incremental adjacent-swap permutation generation. Reuse the
incumbent Apache Steinhaus-Johnson-Trotter implementation and JDK ArrayList bulk
copy operation. A full inverse-position array was rejected for retained-memory
cost; sharing caller-exposed results was rejected for aliasing. No challenge or
third-party implementation code was copied.

## Exact sources and publication base

Inspected source at `9364705b7fd39ff9112a886de4d932e6aba92d65`; unchanged owner
blob rechecked on newer master `7cabef47584539dc355ce98d4e2d06d4a7a9cf6a`.
The latter is the publication parent, preserving merged PR36 and PR53 work.

| Source | Git blob | SHA-256 |
| --- | --- | --- |
| Original owner | `8a72b56f537a482d702e7765c375592f2ddbaefe` | `1bbd649c9f5bc5dc714114cc8afe4993faceb6f4f5bc00127506bd13dbc43ed6` |
| Candidate owner | `c5ed7c4fa8916082dfbe8e7f041d570c28520d71` | `572679113c75c468e0f14534f0f584ee60d3418188be8c945e2718e2da179c59` |
| Independent executable contract | `dab857769273cca46af2c2691c385b3b59cb0085` | `0b389e188d486445c5c742096bb55a80da5ac0eee568b71113da608e26bd4625` |

## Executed against complete real owner sources

Linux OpenJDK 21.0.12.1. This owner has only JDK dependencies; no substitute or
normalized dependency implementations were needed. Strict Java 21 compilation
uses `--release 21 -proc:none -Xlint:all -Werror`. Java 8 targeting uses
`--release 8 -proc:none -Xlint:all,-options -Werror`; execution was on Java 21,
not a Java 8 runtime.

- Original and final candidate each pass 92,515 outputs / 1,373,334 assertions,
  both normal JVM and interpreter; fingerprint `97c014c32cba481e`. Candidate
  release-8 classes pass the same full contract on Java 21.
- An independent insertion recurrence checks complete permutation order for all
  sizes 0 through 8. Nulls, repeated payload identity, mutable returned lists,
  source mutation, exhaustion and remove exceptions, divergent iterator/toArray
  images, short inputs, subclass hasNext exceptions and nested next are covered.
- Separate real-source differential: 2,592 irregular size/direction/input/toArray
  configurations, including exception classes/messages and constructor callbacks,
  fingerprint `f1cade21ed229c49` with full implicit-exception diagnostics enabled.
- Larger-input differential: 81,000 outputs at sizes 9, 32, 256 and 1,024; all
  output elements contribute to matching fingerprint `d64e57a9c032971d`.
- Public/protected javap descriptors match exactly. Original storage references
  remain; one private packed int and one private helper are added.
- Disposable instrumentation first fails on the original. For 63 next calls on
  64 elements, full-key loop iterations fall from 8,064 to 64, and payload
  decodes from 4,032 to 188. Candidate passes normal and interpreter work checks.
- Five faulty variants are rejected: copying the initial toArray image, stale
  largest position, sharing the exposed result, omitting direction reversal and
  retaining a stale canonical flag after allocation failure.
- Sixty injected result-allocation failures followed by resumption match baseline
  fingerprint `39657d26ee85fe75`. This is deterministic fault injection, not proof
  of identical heap-exhaustion thresholds or all asynchronous JVM failures.

These are assertion observations, not 1.3 million distinct JUnit tests. The native
JUnit adapter is authored, but JUnit/Surefire was not executed in this session.

## Scoped timing and allocation diagnostics

Three fresh JVMs per variant and input size; six warmup batches and seven retained
samples per process; alternating variant order. `-Xbatch -Xms128m -Xmx512m`.
Input and iterator construction are excluded; next calls, escaping result lists
and consumed endpoints are included. Values are medians of process medians in
thread CPU nanoseconds per next call. Checksums match. This is not isolated JMH.

| Elements | Original ns | Candidate ns | Original bytes/call | Candidate bytes/call |
| ---: | ---: | ---: | ---: | ---: |
| 2 | 78.239 | 38.245 | 24.003 | 24.003 |
| 3 | 37.666 | 39.985 | 46.668 | 46.668 |
| 8 | 108.435 | 50.862 | 71.999 | 71.999 |
| 32 | 223.692 | 93.220 | 168.002 | 168.002 |
| 256 | 1,483.687 | 192.375 | 1,064.003 | 1,064.003 |
| 1,024 | 5,570.429 | 899.786 | 4,136.011 | 4,136.011 |

Tiny timing is not settled: the unchanged two-element baseline varied from about
23.6 ns in an earlier sweep to 78.2 ns in the final sweep. The final three-element
case is about 6.2% slower despite retaining its original algorithm. Do not infer
universal speedup or a blanket non-regression result. Earlier cached-small-path
candidates were rejected; their raw results remain in the conversation evidence.
Larger cases reduce repeated scalar work; required output allocation is unchanged.

## Reproduction and explicit remaining gates

From the fork root with Java 21:

```sh
mkdir -p target/permutation-check
javac --release 21 -proc:none -Xlint:all -Werror -d target/permutation-check \
  src/main/java/com/m3/collections/apache/iterators/PermutationIterator.java \
  src/test/java/com/m3/collections/apache/iterators/PermutationIteratorArrayContract.java
java -cp target/permutation-check com.m3.collections.apache.iterators.PermutationIteratorArrayContract
java -Xint -cp target/permutation-check com.m3.collections.apache.iterators.PermutationIteratorArrayContract
mvn -B -ntp -Dmaven.compiler.testRelease=21 \
  -Dtest=PermutationIteratorTest,PermutationIteratorArrayTest test
```

The javac/standalone checks were executed. The Maven command is an unexecuted
native integration entry point: Maven is unavailable in this runtime. Full fork
Maven/JUnit/Checkstyle/RAT, hosted CI and independent review remain open for these
new bytes. Predecessor receipts are not reused as new acceptance.

A local hash-pinned Java transformation emitted the source; actual canonical
Maven/OpenRewrite execution remains NOT_RUN. No private recipe, preimage fixture,
registry or authoring tool is included in this public change. Keep this draft.
No master merge or M3jdk21 modification; no installed JDK/JMOD/JNI/GC/jtreg/Windows,
all-collections-complete, or production benchmark acceptance is claimed.
