<!-- Copyright 2026 Hitesh Soliwal and contributors. SPDX-License-Identifier: Apache-2.0 -->
# StaticBucketMap: segmented mappings instead of retained Node chains

This is an actual storage conversion of the existing Commons scratch-fork class,
not a parallel map, adapter, FIFO check or M3JDK21 receiving change. Keep draft.

Base: 7570339b6da117f104f284f4c92bb790ca2add26.
Original production blob: 6e15db7480ad4f9b0a22e753dab980ad5ffe7a57.
Candidate production blob: f42167c553766101c04743a8ebee3ca988faa671.
Candidate SHA256: ec39bc0bbe617186a957be456f25aa6c97e87e47ecc70b355c98069b984bd3b5.

## Storage and compatibility

Normal mapping storage uses 64-slot segments with Object[] key/value payload and
long[] successor/state/free coordinates, plus int[] bucket heads. Existing bucket
monitors and hash distribution remain. A shared atomic high-water allocator grows
stable pages; per-bucket free coordinates reuse removed slots. No Node is allocated
by normal put/get/replacement, and no Node chain is strongly retained by the owner.

Nodes remain ONLY as lazy public Map.Entry/KeyValue projections when iteration
exposes mappings. Weak registrations preserve identity while clients hold entries.
Entry values remain live, and removed entries detach rather than binding to reused
coordinates. Iterators retain the original per-bucket snapshot behavior and reverse
encounter order. They can allocate projections even for key/value iteration; this
is a disclosed cost, not a claim of allocation-free traversal.

User callbacks may reenter bucket operations. Retired coordinates are not reused
until the outer operation exits. A reachable-again bit preserves suffixes which the
original implementation can relink after reentrant removal/clear. The existing
count inconsistencies in those exotic cases are preserved, not newly repaired.
Projection reads do not acquire another bucket's monitor. Publication of exposed
entry writes uses an internal arena lock without user callbacks.

## Executed on complete exact sources

OpenJDK21.0.12.1 Linux, Java8 API/source target. The original owner and all nine
actual dependency files were checked against their Git blob identities; no stubs.
The original closure has a pre-existing this-escape warning in
EntrySetToMapIteratorAdapter.reset() and a raw generic array in the old map.
Baseline compilation uses -Xlint:all,-options,-rawtypes,-this-escape -Werror;
candidate also passed without the rawtypes exclusion. These exclusions are not
reported as repaired warnings. This is not a Java8-runtime qualification.

Final baseline and candidate each pass 593338 assertions normally and under -Xint,
with digest 5e0692d132fb41b0. Includes 96000 seeded mixed map operations, collision
chains, nulls, views, entry identity/detachment, overwrite-reference release,
mapIterator and six-thread insert/read/remove/refill. The structural test is RED
on baseline (retained Node roots) and GREEN on candidate (none before exposure).
Both versions pass 100 cross-bucket callback/entry-getter rounds. A separate
168-case reentrant trace matches byte for byte. Public/protected javap descriptors
for StaticBucketMap match. This finite corpus is not proof of all reentrant or
concurrent programs; no jcstress, full provider suite or release gate is claimed.

Corrections made before publication: the first projection retained an overwritten
value; a regression detected it. The first deferred-recycle candidate cleared
suffixes resurrected by three reentrant scenarios; differential traces detected
that, and the reachable-again state fixed it. Counterexamples and rejected bytes
remain in the private authoring/evidence packet, not public recipe sources.

## Measured wins AND costs

Java instrumentation counts map-owned structural objects, excluding shared Integer
key/value payload and untraversed JDK internals below reference queues. An additional
column includes externally held entry projection objects. This is a bounded object
size observation, not retained-heap dominator analysis or a throughput benchmark.
255 buckets, interpreter execution, fixed input references:

| Entries | Old structure | New before exposure | New after exposure | New including held entries |
| --- | ---: | ---: | ---: | ---: |
| 0 | 6184 | 10320 | 10320 | 10320 |
| 1 | 6208 | 11424 | 11760 | 11792 |
| 256 | 12328 | 14688 | 24096 | 32288 |
| 4096 | 104488 | 80328 | 230856 | 361928 |
| 65536 | 1579048 | 1128696 | 3537144 | 5634296 |

At 65536 mappings, pre-exposure structure decreases about 28.5%. After clear and
refill with the same keys, measured thread allocation is 1572864 -> 0 bytes for
those puts under -Xint, excluding input creation and clearing. This is possible
because the new arena retains reusable capacity. Unlike old linked nodes, its
allocated pages are not released by clear. Bucket-local free slots cannot directly
serve another bucket. Small maps and entry-heavy use can consume MORE memory,
and exposed-entry writes can contend on the internal arena. No universal speedup,
latency/GC/JNI/Windows result or performance admission is asserted.

## Reproduce / remaining gates

Compile the real owner, AbstractIterableMap, EntrySetToMapIteratorAdapter,
IterableMap, IterableGet, Get, Put, MapIterator, ResettableIterator and KeyValue,
plus StaticBucketArrayContract and StaticBucketEntryConcurrency, with the flags
above. Execute com.m3.collections.apache.map.StaticBucketArrayContract normally
and with -Xint; run it with the storage argument for the array-ownership check.
Run StaticBucketEntryConcurrency for the entry-callback lock-order regression.
The included StaticBucketArrayTest wires these bodies into the native JUnit suite.

mvn -B verify was attempted locally but could not start: mvn: command not found.
The JUnit adapter, full Maven/RAT/Checkstyle/hosted CI and canonical Maven/OpenRewrite
execution/fixed-point/refusal have NOT passed here. The existing Synexia hash-pinned
recipe host was selected and its dataset authored locally; canonical publication
and actual execution remain pending. No recipe/preimage/instrumentation enters
this public fork. Author self-review only; independent review remains required.

Original Apache source header, LICENSE and NOTICE remain intact; the source change
notice is additive. New test/receipt expression is Apache-2.0. No third-party
implementation body was newly imported. No other collection source, M3JDK21 file,
default-branch merge, installed SDK/JMOD/JNI/GC/jtreg/Windows qualification is claimed.
