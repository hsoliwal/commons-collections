<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# SparseBloomFilter primitive array storage

This packet replaces the filter's private TreeSet<Integer> with one IndexStore holding primitive key, left/right child, successor and height arrays. Insert and lookup follow an AVL tree; ascending traversal follows the successor array. There are no retained boxed keys or individual tree-node objects in this owner. The original Shape object is retained, copies allocate independent nonempty storage, and clear releases the arrays.

The rotation/height/balance pattern is adapted from the Apache-licensed, already qualified Commons TreeList array owner at predecessor `03f5ae07596f2fd648419cd69e7c4f3dc01d7420`, source SHA256 `2c1f47e3736f1366105258db91eb563780322956462460ffdb403d4f66a96f65`. Integer search/insertion and the ascending successor lane are specialized for this no-individual-delete owner. `STORAGE_CONTRACT.tsv` states the exact callback, copy, capacity and representation boundaries.

Merge retains the original delayed validation: it consumes the extractor, ignores its boolean result, checks the greatest index against the shape before checking the smallest negative index, and leaves any inserted invalid partial state intact. Index traversal preserves stream allMatch's modification check after the predicate, including a false predicate result. Bitmap traversal preserves enhanced-for's check before next, including its lack of a modification check after the final stored index or during trailing bitmap callbacks. Callback exceptions retain precedence.

`M3SparseBloomFilterArrayStorageTest` independently compiles exact original SparseBloomFilter and BitMaps source preimages stored under test resources, validates their SHA256, and loads those two owners in a child-first class loader. It compares original behavior for 9,000 deterministic merge/contains/copy steps, 840 callback configurations, invalid/partial inputs and null overloads. Separate representation checks inspect three insertion orders of 8,192 unique values, AVL bounds and heights, exact successor coverage, independent copy columns, and array release under repeated clear/reuse. The original test-source hashes are sealed in `ORIGINAL_TEST_SHA256.tsv` and must remain unchanged.

Sources are received only from actual Results of `com.synexia.m3jdk.CollectionsContractRefinement20261017` using the existing canonical HashPinned recipes, dataset `commons-sparse-arrays` followed by the formatting/license successor `commons-sparse-gates`, after exact input/source binding, fixed point, drift/refusal and strict receiver qualification. The canonical task evidence records actual SDK/native runs and the final receiver result. This source README defines the contract and reproduction entrypoint; it does not assert an unexecuted gate.

With JDK 21 and the repository's dependencies, the native receiving check is:

```sh
mvn -B -ntp -o -s /tmp/m3-collections-settings.xml -Drat.skip=true -Dmaven.compiler.testRelease=21 -DforkCount=1 -DreuseForks=true -DargLine=-Xmx1024m test
```

The explicit test release is the existing predecessor requirement. RAT is recorded separately because the predecessor already reports unrelated license files; this packet does not change that baseline or weaken source tests. Main source keeps the repository's declared Java 8 target. The focused original-source oracle additionally compiles both leaf implementations with JDK 21 strict warnings.

This is one intrinsic sparse-filter owner. It does not array-convert generic sorted maps, arbitrary supplied delegates, concurrent/reference owners, public node graphs, or other collection families. No measured speedup or heap-size claim is made.
