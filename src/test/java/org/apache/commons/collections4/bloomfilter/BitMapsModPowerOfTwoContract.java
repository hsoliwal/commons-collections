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

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Random;
import com.sun.management.ThreadMXBean;

/** Exact unsigned modular correctness and an optional performance admission. */
public final class BitMapsModPowerOfTwoContract {
    private static volatile long blackhole;
    private static long checks;

    private static void check(final boolean ok, final String label) {
        checks++;
        if (!ok) {
            throw new AssertionError(label);
        }
    }

    private static void behavior() {
        final Random random = new Random(0x5A71B1A5L);
        final long[] boundaries = {0, 1, 2, -1, -2, Long.MIN_VALUE,
                Long.MAX_VALUE, 0x5555555555555555L, 0xAAAAAAAAAAAAAAAAL};
        final int[] divisors = {1, 2, 3, 4, 5, 7, 8, 16, 31, 32, 63, 64,
                65, 127, 128, 255, 256, 257, 1023, 1024, 65536, 65537,
                1 << 29, 1 << 30, Integer.MAX_VALUE};
        for (final int divisor : divisors) {
            for (final long boundary : boundaries) {
                check(BitMaps.mod(boundary, divisor) ==
                        (int) Long.remainderUnsigned(boundary, divisor), "boundary modulus " + divisor);
            }
            for (int i = 0; i < 20000; i++) {
                final long value = random.nextLong();
                check(BitMaps.mod(value, divisor) ==
                        (int) Long.remainderUnsigned(value, divisor), "random modulus " + divisor);
            }
        }
        try {
            BitMaps.mod(7, 0);
            throw new AssertionError("division-by-zero accepted");
        } catch (final ArithmeticException expected) {
            check("/ by zero".equals(expected.getMessage()), "division-by-zero message");
        }
        System.out.println("BITMAPS_MOD_BEHAVIOR_PASS checks=" + checks);
    }

    @SuppressWarnings("deprecation")
    private static long measure(final long[] numbers, final int divisor) {
        final ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isCurrentThreadCpuTimeSupported()) {
            throw new IllegalStateException("CPU time measurement is unsupported");
        }
        bean.setThreadCpuTimeEnabled(true);
        final long[] samples = new long[9];
        for (int trial = -3; trial < samples.length; trial++) {
            long value = 0;
            final long started = bean.getCurrentThreadCpuTime();
            for (int repeat = 0; repeat < 10; repeat++) {
                for (final long number : numbers) {
                    value += BitMaps.mod(number, divisor);
                }
            }
            final long cpu = bean.getCurrentThreadCpuTime() - started;
            blackhole = value;
            if (trial >= 0) {
                samples[trial] = cpu;
            }
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    private static void benchmark() {
        final long[] values = new long[16384];
        final Random random = new Random(0xB17F00DL);
        for (int i = 0; i < values.length; i++) {
            values[i] = random.nextLong();
        }
        final long pow2 = measure(values, 1 << 20);
        final long general = measure(values, (1 << 20) - 3);
        System.out.println("BITMAPS_MOD_WORK power2_ns=" + pow2 + " general_ns=" + general +
                " ratio=" + ((double) pow2 / general));
        // A successful fast path avoids the division that generic positive
        // divisors require. The ratio gate is intentionally interpreter-only.
        check(pow2 * 10L < general * 8L, "power-of-two fast path still divides");
        System.out.println("BITMAPS_MOD_PERF_PASS");
    }

    public static void main(final String[] args) {
        if (args.length > 0 && "perf".equals(args[0])) {
            benchmark();
        } else {
            behavior();
        }
    }
}
