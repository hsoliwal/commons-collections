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
// Modified 2026 by Hitesh Soliwal and Contributors to the Synexia Project:
// array slot/index/mask lowering of the existing ranked, threaded AVL algorithm.
package com.m3.collections.apache.list;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;

import com.m3.collections.apache.OrderedIterator;

/**
 * A {@code List} implementation that is optimized for fast insertions and
 * removals at any index in the list.
 * <p>
 * This list implementation utilizes a tree structure internally to ensure that
 * indexed tree updates are O(log n), with amortized array growth for insertions.
 * The tree structure avoids shifting a contiguous suffix when elements are
 * inserted or removed at arbitrary indexes.
 * </p>
 * <p>
 * The following relative performance statistics were reported for the historical
 * node-based implementation. They are not measurements of this array-storage
 * implementation:
 * </p>
 * <pre>
 *              get  add  insert  iterate  remove
 * TreeList       3    5       1       2       1
 * ArrayList      1    1      40       1      40
 * LinkedList  5800    1     350       2     325
 * </pre>
 * <p>
 * This implementation stores tree links and state in parent-owned primitive
 * arrays, without retaining a node object per entry. Workload-specific latency
 * and retained-memory comparisons require measurements of this implementation.
 * </p>
 * <p>
 * The tree preserves indexed access, ranked updates and threaded iteration.
 * Deleted slots are reused, and {@link #clear()} releases the backing arrays.
 * </p>
 *
 * @param <E> The type of the elements in the list.
 * @since 3.1
 */
public class TreeList<E> extends AbstractList<E> {
//    add; toArray; iterator; insert; get; indexOf; remove
//    TreeList = 1260;7360;3080;  160;   170;3400;  170;
//   ArrayList =  220;1480;1760; 6870;    50;1540; 7200;
//  LinkedList =  270;7360;3350;55860;290720;2910;55200;

    /**
     * Parent-owned ranked/threaded AVL slots. The original tree algorithm is retained;
     * links, relative ranks, heights and thread/live state are primitive array lanes.
     * A slot is stable across capacity growth. Only iterator-facing values escape.
     */
    private static final class SlotStore<E> {
        private static final int NONE = -1;
        private static final int LEFT_PREVIOUS = 1;
        private static final int RIGHT_NEXT = 2;
        private static final int LIVE = 4;
        private static final Object[] EMPTY_VALUES = new Object[0];
        private static final int[] EMPTY_INTS = new int[0];
        private static final byte[] EMPTY_BYTES = new byte[0];
        private Object[] values = EMPTY_VALUES;
        private int[] left = EMPTY_INTS;
        private int[] right = EMPTY_INTS;
        private int[] relativePosition = EMPTY_INTS;
        private byte[] heights = EMPTY_BYTES;
        private byte[] state = EMPTY_BYTES;
        private int used;
        private int free = NONE;
        private int detached = NONE;

        /** Publishes new lanes together, after every allocation succeeds. */
        private void grow() {
            final long proposed = Math.max(8L, values.length + (long) (values.length >>> 1));
            final int capacity = (int) Math.min(Integer.MAX_VALUE - 8L, proposed);
            if (capacity <= values.length) {
                throw new OutOfMemoryError("TreeList slot capacity exhausted");
            }
            final Object[] newValues = Arrays.copyOf(values, capacity);
            final int[] newLeft = Arrays.copyOf(left, capacity);
            final int[] newRight = Arrays.copyOf(right, capacity);
            final int[] newPositions = Arrays.copyOf(relativePosition, capacity);
            final byte[] newHeights = Arrays.copyOf(heights, capacity);
            final byte[] newState = Arrays.copyOf(state, capacity);
            values = newValues;
            left = newLeft;
            right = newRight;
            relativePosition = newPositions;
            heights = newHeights;
            state = newState;
        }

        private int freshSlot() {
            if (used == values.length) {
                grow();
            }
            return used++;
        }

