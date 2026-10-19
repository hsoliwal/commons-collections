// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package com.synexia.m3.commons;

import com.m3.collections.M3ObjectStream;
import java.util.Objects;
import com.m3.collections.apache.Bag;
import com.m3.collections.apache.MultiValuedMap;
/** Opt-in collection bridge without changing Apache predicate/admission policy. */
public final class M3CommonsStreams {
    private M3CommonsStreams() {}
    public static <K,V> M3ObjectStream<V> snapshotValues(MultiValuedMap<K,V> map,K key) {
        return M3ObjectStream.snapshot(Objects.requireNonNull(map, "map").get(key));
    }
    public static <K,V> M3ObjectStream<V> liveValues(MultiValuedMap<K,V> map,K key) {
        return M3ObjectStream.live(Objects.requireNonNull(map, "map").get(key));
    }
    public static <T> M3ObjectStream<T> snapshotBag(Bag<T> bag) {
        return M3ObjectStream.snapshot(Objects.requireNonNull(bag, "bag"));
    }

    /** Universal Iterable adapter for List, Set, Queue, Bag and other provider occurrences. */
    public static <T> M3ObjectStream<T> snapshotAny(Iterable<? extends T> source) {
        return M3ObjectStream.snapshot(Objects.requireNonNull(source, "source"));
    }

    /** Universal live adapter; encounter and fail-fast semantics remain provider-owned. */
    public static <T> M3ObjectStream<T> liveAny(java.util.Collection<T> source) {
        return M3ObjectStream.live(Objects.requireNonNull(source, "source"));
    }
}
