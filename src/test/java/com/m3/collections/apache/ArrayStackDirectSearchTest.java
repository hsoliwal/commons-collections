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
package com.m3.collections.apache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Preserves reverse-search, virtual-get and user-defined equality contracts. */
@SuppressWarnings("deprecation")
class ArrayStackDirectSearchTest {

    private static int originalSearch(final ArrayStack<?> stack, final Object object) {
        int index = stack.size() - 1;
        int depth = 1;
        while (index >= 0) {
            final Object current = stack.get(index);
            if (object == null && current == null || object != null && object.equals(current)) {
                return depth;
            }
            index--;
            depth++;
        }
        return -1;
    }

    @Test
    void emptyNullDuplicatesAndOneBasedDepth() {
        final ArrayStack<Object> stack = new ArrayStack<>();
        assertEquals(-1, stack.search(null));
        assertEquals(-1, stack.search("absent"));
        stack.add(null);
        stack.add("text");
        stack.add(17);
        stack.add("text");
        stack.add(null);
        assertEquals(1, stack.search(null));
        assertEquals(2, stack.search("text"));
        assertEquals(3, stack.search(17));
        assertEquals(-1, stack.search(17L));
        stack.pop();
        assertEquals(4, stack.search(null));
        assertEquals(1, stack.search("text"));
    }

    @Test
    void seededArrayBackedAndLegacySearchAreEquivalentAcrossThreshold() {
        final Random random = new Random(0x5eede21L);
        final Object[] pool = {null, "a", "b", "c", Integer.valueOf(1),
                Integer.valueOf(2), Long.valueOf(1L), Long.valueOf(2L),
                Double.valueOf(1.0), Boolean.TRUE};
        for (final int length : new int[] {0, 1, 2, 31, 127, 1023, 2047, 2048, 4096}) {
            final ArrayStack<Object> stack = new ArrayStack<>(length);
            for (int i = 0; i < length; i++) {
                stack.add(pool[random.nextInt(pool.length)]);
            }
            for (int j = 0; j < 256; j++) {
                final Object query = j % 13 == 0 ? "missing" : pool[random.nextInt(pool.length)];
                assertEquals(originalSearch(stack, query), stack.search(query),
                        "length=" + length + " query=" + query + " iteration=" + j);
            }
        }
    }

    @Test
    void subclassVirtualGetIsNeverBypassed() {
        final class VirtualStack extends ArrayStack<Object> {
            private static final long serialVersionUID = 1L;
            private int readCount;

            @Override
            public Object get(final int index) {
                readCount++;
                return index == 1 ? "virtual" : super.get(index);
            }
        }
        final VirtualStack stack = new VirtualStack();
        for (int i = 0; i < 4096; i++) {
            stack.add("unrelated-" + i);
        }
        assertEquals(4095, stack.search("virtual"));
        assertTrue(stack.readCount > 0, "search must dispatch the subclass getter");
    }

    @Test
    void opaqueEqualsCanMutateTheBackingArrayAndMustUseLegacyTraversal() {
        final ArrayStack<Object> stack = new ArrayStack<>();
        stack.add("bottom");
        stack.add("middle");
        stack.add("top");
        final class DeletingNeedle {
            private int comparisons;

            @Override
            public boolean equals(final Object value) {
                comparisons++;
                stack.remove(0);
                return false;
            }

            @Override
            public int hashCode() {
                return 0;
            }
        }
        final DeletingNeedle needle = new DeletingNeedle();
        assertEquals(-1, stack.search(needle));
        assertEquals(3, needle.comparisons);
        assertTrue(stack.isEmpty());
    }

    @Test
    void stableSerializationAndSearchAfterRoundTrip() throws Exception {
        final ArrayStack<Object> stack = new ArrayStack<>();
        stack.push("original");
        stack.push(null);
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(encoded)) {
            out.writeObject(stack);
        }
        final Object decoded;
        try (ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(encoded.toByteArray()))) {
            decoded = in.readObject();
        }
        assertTrue(decoded instanceof ArrayStack<?>);
        final ArrayStack<?> restored = (ArrayStack<?>) decoded;
        assertEquals(stack, restored);
        assertEquals(1, restored.search(null));
        assertEquals(2, restored.search("original"));
    }
}
