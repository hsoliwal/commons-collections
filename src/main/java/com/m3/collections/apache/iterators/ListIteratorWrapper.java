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
// Modified 2026 by Hitesh Soliwal and contributors: stable paged replay storage.
package com.m3.collections.apache.iterators;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;

import com.m3.collections.apache.ResettableIterator;
import com.m3.collections.apache.ResettableListIterator;

/**
 * Converts an {@link Iterator} into a {@link ResettableListIterator}.
 * For plain {@code Iterator}s this is accomplished by caching the returned
 * elements.  This class can also be used to simply add
 * {@link ResettableIterator ResettableIterator}
 * functionality to a given {@link ListIterator}.
 * <p>
 * The {@code ListIterator} interface has additional useful methods
 * for navigation - {@code previous()} and the index methods.
 * This class allows a regular {@code Iterator} to behave as a
 * {@code ListIterator}. It achieves this by building a list internally
 * of as the underlying iterator is traversed.
 * </p>
 * <p>
 * The optional operations of {@code ListIterator} are not supported for plain {@code Iterator}s.
 * </p>
 * <p>
 * This class implements ResettableListIterator from Commons Collections 3.2.
 * </p>
 *
 * @param <E> The type of elements in this iterator.
 * @since 2.1
 */
public class ListIteratorWrapper<E> implements ResettableListIterator<E> {

    /** Message used when set or add are called. */
    private static final String UNSUPPORTED_OPERATION_MESSAGE =
        "ListIteratorWrapper does not support optional operations of ListIterator.";

    /** Message used when set or add are called. */
    private static final String CANNOT_REMOVE_MESSAGE = "Cannot remove element at index {0}.";

    /** The underlying iterator being decorated. */
    private final Iterator<? extends E> iterator;

    /** Payload pages never move after reaching their bounded size. */
    private static final int PAGE_SHIFT = 10;
    private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    private static final int PAGE_MASK = PAGE_SIZE - 1;
    private static final Object[][] EMPTY_PAGES = new Object[0][];

    /** Native ListIterator delegation never allocates replay storage. */
    private Object[][] pages = EMPTY_PAGES;

    /** Kept separate from cursor state because child callbacks may reenter. */
    private int cacheSize;

    /** The current index of this iterator. */
    private int currentIndex;

    /** The current index of the wrapped iterator. */
    private int wrappedIteratorIndex;

    /** Recall whether the wrapped iterator's "cursor" is in such a state as to allow remove() to be called */
    private boolean removeState;

    /**
     * Constructs a new {@code ListIteratorWrapper} that will wrap
     * the given iterator.
     *
     * @param iterator  The iterator to wrap
     * @throws NullPointerException if the iterator is null
     */
    public ListIteratorWrapper(final Iterator<? extends E> iterator) {
        this.iterator = Objects.requireNonNull(iterator, "iterator");
    }

    /**
     * Throws {@link UnsupportedOperationException}
     * unless the underlying {@code Iterator} is a {@code ListIterator}.
     *
     * @param obj  The object to add
     * @throws UnsupportedOperationException if the underlying iterator is not of
     * type {@link ListIterator}
     */
    @Override
    public void add(final E obj) throws UnsupportedOperationException {
        if (iterator instanceof ListIterator) {
            @SuppressWarnings("unchecked")
            final ListIterator<E> li = (ListIterator<E>) iterator;
            li.add(obj);
            return;
        }
        throw new UnsupportedOperationException(UNSUPPORTED_OPERATION_MESSAGE);
    }

    /**
     * Returns true if there are more elements in the iterator.
     *
     * @return true if there are more elements
     */
    @Override
    public boolean hasNext() {
        if (currentIndex == wrappedIteratorIndex || iterator instanceof ListIterator) {
            return iterator.hasNext();
        }
        return true;
    }

    /**
     * Returns true if there are previous elements in the iterator.
     *
     * @return true if there are previous elements
     */
    @Override
    public boolean hasPrevious() {
        if (iterator instanceof ListIterator) {
            final ListIterator<?> li = (ListIterator<?>) iterator;
            return li.hasPrevious();
        }
        return currentIndex > 0;
    }

    /**
     * Returns the next element from the iterator.
     *
     * @return The next element
     * @throws NoSuchElementException if there are no more elements
     */
    @Override
    public E next() throws NoSuchElementException {
        if (iterator instanceof ListIterator) {
            return iterator.next();
        }

        if (currentIndex < wrappedIteratorIndex) {
            ++currentIndex;
            return cached(currentIndex - 1);
        }

        final E retval = iterator.next();
        cache(retval);
        ++currentIndex;
        ++wrappedIteratorIndex;
        removeState = true;
        return retval;
    }

    /**
     * Returns the index of the next element.
     *
     * @return The index of the next element
     */
    @Override
    public int nextIndex() {
        if (iterator instanceof ListIterator) {
            final ListIterator<?> li = (ListIterator<?>) iterator;
            return li.nextIndex();
        }
        return currentIndex;
    }

