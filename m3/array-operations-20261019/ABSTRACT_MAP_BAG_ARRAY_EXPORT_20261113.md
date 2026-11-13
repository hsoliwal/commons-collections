<!-- SPDX-License-Identifier: Apache-2.0 -->
# Commons Collections AbstractMapBag: span-based repeated-count array export

**Direct fork target:** `hsoliwal/commons-collections@1c3d291123d3e991e982005c6bc1ee91dc682fad`, `src/main/java/com/m3/collections/apache/bag/AbstractMapBag.java`, existing full Git blob `0533f34f26feaae4f912d1ab426edcb10470d6cb`.

## Semantic contract (locked)

Optimize the two existing arrayification methods, `toArray()` and `toArray(T[])`, without replacing the subclass-supplied backing `Map<E,MutableInteger>`, changing iteration ordering, count arithmetic, `getCount` overrides, serialization or public method signatures. Each distinct element retains one `getCount` call. For a repeated run >=8 elements that fits the remaining destination array, fill its contiguous span with `java.util.Arrays.fill` and advance the output offset once; if a run exceeds the available output space, retain the original one-by-one loop to preserve exception and partial-write behavior. Typed arrays retain runtime store checks and the exact caller-provided component type. The typed array's **entire unused suffix** must remain nulled as in the original code, now using `Arrays.fill`.

No new stored array, wrapper, helper class or runtime dependency; source remains Java 8 compatible. Original ASF Apache-2.0 notice remains. This changes the existing fork class, not M3JDK.

## Executed bounded preflight

An independently compiled Java 21 original/candidate **exact-loop** witness passed `javac -Xlint:all -Werror` and **210,000 parity checks**, including varying repeat counts, null keys, typed arrays with incompatible values and failure-prefix comparison, undersized/oversized arrays and tail nulling. Seven sample performance ratios on a duplicate-heavy 16,384-element array ranged from 0.627 to 1.187; performance is variable, so **no universal speedup is admitted**. A real Commons JUnit5 test and full Maven reactor remain mandatory and NOT_RUN locally; the standalone witness does not qualify the product class. Keep this PR a review draft and do not merge to default without complete fork checks.

Original full-source SHA-256, candidate SHA-256, exact Git blob and source-diff receipts will be appended after branch write. Unexpected non-atom changes hard fail.

## Second family member: AbstractMapMultiSet

The same per-occurrence expansion also exists in `src/main/java/com/m3/collections/apache/multiset/AbstractMapMultiSet.java` (original full Git blob `3a6b8ea53295fdab5351f6e35948b4f454690040`). Its two methods use `entrySet()` to retrieve `MutableInteger.value` rather than `getCount`; preserve this distinction. Introduce the exact same bounded `Arrays.fill` span for runs of at least eight while keeping the original overflow/typed-array failure loop and full trailing-null behavior. Add an independent fork-native `HashMultiSet` JUnit test, including repeated element identity, null and typed array incompatibility. No change to source entries, storage ownership, protected map exposure or serialized form. This PR now owns **two production classes**, each with exactly two changed array-export methods; no unrelated collection owner is in scope.
