<!-- SPDX-License-Identifier: Apache-2.0 -->
# Array-fill AbstractMapBag export atom (Java 21 recipe / Java 8 output)

Target: `src/main/java/com/m3/collections/apache/bag/AbstractMapBag.java`.

The public `toArray()` and `toArray(T[])` signatures are unchanged. For a bag
element with count *n*, use one bounded `Arrays.fill` instead of *n* Java
assignments. The same element iteration order, trailing `null` fill, runtime
`ArrayStoreException`, and non-positive overridden `getCount` skip are kept.
No new collection state is allocated and the source remains Java 8-compatible.

`AbstractMapBagArrayFillRecipe.java` is the executable source-sealed transform:
it accepts only the pinned original SHA-256 or the pinned output, and its second
pass must not change bytes. The YAML describes the convergence workspace's
OpenRewrite recipe owner; that external recipe is **not** claimed to have run
merely because the YAML exists. This fork contains the reproducible candidate.

Run from the repo root on Java 21:

```sh
java m3/recipes/abstract-map-bag-array-fill-20261113/AbstractMapBagArrayFillRecipe.java --check \
  src/main/java/com/m3/collections/apache/bag/AbstractMapBag.java
mvn -B -ntp -Drat.skip=true -Dmaven.compiler.release=8 \
  -Dmaven.compiler.testRelease=21 \
  -Dtest=M3AbstractMapBagArrayFillTest,HashBagTest,TreeBagTest test
```

The test covers HashBag/TreeBag, duplicate/null cardinality, typed and oversized
arrays, runtime store exceptions, empty bags, and overridden non-positive counts.
No serialization field or wire format changed. `rat.skip=true` is used only to
isolate this source atom from unrelated pre-existing release-accounting receipts;
run the full RAT check as a separate mandatory acceptance gate.

## 2026-11-13 local qualification

- Production compile: 367 Java sources using JDK 21 with `--release 8` — pass.
- Test compile: 330 sources with `--release 21` because pre-existing M3
  test helpers use `HexFormat`, `Map.of`, and `Path.of` — pass.
- Focused Maven JUnit: **164 run, 0 failures, 0 errors, 0 skipped**.
- Source-time Java 21 recipe: first pass changed pinned original, second
  `--check` pass changed **0** bytes; output SHA-256
  `32fe4764cf4aa265bf4c838cc1e9efe13f1914945985b303af1d22d13a5282e1`.
- No claimed wall-clock speedup: this is an allocation-free bounded bulk-store
  optimization whose throughput still needs representative benchmark data.

Do not promote into the standalone M3JDK21 or overwrite production Java
collections until recipe fixed-point, JUnit, license and ABI checks have passed.
