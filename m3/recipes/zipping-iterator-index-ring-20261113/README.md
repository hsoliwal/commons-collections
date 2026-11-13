<!-- SPDX-License-Identifier: Apache-2.0 -->
# ZippingIterator indexed ring (2026-11-13)

The original `ZippingIterator` copies child iterators into an `ArrayList` and
passes it to `FluentIterable.loop()`. Exhaustion calls `ArrayList` iterator
removal, shifting the remaining references in the list. On repeated laps,
`LoopingIterator` also recreates its list iterator.

The candidate replaces that private storage with a cloned `Iterator[]` and
primitive `int[]` successor/predecessor links. The circular cursor preserves
the original input order, skips exhausted slots and unlinks each such slot in
constant time, clearing its reference for reclamation. The `lastReturned`
reference is retained separately so delegated `remove()` behaves the same.

Public constructors and methods are unchanged. There is no new retained
per-child object, no extra runtime dependency and no serialization format
change. Two primitive index arrays cost more fixed memory than a simple
reference list for very small source counts; do not infer a general throughput
speedup from the structural improvement alone.

## Recipe

The Java 21 source-time transform is sealed to the original file SHA-256
`75e0faa835583fdb7285cd7de29ec5af06b6914e91241e024fd71a62ed9e4418`
and its result
`d8ca6656713d8501bfab42409c81d8eaa8f2a0611c14ddc9e532fcd2f38a24e9`.
The YAML declares the corresponding M3 convergence-workspace OpenRewrite
owner; its external implementation is not asserted to run inside this fork.

```sh
java m3/recipes/zipping-iterator-index-ring-20261113/ZippingIteratorIndexRingRecipe.java \
  --check src/main/java/com/m3/collections/apache/iterators/ZippingIterator.java
mvn -B -ntp -Drat.skip=true -Dmaven.compiler.release=8 \
  -Dmaven.compiler.testRelease=21 \
  -Dtest=M3ZippingIteratorIndexedRingTest,ZippingIteratorTest,IteratorUtilsTest test
```

## Local qualification

- First recipe application: exactly one source change.
- Second `--check`: `changed=false`, byte-for-byte fixed point.
- Production compilation: 367 sources under Java 21 with `--release 8`.
- Tests: 330 sources under Java 21 with `--release 21` (existing test helpers
  require newer APIs). **78 JUnit tests passed**, 0 failures/errors/skips.
- Tests include 256 deterministic reference-vs-candidate scenarios, empty and
  duplicate iterators, mutable child removal, and 4,096 child slots.
- Full project suite, with the complete Java 21 compiler path available to
  source-oracle tests and JaCoCo instrumentation disabled for exact-field
  reflection tests: **22,333 tests, 0 failures, 0 errors, 82 skipped**;
  `mvn -o -B -ntp -Drat.skip=true -Djacoco.skip=true`
  `-Dmaven.compiler.release=8 -Dmaven.compiler.testRelease=21 test`.
- Wall-clock speedup and all possible downstream binary-compatibility effects
  have not been benchmarked or certified; no M3JDK promotion is claimed.

`rat.skip=true` isolates the focused tests from pre-existing RAT debt in three
unrelated `m3scale/qualifications/m3-commons-predicated-bulk-capture-20261018/`
files. Run the full license gate separately before a release.
