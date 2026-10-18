<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
     SPDX-License-Identifier: Apache-2.0 -->
# Fork workspace: bounded primitive-long indexed filtering

This isolated **experiment** lives under `m3/workspaces`, not the upstream Apache Commons Collections production tree. The first-party source templates and recipe are reproduced exactly in `hsoliwal/com.synexia` as the canonical convergence authority, then received into `hsoliwal/M3jdk21` by executing the admitted Maven recipe. All runtime names use `M3*` conventions.

This fork supplies the separately reviewable comparison with the existing `PredicatedCollection` insertion-time admission contract, pinned at `df02771bdf416a1a0c3e613545be7a659520a5ad`. Insertion-time validation does not provide occurrence-index filtering or bounded scanning with signed strides; no Apache implementation was copied into the M3 source. The first-party experiment is Apache-2.0.

The prototype composes existing `M3LongIndexPathMasks.scanWindow` and `M3LongIndexPathMasksJNI.scanWindow` so rejected rows count against `maxExamined` even if the requested output buffer is not full. Source array and state-mask ownership stay with the existing M3 collectors. No JNI ABI addition or arbitrary Java callback is introduced.

Execute this experiment by compiling the sealed recipe with Maven 3.9+/Java21, then `--apply` on an **isolated checkout of M3JDK21** containing the prior long-path Java/JNI owners. The target product PR includes a focused GitHub Actions test of Java/JNI output, input advancement, negative strides, duplicates, zero-work semantics, and source/postimage consistency. This fork's workspace PR does not claim that Apache Commons Collections has changed its public API, nor that a complete custom Maven/Gradle/JDK build has passed.

Local selected proof: Java21 strict compile PASS, Java-only 27,250 assertions; loaded JNI and C17 UBSan 54,497 assertions each, 2,200 seeded rounds; Maven recipe source seals and fixed-point replay checked with local `javac`/`java`. Full Maven execution remains an independent workflow gate.
