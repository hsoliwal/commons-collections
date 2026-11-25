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
/*
 * Copyright 2026 Hitesh Soliwal and Contributors to the Synexia Project.
 * SPDX-License-Identifier: Apache-2.0
 */
// Modified 2026 by Hitesh Soliwal and contributors: qualify exact inherited overrides without widening the API.
// Modified 2026 by Hitesh Soliwal and contributors: bind admitted membership/default overrides and reject surface drift.
package org.apache.commons.collections4.queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.TreeSet;
import java.util.function.Consumer;

import org.apache.commons.collections4.ArrayStorageOracle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exhaustive original-source checks for contiguous spans, cursor state and retained storage.
 * <p>
 * {@link ArrayStorageOracle} compiles the pinned originals with {@code javac --release 21}, so these tests run on Java 21 and later.
 * </p>
 */
@EnabledForJreRange(min = JRE.JAVA_21)
class CircularFifoQueueArrayStorageTest {
    @TempDir
    static Path work;
    private static ArrayStorageOracle oracle;
    private static Field originalElements;
    private static Field currentElements;
    private static Field originalStart;
    private static Field currentStart;
    private static Field originalEnd;
    private static Field currentEnd;
    private static Field originalFull;
    private static Field currentFull;

    @BeforeAll
    static void originalSource() throws Exception {
        oracle = new ArrayStorageOracle(work);
        final Class<?> original = oracle.originalClass("queue.CircularFifoQueue");
        assertNotSame(CircularFifoQueue.class, original);
        originalElements = field(original, "elements");
        currentElements = field(CircularFifoQueue.class, "elements");
        originalStart = field(original, "start");
        currentStart = field(CircularFifoQueue.class, "start");
        originalEnd = field(original, "end");
        currentEnd = field(CircularFifoQueue.class, "end");
        originalFull = field(original, "full");
        currentFull = field(CircularFifoQueue.class, "full");
    }

    @AfterAll
    static void closeOracle() throws Exception {
        oracle.close();
    }

