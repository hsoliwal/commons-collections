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
// Modified 2026-11-15 by Hitesh Soliwal and Synexia contributors: defer and own pushback storage.
package org.apache.commons.collections4.iterators;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;

/**
 * Decorates an iterator to support pushback of elements.
 * <p>
 * The decorator stores the pushed back elements in a LIFO manner: the last element
 * that has been pushed back, will be returned as the next element in a call to {@link #next()}.
 * </p>
 * <p>
 * The decorator does not support the removal operation. Any call to {@link #remove()} will
 * result in an {@link UnsupportedOperationException}.
 * </p>
 *
 * @param <E> The type of elements returned by this iterator.
 * @since 4.0
 */
public class PushbackIterator<E> implements Iterator<E> {

    /** Shared storage before an item is pushed back. */
    private static final Object[] EMPTY_ITEMS = new Object[0];

    /**
     * Decorates the specified iterator to support one-element lookahead.
     * <p>
     * If the iterator is already a {@link PushbackIterator} it is returned directly.
     * </p>
     *
     * @param <E>  the element type
     * @param iterator  The iterator to decorate
     * @return A new peeking iterator
     * @throws NullPointerException if the iterator is null
     */
    public static <E> PushbackIterator<E> pushbackIterator(final Iterator<? extends E> iterator) {
        Objects.requireNonNull(iterator, "iterator");
        if (iterator instanceof PushbackIterator<?>) {
            @SuppressWarnings("unchecked") // safe cast
            final PushbackIterator<E> it = (PushbackIterator<E>) iterator;
            return it;
        }
        return new PushbackIterator<>(iterator);
    }

    /** The iterator being decorated. */
    private final Iterator<? extends E> iterator;

    /** The owned LIFO storage containing the pushed back items. */
    private Object[] items = EMPTY_ITEMS;

    /** The number of pushed back items. */
    private int itemCount;

    /**
     * Constructs a new instance.
     *
     * @param iterator  The iterator to decorate
     */
    public PushbackIterator(final Iterator<? extends E> iterator) {
        this.iterator = iterator;
    }

    @Override
    public boolean hasNext() {
        return itemCount != 0 || iterator.hasNext();
    }

    @Override
    public E next() {
        if (itemCount == 0) {
            return iterator.next();
        }
        @SuppressWarnings("unchecked")
        final E item = (E) items[--itemCount];
        items[itemCount] = null;
        return item;
    }

    /**
     * Push back the given element to the iterator.
     * <p>
     * Calling {@link #next()} immediately afterwards will return exactly this element.
     * </p>
     *
     * @param item  The element to push back to the iterator
     */
    public void pushback(final E item) {
        Objects.requireNonNull(item);
        if (itemCount == items.length) {
            final long required = (long) itemCount + 1;
            final long maximum = Integer.MAX_VALUE - 8L;
            if (required > maximum) {
                throw new OutOfMemoryError("PushbackIterator item capacity exhausted");
            }
            final int capacity = (int) Math.min(maximum,
                    Math.max(required, Math.max(8L, (long) items.length + (items.length >>> 1))));
            items = Arrays.copyOf(items, capacity);
        }
        items[itemCount++] = item;
    }

    /**
     * Always throws {@link UnsupportedOperationException}.
     *
     * @throws UnsupportedOperationException Always thrown.
     */
    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

}
