<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 array-operation source candidate (2026-10-31)

This is a source-only candidate, not a released library or a claim that every collection has array-backed storage. It is a **materialization of the pre/postimages in [canonical Synexia draft PR #14952](https://github.com/hsoliwal/com.synexia/pull/14952)**, crate `synexia-openrewrite-recipes/crates/m3-bulk-arrayification-20261031/`. The reuse/recipe engine is owned by Synexia; no fork-local mutation engine is created.

## Modified owners

- `src/main/java/com/m3/collections/apache/queue/CircularFifoQueue.java`: Two-span ring lookup. Original blob: `4647485b0b0787981579f22693d2f6b65ffe42ab`.

Everything outside the exact selected method atoms remains unchanged; all provider API package declarations, simple names, public fields, methods and descriptors remain unchanged. These edits use existing Java arrays only and do **not** establish JNI array allocation or arrayification of other classes. No copied challenge solutions. Existing donor licenses/NOTICE and prior history are untouched.

## Admission

- **Completed:** isolated Java 21 arithmetic exercise checked 100,000 ring-index equivalences, 256 append equivalences and 262,144 swaps (refer to canonical PR). It is not a provider binary/source compilation.
- **Pending:** Full Commons Maven suite, RAT/license baseline, subclass and serialization/iterator differentials, benchmark.
- **Pending:** exact recipe replay, fixed point, API binary descriptors, native JNI backing-array integration and collection superset tests.
- **Held:** speed, allocation, packaging, rollout and merge acceptance. Measurements are not asserted.

The historical local `.m3-relocation/rewrite.yml` is superseded; do not apply it to this already-relocated source. Continue serial per-owner atomization/patternization and contract-preserving recipe improvements. In JDK-received classes add the `M3` prefix only to new first-party owner types; keep received donor class simple names.
