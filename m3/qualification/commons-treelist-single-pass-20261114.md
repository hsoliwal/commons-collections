<!-- SPDX-License-Identifier: Apache-2.0 -->
# TreeList: one removal traversal and nonredundant AVL height updates

## Production change and custody

Continue Commons Collections PR #54 on parent `b93ccaf24088ccef3a4e0f709bda875be5e4ecfb`, retaining its permutation optimization. The inspected master was `465a5289c8aa5ca00bfe89b22165536db6bd1e97`; the entire TreeList preimage was independently confirmed identical on the PR branch. This continuation does not merge master or replace concurrent work.

Exact TreeList instances no longer locate the removed value with a separate ranked `get` traversal. The existing detached slot carries that value until it is read and immediately released. The two-child deletion path saves the logical removed payload rather than returning the predecessor/successor payload. Subclasses retain the original virtual `get` call, returned value, failure timing and nested callbacks.

Insertion, removal, remove-minimum and remove-maximum no longer recalculate a height already updated by `setLeft`/`setRight` or a rotation. Necessary rotation/build/removeSelf height updates remain. The original arrays, free-slot ordering, ranks, threading and release behavior are retained. No new fields, methods, arrays, wrappers, dependencies or per-entry objects are introduced. Original ASF licensing and existing contributor notices are preserved, with one additive modification notice.

| Source | Git blob | SHA-256 |
| --- | --- | --- |
| Original TreeList | `919e7c74a6adce8b095916cd97aa50d3d0838d77` | `0e1483d5e5f56e4837f72d440b9ed6e77f5ddd07849fbf39d0b6184a45a835e3` |
| Candidate TreeList | `f0e96a415585c5090595efd04b68d990e20c792a` | `a660620e9fdb9928552e4f27d71348f1e7d604122963a28d837d5eccd0a5327e` |
| Independent contract | `8b67d61074a4798b32cccc705c6c56e16c7753fa` | `eba632e36bc0ccc6c86a20e244c6f1947c44b47a4cd19e159199ff03761a2a0d` |
| Unchanged OrderedIterator dependency | `717a31655cf448e8803145a034cdd925c0a9c044` | `ffced829bb6531a04d85257b5a6315dbdf0ed5887ab83dd92c30e875b3d15d8d` |

## Executed on complete actual source files

Linux OpenJDK 21.0.12.1+1-1-deb13u1-Debian. The real complete baseline/candidate TreeList and exact real OrderedIterator dependency were compiled; no dependency stubs, normalized sources or substitute collection implementation were used.

- Strict Java 21 compilation: `--release 21 -proc:none -Xlint:all -Werror`.
- Java 8 source/API targeting: `--release 8 -proc:none -Xlint:all,-options -Werror`. Only obsolete-target options warnings excluded; execution remained on Java 21, not a Java 8 runtime.
- Baseline and final candidate normal runs: **43,528 transitions / 12,425,548 assertions each**, digest `4c597274dd66b0c5`. The release-8 candidate produces the same result.
- Bounded interpreter runs: **7,528 transitions / 2,785,631 assertions each**, digest `b13f2477afb8669a`. All exhaustive boundary cases remain; random steps per seed are 500 rather than 5,000.
- Eight seeded mutation streams; every removal position at sizes 1..48 across bulk, incremental and recycled layouts. Nulls, repeated payload identity, both donor branches, rank/height/thread/free-chain contents, detached payload clearing, iterators, sublists, joins, clear/refill and invalid bounds are checked.
- Subclass checks preserve returned sentinels, exact exception identity, nested add/set/remove callbacks, modification counts and size-call counts. No payload equals/hashCode calls are permitted during indexed operations.
- Public and private `javap -p -s` declarations for freshly compiled Java21 TreeList, SlotStore and TreeListIterator are identical. Full state-array digests, not just public values, match.
- Four deliberate faulty variants are rejected: returning the donor payload, bypassing subclass get, retaining freed payload and omitting a necessary left-height update.
- Fresh baseline/candidate compilation, full normal replay, bounded candidate interpreter replay and descriptor comparisons were repeated before publication.

