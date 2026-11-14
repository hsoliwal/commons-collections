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
package com.m3.collections.apache.iterators;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** A bytecode-compatible constructor ownership and array-backed zip regression. */
public class ZippingIteratorConstructorArrayTest {
    private static int checks;

    private static void check(final boolean value) {
        ++checks;
        if (!value) throw new AssertionError("ZIP_OWNED_ARRAY_CHECK_" + checks);
    }

    private static List<Integer> collect(final ZippingIterator<Integer> iterator) {
        final List<Integer> result = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            final boolean available = iterator.hasNext();
            check(available == iterator.hasNext());
            if (!available) return result;
            result.add(iterator.next());
        }
        throw new AssertionError("ZIP_ITERATOR_DID_NOT_FINISH");
    }

    @Test
    public void twoAndThreeOverloadsUseOwnedArrays() {
        final ZippingIterator<Integer> two = new ZippingIterator<>(
                Arrays.asList(1, 3, 5).iterator(), Arrays.asList(2, 4).iterator());
        check(collect(two).equals(Arrays.asList(1, 2, 3, 4, 5)));
        final ZippingIterator<Integer> three = new ZippingIterator<>(
                Arrays.asList(1, 4).iterator(),
                Collections.<Integer>emptyList().iterator(),
                Arrays.asList(3, 6).iterator());
        check(collect(three).equals(Arrays.asList(1, 3, 4, 6)));
    }

    @Test
    public void callerVarargsArrayIsStillCloned() throws Exception {
        @SuppressWarnings("unchecked")
        final Iterator<Integer>[] source = new Iterator[] {
            Collections.singletonList(11).iterator(),
            Collections.singletonList(22).iterator(),
            Collections.singletonList(33).iterator(),
            Collections.singletonList(44).iterator()
        };
        final ZippingIterator<Integer> result = new ZippingIterator<>(source);
        final Field field = ZippingIterator.class.getDeclaredField("iterators");
        field.setAccessible(true);
        check(field.get(result) != source);
        source[0] = Collections.singletonList(99).iterator();
        check(collect(result).equals(Arrays.asList(11, 22, 33, 44)));
        check(Modifier.isPrivate(ZippingIterator.class
                .getDeclaredConstructor(Iterator[].class, boolean.class).getModifiers()));
    }

    @Test
    public void removeAndNullContractsStayUnchanged() {
        final List<Integer> first = new ArrayList<>(Arrays.asList(1, 3));
        final List<Integer> second = new ArrayList<>(Arrays.asList(2, 4));
        final ZippingIterator<Integer> zipper = new ZippingIterator<>(
                first.iterator(), second.iterator());
        check(zipper.next().intValue() == 1);
        zipper.remove();
        check(first.equals(Collections.singletonList(3)));
        check(zipper.next().intValue() == 2);
        zipper.remove();
        check(second.equals(Collections.singletonList(4)));
        check(collect(zipper).equals(Arrays.asList(3, 4)));
        try {
            new ZippingIterator<Integer>(first.iterator(), null);
            throw new AssertionError("null child accepted");
        } catch (NullPointerException expected) { ++checks; }
        try {
            new ZippingIterator<Integer>(first.iterator(), null, second.iterator());
            throw new AssertionError("null child accepted");
        } catch (NullPointerException expected) { ++checks; }
        try {
            new ZippingIterator<Integer>().next();
            throw new AssertionError("empty next accepted");
        } catch (NoSuchElementException expected) { ++checks; }
    }

    @Test
    public void randomDifferentialWithRoundRobinReference() {
        final Random random = new Random(0x5a1e2026L);
        for (int trial = 0; trial < 2000; trial++) {
            final int count = random.nextInt(5);
            @SuppressWarnings("unchecked")
            final Iterator<Integer>[] source = new Iterator[count];
            final List<List<Integer>> inputs = new ArrayList<>();
            for (int slot = 0; slot < count; slot++) {
                final List<Integer> list = new ArrayList<>();
                for (int n = random.nextInt(9); n > 0; n--) list.add(random.nextInt());
                inputs.add(list);
                source[slot] = list.iterator();
            }
            final ZippingIterator<Integer> zipper;
            if (count == 2) zipper = new ZippingIterator<>(source[0], source[1]);
            else if (count == 3) zipper = new ZippingIterator<>(
                    source[0], source[1], source[2]);
            else zipper = new ZippingIterator<>(source);
            final List<Integer> expected = new ArrayList<>();
            for (int offset = 0; ; offset++) {
                boolean any = false;
                for (final List<Integer> list : inputs) {
                    if (offset < list.size()) {
                        expected.add(list.get(offset));
                        any = true;
                    }
                }
                if (!any) break;
            }
            check(expected.equals(collect(zipper)));
        }
    }

    public static void main(final String[] args) throws Exception {
        final ZippingIteratorConstructorArrayTest test =
                new ZippingIteratorConstructorArrayTest();
        test.twoAndThreeOverloadsUseOwnedArrays();
        test.callerVarargsArrayIsStillCloned();
        test.removeAndNullContractsStayUnchanged();
        test.randomDifferentialWithRoundRobinReference();
        System.out.println("ZIP_CONSTRUCTOR_ARRAY_PASS checks=" + checks);
    }
}
