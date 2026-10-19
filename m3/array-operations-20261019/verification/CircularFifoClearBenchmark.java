// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package com.synexia.verification;

import com.m3.collections.apache.queue.CircularFifoQueue;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CircularFifoClearBenchmark {
    @Param({"8", "64", "65536"}) public int capacity;
    private CircularFifoQueue<Object> queue;
    private final Object payload = new Object();
    @Setup public void setup() { queue = new CircularFifoQueue<>(capacity); }
    @Benchmark public int sparseClearAndRefill() {
        queue.add(payload); queue.clear(); return queue.size();
    }
    @Benchmark public int denseClearAndRefill() {
        for (int i = 0; i < capacity; i++) queue.add(payload);
        queue.clear(); return queue.size();
    }
}