        private int acquireSlot() {
            if (free == NONE) {
                return freshSlot();
            }
            final int slot = free;
            free = left[slot];
            return slot;
        }

        private int newLeaf(final int position, final E value, final int next, final int previous) {
            final int slot = acquireSlot();
            relativePosition[slot] = position;
            values[slot] = value;
            left[slot] = previous;
            right[slot] = next;
            heights[slot] = 0;
            state[slot] = LEFT_PREVIOUS | RIGHT_NEXT | LIVE;
            return slot;
        }

        /** Reuse slots while journaling this build's ownership for failure cleanup. */
        private int build(final Collection<? extends E> source) {
            final Iterator<? extends E> iterator = source.iterator();
            final int end = source.size() - 1;
            final long journalLength = Math.max(1L, end + 1L) + 1L;
            if (journalLength > Integer.MAX_VALUE - 8L) {
                throw new OutOfMemoryError("TreeList build capacity exhausted");
            }
            // Cell zero is the initialized prefix length; remaining cells are slot IDs.
            final int[] acquired = new int[(int) journalLength];
            try {
                return build(iterator, 0, end, 0, NONE, NONE, acquired);
            } catch (final RuntimeException | Error failure) {
                // Only this uncommitted subtree is released. Reversing acquisition
                // restores the popped free-slot order without retaining partial input.
                for (int index = acquired[0]; index > 0; index--) {
                    releaseSlot(acquired[index]);
                }
                throw failure;
            }
        }

        private int build(final Iterator<? extends E> iterator, final int start, final int end,
                final int parentPosition, final int previous, final int next, final int[] acquired) {
            final int slot = acquireSlot();
            acquired[++acquired[0]] = slot;
            final int mid = start + (end - start) / 2;
            state[slot] = LIVE;
            if (start < mid) {
                final int child = build(iterator, start, mid - 1, mid, previous, slot, acquired);
                left[slot] = child;
            } else {
                state[slot] |= LEFT_PREVIOUS;
                left[slot] = previous;
            }
            final E value = iterator.next();
            values[slot] = value;
            relativePosition[slot] = mid - parentPosition;
            if (mid < end) {
                final int child = build(iterator, mid + 1, end, mid, slot, next, acquired);
                right[slot] = child;
            } else {
                state[slot] |= RIGHT_NEXT;
                right[slot] = next;
            }
            recalcHeight(slot);
            return slot;
        }

        private int leftSubTree(final int slot) {
            return (state[slot] & LEFT_PREVIOUS) != 0 ? NONE : left[slot];
        }

        private int rightSubTree(final int slot) {
            return (state[slot] & RIGHT_NEXT) != 0 ? NONE : right[slot];
        }

        private int height(final int slot) {
            return slot == NONE ? -1 : heights[slot];
        }

        private int offset(final int slot) {
            return slot == NONE ? 0 : relativePosition[slot];
        }

        private void setOffset(final int slot, final int position) {
            if (slot != NONE) {
                relativePosition[slot] = position;
            }
        }

        private void recalcHeight(final int slot) {
            heights[slot] = (byte) (Math.max(height(leftSubTree(slot)), height(rightSubTree(slot))) + 1);
        }

        private int heightRightMinusLeft(final int slot) {
            return height(rightSubTree(slot)) - height(leftSubTree(slot));
        }

        private void setLeft(final int slot, final int child, final int previous) {
            state[slot] = (byte) (child == NONE ? state[slot] | LEFT_PREVIOUS : state[slot] & ~LEFT_PREVIOUS);
            left[slot] = child == NONE ? previous : child;
            recalcHeight(slot);
        }

        private void setRight(final int slot, final int child, final int next) {
            state[slot] = (byte) (child == NONE ? state[slot] | RIGHT_NEXT : state[slot] & ~RIGHT_NEXT);
            right[slot] = child == NONE ? next : child;
            recalcHeight(slot);
        }

        @SuppressWarnings("unchecked")
        private E value(final int slot) {
            return (E) values[slot];
        }

