<!-- SPDX-License-Identifier: Apache-2.0 -->
# Counting Bloom filter: avoid empty and sparse full-array clear writes

## Source change

Continue existing Commons Collections draft PR #36 at inspected parent
`39a56a7cded3003da69349591d625c1c87b29fc6`. Only the existing
`ArrayCountingBloomFilter.clear()` implementation changes. All original source
bytes outside that method, including ASF licensing and contributor notices, are
preserved. No new retained fields, arrays, objects, public APIs or dependencies.

The existing `nonzeroCount` selects an empty fast path or, at occupancy no greater
than `cells.length / Long.SIZE`, a traversal of the existing nonzero mask. Sparse
clear writes only occupied counters (including negative values) and occupied mask
words. Denser filters retain the original two `Arrays.fill` calls. Positive-only
cardinality is not used to detect emptiness. Both counters are reset and the
permanent invalid-state history is deliberately retained. Array identity and
capacity do not change. Existing concurrency guarantees do not change.

## Exact source identities

| Source | Git blob | SHA-256 |
| --- | --- | --- |
| Original owner | `060c759fb812c24cd1a1f777835181f83b053707` | `b52c872af9ac1b29318ca3ad2ab03a927e00ff0535ca90a05811b84df658a8d7` |
| Candidate owner | `517bd8ee7b16e3e8b45e738c0b5ba9310c3f23c3` | `9109c36ebf4aabe3d96ff69e896a0b26901255aca9f9ebce25cbf3eb0bcddd00` |
| Independent contract | `57b5189417093d137c8ba1bb881de68bb3d078c5` | `185ef6b3ee3318d4fbbfceceeac7bf88d3e0730699cce8264cb547b96134ae6c` |
| Native JUnit entry | `5ece1d5b44c5a0821aa44a71eb7fee4c73d4a2c0` | `9b5f1b4dc88a1abc0d21cfa76818412fce4fa5920c4c74fd0ca3bc52b31999d7` |

## Executed verification and its limits

Linux OpenJDK 21.0.12.1. The complete original and candidate target owner bytes
were compiled, not a substitute filter model. The independently authored contract
compares them to an integer-array model and reads their actual storage invariants.
**Compile scope:** eleven dependency implementations were copied with comments and
formatting normalized; they were not original-byte full-repository inputs. This
was a scoped source-closure run, not compilation or testing of the complete fork.

Existing overload warnings in dependency interfaces require
`-Xlint:all,-overloads -Werror` for that dependency-only compile. Target owner and
contract compile separately with `--release 21 -proc:none -Xlint:all -Werror`,
without that exclusion. Java 8 source/API targeting also passes, excluding only
obsolete options warnings for the owner/contract; the resulting classes executed
on Java 21, not on a Java 8 runtime.

- Normal original and candidate: **322,353 transitions and 13,769,087 assertion
  checks each**, identical digest `c54a1e6927a8994d`.
- Interpreter original and candidate: **291,153 transitions and 10,310,559 checks
  each**, identical digest `ccd07a6d9c5a49c6`. All boundary cases are retained;
  random steps per shape are 100 instead of the normal run's 4,000.
- The same full normal result passes with Java 8-target candidate classes.
- Public and private `javap -s` declarations match exactly; no retained-field or
  method-shape change.
- Boundary/threshold tests include positive/negative-only counters, min/max integer
  values and overflow, last bits of partial words, repeated clears, clone
  independence, clear/refill, bad-index partial updates, producer exception
  identity, permanent invalidity, and clearing/refilling during live cell/index/
  bitmap callbacks.
- Four deliberately incorrect variants are rejected: using positive cardinality
  to skip negative-only counters; resetting permanent invalidity; leaving mask
  words uncleared; and clearing the wrong last bit of a word.

These are assertion checks, not millions of separate JUnit tests. The new native
JUnit entry invokes the same contract, but **JUnit/Surefire was not executed**.

## Deterministic work regression

Disposable, non-shipped instrumentation counts actual stores inside the original
and candidate `clear()` bodies. On an empty 65,536-cell original, the regression
fails with 65,536 counter writes instead of zero. The candidate passes:

| Occupied counters / 65,536 | Counter writes | Mask-word writes |
| ---: | ---: | ---: |
| 0 | 0 | 0 |
| 8 (positive or negative) | 8 | 8 |
| 1,024 (sparse boundary) | 1,024 | 1,024 |
| 1,025 (dense fallback) | 65,536 | 1,024 |
| 65,536 negative counters | 65,536 | 1,024 |

Sparse work is O(mask words + occupied counters); empty work is O(1). The original
and dense fallback are O(counter capacity + mask words). Successful final storage
and retained capacity are unchanged; this is not a retained-memory reduction.

## Scoped timing diagnostics, including regressions

Three fresh JVMs per original/candidate workload; six warmup batches then seven
measurement batches each; `-Xbatch -Xms128m -Xmx512m`. Values below are medians of
per-process medians in thread-CPU nanoseconds per clear. Public-API refill is
outside timing; a bank of 32 filters (eight for the largest shape) is timed, and
results are consumed. Both versions measured **zero allocation per clear**.

| Capacity | Occupied | Original ns | Candidate ns | Candidate CPU change |
| ---: | ---: | ---: | ---: | ---: |
| 64 | 1 | 19.161 | 12.325 | -35.68% |
| 4,096 | 8 | 196.389 | 41.127 | -79.06% |
| 65,536 | 0 | 3,307.964 | 11.025 | -99.67% |
| 65,536 | 8 | 3,188.061 | 248.889 | -92.19% |
| 65,536 | 1,024 | 3,230.509 | 1,247.560 | -61.38% |
| 65,536 | 1,025 | 3,187.493 | 3,468.542 | **+8.82%** |
| 65,536 | 65,536 | 3,830.029 | 4,031.195 | **+5.25%** |
| 1,048,576 | 8 | 114,757.073 | 3,757.339 | -96.73% |

The sparse cases improve in these measurements, but **dense results regress**.
This candidate is not a universal speedup or non-regression acceptance. Empty
measurements approach measurement/dispatch overhead. These are bounded diagnostics,
not JMH, production workload evidence, or full benchmark acceptance. Raw samples
and probe source remain in the accompanying conversation evidence bundle.

## Reproduce and admission status

Using already compiled production classes, compile and run
`src/test/java/com/m3/collections/apache/bloomfilter/CountingBloomClearContract.java`:

```sh
javac --release 21 -proc:none -Xlint:all -Werror -cp target/classes -d target/clear-check \
  src/test/java/com/m3/collections/apache/bloomfilter/CountingBloomClearContract.java
java -cp target/classes:target/clear-check com.m3.collections.apache.bloomfilter.CountingBloomClearContract
java -Xint -cp target/classes:target/clear-check com.m3.collections.apache.bloomfilter.CountingBloomClearContract 100
mvn -B -ntp -Dtest=CountingBloomClearTest test
```

The Maven command is an integration entry point, **not a passing receipt**. This
session has no Maven/dependency resolution. Whole-fork native tests, Checkstyle,
RAT, hosted CI and independent review remain unverified for the new commit.
Previous receipts for predecessor commits do not qualify these new bytes.

A private local Java source-pinned transformation generated the candidate. A
three-source dataset for the existing private collection-refinement host is
**authored locally only**; canonical Maven/OpenRewrite execution remains NOT_RUN.
No private materializer, recipe, templates, preimages, instrumentation or master
registry is included in this public change. Author self-review only.

Keep PR #36 draft. No merge or M3jdk21 change; no installed JDK, JMOD, JNI, GC,
jtreg, Windows or all-collections-complete acceptance is claimed.
