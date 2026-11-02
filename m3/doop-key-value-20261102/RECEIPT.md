<!--
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# DOOP key/value source and native-qualification receipt

Flat3Map key and value iterators read the owner slots directly. The original Java
outputs remain byte-identical to [canonical source commit cfa05713fe53](https://github.com/hsoliwal/com.synexia/commit/cfa05713fe5395694af36df74373dcfe1e7e9031),
received in commit `b98edfb8930b06525a754a135110f236f9d26f21` from parent
`7f942ecd4a80a1904ca9657e91b634e98e9c3ed6`.

## Native license-gate repair

The two passive metadata changes below are exact outputs of the existing
`M3HashPinnedTextSnapshotRecipe` dataset
`doop-key-value-commons-license-20261102` in [canonical commit 905af4762e79](https://github.com/hsoliwal/com.synexia/commit/905af4762e794c2ad36cbf418f14c3ff29323df1).
Their receiving parent is `b98edfb8930b06525a754a135110f236f9d26f21`.

The candidate TSV gains an SPDX Apache-2.0 comment, and its adjacent README
documents leading metadata comments. All 196 original TSV bytes remain intact.
This is an explicit comment-prefixed format convention; header-first TSV consumers
must skip leading `#` metadata lines. No existing consumer of this ledger was found.

## Executed verification

Strict RAT passes with 764 approved and zero unapproved files, with no new
exclusion or skip. The unchanged selected native lifecycle compiles 367 main and
324 test sources and passes all 234 tests: 230 original Flat3Map cases and four
new regression cases, with zero failures, errors or skips. Runtime and test
release are Java 21. The original POM, dependency declarations, production and
test source files remain unchanged by this metadata repair.

## Exact passive SDK output blobs

| Path | Git blob |
|---|---|
| `m3/arrayification-20261031/CANDIDATES.tsv` | `c6a06690d045578d6f9a60c1870d8e288ae1ef6b` |
| `m3/arrayification-20261031/README.md` | `493b7cd57a4120b0154b9d1ca3e57ec427c2bc63` |

## Acceptance scope

Public entry iteration and removal invalidation retain their existing contracts.
Other retained map owners, including the protected AbstractHashedMap entry
family, remain separate work. The full default clean verify, PMD, Checkstyle,
Japicmp and Javadoc sequence was not executed by this bounded repair. This
receipt does not claim whole-repository or fleet-wide conversion.