        private int get(final int slot, final int index) {
            final int relative = index - relativePosition[slot];
            if (relative == 0) {
                return slot;
            }
            final int next = relative < 0 ? leftSubTree(slot) : rightSubTree(slot);
            return next == NONE ? NONE : get(next, relative);
        }

        private int min(final int slot) {
            return leftSubTree(slot) == NONE ? slot : min(left[slot]);
        }

        private int max(final int slot) {
            return rightSubTree(slot) == NONE ? slot : max(right[slot]);
        }

        private int next(final int slot) {
            return (state[slot] & RIGHT_NEXT) != 0 || right[slot] == NONE ? right[slot] : min(right[slot]);
        }

        private int previous(final int slot) {
            return (state[slot] & LEFT_PREVIOUS) != 0 || left[slot] == NONE ? left[slot] : max(left[slot]);
        }

        private int indexOf(final int slot, final Object object, final int index) {
            if (leftSubTree(slot) != NONE) {
                final int found = indexOf(left[slot], object, index + relativePosition[left[slot]]);
                if (found != -1) {
                    return found;
                }
            }
            if (Objects.equals(values[slot], object)) {
                return index;
            }
            return rightSubTree(slot) == NONE ? -1
                    : indexOf(right[slot], object, index + relativePosition[right[slot]]);
        }

        private void toArray(final int slot, final Object[] array, final int index) {
            array[index] = values[slot];
            if (leftSubTree(slot) != NONE) {
                toArray(left[slot], array, index + relativePosition[left[slot]]);
            }
            if (rightSubTree(slot) != NONE) {
                toArray(right[slot], array, index + relativePosition[right[slot]]);
            }
        }

        private int rotateLeft(final int slot) {
            final int top = right[slot];
            final int moved = leftSubTree(rightSubTree(slot));
            final int topPosition = relativePosition[slot] + offset(top);
            final int myPosition = -relativePosition[top];
            final int movedPosition = offset(top) + offset(moved);
            setRight(slot, moved, top);
            setLeft(top, slot, NONE);
            setOffset(top, topPosition);
            setOffset(slot, myPosition);
            setOffset(moved, movedPosition);
            return top;
        }

        private int rotateRight(final int slot) {
            final int top = left[slot];
            final int moved = rightSubTree(leftSubTree(slot));
            final int topPosition = relativePosition[slot] + offset(top);
            final int myPosition = -relativePosition[top];
            final int movedPosition = offset(top) + offset(moved);
            setLeft(slot, moved, top);
            setRight(top, slot, NONE);
            setOffset(top, topPosition);
            setOffset(slot, myPosition);
            setOffset(moved, movedPosition);
            return top;
        }

        private int balance(final int slot) {
            switch (heightRightMinusLeft(slot)) {
            case -1:
            case 0:
            case 1:
                return slot;
            case -2:
                if (heightRightMinusLeft(left[slot]) > 0) {
                    setLeft(slot, rotateLeft(left[slot]), NONE);
                }
                return rotateRight(slot);
            case 2:
                if (heightRightMinusLeft(right[slot]) < 0) {
                    setRight(slot, rotateRight(right[slot]), NONE);
                }
                return rotateLeft(slot);
            default:
                throw new IllegalStateException("tree inconsistent.");
            }
        }

        private int insert(final int slot, final int index, final E value) {
            final int relative = index - relativePosition[slot];
            if (relative <= 0) {
                setLeft(slot, leftSubTree(slot) == NONE ? newLeaf(-1, value, slot, left[slot])
                        : insert(left[slot], relative, value), NONE);
                if (relativePosition[slot] >= 0) {
                    relativePosition[slot]++;
                }
            } else {
                setRight(slot, rightSubTree(slot) == NONE ? newLeaf(1, value, right[slot], slot)
                        : insert(right[slot], relative, value), NONE);
                if (relativePosition[slot] < 0) {
                    relativePosition[slot]--;
                }
            }
            final int result = balance(slot);
            recalcHeight(slot);
            return result;
        }

