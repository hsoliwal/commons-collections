/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.collections4.list;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

/** Coverage for dense, sparse-fallback, unsupported, and no-op membership decisions. */
public final class TreeListMembershipDecisionContract {
    private static long checks;
    private static long fingerprint=1;
    private TreeListMembershipDecisionContract() { }

    private static void check(final boolean condition, final String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    private static Object field(final Object object, final String name) {
        try {
            final Field f=object.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(object);
        } catch (final ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void state(final TreeList<?> tree, final List<?> expected, final Object[] lanes) {
        check(tree.size()==expected.size(),"size");
        final Object[] got=tree.toArray();
        for(int i=0;i<got.length;i++) {
            check(got[i]==expected.get(i) && got[i]==tree.get(i),"ordered identity");
            fingerprint=fingerprint*31+i;
        }
        final Object slots=field(tree,"slots");
        final byte[] flags=(byte[])field(slots,"state");
        final Object[] values=(Object[])field(slots,"values");
        final int[] left=(int[])field(slots,"left");
        int free=(Integer)field(slots,"free"),released=0;
        final boolean[] seen=new boolean[values.length];
        while(free!=-1) {
            check(free>=0 && free<values.length && !seen[free],"free ownership/cycle");
            check(values[free]==null && flags[free]==0,"released payload/flags");
            seen[free]=true;
            free=left[free];
            released++;
        }
        final int used=(Integer)field(slots,"used");
        check(used-released==tree.size(),"slot accounting");
        for(int i=0;i<used;i++) check((flags[i]&~7)==0,"transient marker leaked");
        if(lanes!=null) {
            check(field(slots,"values")==lanes[0] && field(slots,"left")==lanes[1]
                && field(slots,"right")==lanes[2] && field(slots,"state")==lanes[3],"backing lanes replaced");
        }
    }
    private static Object[] lanes(final TreeList<?> tree) {
        final Object slots=field(tree,"slots");
        return new Object[]{field(slots,"values"),field(slots,"left"),field(slots,"right"),field(slots,"state")};
    }
    private static final class Custom {
        final int id;
        Custom(int id){this.id=id;}
        @Override public int hashCode(){return id;}
        @Override public boolean equals(Object obj){return obj instanceof Custom && ((Custom)obj).id==id;}
    }
    private static void scenario(final int n, final int kind, final boolean retain) {
        final List<Object> expected=new ArrayList<>(n);
        final HashSet<Object> selection=new HashSet<>();
        for(int i=0;i<n;i++) {
            final Object value;
            if(kind==0) value=i; // dense, all distinct
            else if(kind==1) value=i%4==0?null:Integer.valueOf(i%7); // heavy duplicates
            else if(kind==2) value=i%100==33?new Custom(i):Integer.valueOf(i%100); // unsupported at some slots
            else if(kind==3) value=i%3==0?Long.valueOf(i):"word"+(i%101); // mixed final immutable
            else value=Integer.valueOf(i%16); // mostly no-op / fallback
            expected.add(value);
            final boolean include=retain ? (i%4==0) : (i%4!=0);
            if(include && kind!=2) selection.add(value);
        }
        if(kind==2) {
            for(int i=0;i<n;i+=2) selection.add(Integer.valueOf(i));
        }
        if(kind==4) {
            selection.clear();
            for(int i=0;i<n/2;i++) selection.add(100_000+i);
        }
        final TreeList<Object> tree=new TreeList<>(expected);
        final Object[] before=lanes(tree);
        final Iterator<Object> old=tree.iterator();
        final boolean expectedChange=retain?expected.retainAll(selection):expected.removeAll(selection);
        final boolean actualChange=retain?tree.retainAll(selection):tree.removeAll(selection);
        check(expectedChange==actualChange,"return");
        state(tree,expected,before);
        if(actualChange) {
            try{old.next();throw new AssertionError("iterator not invalidated");}
            catch(final ConcurrentModificationException ok){checks++;}
        } else if(!expected.isEmpty()) {
            check(old.next()==expected.get(0),"no-op iterator invalidated");
        }
        final List<Object> refill=Arrays.asList("refill",null,Long.valueOf(99));
        tree.addAll(refill);
        expected.addAll(refill);
        state(tree,expected,null);
        if(tree.size()>30) {
            final int from=tree.size()/4,to=tree.size()/2;
            tree.subList(from,to).clear();
            expected.subList(from,to).clear();
            state(tree,expected,null);
        }
    }
    private static final class ThrowingHash {
        private final RuntimeException error = new IllegalArgumentException("synthetic hash failure");
        @Override public int hashCode() { throw error; }
    }
    private static void failedFallback() {
        final HashSet<Object> membership=new HashSet<>();
        final List<Object> list=new ArrayList<>();
        final ThrowingHash broken=new ThrowingHash();
        for(int i=0;i<8192;i++) {
            list.add(i==15?broken:Integer.valueOf(i));
            membership.add(i);
        }
        final TreeList<Object> tree=new TreeList<>(list);
        final Object[] before=lanes(tree);
        try {
            tree.removeAll(membership);
            throw new AssertionError("expected user callback failure");
        } catch(final IllegalArgumentException exception) {
            check(exception==broken.error,"exact callback exception identity");
        }
        // The dense preflight abandoned the private fast path at index 15.
        // The inherited iterator was allowed to remove the successful prefix.
        check(tree.size()==8177 && tree.get(0)==broken,"original partial mutation order");
        list.subList(0,15).clear();
        state(tree,list,before);
    }
    private static void sparseFallbackAfterMarks() {
        // HashSet cardinality passes the cheap gate, but most of its elements
        // are absent. The private preflight has marked real slots before the
        // selected-count gate hands control back to the inherited iterator.
        final List<Object> expected=new ArrayList<>();
        final HashSet<Object> selection=new HashSet<>();
        for(int i=0;i<8192;i++) {
            expected.add(Integer.valueOf(i));
            if((i&3)==0) selection.add(Integer.valueOf(i));
            selection.add(Integer.valueOf(i+8192));
        }
        final TreeList<Object> tree=new TreeList<>(expected);
        final Object[] before=lanes(tree);
        check(expected.removeAll(selection)==tree.removeAll(selection),"sparse return");
        state(tree,expected,before);
    }
    public static void verify() {
        checks=0;fingerprint=1;
        for(final int n:new int[]{0,1,31,4095,4096,8192,16384}) {
            for(int kind=0;kind<5;kind++) {
                for(boolean retain:new boolean[]{false,true})scenario(n,kind,retain);
            }
        }
        failedFallback();
        sparseFallbackAfterMarks();
        System.out.println("MEMBERSHIP_DECISION_PASS checks="+checks+" fingerprint="+Long.toHexString(fingerprint));
    }
    public static void main(final String[] args) {verify();}
}
