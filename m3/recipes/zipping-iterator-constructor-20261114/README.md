# Commons ZippingIterator array-owned constructor optimization — 2026-11-14

A first-party source-sealed OpenRewrite recipe on the *actual* Commons fork.
The Java 8 production class already uses a primitive successor index lane and
owned Iterator[] slots. The two-/three-iterator overloads allocated the
temporary array then called the public varargs constructor, which cloned it.
This change uses a private two-argument constructor to skip the redundant clone
of an array that no caller can retain.

The public varargs entry point still clones its caller-owned array; all public
constructor/method descriptors, child validation, iterator order, remove()
delegation, exhausted-slot unlink and GC clearing remain unchanged.

Source target: src/main/java/com/m3/collections/apache/iterators/ZippingIterator.java
Exact base commit: 973eedde2ce42074b8ca68b2aa534dc7d05869b2
Old Git blob: aa4a2661f1b402d2cbc045fbec6287cb7d1e1a35
New Git blob: e4693e3b3bff354a3893f0c49bc28e15db5866bc
Old SHA-256: 0968beaf8030ed0a7cff6022cdc38c91086725dd8d530984c34fb25e382a0378
New SHA-256: 92cb2391b7428ee70ba095fa6d7664fae30461003bf49c73301e838fa917f069

Local focused Java 8-compatible differential: 156,888 behavioral checks per
tiered, -Xint and -Xcomp mode. Additional five-entry JUnit wrapper test:
17,849 assertions per mode, against a faithful extracted owner. Java 21
javac --release 8 compiled. Thread allocated bytes/op in interpreted mode:
two children 120 -> 96, three children 144 -> 112. Under -Xcomp no
allocation saving measured. Full Maven/Reactor and hosted CI NOT_EXECUTED.
No universal throughput or product/JDK approval claimed.

Recipe replay requires exact before SHA-256 and must reach exact after SHA-256;
the FindAndReplace visitor alone is NOT an admission authority. The recipe
edits only this FILE path. For manual Java 8 compilation and standalone
regression, run the added ZippingIteratorConstructorArrayTest.main().