        private int remove(final int slot, final int index) {
            final int relative = index - relativePosition[slot];
            if (relative == 0) {
                return removeSelf(slot);
            }
            if (relative > 0) {
                setRight(slot, remove(right[slot], relative), right[right[slot]]);
                if (relativePosition[slot] < 0) {
                    relativePosition[slot]++;
                }
            } else {
                setLeft(slot, remove(left[slot], relative), left[left[slot]]);
                if (relativePosition[slot] > 0) {
                    relativePosition[slot]--;
                }
            }
            recalcHeight(slot);
            return balance(slot);
        }

        private int removeMax(final int slot) {
            if (rightSubTree(slot) == NONE) {
                return removeSelf(slot);
            }
            setRight(slot, removeMax(right[slot]), right[right[slot]]);
            if (relativePosition[slot] < 0) {
                relativePosition[slot]++;
            }
            recalcHeight(slot);
            return balance(slot);
        }

        private int removeMin(final int slot) {
            if (leftSubTree(slot) == NONE) {
                return removeSelf(slot);
            }
            setLeft(slot, removeMin(left[slot]), left[left[slot]]);
            if (relativePosition[slot] > 0) {
                relativePosition[slot]--;
            }
            recalcHeight(slot);
            return balance(slot);
        }

        /** Defer slot release until the donor's post-recursion thread reads complete. */
        private int removeSelf(final int slot) {
            if (rightSubTree(slot) == NONE && leftSubTree(slot) == NONE) {
                detached = slot;
                return NONE;
            }
            if (rightSubTree(slot) == NONE) {
                if (relativePosition[slot] > 0) {
                    relativePosition[left[slot]] += relativePosition[slot];
                }
                setRight(max(left[slot]), NONE, right[slot]);
                detached = slot;
                return left[slot];
            }
            if (leftSubTree(slot) == NONE) {
                relativePosition[right[slot]] += relativePosition[slot] - (relativePosition[slot] < 0 ? 0 : 1);
                setLeft(min(right[slot]), NONE, left[slot]);
                detached = slot;
                return right[slot];
            }
            if (heightRightMinusLeft(slot) > 0) {
                final int successor = min(right[slot]);
                values[slot] = values[successor];
                if ((state[slot] & LEFT_PREVIOUS) != 0) {
                    left[slot] = left[successor];
                }
                right[slot] = removeMin(right[slot]);
                if (relativePosition[slot] < 0) {
                    relativePosition[slot]++;
                }
            } else {
                final int predecessor = max(left[slot]);
                values[slot] = values[predecessor];
                if ((state[slot] & RIGHT_NEXT) != 0) {
                    right[slot] = right[predecessor];
                }
                final int previous = left[left[slot]];
                left[slot] = removeMax(left[slot]);
                if (left[slot] == NONE) {
                    left[slot] = previous;
                    state[slot] |= LEFT_PREVIOUS;
                }
                if (relativePosition[slot] > 0) {
                    relativePosition[slot]--;
                }
            }
            recalcHeight(slot);
            return slot;
        }

        private void releaseSlot(final int slot) {
            values[slot] = null;
            state[slot] = 0;
            heights[slot] = 0;
            relativePosition[slot] = 0;
            right[slot] = NONE;
            left[slot] = free;
            free = slot;
        }

        private void releaseDetached() {
            releaseSlot(detached);
            detached = NONE;
        }

