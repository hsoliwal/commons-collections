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
package org.apache.commons.collections4;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Calls one method with inputs derived from a seed and prints what happened: the returned value or the thrown
 * exception, the arguments after the call and, for an instance method, the receiver after the call.
 * <p>
 * The same inputs always give the same text, so a text recorded once is an expectation for later runs. A method is
 * supported when every parameter is of a type listed in {@link #argument(Class, Random)} and, unless it is static,
 * its class is concrete and has a constructor whose parameters are of such types; for any other method the text
 * starts with {@link #UNSUPPORTED}.
 * </p>
 */
final class MethodBehaviour {

    /** Start of the text for a method this class cannot call. */
    static final String UNSUPPORTED = "UNSUPPORTED ";

    /** The package of this library, as the engine itself is declared in it. */
    private static final String ROOT = MethodBehaviour.class.getPackage().getName();

    /** Longest printed value kept verbatim; longer ones keep this prefix, their length and their hash code. */
    private static final int MAX_TEXT = 240;

    /** Exceptions whose message is worded by the virtual machine and differs between versions and class loaders. */
    private static final Set<Class<?>> VM_WORDED = new HashSet<>(Arrays.asList(ClassCastException.class,
            NullPointerException.class, ArrayStoreException.class, IndexOutOfBoundsException.class,
            ArrayIndexOutOfBoundsException.class, StringIndexOutOfBoundsException.class, NegativeArraySizeException.class));

    /** Exclusive bound of the small numbers used in inputs. */
    private static final int SMALL = 9;

    private MethodBehaviour() {
    }

    /**
     * Prints the outcome of one call.
     *
     * @param className binary name of the declaring class
     * @param methodName name of the method
     * @param parameterTypes binary names of the parameter types, separated by commas
     * @param index the input number
     * @return the outcome text
     */
    static String outcome(final String className, final String methodName, final String parameterTypes, final int index) {
        try {
            final Class<?> type = Class.forName(className);
            final Method method = Arrays.stream(type.getDeclaredMethods())
                    .filter(m -> m.getName().equals(methodName) && names(m).equals(parameterTypes))
                    .findFirst().orElse(null);
            return method == null ? UNSUPPORTED + "no such method" : outcome(type, method, index);
        } catch (final ReflectiveOperationException | LinkageError e) {
            return UNSUPPORTED + e.getClass().getSimpleName();
        }
    }

    /** Binary names of the parameter types of a method, separated by commas. */
    static String names(final Method method) {
        return Arrays.stream(method.getParameterTypes()).map(Class::getName).collect(Collectors.joining(","));
    }

    private static String outcome(final Class<?> type, final Method method, final int index) {
        final Random random = new Random(31L * relative(type.getName() + '#' + method.getName() + '(' + names(method) + ')').hashCode() + index);
        final Object[] arguments = arguments(method.getParameterTypes(), random);
        if (arguments == null) {
            return UNSUPPORTED + "parameter " + unsupported(method.getParameterTypes());
        }
        final boolean isStatic = Modifier.isStatic(method.getModifiers());
        final Object receiver = isStatic ? null : instance(type, random);
        if (!isStatic && receiver == null) {
            return UNSUPPORTED + "no instance";
        }
        return relative(call(method, receiver, arguments) + " ARGS " + print(arguments) + (isStatic ? "" : " THIS " + print(receiver)));
    }

    /**
     * The text with this library's package name written as {@code ~}. Seeds and recorded outcomes are derived from
     * such text only, so the recorded table stays valid when the library is carried under another package name.
     */
    static String relative(final String text) {
        return text.replace(ROOT, "~");
    }

    /** One value per parameter type, or null when a type is not supported. */
    private static Object[] arguments(final Class<?>[] types, final Random random) {
        final Object[] arguments = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            arguments[i] = argument(types[i], random);
            if (arguments[i] == null) {
                return null;
            }
        }
        return arguments;
    }

    private static String unsupported(final Class<?>[] types) {
        return Arrays.stream(types).filter(type -> argument(type, new Random(0)) == null).map(Class::getSimpleName)
                .findFirst().orElse("?");
    }

    /** An instance from the first constructor, fewest parameters first, that takes supported types and succeeds. */
    private static Object instance(final Class<?> type, final Random random) {
        if (Modifier.isAbstract(type.getModifiers())) {
            return null;
        }
        final Constructor<?>[] constructors = type.getDeclaredConstructors();
        Arrays.sort(constructors, Comparator.comparingInt((Constructor<?> c) -> c.getParameterCount())
                .thenComparing(Constructor::toGenericString));
        for (final Constructor<?> constructor : constructors) {
            final Object[] arguments = arguments(constructor.getParameterTypes(), random);
            if (arguments != null) {
                try {
                    constructor.setAccessible(true);
                    return constructor.newInstance(arguments);
                } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                    continue;
                }
            }
        }
        return null;
    }

    private static String call(final Method method, final Object receiver, final Object[] arguments) {
        try {
            method.setAccessible(true);
            return "RETURN " + print(method.invoke(receiver, arguments));
        } catch (final InvocationTargetException e) {
            final Throwable cause = e.getCause();
            return "THROW " + cause.getClass().getName()
                    + (VM_WORDED.contains(cause.getClass()) ? "" : ": " + clip(Objects.toString(cause.getMessage())));
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return UNSUPPORTED + "invoke " + e.getClass().getSimpleName();
        }
    }

    /** A printed value that does not depend on object identity: arrays by content, other objects by class and text. */
    private static String print(final Object value) {
        if (value == null) {
            return "null";
        }
        if (value.getClass().isArray()) {
            return clip(Arrays.deepToString(new Object[] {value}));
        }
        try {
            return value.getClass().getName() + ':' + clip(value.toString());
        } catch (final RuntimeException e) {
            return value.getClass().getName() + ":toString threw " + e.getClass().getName();
        }
    }

    /** One line without identity hash codes, cut to a bounded length. */
    private static String clip(final String text) {
        final String line = relative(text).replaceAll("@[0-9a-f]{1,8}\\b", "@").replace("\\", "\\\\").replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", "\\t");
        return line.length() <= MAX_TEXT ? line : line.substring(0, MAX_TEXT) + "...#" + line.length() + ':' + line.hashCode();
    }

    /**
     * Makes a small value of a parameter type from the random source.
     *
     * @param type the parameter type
     * @param random the source
     * @return the value, or null when the type is not supported
     */
    static Object argument(final Class<?> type, final Random random) {
        if (type == int.class || type == Integer.class) {
            return small(random) - 2;
        }
        if (type == long.class || type == Long.class) {
            return (long) (small(random) - 2);
        }
        if (type == boolean.class || type == Boolean.class) {
            return random.nextBoolean();
        }
        if (type == char.class) {
            return (char) ('a' + small(random));
        }
        if (type == double.class) {
            return small(random) / 2.0;
        }
        if (type == String.class || type == CharSequence.class) {
            return text(random);
        }
        if (type == StringBuilder.class) {
            return new StringBuilder(text(random));
        }
        if (type == Object.class || type == Comparable.class) {
            return random.nextBoolean() ? text(random) : (Object) Integer.valueOf(small(random));
        }
        if (type == Comparator.class) {
            return Comparator.naturalOrder();
        }
        if (type == Map.Entry.class) {
            return new AbstractMap.SimpleEntry<>(text(random), small(random));
        }
        return type.isArray() ? array(type, random) : container(type, random);
    }

    private static Object array(final Class<?> type, final Random random) {
        if (type == int[].class) {
            return list(random).stream().mapToInt(Integer::intValue).toArray();
        }
        if (type == long[].class) {
            return list(random).stream().mapToLong(Integer::longValue).toArray();
        }
        if (type == byte[].class) {
            return text(random).getBytes(StandardCharsets.UTF_8);
        }
        if (type == char[].class) {
            return text(random).toCharArray();
        }
        return type == Object[].class ? list(random).toArray() : null;
    }

    private static Object container(final Class<?> type, final Random random) {
        if (type == List.class || type == Collection.class || type == Iterable.class || type == ArrayList.class) {
            return list(random);
        }
        if (type == Iterator.class) {
            return list(random).iterator();
        }
        if (type == ListIterator.class) {
            return list(random).listIterator();
        }
        if (type == Set.class) {
            return new HashSet<>(list(random));
        }
        if (type == Map.class) {
            final Map<String, Integer> map = new HashMap<>();
            list(random).forEach(value -> map.put("k" + value, value));
            return map;
        }
        return null;
    }

    private static int small(final Random random) {
        return random.nextInt(SMALL);
    }

    private static String text(final Random random) {
        final StringBuilder builder = new StringBuilder();
        final int length = small(random);
        for (int i = 0; i < length; i++) {
            builder.append((char) ('a' + small(random)));
        }
        return builder.toString();
    }

    private static ArrayList<Integer> list(final Random random) {
        final ArrayList<Integer> values = new ArrayList<>();
        final int length = small(random);
        for (int i = 0; i < length; i++) {
            values.add(small(random) - 2);
        }
        return values;
    }
}
