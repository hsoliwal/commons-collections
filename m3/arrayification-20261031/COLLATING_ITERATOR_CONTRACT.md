<!-- SPDX-License-Identifier: Apache-2.0 -->
# CollatingIterator fixed array and presence-mask atoms

The existing ordered merge keeps its cached child values in a private
`ArrayList` with a fixed size established when iteration begins. The child
iterator list is separately exposed through an unmodifiable live view and
remains unchanged.

Replace only the fixed cached-value list with a parent-owned `Object[]`, and
replace the scan of every possible `BitSet` position with `BitSet.isEmpty()`.
The selected minimum still uses the original serial comparator sequence.
Child callback order, duplicate tie ordering, null payloads, throwing and
reentrant callbacks, removal delegation, start/registration locking and the
lazy comparator null check are frozen contracts. Cached values are released
at the same point after each successful `next()`.

The exact original source is retained and SHA-256-checked by the existing
`M3ArrayStorageOracle`. Original-source trace comparison and the owning
`CollatingIteratorTest` qualify this operation. The common merge-k-sorted-
streams problem category does not license changing callback counts or tie
order to a heap implementation. No challenge-site source is copied.

This change removes one private list wrapper per collator and the padded
per-bit presence scan; it makes no measured throughput claim. There is no
new public API, runtime dependency or native ABI.

## Executed local qualification

The combined Commons packet ran **376 Maven/JUnit tests with zero failures,
errors or skips** on Temurin OpenJDK 21.0.13+9 and Maven 3.9.9. This includes
21 existing CollatingIterator tests and 5 new original-source differential
tests. The latter compare 180 deterministic sparse/dense, null/duplicate
merge scenarios (including 129 child iterators), exact comparator/child
callback traces, partial cached state after exceptions, reentrant hasNext,
remove delegation, registration locks and cached-reference release.

```sh
mvn -B -ntp -Dmaven.compiler.testRelease=21 -Drat.skip=true -Dcheckstyle.skip=true \
  '-Dtest=*BloomFilter*,*CellExtractor*,*IndexExtractor*,CollatingIteratorTest,M3CollatingIteratorArrayStorageTest' test
```

Both exact original and candidate CollatingIterator classes compile with
JDK 21 `javac --release 8 -proc:none -Xlint:all,-options,-this-escape -Werror`;
the original constructor calls overridable `setComparator`, so both versions
have the same existing `this-escape` warning, explicitly excluded together
with the obsolete-release option warning. The public `javap -public -s`
descriptors are identical. Complete fork tests, skipped
RAT/checkstyle gates, JNI and measured performance remain separate evidence.
