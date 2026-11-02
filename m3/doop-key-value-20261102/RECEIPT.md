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

# DOOP key/value source receipt

Flat3Map key and value iterators read the existing owner slots without temporary entries. Public entry iteration and removal invalidation retain their contracts.

Canonical recipe and qualification: [Synexia commit cfa05713fe53](https://github.com/hsoliwal/com.synexia/commit/cfa05713fe5395694af36df74373dcfe1e7e9031).

Receiving parent: `7f942ecd4a80a1904ca9657e91b634e98e9c3ed6`. Canonical datasets: `doop-key-value-commons-20261102`. All Java files below are actual verified SDK Result outputs.

## Executed verification

234/234 focused tests; 230 unchanged originals and four new cases. Independent callback/removal probes matched; Java 8 source compatibility passed.

## Exact output blobs

| Path | Git blob |
|---|---|
| `src/main/java/com/m3/collections/apache/map/Flat3Map.java` | `4046f237fef3e342d6cb0db7e905b3bf87fadbb6` |
| `src/test/java/com/m3/collections/apache/map/M3Flat3MapProjectionTest.java` | `31e1cb99405155f8720fc8f21aa34a30bede6289` |

## Acceptance scope

Full native lifecycle remains blocked by the pre-existing RAT finding on m3/arrayification-20261031/CANDIDATES.tsv. Other retained map owners remain outside this finite change.

The existing broader branch acceptance gates remain in force. This receipt does not claim whole-repository or fleet-wide conversion, universal throughput gains, or default-branch integration.
