# Apache Commons Collections agent instructions

## Clean output rule (owner directive, 2026-11-14) - read first

This rule overrides any older text in this file.

- This fork is clean output: one coherent Apache Commons Collections repository. M3 work lives
  inside the project's own classes and packages, written the way the project
  writes its code. It is not stamped across folders.
- Do not add `m3/`, `.m3/`, `docs/m3/` or `tools/m3*` folders.
- Do not add any of the following. They belong in `hsoliwal/com.synexia`, the
  convergence workspace:
  - qualification bundles;
  - evidence, receipts and benchmark tables;
  - work orders;
  - donor copies;
  - recipe sources or recipe resources.
- Do not add parallel `M3`-prefixed types or `*.m3` packages where an existing
  class owns the behaviour. Change that class in place, keep it internal unless
  the owner admits new API, and follow the project's naming.
- A pull request here contains only the product change, plus its tests in the
  project's own test layout. Put the proof material in the paired `com.synexia`
  pull request and link to it.
- This fork's merged pull-request history shows the intended direction.
- Collection forks: arrayify and leetcodify the implementations here, then consolidate them into M3JDK (hsoliwal/M3jdk21), which owns the final implementations.


## Priority 0 — additive superset, best-proven algorithms and full-history checkpoints (2026-11-18)

All work on this fork must preserve the **backward-compatible optimized superset** of applicable previously admitted upstream and fork capabilities, stable public API/ABI, behavior, tests and proven fixes/optimizations. Select the best *validated* candidate after comparing incumbent implementations and old/new branch and PR variants for correctness, speed, retained allocation and memory, portability, and maintainability. Add compatible APIs; never silently remove features, change contracts or discard good earlier code. Before every source promotion or merge, inspect earliest reachable ancestors plus relevant current/archived branches, tags, merged/closed/reverted PRs, merge bases and both merge parents for losses, bad commits and reintroduced regressions. Do not rebase `develop`. Record last checked date and exact commit SHA per ref, scan coverage/root, last verified anchor, findings and status; metadata-only or latest HEAD samples stay PENDING until fully examined. Canonical Synexia Maven/OpenRewrite recipes, provenance/history gates and repository-role boundaries govern source-changing work. New, separable first-party material prefers Apache-2.0; preserve all upstream/donor licenses, NOTICE and copyright. LLM-assisted atomization/patternization is not automatic independent authorship or relicensing. Canonical policy and PENDING 236-repo audit inventory: [com.synexia PR #18764](https://github.com/hsoliwal/com.synexia/pull/18764). Product-only forks must obey their existing clean postimage-only AGENTS boundaries.

## Upstream Apache Commons guidance

The fork directives above remain applicable. The upstream project also supplies the following guidance.

<!--
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

     https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# AGENTS.md

Guidance for AI coding agents working in this repository.

The actual AGENTS.md file is [AGENTS.md](https://raw.githubusercontent.com/apache/commons-parent/refs/heads/master/AGENTS.md)

## Owner directive: additive best-of-best merges, full history and last checked (2026-11-18)

Before replacing a class/method/algorithm, traverse all accessible commit history back to its root(s), every branch and tag, and all paginated PRs (open, closed, merged, rejected) with PR source/merge refs. Compare actual before/after owner source blobs, member inventories, contracts, tests and resource guarantees; merge ancestry alone is not evidence that earlier code survived. Detect bad commits and restore lost verified algorithms/APIs/tests additively using the canonical Synexia recipe, without deleting/rebasing history.

Choose strongest compatible implementations and combine them into an additive superset rather than winner-takes-all code replacement. Preserve public/protected source and binary API, reflection, serialization, null/exception semantics, concurrency, performance and memory properties. The canonical recipe, atomization/patternization, source-sealed and loss-detection process resides in `hsoliwal/com.synexia`.

Prefer Apache-2.0 for original eligible implementations; honor all inherited license variants and exceptions, notices and provenance. Ideas and abstract algorithms can be independently implemented when lawful; an LLM rewrite does not automatically remove third-party source copyright or relevant patent obligations.

Record verified ref SHA/date, reachable roots, branch and PR enumeration, delivered-source comparison, tests and unresolved losses in the private canonical ledger `hsoliwal/com.synexia/docs/M3-SCALE/invariants/SYNEXIA-HISTORY-REVIEW-LAST-CHECKED.tsv`. Until the entire history and source/contract gates run, label the review PARTIAL.


## Superset merge and history-audit rule (2026-11-18)

Read [.github/MERGE_RULE.md](.github/MERGE_RULE.md) before changing an existing implementation or integrating any branch or PR.
Preserve the supported additive API/behavior superset and select implementations by correctness, predictability, measured performance, then elegance.
Audit all reachable history back to every root, all branches/tags and all PR states for lost code or regressions; preserve append-only history.
Record actual UTC coverage and remaining work in [.github/HISTORY_AUDIT.tsv](.github/HISTORY_AUDIT.tsv); policy installation is not a completed source-history audit.
Prefer verified Apache-2.0 sources and independent implementations while preserving all existing third-party rights; LLM rewriting does not remove license obligations.
Detailed private recipe/proof records remain in canonical Synexia; the compact local policy and checkpoint make the rule discoverable here.
<!-- MERGE-SUPERSET-HISTORY-20261118-2 -->

## MOP SDK cross-repository agent read gate (2026-11-18)
<!-- MOP-AGENT-2026-11-18-1 -->
Before any source analysis, refactor, PR, merge, licensing decision, or history checkpoint, also read [AGENT.md](AGENT.md). It governs bidirectional atomization/patternization, documented contract-sealed atoms, additive best-of-best API supersets, complete available-history loss checks, dated last-verified proof, Apache-2.0 eligibility and upstream rights. Existing stronger project-specific and locked-canon constraints remain in force. Installation of this pointer does not establish that a source or historical audit has run.
