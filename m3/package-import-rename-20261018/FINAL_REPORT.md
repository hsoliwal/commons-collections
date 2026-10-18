<!-- SPDX-License-Identifier: Apache-2.0 -->
# Commons Collections package rename: final local result

The recursive package rename from `org.apache.commons.collections4` to
`com.m3.collections.apache` is implemented across the fork source, imports,
qualified Java references, active build metadata, resource paths and current API
links. Class and method names and all 367 production implementation token
sequences are preserved after the admitted namespace normalization. The qualified
OpenRewrite 8.17.1 source recipe processed 681 inputs with 679 changes. Two
preexisting relocated targets were admitted only after mapped-byte equality.

The unchanged complete suite passed: **22,257 tests, zero failures, zero errors,
and 82 inherited conditional skips**. No exclusions or skip annotations were
added. `FULL_TEST_RECEIPT.json` and the unchanged `FULL_TEST_LOG.txt` are the
primary evidence. Verification used Maven 512 MiB, one Surefire fork with 1024
MiB, `-Drat.skip=true` and `-Dmaven.compiler.testRelease=21`. The default lifecycle
still has five preexisting RAT metadata license failures; the existing fork proof
requires Java 21 syntax. A preceding 512 MiB test fork exhausted memory in the
unchanged Bloom test; its failure record remains. The original complete baseline
was interrupted for shared-host resource pressure and is not claimed as passing.

The actual Maven jar contains 635 relocated classes, the relocated automatic
module name and all 20 public OSGi package exports. Class payloads match the
successful test build. A standalone JDK-only namespace smoke passed; original
namespace production classes are absent. All 197 serialized test resources moved
with unchanged bytes. A test-only object-input resolver reads their historical
class descriptors; this does not claim unchanged production wire names.

The active package-first workflow uses immutable original sources and oracle
from `6404dfdfe553097bb934e044034abcec5a4dbdd0`, separately compiled from relocated
owners. Its genuine differential passed 65,711 checks. The mechanically renamed
current standalone main now explicitly refuses a same-class comparison, so it
cannot report false differential success. The immutable original workflow remains
unchanged by this review correction.

After the full suite, a separately sealed review crate corrected only qualified
Javadoc links in 13 package-info files and added the unused proof-main guard.
The incumbent Java snapshot owner applied 14 exact targets with lossless parsing,
fixed point and drift, missing and duplicate refusal controls; occupied-target
control is inapplicable to an update-only crate. A separate incumbent text
snapshot updated the current staging README. Focused JDK javadoc reference
validation passes for all 13 packages; strict `javac -Xlint:all -Werror` passes for
the proof, whose main exits nonzero with the expected refusal before any PASS
output. See `REVIEW_VALIDATION.json`, `REVIEW_JAVADOC.txt` and
`REVIEW_PROOF_REFUSAL.txt`. These later focused checks do not claim another full
suite or archive rebuild. Historical test logs were not rewritten.

The source/evidence set is frozen locally in the isolated branch. The preserved
base includes `a004341c18470deb6c7779e70854c96bda954fdb`. Exact owner and provider
references are in `PROVENANCE.tsv`; execution limitations and timing are in
`RUN_CONTEXT.tsv` and `VERIFY_CONTRACT.tsv`; output scope is in
`OUTPUT_CONTRACT.tsv`. No remote writes were performed. Parent-owned independent
review and publication remain outside this local implementation handoff.
