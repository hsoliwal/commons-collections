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
// Array-operation contribution: Copyright (c) 2026 Hitesh Soliwal and Synexia contributors.
// Modified 2026 by Hitesh Soliwal and Contributors to the Synexia Project:
// bounded contiguous-span compaction and array export of the existing array-owned ring.
package com.m3.collections.apache.queue;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;

import com.m3.collections.apache.BoundedCollection;

/**
 * CircularFifoQueue is a first-in first-out queue with a fixed size that
 * replaces its oldest element if full.
 * <p>
 * The removal order of a {@link CircularFifoQueue} is based on the
 * insertion order; elements are removed in the same order in which they
 * were added.  The iteration order is the same as the removal order.
 * </p>
 * <p>
 * The {@link #add(Object)}, {@link #remove()}, {@link #peek()}, {@link #poll()},
 * {@link #offer(Object)} operations all perform in constant time.
 * All other operations perform in linear time or worse.
 * </p>
 * <p>
 * This queue prevents null objects from being added.
 * </p>
 *
 * @param <E> The type of elements in this collection
 * @since 4.0
 */
public class CircularFifoQueue<E> extends AbstractCollection<E>
    implements Queue<E>, BoundedCollection<E>, Serializable {

    /** Serialization version. */
    private static final long serialVersionUID = -8423413834657610406L;

    /** Underlying storage array. */
    private transient E[] elements;

    /** Array index of first (oldest) queue element. */
    private transient int start;

    /**
     * Index mod maxElements of the array position following the last queue
     * element.  Queue elements start at elements[start] and "wrap around"
     * elements[maxElements-1], ending at elements[decrement(end)].
     * For example, elements = {c,a,b}, start=1, end=1 corresponds to
     * the queue [a,b,c].
     */
    private transient int end;

    /** Flag to indicate if the queue is currently full. */
    private transient boolean full;

    /** Capacity of the queue. */
    private final int maxElements;

    /**
     * Constructor that creates a queue with the default size of 32.
     */
    public CircularFifoQueue() {
        this(32);
    }

    /**
     * Constructor that creates a queue from the specified collection.
     * The collection size also sets the queue size.
     *
     * @param coll  The collection to copy into the queue, may not be null
     * @throws NullPointerException if the collection is null
     */
    public CircularFifoQueue(final Collection<? extends E> coll) {
        this(coll.size());
        addAll(coll);
    }

    /**
     * Constructor that creates a queue with the specified size.
     *
     * @param size  The size of the queue (cannot be changed)
     * @throws IllegalArgumentException  if the size is &lt; 1
     */
    @SuppressWarnings("unchecked")
    public CircularFifoQueue(final int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("The size must be greater than 0");
        }
        elements = (E[]) new Object[size];
        maxElements = elements.length;
    }

    /**
     * Adds the given element to this queue. If the queue is full, the least recently added
     * element is discarded so that a new element can be inserted.
     *
     * @param element  The element to add
     * @return true, always
     * @throws NullPointerException  if the given element is null
     */
    @Override
    public boolean add(final E element) {
        Objects.requireNonNull(element, "element");

        // An exact CircularFifoQueue owns its ring without subclass hooks.
        // In the full state, start == end. Replacing that one slot and
        // advancing both cursors preserves the original physical layout,
        // without clearing and rewriting the evicted reference.
        // Retain virtual isAtFullCapacity()/remove() for every subclass.
        if (full && getClass() == CircularFifoQueue.class) {
            final int next = increment(end);
            elements[end] = element;
            start = next;
            end = next;
            return true;
        }

        if (isAtFullCapacity()) {
            remove();
        }

        elements[end++] = element;

        if (end >= maxElements) {
            end = 0;
        }

        if (end == start) {
            full = true;
        }

        return true;
    }

    /**
     * Bulk insert for an exact ArrayList source. The ring retains only its final
     * capacity worth of elements, so an oversized batch need not write entries
     * that would be immediately evicted. No temporary array is allocated.
     *
     * <p>All unknown collections and subclasses use the inherited iterator
     * protocol. A null element preserves the original successful-prefix writes
     * before the original exception, rather than claiming atomic failure.
     * This class remains intentionally unsynchronized.</p>
     *
     * @param values elements to add, in encounter order
     * @return true if at least one element was inserted
     */
    @Override
    public boolean addAll(final Collection<? extends E> values) {
        if (getClass() != CircularFifoQueue.class || values == null
                || values.getClass() != ArrayList.class) {
            return super.addAll(values);
        }
        final ArrayList<? extends E> input = (ArrayList<? extends E>) values;
        final int count = input.size();
        if (count == 0) {
            return false;
        }

        // Preserve inherited addAll's partial mutation on the first null.
        // The exact ArrayList.get implementation has no overridable callbacks.
        for (int index = 0; index < count; index++) {
            if (input.get(index) == null) {
                for (int accepted = 0; accepted < index; accepted++) {
                    add(input.get(accepted));
                }
                throw new NullPointerException("element");
            }
        }

        // Recreate the *physical* ring positions of count serial add calls.
        // Only the final min(count, capacity) writes can remain observable.
        final int retained = Math.min(count, maxElements);
        final int firstSource = count - retained;
        int physical = (int) (((long) end + firstSource) % maxElements);
        for (int index = firstSource; index < count; index++) {
            elements[physical] = input.get(index);
            if (++physical == maxElements) {
                physical = 0;
            }
        }
        final int nextEnd = (int) (((long) end + count) % maxElements);
        final boolean saturated = count >= maxElements - size();
        if (saturated) {
            start = nextEnd;
        }
        end = nextEnd;
        full = saturated;
        return true;
    }

    /**
     * Clears this queue.
     */
    @Override
    public void clear() {
        // Free slots are already null; use the existing ring indices to release live payloads.
        if (full) {
            Arrays.fill(elements, null);
        } else if (start < end) {
            Arrays.fill(elements, start, end, null);
        } else if (start > end) {
            Arrays.fill(elements, start, maxElements, null);
            Arrays.fill(elements, 0, end, null);
        }
        full = false;
        start = 0;
        end = 0;
    }

    /**
     * Checks the live ring in iterator order without constructing an iterator.
     *
     * <p>The local cursor deliberately follows the existing iterator's
     * full-ring and dynamic-end rules. In particular, the candidate's
     * {@code equals} method runs after advancing the local cursor, so a
     * reentrant modification has the same subsequent traversal behavior as
     * the inherited {@link AbstractCollection#contains(Object)}.
     * Subclasses retain inherited dispatch through their own iterators.</p>
     *
     * @param candidate the object to find, possibly null
     * @return whether the FIFO sequence contains the object
     */
    @Override
    public boolean contains(final Object candidate) {
        if (getClass() != CircularFifoQueue.class) {
            return super.contains(candidate);
        }
        int cursor = start;
        boolean first = full;
        while (first || cursor != end) {
            first = false;
            final E element = elements[cursor];
            cursor = increment(cursor);
            if (candidate == null ? element == null : candidate.equals(element)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copies the live ring spans in FIFO order into an API-owned array.
     *
     * @param array The destination array.
     * @param size The number of live elements to copy.
     */
    private void copyElements(final Object[] array, final int size) {
        if (size == 0) {
            return;
        }
        if (size == 1) {
            array[0] = elements[start];
            return;
        }
        final int firstSpan = Math.min(size, maxElements - start);
        System.arraycopy(elements, start, array, 0, firstSpan);
        if (firstSpan < size) {
            System.arraycopy(elements, 0, array, firstSpan, size - firstSpan);
        }
    }

    /**
     * Decrements the internal index.
     *
     * @param index  The index to decrement
     * @return The updated index
     */
    private int decrement(int index) {
        index--;
        if (index < 0) {
            index = maxElements - 1;
        }
        return index;
    }

    @Override
    public E element() {
        if (isEmpty()) {
            throw new NoSuchElementException("queue is empty");
        }
        return peek();
    }

    /**
     * Gets the element at the specified position in this queue.
     *
     * @param index The position of the element in the queue
     * @return The element at position {@code index}
     * @throws NoSuchElementException if the requested position is outside the range [0, size)
     */
    public E get(final int index) {
        final int sz = size();
        if (index < 0 || index >= sz) {
            throw new NoSuchElementException(
                    String.format("The specified index %1$d is outside the available range [0, %2$d)",
                                  Integer.valueOf(index), Integer.valueOf(sz)));
        }

        // Use two validated spans to avoid division and index-sum overflow.
        final int untilWrap = maxElements - start;
        final int idx = index < untilWrap ? start + index : index - untilWrap;
        return elements[idx];
    }

    /**
     * Increments the internal index.
     *
     * @param index  The index to increment
     * @return The updated index
     */
    private int increment(int index) {
        index++;
        if (index >= maxElements) {
            index = 0;
        }
        return index;
    }

    /**
     * Returns {@code true} if the capacity limit of this queue has been reached,
     * i.e. the number of elements stored in the queue equals its maximum size.
     *
     * @return {@code true} if the capacity limit has been reached, {@code false} otherwise
     * @since 4.1
     */
    public boolean isAtFullCapacity() {
        return size() == maxElements;
    }

    /**
     * Returns true if this queue is empty; false otherwise.
     *
     * @return true if this queue is empty
     */
    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * {@inheritDoc}
     * <p>
     * A {@code CircularFifoQueue} can never be full, thus this returns always
     * {@code false}.
     *
     * @return always returns {@code false}
     */
    @Override
    public boolean isFull() {
        return false;
    }

    /**
     * Returns an iterator over this queue's elements.
     *
     * @return An iterator over this queue's elements
     */
    @Override
    public Iterator<E> iterator() {
        return new Iterator<E>() {

            private int index = start;
            private int lastReturnedIndex = -1;
            private boolean isFirst = full;

            @Override
            public boolean hasNext() {
                return isFirst || index != end;
            }

            @Override
            public E next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                isFirst = false;
                lastReturnedIndex = index;
                index = increment(index);
                return elements[lastReturnedIndex];
            }

            @Override
            public void remove() {
                if (lastReturnedIndex == -1) {
                    throw new IllegalStateException();
                }

                // First element can be removed quickly
                if (lastReturnedIndex == start) {
                    CircularFifoQueue.this.remove();
                    lastReturnedIndex = -1;
                    return;
                }

                shiftSuffixTowardRemovedSlot(lastReturnedIndex);

                lastReturnedIndex = -1;
                end = decrement(end);
                elements[end] = null;
                full = false;
                index = decrement(index);
            }

        };
    }

    /**
     * Gets the maximum size of the collection (the bound).
     *
     * @return The maximum number of elements the collection can hold
     */
    @Override
    public int maxSize() {
        return maxElements;
    }

    /**
     * Adds the given element to this queue. If the queue is full, the least recently added
     * element is discarded so that a new element can be inserted.
     *
     * @param element  The element to add
     * @return true, always
     * @throws NullPointerException  if the given element is null
     */
    @Override
    public boolean offer(final E element) {
        return add(element);
    }

    @Override
    public E peek() {
        if (isEmpty()) {
            return null;
        }
        return elements[start];
    }

    @Override
    public E poll() {
        if (isEmpty()) {
            return null;
        }
        return remove();
    }

    /**
     * Deserializes the queue in using a custom routine.
     *
     * @param in  The input stream
     * @throws IOException Thrown if an I/O error occurs while writing to the output stream
     * @throws ClassNotFoundException if the class of a serialized object cannot be found
     */
    @SuppressWarnings("unchecked")
    private void readObject(final ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        if (maxElements < 1) {
            throw new InvalidObjectException("maxElements must be greater than 0");
        }
        elements = (E[]) new Object[maxElements];
        final int size = in.readInt();
        if (size < 0 || size > maxElements) {
            throw new InvalidObjectException("size is out of range: " + size);
        }
        for (int i = 0; i < size; i++) {
            elements[i] = (E) in.readObject();
        }
        start = 0;
        full = size == maxElements;
        if (full) {
            end = 0;
        } else {
            end = size;
        }
    }

    /**
     * Removes the first matching ring element without allocating an iterator.
     *
     * <p>The physical cursor and the removal path reproduce the inherited
     * AbstractCollection iterator algorithm, including candidate-equals
     * callback timing, the head fast path, and contiguous suffix shifts.
     * Subclasses retain their iterator-based removal contract.</p>
     *
     * @param candidate the value to remove, possibly null
     * @return true if a matching element was found
     */
    @Override
    public boolean remove(final Object candidate) {
        if (getClass() != CircularFifoQueue.class) {
            return super.remove(candidate);
        }
        int cursor = start;
        boolean first = full;
        while (first || cursor != end) {
            first = false;
            final int returned = cursor;
            cursor = increment(cursor);
            final E element = elements[returned];
            if (candidate == null ? element == null : candidate.equals(element)) {
                if (returned == start) {
                    remove();
                } else {
                    shiftSuffixTowardRemovedSlot(returned);
                    end = decrement(end);
                    elements[end] = null;
                    full = false;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public E remove() {
        if (isEmpty()) {
            throw new NoSuchElementException("queue is empty");
        }

        final E element = elements[start];
        if (element != null) {
            elements[start++] = null;

            if (start >= maxElements) {
                start = 0;
            }
            full = false;
        }
        return element;
    }

    /**
     * Shifts the remaining suffix into an iterator's removed slot using the
     * ring's contiguous array spans. The caller owns cursor and end-state updates.
     *
     * @param removedIndex The physical slot removed by the iterator.
     */
    private void shiftSuffixTowardRemovedSlot(final int removedIndex) {
        final int following = removedIndex + 1;
        if (following < end) {
            System.arraycopy(elements, following, elements, removedIndex, end - following);
        } else if (following > end) {
            System.arraycopy(elements, following, elements, removedIndex, maxElements - following);
            if (end > 0) {
                elements[maxElements - 1] = elements[0];
                System.arraycopy(elements, 1, elements, 0, end - 1);
            }
        }
    }

    /**
     * Returns the number of elements stored in the queue.
     *
     * @return this queue's size
     */
    @Override
    public int size() {
        int size = 0;

        if (end < start) {
            size = maxElements - start + end;
        } else if (end == start) {
            size = full ? maxElements : 0;
        } else {
            size = end - start;
        }

        return size;
    }

    /**
     * Returns a new array containing the queue elements in FIFO order.
     *
     * @return An independent array containing the queue elements.
     */
    @Override
    public Object[] toArray() {
        if (getClass() != CircularFifoQueue.class) {
            // Subclasses may expose a different iterator or size.
            return super.toArray();
        }
        final int size = size();
        final Object[] array = new Object[size];
        copyElements(array, size);
        return array;
    }

    /**
     * Copies the queue elements in FIFO order into an array of the requested type.
     * A sufficiently large input array is reused and, if larger than the queue,
     * its first unused position is set to {@code null}.
     *
     * @param <T> The component type of the destination array.
     * @param array The destination array, or an array specifying the result type.
     * @return The input array if large enough; otherwise a new array of the same type.
     * @throws NullPointerException if the input array is null.
     * @throws ArrayStoreException if a queue element is incompatible with the array type.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(final T[] array) {
        if (getClass() != CircularFifoQueue.class) {
            return super.toArray(array);
        }
        final int size = size();
        final T[] result = array.length >= size ? array
                : (T[]) Array.newInstance(array.getClass().getComponentType(), size);
        // Copy the first span before the second to preserve the successful prefix
        // in the caller's array if an incompatible element causes ArrayStoreException.
        copyElements(result, size);
        if (result.length > size) {
            result[size] = null;
        }
        return result;
    }

    /**
     * Serializes this object to an ObjectOutputStream.
     *
     * @param out The target ObjectOutputStream.
     * @throws IOException thrown when an I/O errors occur writing to the target stream.
     */
    private void writeObject(final ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeInt(size());
        for (final E e : this) {
            out.writeObject(e);
        }
    }

}