    /**
     * Returns the previous element.
     *
     * @return The previous element
     * @throws NoSuchElementException  if there are no previous elements
     */
    @Override
    public E previous() throws NoSuchElementException {
        if (iterator instanceof ListIterator) {
            @SuppressWarnings("unchecked")
            final ListIterator<E> li = (ListIterator<E>) iterator;
            return li.previous();
        }

        if (currentIndex == 0) {
            throw new NoSuchElementException();
        }
        removeState = wrappedIteratorIndex == currentIndex;
        return cached(--currentIndex);
    }

    /**
     * Returns the index of the previous element.
     *
     * @return  the index of the previous element
     */
    @Override
    public int previousIndex() {
        if (iterator instanceof ListIterator) {
            final ListIterator<?> li = (ListIterator<?>) iterator;
            return li.previousIndex();
        }
        return currentIndex - 1;
    }

    /**
     * Removes the last element that was returned by {@link #next()} or {@link #previous()} from the underlying collection.
     * This call can only be made once per call to {@code next} or {@code previous} and only if {@link #add(Object)} was not called in between.
     *
     * @throws IllegalStateException if {@code next} or {@code previous} have not been called before, or if {@code remove} or {@code add} have been called after the last call to {@code next} or {@code previous}
     */
    @Override
    public void remove() throws IllegalStateException {
        if (iterator instanceof ListIterator) {
            iterator.remove();
            return;
        }
        int removeIndex = currentIndex;
        if (currentIndex == wrappedIteratorIndex) {
            --removeIndex;
        }
        if (!removeState || wrappedIteratorIndex - currentIndex > 1) {
            throw new IllegalStateException(MessageFormat.format(CANNOT_REMOVE_MESSAGE, Integer.valueOf(removeIndex)));
        }
        iterator.remove();
        removeCached(removeIndex);
        currentIndex = removeIndex;
        wrappedIteratorIndex--;
        removeState = false;
    }

    /**
     * Resets this iterator back to the position at which the iterator
     * was created.
     *
     * @since 3.2
     */
    @Override
    public void reset()  {
        if (iterator instanceof ListIterator) {
            final ListIterator<?> li = (ListIterator<?>) iterator;
            while (li.previousIndex() >= 0) {
                li.previous();
            }
            return;
        }
        currentIndex = 0;
    }

    /**
     * Throws {@link UnsupportedOperationException}
     * unless the underlying {@code Iterator} is a {@code ListIterator}.
     *
     * @param obj  The object to set
     * @throws UnsupportedOperationException if the underlying iterator is not of
     * type {@link ListIterator}
     */
    @Override
    public void set(final E obj) throws UnsupportedOperationException {
        if (iterator instanceof ListIterator) {
            @SuppressWarnings("unchecked")
            final ListIterator<E> li = (ListIterator<E>) iterator;
            li.set(obj);
            return;
        }
        throw new UnsupportedOperationException(UNSUPPORTED_OPERATION_MESSAGE);
    }

    /** Retrieve by logical index without traversing a page directory. */
    @SuppressWarnings("unchecked")
    private E cached(final int index) {
        checkCacheIndex(index);
        return (E) pages[index >>> PAGE_SHIFT][index & PAGE_MASK];
    }

    private void checkCacheIndex(final int index) {
        if (index < 0 || index >= cacheSize) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + cacheSize);
        }
    }

    /** Grow only the page directory; full payload pages are never recopied. */
    private void cache(final E value) {
        if (cacheSize == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("ListIteratorWrapper cache capacity exhausted");
        }
        final int pageIndex = cacheSize >>> PAGE_SHIFT;
        final int offset = cacheSize & PAGE_MASK;
        Object[][] directory = pages;
        Object[] page = pageIndex < directory.length ? directory[pageIndex] : null;
        if (page == null) {
            // Preserve small-cache economy without a separate list owner.
            page = new Object[pageIndex < 2 ? 10 : PAGE_SIZE];
            if (pageIndex >= directory.length) {
                final int capacity = Math.max(pageIndex + 1,
                        directory.length + (directory.length >>> 1));
                directory = Arrays.copyOf(directory, capacity);
            }
            directory[pageIndex] = page;
            pages = directory;
        } else if (offset == page.length) {
            // Only the first two pages use bounded geometric growth.
            final int capacity = Math.min(PAGE_SIZE, page.length + (page.length >>> 1));
            page = Arrays.copyOf(page, capacity);
            directory[pageIndex] = page;
        }
        page[offset] = value;
        cacheSize++;
    }

    /**
     * Ordinarily removes the cached tail. Reentrant child removal may append
     * more values first, so preserve the former list's arbitrary-index removal.
     */
    private void removeCached(final int index) {
        checkCacheIndex(index);
        final int last = cacheSize - 1;
        int destination = index;
        while (destination < last) {
            final int source = destination + 1;
            final int length = Math.min(last - destination,
                    Math.min(PAGE_SIZE - (destination & PAGE_MASK), PAGE_SIZE - (source & PAGE_MASK)));
            System.arraycopy(pages[source >>> PAGE_SHIFT], source & PAGE_MASK,
                    pages[destination >>> PAGE_SHIFT], destination & PAGE_MASK, length);
            destination += length;
        }
        pages[last >>> PAGE_SHIFT][last & PAGE_MASK] = null;
        cacheSize = last;
    }

}
