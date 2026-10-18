// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package com.synexia.m3.commons;

import java.util.List;
import java.util.stream.Collectors;
import com.m3.collections.apache.bag.HashBag;
import com.m3.collections.apache.multimap.ArrayListValuedHashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class M3CommonsStreamsTest {
    @Test void orderedMultimapAndBag() {
        var data = new ArrayListValuedHashMap<String,Integer>();
        data.put("x",1); data.put("x",1);
        var frozen = M3CommonsStreams.snapshotValues(data,"x");
        var live = M3CommonsStreams.liveValues(data,"x");
        data.put("x",3);
        assertEquals(List.of(1,1),frozen.collect(Collectors.toList()));
        assertEquals(List.of(1,1,3),live.collect(Collectors.toList()));
        assertEquals(List.of(2,2,6),live.map(x -> x*2).collect(Collectors.toList()));
        var bag = new HashBag<Integer>(); bag.add(9,3);
        assertEquals(3L,M3CommonsStreams.snapshotBag(bag).count());
    }
}
