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
