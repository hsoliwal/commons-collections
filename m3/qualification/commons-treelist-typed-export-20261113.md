<!-- Copyright 2026 Hitesh Soliwal and contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Commons fork TreeList typed-array export

This is a scratch-fork source optimization, not M3JDK21 receiving or release admission.
The actual existing TreeList owner traverses its current threaded primitive indices
without allocating an iterator. Subclasses still use AbstractCollection dispatch.
No extra retained field, node, copied backing store, recipe or runtime dependency.

Base: 1c3d291123d3e991e982005c6bc1ee91dc682fad.
Source before: 97e5b8ae8d0b5191f17b6e77ea1aff64dfdf362f.
Source after: 72cf1a21556c7e317b861142620d38ccb5684ccf.
Source after SHA-256: bc4b3cb98ad725f37cfce3a31929b94f5f5e6e4a229b4281677f79a1c477ad0e.
Independent contract: cf7d6bec799e9f38d2acc83bfa6ff4f973a52d4d.

## Actually executed locally

Exact complete TreeList and OrderedIterator sources plus the independent contract
compile with javac --release 8 -Xlint:all,-options -Werror on OpenJDK 21.0.12.1,
Linux. Only the compiler's obsolete-release options warning is excluded.
The unchanged baseline and candidate each pass 392,447 assertions in both normal
and -Xint execution: fingerprint db14ec6e88316138. Inputs include randomized tree
mutation/slot reuse, nulls, empty arrays, small/exact/oversized destination arrays,
runtime component types, successful prefixes on ArrayStoreException, retained tail
cells, subclasses overriding size/iterator/listIterator, sublists and generator mutation.
Three deliberately broken variants (subclass dispatch, null termination, encounter
order) fail the regression. The JUnit entry point is added for the normal suite;
that wrapper has not been executed here through JUnit/Surefire.

A test-only instrumented baseline constructs an iterator on typed export and fails
the no-unnecessary-iterator assertion. The candidate passes at sizes 0, 1, 8, 1024.
Instrumentation is not in product source. Interpreter allocation counter observations
show 40 fewer bytes per export. In the warmed ordinary JIT samples, escape analysis
already removes the baseline iterator, so both variants allocate zero for reuse.
Do not describe the interpreter allocation saving as universal JIT heap savings.

Three fresh JVM diagnostics (-Xms64m -Xmx256m) include 7 samples per size/mode after
5 warmup rounds. For reused typed arrays of 8,192 elements, median thread CPU is
44,817.730 ns before and 37,454.975 ns after (about 16.4% lower in this sample).
The 1,024-element reuse medians are 4,789.860 and 4,627.462 ns. Tiny cases are mixed,
including empty/single-element reuse regressions. These are standalone diagnostics,
not JMH, retained-memory, production workloads or a universal speedup claim.

Resolved public method name/parameter/return/modifier sets match. One new declared
override of the existing inherited toArray(T[]) method is intentional; declared-method
reflection is not unchanged. Existing collection storage fields are unchanged.

## Pending, not bypassed

The full fork Maven suite was attempted but could not start: mvn: command not found.
Runtime network resolution is unavailable, so no Maven/JUnit dependency installation
or whole-repository build is claimed. No hosted-CI result has been counted as a pass.
Canonical source-sealed OpenRewrite resources are authored in the private Synexia
collection-refinement dataset commons-tree-typed-20261113; its actual Maven execution,
fixed-point/refusal qualification and independent review remain pending. This scratch
prototype is not permission to move the result into M3JDK21. No M3JDK21 file was edited.

The original Apache header, LICENSE and NOTICE remain intact. The change notice is
additive; new test/evidence expression is Copyright 2026 Hitesh Soliwal and contributors,
Apache-2.0. No new third-party implementation was imported. No merge is requested.
