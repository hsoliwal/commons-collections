<!-- SPDX-License-Identifier: Apache-2.0 -->
# Donor-first package relocation: Apache array-backed bounded FIFO

Recipe stage 1 works in `hsoliwal/commons-collections` at exact original fork
commit `2d985c27e07bdcbb9516aea44b0dda3f3e487ced`. The original `org.apache.commons.collections4` source
remains unmodified. Two complete Apache-licensed Java sources are added under
`com.m3.collections.apache` and `com.m3.collections.apache.queue`:
`BoundedCollection` and `CircularFifoQueue`. There are NO method/field/body
changes. The queue's only non-package edit updates the import of relocated
`BoundedCollection`.

The portable declarative OpenRewrite `ChangePackage` recipe gives the same
family-level first pass for Apache, Guava, and Eclipse on separate donor working
copies. This bounded source-sealed `M3HashPinnedJavaSnapshotRecipe` admits
only these three additive outputs, with source-only fixed-point/negative gates.
The original Apache fork is the working area, not a second M3 product backend:
M3JDK21 already contains `M3CircularFifoQueue`.

The standalone JDK21 donor vs relocated queue oracle compares public behavior,
logical indexes, duplicates, overwrites, iterator removals, bounded capacity,
null refusal and serialization roundtrips on deterministic randomized rings.
No private JNI ABI or M3String/M3AST changes, no upstream license removal.
The next M3 product gate can use this relocated original as the contract donor,
then refine existing M3 arrays/offsets/masks through separate recipes.

Relocating Java source changes class identity; external users of
`org.apache.commons.collections4.queue.CircularFifoQueue`, reflective
lookups, serialization streams and service descriptors require explicit adapters
or the original upstream library, not a blanket binary drop-in claim.
