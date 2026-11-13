# CircularFifoQueue array-ring membership and removal qualification

Status: source and tests committed to the actual Commons Collections fork; full Maven/JUnit and upstream CI are **not yet verified**. This is a direct source change, not a migration inventory.

## Exact owner and source seals
- Owner: `src/main/java/com/m3/collections/apache/queue/CircularFifoQueue.java`.
- Original Git blob: `660db10562b1b2664d2fd288db4f602846b47ecc`.
- Candidate Git blob: `a39692c49a388d8b557a673aa795452bd56911bb`.
- Candidate SHA-256: `3dc7770e623c712045a4d48d243c3266ce068aeac016cfe11a9d82f70e706318`.
- API gate Git blob: `c49641eb3eb2e03f2151ad8601897847fc707740`.
- Contains oracle Git blob: `19c7bc71740ca7faf359ab30c318008a25688485`.
- Remove oracle Git blob: `dfdc14b068813c33c21524c32ec160f2686311b7`.

## Array-backed source refinement

The original owner already stores payloads in the same `E[] elements` ring. `AbstractCollection.contains(Object)` and `AbstractCollection.remove(Object)` were materializing an iterator to check each physical slot. Both now scan the existing array directly for the exact `CircularFifoQueue` class. Subclasses delegate to the original inherited methods, retaining projected iterators. No new owner field, callback, entry object, wrapper, native boundary, serialization field, or runtime dependency is introduced.

The exact iterator's physical `start`/`end`/`full` semantics and dynamic end observation are maintained. Each `candidate.equals(element)` call occurs after the local cursor increment; removal invokes the same `remove()` or `shiftSuffixTowardRemovedSlot` path as iterator removal. Null queries and malformed live nulls remain observable as before.

The original-source API/serialization test retains every previous public/protected declaration, generic method signature, field and UID. It permits only exact overrides of the inherited array exports, membership, and single-value removal.

## Executed local evidence (isolated Java 21 model, not full Commons build)

A standalone Java 21 harness reproducing the fork's ring state, iterator and the *exact two candidate method bodies* was compiled and executed in HotSpot interpreter mode (`-Xint`). Across **2,880,000** deterministic randomized state transitions, direct-array and inherited-iterator owners agreed in size, array order, physical start/end and full flag. A reentrant equality mutation parity case also passed.

With `com.sun.management.ThreadMXBean` thread-allocation measurements under `-Xint`, unsuccessful membership and removal allocated **0 bytes per operation** on the direct-ring path versus **32 bytes per operation** on the inherited iterator path, over 40,000 measurements each. These figures prove the isolated execution model, not a general workload speedup or fork-wide Maven acceptance.

## Product-side pending gates

- `CircularFifoQueueContainsHotPathTest`: exhaustive positions, randomized iterator mutations, directed equals, exceptions, reentrance, subclass projections, malformed null.
- `CircularFifoQueueRemoveHotPathTest`: exhaustive first-match physical removal, randomized ops, directed equals, exceptions, reentrance, subclass projection, malformed null.
- `CircularFifoQueueArrayStorageTest`: original-source effective API, declared API exception whitelist, serialized storage.
- `m3-arrayification-ring-20261101.yml` and `m3-apache-circular-package-first-20261018.yml`: current SHA-256 source seal and real JUnit gates; previous historical manifest preserved.

On 2026-11-13, the new PR head had **no GitHub Actions workflow runs**, and Maven is not installed in the isolated local container. Do not mark this candidate accepted or merged until these checks execute cleanly.