<!-- SPDX-License-Identifier: Apache-2.0 -->
# TreeList: reserve bulk array capacity once

This changes the existing array-backed `TreeList` in the Commons Collections
fork, not a wrapper or another collection. Base: `1c3d291123d3e991e982005c6bc1ee91dc682fad`.
The original ASF license header, existing modification attribution and tree
algorithms remain intact.

## Implementation

A sized constructor or `addAll(Collection)` formerly grew six lanes repeatedly
while recursively building its incoming subtree. One primitive `freeCount`
now accounts for slots available for reuse. The build reserves only its new
high-water slots before acquisition. `ensureCapacity(long)` computes exactly
the old geometric endpoint, then copies each lane once. All six local arrays
must allocate successfully before any field is published. Single insertions
still grow geometrically, and clear resets the counter with the released lanes.
The existing acquisition journal handles iterator exceptions and reentrant
insertions; no payload, link, rank or state representation is replaced.

Trade-off: reservation happens earlier. A source that throws early or exaggerates
its size can retain more spare capacity or encounter allocation failure earlier
than before. The public contents and callback sequence are preserved in the
executed cases. This is not a universal latency improvement or heap-size claim.

## Executed on the exact production source

OpenJDK 21.0.12.1 (Linux), `javac --release 21 -proc:none -Xlint:all -Werror`.
Both the baseline and candidate use the actual fork `OrderedIterator`.
No production dependency stubs or substitute model implementation are used.

* Baseline contract replay: 80,000 seeded transitions, 645,897 assertions.
* Candidate: the same 80,000 transitions, 646,694 assertions (the new free counter
  adds checks), both normal JVM and `-Xint`.
* All three behavioral output digests: `10663051697907488657`.
* Reservation regression first failed on baseline (`8 < 9`) and passes on the
  candidate for batches through 100,000 entries and 9,900 reused slots.
* Throwing iterators, Error identity, reentrant insertion before failure,
  self-add, nulls, both join-height regimes, iterator edits, sublist removal,
  rollback/refill, clear and overflow are covered.
* A separate `-Xmx32m` process forces bulk allocation failure; the candidate's
  six original lane identities remain unchanged and the list remains usable.
* `javap -protected -s` outputs for TreeList and TreeListIterator match exactly.
* Java 8 source/API targeting also compiles on Java 21 with
  `--release 8 -Xlint:all,-options -Werror`; only obsolete-target option warnings
  are disabled. This is not execution on a Java 8 runtime.

## Per-thread allocated bytes, not a timing benchmark

ThreadMXBean; 100,000 incoming cached Integer references; 3 warmups + 7 samples;
128 MB heap; separate baseline/candidate JVMs. All seven samples within each
row were identical. Input construction and existing-list preparation are
outside the measurement. Final capacities match in every row.

| Workload | Baseline bytes | Candidate bytes | Final capacity |
| --- | ---: | ---: | ---: |
| Constructor | 7,551,672 | 2,783,232 | 132,387 |
| Append to 8-entry list | 7,551,624 | 2,783,416 | 132,387 |
| Append requiring one original growth | 3,974,872 | 3,974,872 | 198,580 |
| Reuse 100,000 released slots | 400,328 | 400,328 | 132,387 |
| 100,000 individual insertions | 7,151,536 | 7,151,536 | 132,387 |

The optimized constructor allocates 4,768,440 fewer bytes (~63.1%) in this
observation. The benefit is elimination of intermediate growth, not lower
final array capacity. One extra int is retained in the slot owner; owner
alignment effects are JVM-specific. No throughput or JMH claim is made.

## Reproduce

From the fork checkout, with Java 21 on PATH:

```sh
bash m3/qualification/treelist-bulk-capacity-20261113/verify.sh allocation
mvn -Dtest=TreeListTest,TreeListBulkCapacityTest test
```

The first command was executed here. The second is the native JUnit integration
command and was **not executed here**: Maven and its dependencies are unavailable.
The JUnit adapter invokes the same independently authored regression body.
Full fork Maven/JUnit, RAT/Checkstyle, hosted CI, canonical Maven/OpenRewrite
qualification, M3JDK21 integration, installed JDK/JMOD/JNI/GC/jtreg/Windows and
benchmark acceptance remain unclaimed. No private Synexia source or fixtures
are part of this change.
