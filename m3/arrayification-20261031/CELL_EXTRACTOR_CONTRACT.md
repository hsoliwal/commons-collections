<!-- SPDX-License-Identifier: Apache-2.0 -->
# CellExtractor primitive count storage

The selected existing owner is `CellExtractor.from(IndexExtractor)` at Commons
fork commit `14cbd37dca1b2717e503dfcdf60cfa62ec4c8cc4`. Its anonymous extractor
currently retains a `TreeMap<CounterCell, CounterCell>` and creates a counter
object for every input occurrence, including repeated indices.

The implementation replaces those retained nodes and counters with primitive
index/count/left/right/successor/height lanes. The AVL balancing and successor
pattern is adapted from the existing Apache-licensed
`SparseBloomFilter.IndexStore` in this same fork. Admission remains worst-case
O(log k), traversal remains O(k), and there is no retained object per cell.

The following observable contracts are frozen before the source change:

- Population remains lazy. Empty results are retried on the next call; a
  nonempty prefix is retained after a source exception and is not replayed.
- Input traversal happens once for nonempty results. A false result from the
  input extractor remains ignored, exactly as before.
- Signed int ordering, duplicate aggregation and Java int overflow remain.
- Callback order, early false, exception identity and null behavior remain.
- A source may retain its supplied predicate. Later duplicate callbacks change
  counts without structural invalidation. New keys invalidate an active
  traversal before its next element, but not after an early false or after the
  last already-selected element.
- `asIndexArray()` returns a distinct array on every call. Public interface
  descriptors, default methods and `uniqueIndices()` identity remain unchanged.

Qualification uses the exact SHA-256-pinned original Java source compiled at
its original binary name by the existing `M3ArrayStorageOracle`, independent
TreeMap frequency expectations, existing Bloom filter tests and a dedicated
JUnit case for each behavior group. Array structure and AVL height/order are
also checked. Root Synexia owns the deterministic Maven/OpenRewrite source
recipe, replay and fixed-point evidence; this public fork receives the result.

This is one actual owner conversion. Other linked, hash, trie, reference,
concurrent and subclass-exposed collection owners remain separate work.
There is no JNI change or throughput claim in this packet.

## Executed local qualification

On Temurin OpenJDK 21.0.13+9, Maven 3.9.9 compiled all 325 existing/new test
sources and ran the affected BloomFilter, CellExtractor and IndexExtractor
families: **350 tests, zero failures, errors or skips**. The narrower run had
24 tests (15 original extractor tests plus 9 new original-source proofs).
The 9 new tests include 400 deterministic source shapes, 32,768 monotonic
distinct indices, retained-predicate reentrancy, exact fail-fast boundaries,
source failure prefixes, null/false callbacks, count overflow and public
descriptor parity.

Commands (with normal local proxy/repository settings when needed):

```sh
mvn -B -ntp -Dmaven.compiler.testRelease=21 -Drat.skip=true -Dcheckstyle.skip=true \
  '-Dtest=*BloomFilter*,*CellExtractor*,*IndexExtractor*' test
```

Both exact original and candidate owners also compile with JDK 21
`javac --release 8 -proc:none -Xlint:all,-options -Werror`; only the compiler's
obsolete-release option warning is suppressed. `javap -public -s` outputs are
identical. The complete fork suite, RAT/checkstyle, JNI, JMH and installed M3JDK
image checks are separate gates and are not inferred from these results.