        /** Height-matched AVL join from the original owner, using a primitive ancestor stack. */
        private int addAll(final int root, int other, final int currentSize, final int[] ancestors) {
            final int maximum = max(root);
            final int otherMinimum = min(other);
            int depth = 0;
            if (height(other) > height(root)) {
                final int leftTree = removeMax(root);
                int subtree = other;
                int absolute = relativePosition[subtree] + currentSize;
                int parentAbsolute = 0;
                while (subtree != NONE && height(subtree) > height(leftTree)) {
                    parentAbsolute = absolute;
                    ancestors[depth++] = subtree;
                    subtree = left[subtree];
                    if (subtree != NONE) {
                        absolute += relativePosition[subtree];
                    }
                }
                setLeft(maximum, leftTree, NONE);
                setRight(maximum, subtree, otherMinimum);
                if (leftTree != NONE) {
                    setRight(max(leftTree), NONE, maximum);
                    relativePosition[leftTree] -= currentSize - 1;
                }
                if (subtree != NONE) {
                    setLeft(min(subtree), NONE, maximum);
                    relativePosition[subtree] = absolute - currentSize + 1;
                }
                relativePosition[maximum] = currentSize - 1 - parentAbsolute;
                relativePosition[other] += currentSize;
                subtree = maximum;
                while (depth != 0) {
                    final int ancestor = ancestors[--depth];
                    setLeft(ancestor, subtree, NONE);
                    subtree = balance(ancestor);
                }
                detached = NONE;
                return subtree;
            }
            other = removeMin(other);
            int subtree = root;
            int absolute = relativePosition[subtree];
            int parentAbsolute = 0;
            while (subtree != NONE && height(subtree) > height(other)) {
                parentAbsolute = absolute;
                ancestors[depth++] = subtree;
                subtree = right[subtree];
                if (subtree != NONE) {
                    absolute += relativePosition[subtree];
                }
            }
            setRight(otherMinimum, other, NONE);
            setLeft(otherMinimum, subtree, maximum);
            if (other != NONE) {
                setLeft(min(other), NONE, otherMinimum);
                relativePosition[other]++;
            }
            if (subtree != NONE) {
                setRight(max(subtree), NONE, otherMinimum);
                relativePosition[subtree] = absolute - currentSize;
            }
            relativePosition[otherMinimum] = currentSize - parentAbsolute;
            subtree = otherMinimum;
            while (depth != 0) {
                final int ancestor = ancestors[--depth];
                setRight(ancestor, subtree, NONE);
                subtree = balance(ancestor);
            }
            detached = NONE;
            return subtree;
        }

        private void clear() {
            values = EMPTY_VALUES;
            left = EMPTY_INTS;
            right = EMPTY_INTS;
            relativePosition = EMPTY_INTS;
            heights = EMPTY_BYTES;
            state = EMPTY_BYTES;
            used = 0;
            free = NONE;
            detached = NONE;
        }
    }

    /**
     * A list iterator over the linked list.
     */
    static class TreeListIterator<E> implements ListIterator<E>, OrderedIterator<E> {

        /** The parent list */
        private final TreeList<E> parent;

        /**
         * Cache of the next node that will be returned by {@link #next()}.
         */
        private int next = -1;

        /**
         * The index of the next node to be returned.
         */
        private int nextIndex;

        /**
         * Cache of the last node that was returned by {@link #next()}
         * or {@link #previous()}.
         */
        private int current = -1;

        /**
         * The index of the last node that was returned.
         */
        private int currentIndex;

        /**
         * The modification count that the list is expected to have. If the list
         * doesn't have this count, then a
         * {@link ConcurrentModificationException} may be thrown by
         * the operations.
         */
        private int expectedModCount;

        /**
         * Create a ListIterator for a list.
         *
         * @param parent  The parent list
         * @param fromIndex  The index to start at
         */
        protected TreeListIterator(final TreeList<E> parent, final int fromIndex) {
            checkInterval(fromIndex, 0, parent.size(), parent.size());
            this.parent = parent;
            this.expectedModCount = parent.modCount;
            this.next = parent.root == -1 ? -1 : parent.slots.get(parent.root, fromIndex);
            this.nextIndex = fromIndex;
            this.currentIndex = -1;
        }

        @Override
        public void add(final E obj) {
            checkModCount();
            parent.add(nextIndex, obj);
            current = -1;
            currentIndex = -1;
            nextIndex++;
            expectedModCount++;
        }

