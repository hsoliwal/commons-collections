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
- Backing list has at least 256 entries; argument set has at least 64 entries.
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
