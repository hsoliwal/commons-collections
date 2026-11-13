// SPDX-License-Identifier: Apache-2.0
// Exploratory Java21 benchmark for actual Commons SetUniqueList; not JMH.
import com.m3.collections.apache.list.SetUniqueList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Compares original item-wise removal with the candidate bulk method on the same class. */
public final class SetUniqueBulkBenchmark {
    private static volatile long sink;

    private static long run(List<Integer> data, Set<Integer> removal, boolean bulk) {
        // Do not time construction or initial de-duplication.
        SetUniqueList<Integer> list = SetUniqueList.setUniqueList(new ArrayList<>(data));
        long started = System.nanoTime();
        boolean changed;
        if (bulk) {
            changed = list.removeAll(removal);
        } else {
            changed = false;
            for (Object member : removal) {
                changed |= list.remove(member);
            }
        }
        long elapsed = System.nanoTime() - started;
        if (!changed || list.size() != data.size() - removal.size()
                || list.asSet().size() != list.size()) {
            throw new AssertionError("BULK_BENCH_CONTRACT_DRIFT");
        }
        sink += list.size();
        return elapsed;
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException("USAGE: size removedCount repetitions");
        }
        int size = Integer.parseInt(args[0]);
        int removed = Integer.parseInt(args[1]);
        int repetitions = Integer.parseInt(args[2]);
        if (size < 1 || removed < 1 || removed >= size || repetitions < 3) {
            throw new IllegalArgumentException("INVALID_BENCH_ARGUMENTS");
        }

        ArrayList<Integer> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(i);
        }
        HashSet<Integer> removal = new HashSet<>();
        for (int i = 0; i < removed; i++) {
            removal.add((i * 3) % size);
        }
        for (int i = 0; i < 6; i++) {
            run(values, removal, false);
            run(values, removal, true);
        }
        long[] original = new long[repetitions];
        long[] candidate = new long[repetitions];
        for (int i = 0; i < repetitions; i++) {
            // Alternate order to reduce hot/cold position bias.
            if ((i & 1) == 0) {
                original[i] = run(values, removal, false);
                candidate[i] = run(values, removal, true);
            } else {
                candidate[i] = run(values, removal, true);
                original[i] = run(values, removal, false);
            }
        }
        Arrays.sort(original);
        Arrays.sort(candidate);
        System.out.printf(Locale.ROOT,
                "SETUNIQUE_BULK_BENCH size=%d removal=%d original_median_ns=%d"
                        + " candidate_median_ns=%d samples=%d sink=%d%n",
                size, removed, original[repetitions / 2],
                candidate[repetitions / 2], repetitions, sink);
    }
}
