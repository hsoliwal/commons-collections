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
package com.m3.collections.apache.bloomfilter;

import java.util.Arrays;
import java.util.Random;
import java.util.function.BooleanSupplier;

/** The exact-peer path must match the original scalar IndexExtractor dispatch. */
public final class ArrayCountingPeerContainsContract {
    private static long checks;

    private ArrayCountingPeerContainsContract() { }

    private static void require(final boolean good, final String label) {
        checks++;
        if (!good) {
            throw new AssertionError(label + " at " + checks);
        }
    }

    private static String outcome(final BooleanSupplier operation) {
        try {
            return Boolean.toString(operation.getAsBoolean());
        } catch (final RuntimeException exception) {
            return exception.getClass().getName() + ":" + exception.getMessage();
        }
    }

    private static ArrayCountingBloomFilter populated(
            final int bits, final long seed, final int pattern) {
        final ArrayCountingBloomFilter owner =
                new ArrayCountingBloomFilter(Shape.fromKM(1, bits));
        final Random random = new Random(seed);
        final int[] positives = new int[bits];
        final int[] negatives = new int[bits];
        for (int index = 0; index < bits; index++) {
            if (pattern == 0 || pattern == 2 && (index & 1) == 0
                    || pattern == 3 && index == bits - 1
                    || pattern >= 4 && random.nextInt(5) == 0) {
                positives[index] = 1 + random.nextInt(3);
            }
            if (pattern == 1 && (index & 3) == 0
                    || pattern >= 4 && random.nextInt(11) == 0) {
                negatives[index] = 1 + random.nextInt(2);
            }
        }
        owner.add((CellExtractor) predicate -> {
            for (int index = 0; index < bits; index++) {
                if (positives[index] != 0 && !predicate.test(index, positives[index])) {
                    return false;
                }
            }
            return true;
        });
        owner.subtract((CellExtractor) predicate -> {
            for (int index = 0; index < bits; index++) {
                if (negatives[index] != 0 && !predicate.test(index, negatives[index])) {
                    return false;
                }
            }
            return true;
        });
        return owner;
    }

    public static void verify() {
        checks = 0;
        final int[] sizes = {1, 2, 31, 32, 63, 64, 65, 66, 127, 128,
                129, 255, 256, 257, 512, 1025};
        for (final int targetSize : sizes) {
            for (final int sourceSize : sizes) {
                for (int pattern = 0; pattern < 9; pattern++) {
                    final ArrayCountingBloomFilter target =
                            populated(targetSize, 1337L + pattern, pattern);
                    final ArrayCountingBloomFilter source =
                            populated(sourceSize, 2143L + pattern, pattern);
                    final IndexExtractor scalar = source::processIndices;
                    final long[] before = target.asBitMapArray();
                    final long[] sourceBefore = source.asBitMapArray();
                    final int count = target.cardinality();
                    final boolean validity = target.isValid();
                    final String expected = outcome(() -> target.contains(scalar));
                    final String observed =
                            outcome(() -> target.contains((IndexExtractor) source));
                    require(expected.equals(observed),
                            "scalar/word disagreement target=" + targetSize
                                    + " source=" + sourceSize + " pattern=" + pattern
                                    + ": " + expected + " / " + observed);
                    require(Arrays.equals(before, target.asBitMapArray()),
                            "containment modified target");
                    require(Arrays.equals(sourceBefore, source.asBitMapArray()),
                            "containment modified source");
                    require(count == target.cardinality() && validity == target.isValid(),
                            "containment changed target counters");
                    require(target.contains((IndexExtractor) target),
                            "self containment, including invalid cells");
                }
            }
        }

        final ArrayCountingBloomFilter filter = populated(65, 42, 0);
        final RuntimeException sentinel = new IllegalArgumentException("producer sentinel");
        final int[] callbacks = {0};
        try {
            filter.contains((IndexExtractor) predicate -> {
                callbacks[0]++;
                predicate.test(0);
                throw sentinel;
            });
            throw new AssertionError("arbitrary producer failure swallowed");
        } catch (final IllegalArgumentException exception) {
            require(exception == sentinel, "producer exception identity");
        }
        require(callbacks[0] == 1, "arbitrary producer callback count");
        require(outcome(() -> filter.contains((IndexExtractor) null))
                        .startsWith("java.lang.NullPointerException:"),
                "null input dispatch changed");
        System.out.println("COUNTING_PEER_CONTAINS_PASS checks=" + checks);
    }

    public static void main(final String[] args) {
        verify();
    }
}