        /**
         * Checks the modification count of the list is the value that this
         * object expects.
         *
         * @throws ConcurrentModificationException If the list's modification
         * count isn't the value that was expected.
         */
        protected void checkModCount() {
            if (parent.modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
        }

        @Override
        public boolean hasNext() {
            return nextIndex < parent.size();
        }

        @Override
        public boolean hasPrevious() {
            return nextIndex > 0;
        }

        @Override
        public E next() {
            checkModCount();
            if (!hasNext()) {
                throw new NoSuchElementException("No element at index " + nextIndex + ".");
            }
            if (next == -1) {
                next = parent.slots.get(parent.root, nextIndex);
            }
            final E value = parent.slots.value(next);
            current = next;
            currentIndex = nextIndex++;
            next = parent.slots.next(next);
            return value;
        }

        @Override
        public int nextIndex() {
            return nextIndex;
        }

        @Override
        public E previous() {
            checkModCount();
            if (!hasPrevious()) {
                throw new NoSuchElementException("Already at start of list.");
            }
            if (next == -1) {
                next = parent.slots.get(parent.root, nextIndex - 1);
            } else {
                next = parent.slots.previous(next);
            }
            final E value = parent.slots.value(next);
            current = next;
            currentIndex = --nextIndex;
            return value;
        }

        @Override
        public int previousIndex() {
            return nextIndex() - 1;
        }

        @Override
        public void remove() {
            checkModCount();
            if (currentIndex == -1) {
                throw new IllegalStateException();
            }
            parent.remove(currentIndex);
            if (nextIndex != currentIndex) {
                // remove() following next()
                nextIndex--;
            }
            // the AVL node referenced by next may have become stale after a remove
            // reset it now: will be retrieved by next call to next()/previous() via nextIndex
            next = -1;
            current = -1;
            currentIndex = -1;
            expectedModCount++;
        }

        @Override
        public void set(final E obj) {
            checkModCount();
            if (current == -1) {
                throw new IllegalStateException();
            }
            parent.slots.values[current] = obj;
        }
    }

    /**
     * Checks whether the index is valid.
     *
     * @param index  The index to check.
     * @param startIndex  The first allowed index.
     * @param endIndex  The last allowed index.
     * @param endIndex  The size.
     * @throws IndexOutOfBoundsException if the index is invalid
     */
    private static void checkInterval(final int index, final int startIndex, final int endIndex, final int size) {
        if (index < startIndex || index > endIndex) {
            throw new IndexOutOfBoundsException("Invalid index:" + index + ", size=" + size);
        }
    }

    /** The root node in the AVL tree */
    private int root = -1;

    /** The one owner of all payload/index/state lanes. */
    private final SlotStore<E> slots = new SlotStore<>();

    /** The current size of the list */
    private int size;

    /**
     * Constructs a new empty list.
     */
    public TreeList() {
    }

    /**
     * Constructs a new empty list that copies the specified collection.
     *
     * @param coll  The collection to copy
     * @throws NullPointerException if the collection is null
     */
    public TreeList(final Collection<? extends E> coll) {
        if (!coll.isEmpty()) {
            root = slots.build(coll);
            size = coll.size();
        }
    }

    /**
     * Adds a new element to the list.
     *
     * @param index  The index to add before
     * @param obj  The element to add
     */
    @Override
    public void add(final int index, final E obj) {
        checkInterval(index, 0, size());
        modCount++;
        if (root == -1) {
            root = slots.newLeaf(index, obj, -1, -1);
        } else {
            root = slots.insert(root, index, obj);
        }
        size++;
    }

