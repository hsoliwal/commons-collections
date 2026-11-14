// SPDX-License-Identifier: Apache-2.0
// Exploratory repeatable benchmark, not JMH. Same compiled harness for both variants.
import com.m3.collections.apache.ArrayStack;
import java.util.Arrays;

@SuppressWarnings("deprecation")
public final class ArrayStackSearchBenchmark {
    private static volatile long SINK;

    private static void exercise(ArrayStack<Object> stack, Object probe, int rounds) {
        long sum = 0;
        for (int i = 0; i < rounds; i++) {
            sum += stack.search(probe);
        }
        SINK = sum;
    }

    private static long median(long[] data) {
        Arrays.sort(data);
        return data[data.length / 2];
    }

    public static void main(String[] args) {
        final int n = Integer.parseInt(args[0]);
        final String workload = args[1];
        ArrayStack<Object> stack = new ArrayStack<>(n);
        for (int i = 0; i < n; i++) {
            stack.add("key-" + i);
        }
        Object query = workload.equals("miss") ? "key-MISSING" : "key-0";
        int rounds = Math.max(1000, 8000000 / n);
        exercise(stack, query, rounds);
        exercise(stack, query, rounds);
        long[] durations = new long[5];
        for (int k = 0; k < durations.length; k++) {
            long start = System.nanoTime();
            exercise(stack, query, rounds);
            durations[k] = System.nanoTime() - start;
        }
        int position = stack.search(query);
        System.out.printf("ARRAYSTACK_BENCH n=%d mode=%s rounds=%d probe=%d nsPerSearch=%.1f sink=%d%n",
                n, workload, rounds, position, (double) median(durations) / rounds, SINK);
    }
}
