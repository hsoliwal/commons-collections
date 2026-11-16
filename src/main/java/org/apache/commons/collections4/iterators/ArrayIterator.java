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
// Modified 2026 by Hitesh Soliwal and contributors: direct reference-array reads with reflective fallbacks.
// Modified 2026 by Synexia contributors: direct Java-8 primitive array lanes.
package org.apache.commons.collections4.iterators;

import java.lang.reflect.Array;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.commons.collections4.ResettableIterator;

/**
 * Implements an {@link Iterator Iterator} over any array.
 * <p>
 * The array can be either an array of object or of primitives. If you know
 * that you have an object array, the
 * {@link ObjectArrayIterator ObjectArrayIterator}
 * class is a better choice, as it will perform better.
 * </p>
 * <p>
 * The iterator implements a {@link #reset} method, allowing the reset of
 * the iterator back to the start if required.
 * </p>
 *
 * @param <E> The type of elements returned by this iterator.
 * @since 1.0
 */
public class ArrayIterator<E> implements ResettableIterator<E> {

    /** The array to iterate over */
    final Object array;

    /** The start index to loop from */
    final int startIndex;

    /** The end index to loop to */
    final int endIndex;

    /** The current iterator index */
    int index;

    /**
     * Constructs an ArrayIterator that will iterate over the values in the
     * specified array.
     *
     * @param array The array to iterate over.
     * @throws IllegalArgumentException if {@code array} is not an array.
     * @throws NullPointerException if {@code array} is {@code null}
     */
    public ArrayIterator(final Object array) {
        this(array, 0);
    }

    /**
     * Constructs an ArrayIterator that will iterate over the values in the
     * specified array from a specific start index.
     *
     * @param array  The array to iterate over.
     * @param startIndex  The index to start iterating at.
     * @throws IllegalArgumentException if {@code array} is not an array.
     * @throws NullPointerException if {@code array} is {@code null}
     * @throws IndexOutOfBoundsException if the index is invalid
     */
    public ArrayIterator(final Object array, final int startIndex) {
        this(array, startIndex, Array.getLength(array));
    }

    /**
     * Constructs an ArrayIterator that will iterate over a range of values
     * in the specified array.
     *
     * @param array  The array to iterate over.
     * @param startIndex  The index to start iterating at.
     * @param endIndex  The index to finish iterating at.
     * @throws IllegalArgumentException if {@code array} is not an array.
     * @throws NullPointerException if {@code array} is {@code null}
     * @throws IndexOutOfBoundsException if either index is invalid
     */
    public ArrayIterator(final Object array, final int startIndex, final int endIndex) {
        this.array = array;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.index = startIndex;

        final int len = Array.getLength(array);
        checkBound(startIndex, len, "start");
        checkBound(endIndex, len, "end");
        if (endIndex < startIndex) {
            throw new IllegalArgumentException("End index must not be less than start index.");
        }
    }

    /**
     * Checks whether the index is valid or not.
     *
     * @param bound  The index to check
     * @param len  The length of the array
     * @param type  The index type (for error messages)
     * @throws IndexOutOfBoundsException if the index is invalid
     */
    protected void checkBound(final int bound, final int len, final String type) {
        if (bound > len) {
            throw new ArrayIndexOutOfBoundsException("Attempt to make an ArrayIterator that " + type + "s beyond the end of the array. ");
        }
        if (bound < 0) {
            throw new ArrayIndexOutOfBoundsException("Attempt to make an ArrayIterator that " + type + "s before the start of the array. ");
        }
    }

    /**
     * Gets the array that this iterator is iterating over.
     *
     * @return The array this iterator iterates over.
     */
    public Object getArray() {
        return array;
    }

    /**
     * Gets the end index to loop to.
     *
     * @return The end index
     * @since 4.0
     */
    public int getEndIndex() {
        return endIndex;
    }

    /**
     * Gets the start index to loop from.
     *
     * @return The start index
     * @since 4.0
     */
    public int getStartIndex() {
        return startIndex;
    }

    /**
     * Returns true if there are more elements to return from the array.
     *
     * @return true if there is a next element to return
     */
    @Override
    public boolean hasNext() {
        return index < endIndex;
    }

    /**
     * Returns the next element in the array.
     *
     * @return The next element in the array
     * @throws NoSuchElementException if all the elements in the array
     *  have already been returned
     */
    @Override
    @SuppressWarnings("unchecked")
    public E next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        if (array instanceof Object[]) {
            final Object[] values = (Object[]) array;
            if (index >= 0 && index < values.length) {
                return (E) values[index++];
            }
        }
        // Constructor-validated primitive ranges use direct typed loads.
        // Overridden hasNext() and package-local cursor corruption can admit
        // an invalid index: retain the original Array.get exception message.
        if (index >= 0 && index < endIndex) {
            return (E) primitiveAt(array, index++);
        }
        return (E) Array.get(array, index++);
    }

    /**
     * Read all eight primitive array kinds through direct typed lanes, avoiding
     * java.lang.reflect.Array.get and retaining its boxing types. Callers only
     * use this while a constructor-validated index remains within the slice;
     * overridden bounds callbacks still delegate to Array.get for failures.
     */
    static Object primitiveAt(final Object source, final int position) {
        if (source instanceof int[]) {
            return ((int[]) source)[position];
        }
        if (source instanceof long[]) {
            return ((long[]) source)[position];
        }
        if (source instanceof double[]) {
            return ((double[]) source)[position];
        }
        if (source instanceof float[]) {
            return ((float[]) source)[position];
        }
        if (source instanceof short[]) {
            return ((short[]) source)[position];
        }
        if (source instanceof char[]) {
            return ((char[]) source)[position];
        }
        if (source instanceof byte[]) {
            return ((byte[]) source)[position];
        }
        if (source instanceof boolean[]) {
            return ((boolean[]) source)[position];
        }
        return Array.get(source, position);
    }

    /**
     * Exact boxed writes skip reflection; widening conversions, nulls and
     * incompatible payloads intentionally fall back to Array.set in the caller.
     * A caller must establish that the index is valid before invoking this.
     */
    static boolean writePrimitive(final Object source, final int position, final Object value) {
        if (source instanceof int[] && value instanceof Integer) {
            ((int[]) source)[position] = (Integer) value;
        } else if (source instanceof long[] && value instanceof Long) {
            ((long[]) source)[position] = (Long) value;
        } else if (source instanceof double[] && value instanceof Double) {
            ((double[]) source)[position] = (Double) value;
        } else if (source instanceof float[] && value instanceof Float) {
            ((float[]) source)[position] = (Float) value;
        } else if (source instanceof short[] && value instanceof Short) {
            ((short[]) source)[position] = (Short) value;
        } else if (source instanceof char[] && value instanceof Character) {
            ((char[]) source)[position] = (Character) value;
        } else if (source instanceof byte[] && value instanceof Byte) {
            ((byte[]) source)[position] = (Byte) value;
        } else if (source instanceof boolean[] && value instanceof Boolean) {
            ((boolean[]) source)[position] = (Boolean) value;
        } else {
            return false;
        }
        return true;
    }

    /**
     * Always throws {@link UnsupportedOperationException}.
     *
     * @throws UnsupportedOperationException Always thrown.
     */
    @Override
    public void remove() {
        throw new UnsupportedOperationException("remove() method is not supported");
    }

    /**
     * Resets the iterator back to the start index.
     */
    @Override
    public void reset() {
        index = startIndex;
    }

}