    private static Field field(final Class<?> owner, final String name) throws Exception {
        final Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static <E> void moveEmptyStart(final Queue<E> original, final Queue<E> current,
            final int start, final E marker) {
        for (int index = 0; index < start; index++) {
            original.add(marker);
            current.add(marker);
            assertSame(original.remove(), current.remove());
        }
    }

    private static void sameRetainedState(final Queue<?> original, final CircularFifoQueue<?> current)
            throws Exception {
        assertEquals(original.size(), current.size());
        assertSame(original.peek(), current.peek());
        assertArrayEquals(original.toArray(), current.toArray());
        assertEquals(originalStart.getInt(original), currentStart.getInt(current));
        assertEquals(originalEnd.getInt(original), currentEnd.getInt(current));
        assertEquals(originalFull.getBoolean(original), currentFull.getBoolean(current));
        final Object[] expected = (Object[]) originalElements.get(original);
        final Object[] actual = (Object[]) currentElements.get(current);
        assertEquals(expected.length, actual.length);
        final int start = currentStart.getInt(current);
        for (int offset = 0; offset < actual.length; offset++) {
            final int index = (start + offset) % actual.length;
            assertSame(expected[index], actual[index], "exact slot identity");
            if (offset >= current.size()) {
                assertNull(actual[index], "inactive slots must release payload references");
            }
        }
    }

    @Test
    void everySmallRingLayoutAndRemovalMatchesTheOriginalOwner() throws Exception {
        int scenarios = 0;
        for (int capacity = 1; capacity <= 24; capacity++) {
            for (int start = 0; start < capacity; start++) {
                for (int count = 1; count <= capacity; count++) {
                    for (int removed = 0; removed < count; removed++) {
                        final Queue<Object> original = oracle.originalQueue(capacity);
                        final CircularFifoQueue<Object> current = new CircularFifoQueue<>(capacity);
                        moveEmptyStart(original, current, start, new Object());
                        for (int index = 0; index < count; index++) {
                            final Object value = new Object();
                            original.add(value);
                            current.add(value);
                        }
                        final Object ownedArray = currentElements.get(current);
                        final Iterator<Object> expected = original.iterator();
                        final Iterator<Object> actual = current.iterator();
                        assertThrows(IllegalStateException.class, expected::remove);
                        assertThrows(IllegalStateException.class, actual::remove);
                        for (int index = 0; index <= removed; index++) {
                            assertSame(expected.next(), actual.next());
                        }
                        expected.remove();
                        actual.remove();
                        assertThrows(IllegalStateException.class, expected::remove);
                        assertThrows(IllegalStateException.class, actual::remove);
                        assertSame(ownedArray, currentElements.get(current), "removal reuses the owner's array");
                        sameRetainedState(original, current);
                        while (expected.hasNext()) {
                            assertEquals(expected.hasNext(), actual.hasNext());
                            assertSame(expected.next(), actual.next());
                        }
                        assertFalse(actual.hasNext());
                        assertThrows(NoSuchElementException.class, expected::next);
                        assertThrows(NoSuchElementException.class, actual::next);
                        for (int index = 0; index <= capacity; index++) {
                            final Object value = new Object();
                            original.add(value);
                            current.add(value);
                        }
                        sameRetainedState(original, current);
                        while (!original.isEmpty()) {
                            assertSame(original.remove(), current.remove());
                        }
                        sameRetainedState(original, current);
                        scenarios++;
                    }
                }
            }
        }
        assertEquals(47450, scenarios);
    }

    @Test
    void everySmallRemovalSubsetPreservesOneLiveIteratorAcrossWraps() throws Exception {
        for (int capacity = 1; capacity <= 8; capacity++) {
            for (int start = 0; start < capacity; start++) {
                for (int count = 1; count <= capacity; count++) {
                    for (int mask = 0; mask < 1 << count; mask++) {
                        final Queue<Integer> original = oracle.originalQueue(capacity);
                        final CircularFifoQueue<Integer> current = new CircularFifoQueue<>(capacity);
                        moveEmptyStart(original, current, start, -1);
                        for (int value = 0; value < count; value++) {
                            original.add(value);
                            current.add(value);
                        }
                        final Iterator<Integer> expected = original.iterator();
                        final Iterator<Integer> actual = current.iterator();
                        for (int index = 0; index < count; index++) {
                            assertEquals(expected.hasNext(), actual.hasNext());
                            assertSame(expected.next(), actual.next());
                            if ((mask & 1 << index) != 0) {
                                expected.remove();
                                actual.remove();
                                sameRetainedState(original, current);
                            }
                        }
                        assertFalse(expected.hasNext());
                        assertFalse(actual.hasNext());
                        sameRetainedState(original, current);
                    }
                }
            }
        }
    }

    @Test
    void originalSerializationBytesRemainReadableAfterSpanRemoval() throws Exception {
        for (int capacity = 1; capacity <= 10; capacity++) {
            for (int start = 0; start < capacity; start++) {
                for (int removed = 0; removed < capacity; removed++) {
                    final Queue<Integer> original = oracle.originalQueue(capacity);
                    final CircularFifoQueue<Integer> current = new CircularFifoQueue<>(capacity);
                    moveEmptyStart(original, current, start, -1);
                    for (int value = 0; value < capacity; value++) {
                        original.add(value);
                        current.add(value);
                    }
                    final Iterator<Integer> expected = original.iterator();
                    final Iterator<Integer> actual = current.iterator();
                    for (int index = 0; index <= removed; index++) {
                        assertEquals(expected.next(), actual.next());
                    }
                    expected.remove();
                    actual.remove();
                    final byte[] originalBytes = serialize(original);
                    final byte[] currentBytes = serialize(current);
                    assertArrayEquals(originalBytes, currentBytes);
                    final Queue<?> restoredOriginal = deserialize(currentBytes, original.getClass().getClassLoader());
                    final Queue<?> restoredCurrent = deserialize(originalBytes, current.getClass().getClassLoader());
                    assertArrayEquals(original.toArray(), restoredCurrent.toArray());
                    assertArrayEquals(current.toArray(), restoredOriginal.toArray());
                    assertArrayEquals(serialize(restoredOriginal), serialize(restoredCurrent));
                }
            }
        }
    }

    @Test
    void headRemovalStillDispatchesToTheSubclassOwner() {
        final ObservingQueue queue = new ObservingQueue(4);
        queue.addAll(Arrays.asList(1, 2, 3));
        final Iterator<Integer> first = queue.iterator();
        first.next();
        first.remove();
        assertEquals(1, queue.removalCalls);
        final Iterator<Integer> later = queue.iterator();
        later.next();
        later.next();
        later.remove();
        assertEquals(1, queue.removalCalls);
        assertArrayEquals(new Object[] {2}, queue.toArray());
    }

    @Test
    void publicProtectedAndSerializedSurfacesAreUnchanged() throws Exception {
        final Class<?> original = oracle.originalClass("queue.CircularFifoQueue");
        assertEquals(surface(original),
                declaredSurfaceWithExactArrayOverrides(original, CircularFifoQueue.class));
        assertEquals(effectiveSurface(original), effectiveSurface(CircularFifoQueue.class));
        assertEquals(original.getModifiers(), CircularFifoQueue.class.getModifiers(), "class modifiers");
        assertEquals(Arrays.toString(original.getGenericInterfaces()),
                Arrays.toString(CircularFifoQueue.class.getGenericInterfaces()));
        assertEquals(original.getGenericSuperclass().getTypeName(),
                CircularFifoQueue.class.getGenericSuperclass().getTypeName());
        final TreeSet<String> originalFields = new TreeSet<>();
        final TreeSet<String> currentFields = new TreeSet<>();
        Arrays.stream(original.getDeclaredFields())
                .filter(field -> !isJacocoDataField(field))
                .forEach(field -> originalFields.add(field.toGenericString()));
        Arrays.stream(CircularFifoQueue.class.getDeclaredFields())
                .filter(field -> !isJacocoDataField(field))
                .forEach(field -> currentFields.add(field.toGenericString()));
        assertEquals(originalFields, currentFields, "all declared storage fields, including transient fields");
        assertEquals(ObjectStreamClass.lookup(original).getSerialVersionUID(),
                ObjectStreamClass.lookup(CircularFifoQueue.class).getSerialVersionUID());
        assertEquals(Arrays.toString(ObjectStreamClass.lookup(original).getFields()),
                Arrays.toString(ObjectStreamClass.lookup(CircularFifoQueue.class).getFields()));
    }

    @Test
    void declaredSurfaceRejectsUnexpectedAndMissingOriginalMembers() throws Exception {
        final Class<?> owner = SurfaceFixture.class;
        final TreeSet<String> baseline = surface(owner);
        assertEquals(baseline, declaredSurfaceWithExactArrayOverrides(owner, owner, baseline));

        final TreeSet<String> added = new TreeSet<>(baseline);
        final Method unexpected = UnexpectedSurfaceFixture.class.getDeclaredMethod("toArray", int.class);
        final String declaration = unexpected.toGenericString().replace(
                UnexpectedSurfaceFixture.class.getName() + ".", owner.getName() + ".");
        assertTrue(added.add(declaration), "inject an unadmitted overload of an admitted name");
        assertThrows(AssertionError.class,
                () -> assertEquals(baseline, declaredSurfaceWithExactArrayOverrides(owner, owner, added)));

        for (final String name : Arrays.asList("originalPublic", "originalProtected")) {
            final TreeSet<String> missing = new TreeSet<>(baseline);
            assertTrue(missing.remove(owner.getDeclaredMethod(name).toGenericString()), "remove the frozen declaration");
            assertThrows(AssertionError.class,
                    () -> assertEquals(baseline, declaredSurfaceWithExactArrayOverrides(owner, owner, missing)));
        }
    }

    @Test
    void forEachBindingRejectsIncompatibleDeclarations() throws Exception {
        for (final Class<?> owner : Arrays.asList(RawForEachFixture.class, FinalForEachFixture.class,
                StaticForEachFixture.class, WrongWildcardForEachFixture.class, WrongReturnForEachFixture.class)) {
            final Method method = owner.getDeclaredMethod("forEach", Consumer.class);
            assertThrows(AssertionError.class, () -> forEachDeclarationSignature(method), owner.getSimpleName());
        }
        final Method varargs = VarargsForEachFixture.class.getDeclaredMethod("forEach", Consumer[].class);
        assertThrows(AssertionError.class, () -> forEachDeclarationSignature(varargs), "variable arity is not admitted");
    }

    /** JaCoCo's offline/agent probe array; never part of the original or current storage surface. */
    private static boolean isJacocoDataField(final Field field) {
        return field.isSynthetic() && "$jacocoData".equals(field.getName())
                && field.getType() == boolean[].class
                && (field.getModifiers() & Modifier.fieldModifiers())
                        == (Modifier.PRIVATE | Modifier.STATIC | Modifier.TRANSIENT);
    }

    private static byte[] serialize(final Object value) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static Queue<?> deserialize(final byte[] bytes, final ClassLoader owner) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            protected Class<?> resolveClass(final ObjectStreamClass descriptor) throws ClassNotFoundException {
                return Class.forName(descriptor.getName(), false, owner);
            }
        }) {
            return (Queue<?>) input.readObject();
        }
    }

    /**
     * Preserve every old declaration and allow only exact implementations of the
     * seven inherited AbstractCollection methods and the inherited Iterable forEach.
     * The declarations remain optional so this gate also accepts the unchanged baseline.
     */
    private static TreeSet<String> declaredSurfaceWithExactArrayOverrides(final Class<?> original,
            final Class<?> current) throws Exception {
        return declaredSurfaceWithExactArrayOverrides(original, current, surface(current));
    }

    /** Also accepts an independently mutated declaration inventory for the negative gate tests. */
    private static TreeSet<String> declaredSurfaceWithExactArrayOverrides(final Class<?> original,
            final Class<?> current, final TreeSet<String> declarations) throws Exception {
        final TreeSet<String> result = new TreeSet<>(declarations);
        // Only these already inherited operations may become exact class declarations.
        final Object[][] admitted = {
            {"toArray", new Class<?>[0], AbstractCollection.class},
            {"toArray", new Class<?>[] {Object[].class}, AbstractCollection.class},
            {"contains", new Class<?>[] {Object.class}, AbstractCollection.class},
            {"remove", new Class<?>[] {Object.class}, AbstractCollection.class},
            {"addAll", new Class<?>[] {Collection.class}, AbstractCollection.class},
            {"removeAll", new Class<?>[] {Collection.class}, AbstractCollection.class},
            {"retainAll", new Class<?>[] {Collection.class}, AbstractCollection.class},
            {"forEach", new Class<?>[] {Consumer.class}, Iterable.class}};
        for (final Object[] entry : admitted) {
            final String name = (String) entry[0];
            final Class<?>[] parameters = (Class<?>[]) entry[1];
            final Method inherited = original.getMethod(name, parameters);
            assertEquals(entry[2], inherited.getDeclaringClass(), "frozen inherited owner");
            final Method implementation;
            try {
                implementation = current.getDeclaredMethod(name, parameters);
            } catch (final NoSuchMethodException stillInherited) {
                continue;
            }
            assertEquals(methodSignature(inherited, original), methodSignature(implementation, current),
                    "override must preserve modifiers, generic signature and declared exceptions");
            result.remove(implementation.toGenericString());
        }
        return result;
    }

    /**
     * Normalize declaring-owner spelling. Only the admitted Iterable default also
     * maps its owner type variable and default marker after validating the exact binding.
     */
    private static String methodSignature(final Method method, final Class<?> receiver) {
        if ("forEach".equals(method.getName()) && Arrays.equals(new Class<?>[] {Consumer.class}, method.getParameterTypes())) {
            final Class<?> owner = method.getDeclaringClass();
            assertTrue(owner == Iterable.class || owner == receiver, "exact inherited or receiving forEach owner");
            assertTrue(Iterable.class.isAssignableFrom(receiver), "receiver implements the inherited Iterable contract");
            return forEachDeclarationSignature(method);
        }
        final String declaration = method.toGenericString();
        final String owner = method.getDeclaringClass().getName() + ".";
        final int start = declaration.indexOf(owner + method.getName() + "(");
        if (start < 0) {
            throw new AssertionError("missing declaring owner in " + declaration);
        }
        return declaration.substring(0, start) + declaration.substring(start + owner.length());
    }

    /** Validate the complete declaration before normalizing the one admitted element-variable binding. */
    private static String forEachDeclarationSignature(final Method method) {
        final Class<?> owner = method.getDeclaringClass();
        assertFalse(method.isVarArgs(), "forEach is not variable arity");
        assertEquals(Modifier.PUBLIC, method.getModifiers(), "forEach remains public and non-static, non-final");
        assertEquals(owner == Iterable.class, method.isDefault(), "only the inherited method is a default");
        assertEquals(1, owner.getTypeParameters().length, "one element type variable");
        assertArrayEquals(new Object[] {Object.class}, owner.getTypeParameters()[0].getBounds(), "unchanged element bound");
        final String element = owner.getTypeParameters()[0].getName();
        final String expected = "public " + (owner == Iterable.class ? "default " : "") + "void "
                + owner.getName() + ".forEach(java.util.function.Consumer<? super " + element + ">)";
        assertEquals(expected, method.toGenericString(), "exact element variance, result and throws signature");
        return "public void forEach(java.util.function.Consumer<? super E>)";
    }

    /** All callable public and inherited protected signatures, with overriding resolved by erased parameters. */
    private static TreeSet<String> effectiveSurface(final Class<?> type) {
        final TreeSet<String> result = new TreeSet<>();
        Arrays.stream(type.getMethods()).forEach(method -> result.add(methodSignature(method, type)));
        final HashSet<String> hidden = new HashSet<>();
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            final HashSet<String> declaredHere = new HashSet<>();
            for (final Method method : owner.getDeclaredMethods()) {
                final int modifiers = method.getModifiers();
                if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) {
                    final String key = method.getName() + Arrays.toString(method.getParameterTypes());
                    if (Modifier.isProtected(modifiers) && !hidden.contains(key)) {
                        result.add(methodSignature(method, type));
                    }
                    declaredHere.add(key);
                }
            }
            hidden.addAll(declaredHere);
        }
        return result;
    }

    private static TreeSet<String> surface(final Class<?> type) {
        final TreeSet<String> result = new TreeSet<>();
        Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) || Modifier.isProtected(method.getModifiers()))
                .forEach(method -> result.add(method.toGenericString()));
        Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers())
                        || Modifier.isProtected(constructor.getModifiers()))
                .forEach(constructor -> result.add(constructor.toGenericString()));
        return result;
    }

    /** Original public and protected declarations for independent inventory-mutation tests. */
    private static class SurfaceFixture<E> extends AbstractCollection<E> {
        @Override
        public Iterator<E> iterator() {
            return java.util.Collections.<E>emptyList().iterator();
        }

        @Override
        public int size() {
            return 0;
        }

        public void originalPublic() {
        }

        protected void originalProtected() {
        }
    }

    private static final class UnexpectedSurfaceFixture {
        public Object[] toArray(final int ignored) {
            return null;
        }
    }

    private static final class RawForEachFixture<E> extends SurfaceFixture<E> {
        @Override
        @SuppressWarnings("rawtypes")
        public void forEach(final Consumer action) {
        }
    }

    private static final class FinalForEachFixture<E> extends SurfaceFixture<E> {
        @Override
        public final void forEach(final Consumer<? super E> action) {
        }
    }

    private static final class StaticForEachFixture<E> {
        public static void forEach(final Consumer<?> action) {
        }
    }

    private static final class WrongWildcardForEachFixture<E> {
        public void forEach(final Consumer<? extends E> action) {
        }
    }

    private static final class WrongReturnForEachFixture<E> {
        public E forEach(final Consumer<? super E> action) {
            return null;
        }
    }

    private static final class VarargsForEachFixture<E> {
        @SuppressWarnings({"unchecked", "varargs"})
        public void forEach(final Consumer<? super E>... action) {
        }
    }

    private static final class ObservingQueue extends CircularFifoQueue<Integer> {
        private static final long serialVersionUID = 1L;
        private int removalCalls;

        ObservingQueue(final int capacity) {
            super(capacity);
        }

        @Override
        public Integer remove() {
            removalCalls++;
            return super.remove();
        }
    }
}
