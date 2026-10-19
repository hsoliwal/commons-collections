// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
import com.m3.collections.apache.queue.CircularFifoQueue;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/** Independent FIFO oracle across wrapped storage, iterator removal, serialization and reuse. */
public final class CircularFifoClearContract {
    private static long checks, transcript = 1;
    private static void require(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("check " + checks);
    }
    private static final class HookQueue extends CircularFifoQueue<Integer> {
        private static final long serialVersionUID = 1L;
        int calls;
        HookQueue(int capacity) { super(capacity); }
        @Override public int size() { calls++; return super.size(); }
        @Override public boolean isEmpty() { calls++; return super.isEmpty(); }
    }
    public static void main(String[] args) throws Exception {
        Field storage = CircularFifoQueue.class.getDeclaredField("elements");
        storage.setAccessible(true);
        Random random = new Random(38119);
        for (int capacity : new int[]{1, 2, 3, 7, 8, 31, 64, 1024}) {
            CircularFifoQueue<Integer> queue = new CircularFifoQueue<>(capacity);
            ArrayList<Integer> oracle = new ArrayList<>();
            for (int step = 0; step < 50000; step++) {
                int operation = random.nextInt(10);
                if (operation < 5) {
                    int value = random.nextInt();
                    queue.add(value);
                    if (oracle.size() == capacity) oracle.remove(0);
                    oracle.add(value);
                } else if (operation == 5) {
                    require(Objects.equals(queue.poll(), oracle.isEmpty() ? null : oracle.remove(0)));
                } else if (operation == 6 && !oracle.isEmpty()) {
                    int index = random.nextInt(oracle.size());
                    Iterator<Integer> iterator = queue.iterator();
                    for (int i = 0; i <= index; i++) iterator.next();
                    iterator.remove(); oracle.remove(index);
                } else if (operation == 7) {
                    Object[] array = (Object[]) storage.get(queue);
                    queue.clear(); oracle.clear();
                    require(storage.get(queue) == array);
                    for (Object element : array) require(element == null);
                    queue.clear();
                }
                require(queue.size() == oracle.size());
                require(Arrays.equals(queue.toArray(), oracle.toArray()));
                require(Objects.equals(queue.peek(), oracle.isEmpty() ? null : oracle.get(0)));
                transcript = transcript * 31 + queue.size();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(queue); }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                Object restored = in.readObject();
                require(restored instanceof CircularFifoQueue<?>);
                CircularFifoQueue<?> copy = (CircularFifoQueue<?>) restored;
                require(Arrays.equals(copy.toArray(), oracle.toArray()));
                copy.clear(); require(copy.isEmpty());
                for (Object element : (Object[]) storage.get(copy)) require(element == null);
            }
            HookQueue hooks = new HookQueue(capacity);
            hooks.add(1); hooks.calls = 0; hooks.clear();
            require(hooks.calls == 0);
            require(hooks.isEmpty());
        }
        System.out.println("checks=" + checks + " transcript=" + transcript);
    }
}
