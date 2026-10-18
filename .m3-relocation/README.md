<!-- SPDX-License-Identifier: Apache-2.0 -->
<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors -->
# M3 ApacheCommons source-workspace relocation

**Original donor fork:** `hsoliwal/commons-collections`, pinned commit `2d985c27e07bdcbb9516aea44b0dda3f3e487ced`.
**Canonical recipe owner:** [hsoliwal/com.synexia#12293](https://github.com/hsoliwal/com.synexia/pull/12293) at `0f96532236f9d2b113b635f8c14884f64a75f6fc`.
**M3JDK21 product receiver:** [#1293](https://github.com/hsoliwal/M3jdk21/pull/1293).

This branch prepares a **real source package relocation recipe** for src/main/java, src/test/java.
Do not rename public upstream packages in fork `master`. Keep the fork's original
public release and provider compatibility libraries intact. Apply the recipe
only in a reviewed, pinned scratch/worktree after the relevant tests are frozen.

**From a detached scratch worktree of this branch** with Maven and OpenRewrite dependencies:
```sh
mvn -B -ntp org.openrewrite.maven:rewrite-maven-plugin:5.23.1:dryRun \
  -Drewrite.configLocation=.m3-relocation/rewrite.yml \
  -Drewrite.activeRecipes=com.synexia.m3.RelocateApacheCommonsFork20261018

# When the dry-run patch, notices, and dependent modules have been admitted:
mvn -B -ntp org.openrewrite.maven:rewrite-maven-plugin:5.23.1:run \
  -Drewrite.configLocation=.m3-relocation/rewrite.yml \
  -Drewrite.activeRecipes=com.synexia.m3.RelocateApacheCommonsFork20261018
```

Source before `org.apache.commons.collections4` -> after `com.m3.sdk.apache.commons.collections4`. Both package
declarations and Java type references must be rewritten through OpenRewrite's
AST visitors. The current PR commits the recipe **but not yet its whole-fork
generated postimages**. No source-relocation execution or donor tests are claimed
until exact generated files have been checked back into the fork branch.

**Licensing:** original donor source remains under **Apache-2.0 and ASF NOTICE** including
all retained copyright headers/NOTICE; this added recipe description is original
first-party Apache-2.0. A package rename does not relicense upstream files.
Existing `com.m3.collections` backends are the target M3 runtime primitives,
not a parallel collection engine. Group semantic atoms by source file and prove
call/return/null/equality/iterator/view/serialization/concurrency contracts,
then apply array-offset-index-mask lowering via separate canonical recipes.
LeetCode, HackerRank, and GeeksforGeeks are reference-only algorithm categories.
