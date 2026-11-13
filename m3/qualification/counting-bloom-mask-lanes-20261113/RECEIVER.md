<!-- SPDX-License-Identifier: Apache-2.0 -->
# Counting Bloom filter primitive nonzero-mask candidate

Repository: hsoliwal/commons-collections (actual fork implementation, not an adapter).
Preimage: master 1c3d291123d3e991e982005c6bc1ee91dc682fad
Target: src/main/java/com/m3/collections/apache/bloomfilter/ArrayCountingBloomFilter.java
Original Git blob: 569ef46f8bbcb2123c0bd69c743ec453c205e630
Candidate Git blob: f68bba3a150f39da5eca23e97004ec8801c981d1
New JUnit test Git blob: ad1b7727ad5f8313281e505b521842e7bba536e1

The existing int[] counter remains authoritative. A long[] nonzero occupancy
mask and primitive nonzeroCount track zero-to-nonzero transitions, including
negative cell values. asIndexArray() and its positive-only cardinality remain
unchanged. The mask accelerates bitmap export and sparse processCells and
processIndices while preserving callback order and mutation visibility.
Dense scans retain their original linear traversal.

Executed standalone Java21 model proof: javac --release 21 -Xlint:all -Werror,
868826 differential assertions PASS over 13 array sizes, randomized add,
subtract, overflow, negative values, callback mutations and clear.
Bounded standalone scan CPU ratios versus scalar: 16/65536 nonzero 0.122,
64/65536 0.014, 2048/65536 0.102, 49152/65536 0.674.
These are model-only sample timings, not production JMH results.

HOLD: full fork Maven/JUnit, callback edge-case provider matrix, JMH,
canonical Synexia Maven/OpenRewrite SDK, downstream M3JDK21 receiving and
source-built installed image NOT RUN. Original ASF licence retained.