These totals count assertions, not separate JUnit tests. The native JUnit adapter invokes the executed contract but has not itself run through Surefire.

## Deterministic work regression

Disposable instrumentation observes the actual methods; it is not included in product sources. Each selected regression fails on the baseline and passes on the candidate.

| Operation on a balanced 4,095-element tree | Original | Candidate |
| --- | ---: | ---: |
| Separate ranked lookup during remove(2345) | 1 | 0 |
| Height recalculations during rightmost removal | 22 | 11 |
| Height recalculations during rightmost insertion | 24 | 12 |

An attempted end-iterator shortcut was rejected because its timings were inconsistent. The final candidate preserves the entire original iterator implementation. Historical cursor measurements are not acceptance evidence for this source.

## Scoped actual-source timing diagnostics

Three fresh JVMs per version/workload/size, alternating version order; 12 warmup batches and seven measurement batches of 20,000 operations; `-Xbatch -Xms128m -Xmx512m`. Input construction, rank generation and receiver preparation are outside timing. Results and final element order/identity are checked. These are medians of process-median thread CPU nanoseconds per operation pair, not deletion-only timings or JMH results.

| Elements | Operation pair | Original ns | Candidate ns |
| ---: | --- | ---: | ---: |
| 32 | random remove/reinsert | 168.324 | 143.898 |
| 1,024 | random remove/reinsert | 402.300 | 329.471 |
| 65,536 | random remove/reinsert | 803.805 | 592.564 |
| 32 | append/remove tail | 119.684 | 90.659 |
| 1,024 | append/remove tail | 219.611 | 170.610 |
| 65,536 | append/remove tail | 375.188 | 299.709 |

At 65,536 elements the observed CPU reductions are 26.3% and 20.1%, respectively. Both versions measured zero allocated bytes per operation pair. Final retained arrays/fields are unchanged. The unchanged get control varied too: its one-element median was 0.568 vs 1.344 ns; larger controls were 28.635 vs 26.178, 56.755 vs 53.029, and 157.615 vs 148.926 ns. Such tiny timings are dominated by measurement/loop effects. No universal non-regression, isolated throughput or production benchmark acceptance is claimed. Raw timing samples and probe source accompany the conversation evidence bundle.

## Reproduction and outstanding gates

```sh
mkdir -p target/treelist-removal-check
javac --release 21 -proc:none -Xlint:all -Werror -d target/treelist-removal-check \
  src/main/java/com/m3/collections/apache/OrderedIterator.java \
  src/main/java/com/m3/collections/apache/list/TreeList.java \
  src/test/java/com/m3/collections/apache/list/TreeListSinglePassRemovalContract.java
java -cp target/treelist-removal-check com.m3.collections.apache.list.TreeListSinglePassRemovalContract
java -Xint -cp target/treelist-removal-check com.m3.collections.apache.list.TreeListSinglePassRemovalContract 500
mvn -B -ntp -Dmaven.compiler.testRelease=21 \
  -Dtest=TreeListTest,TreeListSinglePassRemovalTest test
```

The javac/standalone operations were executed. Maven could not start (`mvn: command not found`); the last command is an unexecuted integration entry point. Full native Maven/JUnit, Checkstyle/RAT, hosted CI and independent review remain open for these new bytes. Author self-review only.

A local source-pinned Java transformation emitted the candidate; exact local replay/fixed-point and drift/empty/duplicate rejection checks passed. This is **not canonical Maven/OpenRewrite execution**, which remains NOT_RUN. No private recipe, source preimage fixtures, instrumentation, registry or authoring tool is copied into this public commit. No merge, M3jdk21 write, installed JDK/JMOD/JNI/GC/jtreg/Windows acceptance or whole-collection-family completion is claimed. Keep PR #54 draft.
