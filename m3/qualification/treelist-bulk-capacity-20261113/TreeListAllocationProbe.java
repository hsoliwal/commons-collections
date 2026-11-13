/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import com.m3.collections.apache.list.TreeList;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Narrow per-thread allocation observation; not a timing benchmark. */
public final class TreeListAllocationProbe {
    private static volatile TreeList<Integer> sink;

    private TreeListAllocationProbe() {
    }

    private static int capacity(final TreeList<Integer> list) throws ReflectiveOperationException {
        final Field slots = TreeList.class.getDeclaredField("slots");
        slots.setAccessible(true);
        final Object store = slots.get(list);
        final Field values = store.getClass().getDeclaredField("values");
        values.setAccessible(true);
        return ((Object[]) values.get(store)).length;
    }

    public static void main(final String[] args) throws ReflectiveOperationException {
        final java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
        if (!(base instanceof ThreadMXBean)) {
            throw new IllegalStateException("Thread allocation counter unavailable");
        }
        final ThreadMXBean bean = (ThreadMXBean) base;
        if (!bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("Thread allocation counter unsupported");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        final long thread = Thread.currentThread().threadId();
        final List<Integer> input = Collections.nCopies(100000, 7);
        for (final String workload : new String[] {"constructor", "append-small", "append-large", "reuse", "single-add"}) {
            final long[] allocations = new long[7];
            for (int trial = -3; trial < allocations.length; trial++) {
                TreeList<Integer> list = new TreeList<>();
                if ("append-small".equals(workload)) {
                    list.addAll(Collections.nCopies(8, 7));
                } else if ("append-large".equals(workload) || "reuse".equals(workload)) {
                    list.addAll(Collections.nCopies(120000, 7));
                    for (int i = 0; i < ("reuse".equals(workload) ? 110000 : 30000); i++) {
                        list.remove(0);
                    }
                }
                final long before = bean.getThreadAllocatedBytes(thread);
                if ("constructor".equals(workload)) {
                    list = new TreeList<>(input);
                } else if ("single-add".equals(workload)) {
                    for (int i = 0; i < input.size(); i++) {
                        list.add(7);
                    }
                } else {
                    list.addAll(input);
                }
                sink = list;
                final long allocated = bean.getThreadAllocatedBytes(thread) - before;
                if (trial >= 0) {
                    allocations[trial] = allocated;
                }
            }
            Arrays.sort(allocations);
            System.out.println(workload + " median_bytes=" + allocations[3]
                    + " min_bytes=" + allocations[0] + " max_bytes=" + allocations[6]
                    + " final_capacity=" + capacity(sink));
        }
    }
}
