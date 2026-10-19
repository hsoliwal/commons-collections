<!-- SPDX-License-Identifier: Apache-2.0 -->
# Source-sealed Java 8 switch compatibility recipe

Problem category: **Java source-level compatibility / switch-statement lowering**.

## Precise source custody

- Source: `src/test/java/com/m3/collections/apache/queue/M3ApacheCircularFifoRelocationProof.java`
- Original Git blob: `bafbe785d87698dc07c821996ace7bc174081941`
- Qualified result Git blob: `5d703ff2b52811dad6dcf0f42992972f580ae24f`
- Recipe: `Java8CircularQueueSwitchRecipe.java` (standalone JDK 21 source launcher, not part of production build)
- CI witness: Commons Collections Java CI `39212273895`, job `117928109451`, test compilation failed on Java 14 switch syntax while compiling with `-source 8`.

The recipe is **source sealed and fail closed**. It accepts exactly one original switch block or exactly one resulting Java 8 block. Unexpected changes, duplicates, or missing blocks are errors. A second pass reaches a fixed point and makes zero changes.

## Invariant

Only lower the finite `switch (choice)` syntax, preserving its case grouping, operation order, random draws, branch conditions, exception flow, and all equality/parity oracles.

The original donor-identity guard, public signatures, serialization checks, production queue classes, and all other source bytes remain unchanged. This **does not claim that the donor-identity guard is satisfiable** in the current package layout; it remains a separate exact provenance requirement.

## Verification

```sh
java m3/recipes/java8-circular-queue-switch/Java8CircularQueueSwitchRecipe.java --check \
  src/test/java/com/m3/collections/apache/queue/M3ApacheCircularFifoRelocationProof.java

mvn -B -ntp -Drat.skip=true -DskipTests test-compile

mvn -B -ntp -Drat.skip=true -Dtest=CircularFifoQueueTest \
  -DfailIfNoTests=false test
```

The first command verifies the recipe fixed point. The second proves the test source compiles at the existing Java 8 source level. The last runs the existing focused queue oracle. `rat.skip=true` isolates this grammar repair from the independent pending generated-receipt RAT fix in PR #10; it is **not** permission to skip that release gate.

## Donor catalogue

The candidate is mechanically equivalent to the Java language's colon-label fallthrough and explicit `break` syntax. This is a syntax compatibility repair, not a new algorithm or a reason to import external implementation code. LeetCode/HackerRank/GeeksforGeeks search is not relevant to this compiler grammar failure.

Acceptance requires executing the gates; writing this document does not constitute a passing build.