    /**
     * Appends all the elements in the specified collection to the end of this list,
     * in the order that they are returned by the specified collection's Iterator.
     * <p>
     * This method runs in O(n + log m) time, where m is
     * the size of this list and n is the size of {@code c}.
     * </p>
     *
     * @param c  The collection to be added to this list
     * @return {@code true} if this list changed as a result of the call
     * @throws NullPointerException if the specified collection contains a
     *         null element and this collection does not permit null elements,
     *         or if the specified collection is null
     */
    @Override
    public boolean addAll(final Collection<? extends E> c) {
        if (c.isEmpty()) {
            return false;
        }
        modCount += c.size();
        // Allocate the bounded join scratch before a new subtree can own payload.
        final int[] ancestors = new int[64];
        final int cTree = slots.build(c);
        root = root == -1 ? cTree : slots.addAll(root, cTree, size, ancestors);
        size += c.size();
        return true;
    }

    /**
     * Checks whether the index is valid.
     *
     * @param index  The index to check
     * @param startIndex  The first allowed index
     * @param endIndex  The last allowed index
     * @throws IndexOutOfBoundsException if the index is invalid
     */
    private void checkInterval(final int index, final int startIndex, final int endIndex) {
        checkInterval(index, startIndex, endIndex, size());
    }

    /**
     * Clears the list, removing all entries.
     */
    @Override
    public void clear() {
        modCount++;
        root = -1;
        slots.clear();
        size = 0;
    }

    /**
     * Searches for the presence of an object in the list.
     *
     * @param object  The object to check
     * @return true if the object is found
     */
    @Override
    public boolean contains(final Object object) {
        return indexOf(object) >= 0;
    }

    /**
     * Gets the element at the specified index.
     *
     * @param index  The index to retrieve
     * @return The element at the specified index
     */
    @Override
    public E get(final int index) {
        checkInterval(index, 0, size() - 1);
        return slots.value(slots.get(root, index));
    }

    /**
     * Searches for the index of an object in the list.
     *
     * @param object  The object to search
     * @return The index of the object, -1 if not found
     */
    @Override
    public int indexOf(final Object object) {
        // override to go 75% faster
        if (root == -1) {
            return -1;
        }
        return slots.indexOf(root, object, slots.relativePosition[root]);
    }

    /**
     * Gets an iterator over the list.
     *
     * @return An iterator over the list
     */
    @Override
    public Iterator<E> iterator() {
        // override to go 75% faster
        return listIterator(0);
    }

    /**
     * Gets a ListIterator over the list.
     *
     * @return The new iterator
     */
    @Override
    public ListIterator<E> listIterator() {
        // override to go 75% faster
        return listIterator(0);
    }

    /**
     * Gets a ListIterator over the list.
     *
     * @param fromIndex  The index to start from.
     * @return The new iterator.
     */
    @Override
    public ListIterator<E> listIterator(final int fromIndex) {
        // override to go 75% faster
        // cannot use EmptyIterator as iterator.add() must work
        return new TreeListIterator<>(this, fromIndex);
    }

    /**
     * Removes the element at the specified index.
     *
     * @param index  The index to remove
     * @return The previous object at that index
     */
    @Override
    public E remove(final int index) {
        checkInterval(index, 0, size() - 1);
        modCount++;
        final E result = get(index);
        root = slots.remove(root, index);
        slots.releaseDetached();
        size--;
        return result;
    }

    /**
     * Sets the element at the specified index.
     *
     * @param index  The index to set
     * @param obj  The object to store at the specified index
     * @return The previous object at that index
     * @throws IndexOutOfBoundsException if the index is invalid
     */
    @Override
    public E set(final int index, final E obj) {
        checkInterval(index, 0, size() - 1);
        final int slot = slots.get(root, index);
        final E result = slots.value(slot);
        slots.values[slot] = obj;
        return result;
    }

    /**
     * Gets the current size of the list.
     *
     * @return The current size
     */
    @Override
    public int size() {
        return size;
    }

    /**
     * Converts the list into an array.
     *
     * @return The list as an array
     */
    @Override
    public Object[] toArray() {
        // override to go 20% faster
        final Object[] array = new Object[size()];
        if (root != -1) {
            slots.toArray(root, array, slots.relativePosition[root]);
        }
        return array;
    }

}
