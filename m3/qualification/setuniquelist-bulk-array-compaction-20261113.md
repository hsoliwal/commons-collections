<!-- SPDX-License-Identifier: Apache-2.0 -->
# Commons Collections: Array-backed SetUniqueList bulk removal

**Receiver:** `hsoliwal/commons-collections/master@1c3d291123d3e991e982005c6bc1ee91dc682fad`.
**Existing owner:** `src/main/java/com/m3/collections/apache/list/SetUniqueList.java`;
exact baseline Git blob `a9d4a21591849b4d78a3b889e9ab3297c070e415`.
**Status:** proposed private method-body optimization; fork-native build/bench acceptance HOLD.

## Problem class and cost

Incumbent `SetUniqueList.removeAll(Collection)` iterates each input and delegates to
`remove(Object)`, which removes the member from its set and then searches/removes the
same value from the backing `List`. For an `ArrayList`, each successful call may search
and shift O(n) elements; removing k members has worst-case O(nk) traversal/movement.
The existing unique-membership `Set` does not eliminate those repeated array shifts.

## Exact bounded transformation

Preserve the existing `removeAll(Collection<?>)` signature and its entire original
member-by-member loop as fallback. Admit a single bulk-array path only when:
- Runtime receiver is **exactly** `SetUniqueList.class`, not a subclass.
- Backing `decorated()` is **exactly** `java.util.ArrayList.class`.
- Internal membership set and argument are both **exactly** `java.util.HashSet.class`.
- Backing list has at least **8,192** entries and argument set at least **1,024** entries, with an additional estimated pair-work gate `(long) size * argumentSize >= 12,000,000`; otherwise preserve the original loop.
- Every list item and argument item has built-in immutable equality: `null`, exact
  `String`, `Integer`, or `Long`; all other types refuse the fast path.
- The original list/set identity invariant is checked (same count and every
  backing-list entry is present in the membership set); mutated external backing
  state must fall back to the old implementation.

Under those conditions, execute `decorated().removeAll(coll)` first (JDK ArrayList
bulk compaction over O(n) entries), then `set.removeAll(coll)` to maintain the
unique-membership invariant, returning the backing list's changed flag.
No temporary data-copy array is installed; the existing backing array is compacted
in-place, and the JDK collection methods own their memory/references.
This reduces the admitted large-workload asymptotic cost to O(n+k), subject to
HashSet average membership assumptions; it is *not* a universal speedup claim.

For potentially mutating/asymmetric custom `equals` or `hashCode`, foreign list or
set implementations, unusual subclasses, external backing mutation, small
collections, or argument aliases, retain the exact old loop and exception behavior.

The changed method may call one private, nonvirtual predicate for stable equality,
without adding public methods, fields, serialized members, JNI bindings, or a new
collection type. Do not change the `SetUniqueList` factory, iterators, `asSet`,
or the long-established `removeIf`/`retainAll` semantics.

## Threshold correction from actual Java 21 benchmarks

The initial conservative-looking `256/64` size gate proved **too broad**.
A real JDK21 `HashSet`/`ArrayList` standalone method-level benchmark showed
a significant regression at `n=512,k=128` (25,848 ns per original removal
vs 132,025 ns for bulk), at `n=2,048,k=512` (236,932 vs 493,863 ns),
and even at `n=8,192,k=1,024` in one sample (1,147,828 vs 1,303,158 ns).
The one-time HashSet-based backing-invariant preflight is not free.

Larger workloads reversed the result: at `n=8,192,k=2,048`, 3,320,604
vs 219,444 ns; `n=16,384,k=1,024`, 1,534,961 vs 190,152 ns; and
`n=16,384,k=2,048`, 4,300,023 vs 209,861 ns.
These timings are exploratory `-Xbatch` medians and are subject to JIT/GC
noise; they justify **narrowing admission**, not asserting global speedups.

The reviewed fast path therefore requires `size >= 8192`,
`coll.size() >= 1024`, and a 64-bit work product of at least 12 million.
This excludes every observed smaller regression, while allowing only
benchmarked substantial bulk-removal work. No threshold is represented as a
universal performance oracle; JMH/CI and original JUnit qualification remain
required before M3JDK integration.

## Required evidence and promotion

Document-before-code, exact Git blob and SHA-256 pre/post source, baseline-vs-candidate
JDK21 strict compiler, actual-class differential behavior for null, duplicates,
large and small inputs, hash collisions, ordering, backing-list identity, iterator
fail-fast, unsafe/custom equality and exceptions; seeded replay and minimum
asymptotic/timing evidence with warmup, not invented performance percentages.
Add focused fork-native JUnit tests; only approved scratch output namespaces,
no binary imports. Merge into the M3JDK21 receiver separately after the fork's
Maven/JUnit + CI, Java21 behavioral/serialization and licence/ABI gates pass.
No unrelated PR/branch modifications.
