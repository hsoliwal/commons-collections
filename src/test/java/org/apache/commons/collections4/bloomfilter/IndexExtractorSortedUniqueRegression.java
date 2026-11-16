/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.collections4.bloomfilter;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Random;
import java.util.TreeSet;

/** Executable behavioral and optional allocation checks for ordered snapshots. */
public final class IndexExtractorSortedUniqueRegression {
    private static int checks;
    private static volatile Object sink;

    private IndexExtractorSortedUniqueRegression() { }

    private static void require(final boolean condition, final String description) {
        checks++;
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void exercise(final int[] values) {
        final int[] before = values.clone();
        final TreeSet<Integer> expected = new TreeSet<>();
        for (final int value : before) {
            expected.add(value);
        }
        final int[] expectedIndices = expected.stream().mapToInt(Integer::intValue).toArray();
        final IndexExtractor result = IndexExtractor.fromIndexArray(values).uniqueIndices();
        require(Arrays.equals(expectedIndices, result.asIndexArray()), "snapshot order/content");
        require(result.uniqueIndices() == result, "idempotent unique view");
        require(Arrays.equals(before, values), "source array mutated");
        final int[] calls = {0};
        require(result.processIndices(value -> {
            require(value == expectedIndices[calls[0]], "callback order");
            calls[0]++;
            return true;
        }), "callback traversal result");
        require(calls[0] == expectedIndices.length, "callback count");
        calls[0] = 0;
        require(!result.processIndices(value -> {
            calls[0]++;
            return false;
        }), "early false not propagated");
        require(calls[0] == 1, "short circuit ignored");
        values[0] = 999999;
        final int[] detached = result.asIndexArray();
        detached[0] = 999999;
        require(Arrays.equals(expectedIndices, result.asIndexArray()), "snapshot aliased input/output");
    }

    public static void verify() {
        checks = 0;
        final Random random = new Random(0x504F5254L);
        for (final int count : new int[] {1, 2, 7, 64, 511, 8192}) {
            for (int pattern = 0; pattern < 14; pattern++) {
                final int[] values = new int[count];
                final int step = pattern < 7 ? 1024 : 1;
                final int divisor = 1 + pattern % 7;
                for (int i = 0; i < count; i++) {
                    values[i] = i / divisor * step;
                }
                if (pattern == 8 && count > 1) {
                    final int temp = values[0];
                    values[0] = values[count - 1];
                    values[count - 1] = temp;
                }
                if (pattern == 9 && count > 1) {
                    values[0] = random.nextInt(20000);
                }
                exercise(values);
            }
        }
        for (final int[] invalid : new int[][] {{-1}, {-1, 0, 12}, {Integer.MIN_VALUE, 5}}) {
            try {
                IndexExtractor.fromIndexArray(invalid).uniqueIndices();
                throw new AssertionError("negative index admitted");
            } catch (final IndexOutOfBoundsException expected) {
                checks++;
            }
        }
        exercise(new int[] {100000000, 100000000, 100000001});
        System.out.println("SORTED_UNIQUE_REGRESSION_PASS checks=" + checks);
    }

    @SuppressWarnings("deprecation") // Thread.getId is needed for Java 8 source compatibility.
    public static void allocated(final boolean widelySpaced) {
        final java.lang.management.ThreadMXBean original = ManagementFactory.getThreadMXBean();
        if (!(original instanceof ThreadMXBean)) {
            throw new IllegalStateException("thread allocation probe unsupported");
        }
        final ThreadMXBean counter = (ThreadMXBean) original;
        counter.setThreadAllocatedMemoryEnabled(true);
        final int[] values = new int[8192];
        for (int i = 0; i < values.length; i++) {
            values[i] = widelySpaced ? (i >>> 11) * 100000 : (i >>> 1) * 1024;
        }
        final IndexExtractor source = IndexExtractor.fromIndexArray(values);
        final long threadId = Thread.currentThread().getId();
        final long[] measurements = new long[21];
        for (int i = -50; i < measurements.length; i++) {
            final long before = counter.getThreadAllocatedBytes(threadId);
            sink = source.uniqueIndices();
            final long delta = counter.getThreadAllocatedBytes(threadId) - before;
            if (i >= 0) measurements[i] = delta;
        }
        Arrays.sort(measurements);
        final long median = measurements[measurements.length / 2];
        final long bound = widelySpaced ? 2000 : 24000;
        System.out.println("SORTED_UNIQUE_ALLOC_" + (widelySpaced ? "WIDE" : "SPARSE") + " median=" + median);
        require(median < bound, "redundant sort/bitmap allocation: " + median);
    }

    public static void main(final String[] args) {
        if (args.length == 0) {
            verify();
        } else {
            allocated("wide".equals(args[0]));
        }
    }
}
