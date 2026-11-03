# M3 Commons dynamic array bulk capture receiver

Canonical Synexia Maven/OpenRewrite source PR:
https://github.com/hsoliwal/com.synexia/pull/12307
Canonical commit: `5e3c7b6eb59172d42c4b68a5d634ac62a6934e8a`.

The original Apache Commons Collections class `PredicatedCollection` first
validates `coll.forEach(...)`, then reopens the caller collection during
`decorated().addAll(coll)`. Mutable, aliased or one-pass collections can
return different unvalidated elements on the second traversal.

Recipe-generated M3 refinement validates the first traversal and copies
**exact validated references** into one temporary dynamic `ArrayList<E>`,
then calls the same backing owner bulk API once. No per-entry state or
secondary persistent payload, new public methods, changed package, JNI ABI,
M3JDK runtime dependency, or external algorithm solution code.

This receiver carries exact source preimage, SHA256 postimage, JUnit test,
source-provenance category ledger, and a real GitHub Maven JUnit gate.
The JUnit test intentionally uses **Java 8 language/library APIs**, matching
the original Commons module source/target setting of 1.8.

A full-fork M3 pass remains separate at PR #3, whose controller scans every
tracked file. This PR is the first actually generated behavioral atom,
not a claim that all files have completed refactoring.

**HOLD** until canonical OpenRewrite replay and real fork JUnit are green.
