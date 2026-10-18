// SPDX-License-Identifier: Apache-2.0
package com.m3.collections.apache.queue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;

/** Fork-only differential: original Apache API versus package-only M3 relocation. */
public final class M3ApacheCircularFifoRelocationProof {
    private static long checks;
    private M3ApacheCircularFifoRelocationProof() { }

    private static void same(Object expected, Object actual, String label) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }
    private static void sameArray(Object[] expected, Object[] actual, String label) {
        checks++;
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(label + " expected=" + Arrays.toString(expected)
                    + " actual=" + Arrays.toString(actual));
        }
    }
    private static <T> T copy(T object) throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(data)) {
            out.writeObject(object);
        }
        try (ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(data.toByteArray()))) {
            @SuppressWarnings("unchecked") T result = (T) in.readObject();
            return result;
        }
    }

    private static void parity(org.apache.commons.collections4.queue.CircularFifoQueue<Integer> donor,
            CircularFifoQueue<Integer> relocated, int limit, String label) {
        same(donor.size(), relocated.size(), label + " size");
        same(donor.isEmpty(), relocated.isEmpty(), label + " empty");
        same(donor.maxSize(), relocated.maxSize(), label + " capacity");
        same(donor.isAtFullCapacity(), relocated.isAtFullCapacity(), label + " full");
        same(donor.isFull(), relocated.isFull(), label + " isFull admission");
        same(donor.peek(), relocated.peek(), label + " peek");
        sameArray(donor.toArray(), relocated.toArray(), label + " order");
        for (int i = 0; i < donor.size(); i++) {
            same(donor.get(i), relocated.get(i), label + " logical offset " + i);
        }
        same(limit, relocated.maxSize(), label + " bound");
    }
    private static void removeOccurrence(
            org.apache.commons.collections4.queue.CircularFifoQueue<Integer> donor,
            CircularFifoQueue<Integer> relocated, int ordinal) {
        Iterator<Integer> first = donor.iterator();
        Iterator<Integer> second = relocated.iterator();
        for (int i = 0; i <= ordinal; i++) {
            same(first.next(), second.next(), "iterator value");
        }
        first.remove();
        second.remove();
    }
    private static void negativeCases() {
        org.apache.commons.collections4.queue.CircularFifoQueue<Integer> a =
                new org.apache.commons.collections4.queue.CircularFifoQueue<>(2);
        CircularFifoQueue<Integer> b = new CircularFifoQueue<>(2);
        boolean aNull = false, bNull = false;
        try { a.add(null); } catch (NullPointerException ex) { aNull = true; }
        try { b.add(null); } catch (NullPointerException ex) { bNull = true; }
        same(true, aNull, "Apache original null admission");
        same(aNull, bNull, "relocated null admission");
        boolean aEmpty = false, bEmpty = false;
        try { a.remove(); } catch (NoSuchElementException ex) { aEmpty = true; }
        try { b.remove(); } catch (NoSuchElementException ex) { bEmpty = true; }
        same(true, aEmpty, "Apache empty remove");
        same(aEmpty, bEmpty, "relocated empty remove");
        boolean badCapA = false, badCapB = false;
        try { new org.apache.commons.collections4.queue.CircularFifoQueue<Integer>(0); }
        catch (IllegalArgumentException ex) { badCapA = true; }
        try { new CircularFifoQueue<Integer>(0); }
        catch (IllegalArgumentException ex) { badCapB = true; }
        same(true, badCapA, "Apache capacity");
        same(badCapA, badCapB, "relocated capacity");
    }
    private static void randomized() throws Exception {
        Random random = new Random(0x4d33434150414349L);
        for (int capacity : new int[] {1, 2, 3, 7, 16, 31, 64}) {
            org.apache.commons.collections4.queue.CircularFifoQueue<Integer> donor =
                    new org.apache.commons.collections4.queue.CircularFifoQueue<>(capacity);
            CircularFifoQueue<Integer> relocated = new CircularFifoQueue<>(capacity);
            for (int iteration = 0; iteration < 800; iteration++) {
                int choice = random.nextInt(8);
                switch (choice) {
                    case 0, 1, 2 -> {
                        int value = random.nextInt(21) - 10;
                        same(donor.add(value), relocated.add(value), "add");
                    }
                    case 3 -> same(donor.poll(), relocated.poll(), "poll");
                    case 4 -> {
                        if (!donor.isEmpty()) {
                            same(donor.remove(), relocated.remove(), "remove");
                        }
                    }
                    case 5 -> {
                        if (random.nextInt(11) == 0) {donor.clear();relocated.clear();}
                    }
                    case 6 -> {
                        if (!donor.isEmpty()) {
                            int which = random.nextInt(donor.size());
                            removeOccurrence(donor, relocated, which);
                        }
                    }
                    default -> { if (!donor.isEmpty()) same(donor.element(), relocated.element(), "element"); }
                }
                parity(donor, relocated, capacity, "capacity=" + capacity + " step=" + iteration);
                if (iteration % 97 == 0) {
                    parity(copy(donor), copy(relocated), capacity, "serialized roundtrip");
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("no arguments accepted");
        negativeCases();
        randomized();
        System.out.println("PASS M3_APACHE_PACKAGE_RELOCATION checks=" + checks);
    }
}
