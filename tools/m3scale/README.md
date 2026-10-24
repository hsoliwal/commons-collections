# M3Scale whole-repository workspace — Apache Commons Collections

This branch is an **isolated donor working area**, not the M3JDK product runtime.
Upstream provenance: apache/commons-collections; license boundary: Apache-2.0.
Do not replace upstream files or rename public packages before source, ABI,
serialization and license gates prove the complete contract.

Canonical first-party Java21 controller: [M3Scale source PR #214](https://github.com/hsoliwal/synexia-m3scale-apache2/pull/214)
(verified Git source blob `5f3cb6a13cb9bfced77be4b47e103196d7d263fc`).

For this fork, the code of interest includes PredicatedSortedSet; collection and map decorators; bag/multiset implementations. However, **every Git-tracked
file**, Java or non-Java, is included in this pass. A symlink is recorded without
dereferencing; a submodule boundary is recorded separately rather than silently
claiming its nested files were processed.

From this fork checkout, run:

```sh
javac --release 21 -Xlint:all -Werror tools/m3scale/M3ForkFilePass.java
java -cp tools/m3scale M3ForkFilePass scan . m3scale-evidence
java -cp tools/m3scale M3ForkFilePass verify . m3scale-evidence
```

GitHub Actions runs the same commands with temporary output outside the checkout
and publishes `all-files.tsv` and `problem-catalogue.tsv`.
The stage `INVENTORIED_NOT_REWRITTEN` is intentional: it **does not**
pretend that Java, native code, tests, resources, public packages or ABI have
been transformed. Selected transformations must come from existing canonical
Synexia M3Scale Maven/OpenRewrite/JDT recipes; use Inline → Atomize →
Patternize → Comment with an exact per-file preimage and verified tests.

M3JDK21 is product/runtime owner. No source module here becomes an M3JDK21
runtime dependency by virtue of this inventory. Reuse is qualified per
problem-category and donor license; never automatically copy challenge answers.
