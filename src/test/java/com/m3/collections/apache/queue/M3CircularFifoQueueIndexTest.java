/*
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.m3.collections.apache.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayDeque;
import java.util.NoSuchElementException;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Independent FIFO oracle for the two-span index mapping. */
class M3CircularFifoQueueIndexTest {
    @Test
    void getMatchesDequeThroughWrapOverwriteAndReuse() {
        for (int capacity : new int[] {1, 2, 3, 7, 16, 33}) {
            CircularFifoQueue<Integer> candidate = new CircularFifoQueue<>(capacity);
            ArrayDeque<Integer> oracle = new ArrayDeque<>(capacity);
            Random random = new Random(0x5eedL + capacity);
            for (int step = 0; step < 4_000; step++) {
                if (oracle.isEmpty() || random.nextInt(4) != 0) {
                    int value = random.nextInt(201) - 100;
                    if (oracle.size() == capacity) {
                        oracle.removeFirst();
                    }
                    oracle.addLast(value);
                    candidate.add(value);
                } else {
                    assertEquals(oracle.removeFirst(), candidate.remove());
                }
                assertEquals(oracle.size(), candidate.size());
                int position = 0;
                for (Integer expected : oracle) {
                    assertEquals(expected, candidate.get(position++));
                }
            }
            assertThrows(NoSuchElementException.class, () -> candidate.get(-1));
            assertThrows(NoSuchElementException.class, () -> candidate.get(candidate.size()));
        }
    }
}
