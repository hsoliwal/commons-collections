// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Hitesh Soliwal and Contributors to the Synexia Project.
import com.m3.collections.apache.bag.HashBag;
import com.m3.collections.apache.bidimap.DualHashBidiMap;
import com.m3.collections.apache.list.TreeList;
import com.m3.collections.apache.map.HashedMap;
import com.m3.collections.apache.multimap.ArrayListValuedHashMap;
import com.m3.collections.apache.queue.CircularFifoQueue;
import com.m3.collections.apache.trie.PatriciaTrie;

public final class StandaloneNamespaceSmoke {
    public static void main(String[] args) throws Exception {
        final HashedMap<String, Integer> map = new HashedMap<>();
        map.put("a", 1);
        check(map.get("a") == 1);
        final HashBag<String> bag = new HashBag<>();
        bag.add("a", 2);
        check(bag.getCount("a") == 2);
        final CircularFifoQueue<Integer> queue = new CircularFifoQueue<>(2);
        queue.add(1); queue.add(2); queue.add(3);
        check(queue.size() == 2 && queue.remove() == 2);
        final ArrayListValuedHashMap<String, Integer> multi = new ArrayListValuedHashMap<>();
        multi.put("a", 1); multi.put("a", 2);
        check(multi.get("a").size() == 2);
        final DualHashBidiMap<String, Integer> bi = new DualHashBidiMap<>();
        bi.put("a", 1);
        check("a".equals(bi.getKey(1)));
        final PatriciaTrie<Integer> trie = new PatriciaTrie<>();
        trie.put("abc", 1);
        check(trie.prefixMap("ab").size() == 1);
        final TreeList<Integer> list = new TreeList<>();
        list.add(1);
        check(list.get(0) == 1);
        try {
            Class.forName("org.apache.commons.collections4.Bag");
            throw new AssertionError("Original namespace unexpectedly present");
        } catch (ClassNotFoundException expected) {
            System.out.println("standalone_jar_namespace_smoke=PASS collections=7 original_namespace=ABSENT");
        }
    }
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Standalone namespace contract");
    }
}
