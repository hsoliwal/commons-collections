/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Copyright 2026 Synexia contributors; first-party behavioral differential test.
package com.m3.collections.apache.queue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Random;

/** Differential test-only oracle, not a production donor implementation. */
public final class CircularFifoQueueBulkArrayTest {
    private static long checks;
    private static final Random RNG = new Random(0x4f1f0b4fL);
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("bulk FIFO check #" + checks);
    }
    private static Object field(Object object, String name) throws Exception {
        Class<?> type = object.getClass();
        while (type != null) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
            catch (NoSuchFieldException ex) { type = type.getSuperclass(); }
        }
        throw new AssertionError(name);
    }
    private static void equalsState(Baseline<?> before, CircularFifoQueue<?> after) throws Exception {
        check(before.size() == after.size());
        check(before.isEmpty() == after.isEmpty());
        check(before.isAtFullCapacity() == after.isAtFullCapacity());
        check(Arrays.equals(before.toArray(), after.toArray()));
        check(java.util.Objects.equals(before.peek(), after.peek()));
        check(field(before,"start").equals(field(after,"start")));
        check(field(before,"end").equals(field(after,"end")));
        check(field(before,"full").equals(field(after,"full")));
        check(Arrays.equals((Object[]) field(before,"elements"), (Object[]) field(after,"elements")));
        for (int i=0; i<before.size(); i++) check(java.util.Objects.equals(before.get(i), after.get(i)));
    }
    private static void iteratorState(Iterator<Integer> a, Iterator<Integer> b, int limit) {
        for(int i=0;i<limit;i++){
            boolean left=a.hasNext(),right=b.hasNext();
            check(left==right);
            if (!left) return;
            check(java.util.Objects.equals(a.next(),b.next()));
        }
        check(a.hasNext()==b.hasNext());
    }
    private static void equalsThrown(Baseline<Integer> baseline, CircularFifoQueue<Integer> candidate,
                                    Collection<Integer> incoming) {
        Throwable first=null,second=null;
        try{baseline.addAll(incoming);}catch(Throwable e){first=e;}
        try{candidate.addAll(incoming);}catch(Throwable e){second=e;}
        check((first==null)==(second==null));
        if (first!=null) check(first.getClass()==second.getClass());
    }
    @org.junit.jupiter.api.Test
    public void stress() throws Exception {
        for (int cap=1; cap<=72; cap++) {
            Baseline<Integer> old = new Baseline<>(cap);
            CircularFifoQueue<Integer> fast = new CircularFifoQueue<>(cap);
            for (int batch=0; batch<330; batch++) {
                int count=RNG.nextInt(cap*4+15);
                ArrayList<Integer> data=new ArrayList<>();
                for(int i=0;i<count;i++)data.add(RNG.nextInt(100000)-50000);
                if (batch % 53 == 0 && count>0) data.set(RNG.nextInt(count),null);
                Iterator<Integer> oldIt=old.iterator(),fastIt=fast.iterator();
                equalsThrown(old,fast,data);
                equalsState(old,fast);
                if ((batch & 15)==0) iteratorState(oldIt,fastIt,cap+3);
                if ((batch & 3)==0 && !old.isEmpty()) {
                    check(java.util.Objects.equals(old.remove(),fast.remove()));
                    equalsState(old,fast);
                }
                if ((batch & 7)==0) {
                    int pops=RNG.nextInt(cap+1);
                    for(int j=0;j<pops && !old.isEmpty();j++)
                        check(java.util.Objects.equals(old.remove(),fast.remove()));
                    equalsState(old,fast);
                }
                if(batch%29==0){old.clear();fast.clear();equalsState(old,fast);}
            }
        }
    }
    private static class Baseline<E> extends CircularFifoQueue<E> {
        private static final long serialVersionUID = 1L;
        Baseline(int n) {super(n);}
    }
    private static class DerivedOld<E> extends Baseline<E> {
        private static final long serialVersionUID = 1L;
        int callbacks;
        DerivedOld(int n){super(n);}
        @Override public boolean add(E value){callbacks++;return super.add(value);}
    }
    private static class DerivedFast<E> extends CircularFifoQueue<E> {
        private static final long serialVersionUID = 1L;
        int callbacks;
        DerivedFast(int n){super(n);}
        @Override public boolean add(E value){callbacks++;return super.add(value);}
    }
    private static class CustomArrayList extends ArrayList<Integer> {
        private static final long serialVersionUID = 1L;
        int iterated;
        @Override public Iterator<Integer> iterator(){iterated++;return super.iterator();}
    }
    @org.junit.jupiter.api.Test
    public void fallback() throws Exception {
        DerivedOld<Integer> old=new DerivedOld<>(9);
        DerivedFast<Integer> fast=new DerivedFast<>(9);
        for(int i=0;i<40;i++){
            ArrayList<Integer> data=new ArrayList<>();
            for(int j=0;j<12;j++)data.add(i*12+j);
            equalsThrown(old,fast,data);
            equalsState(old,fast);
            check(old.callbacks==fast.callbacks);
        }
        CustomArrayList left=new CustomArrayList(),right=new CustomArrayList();
        for(int i=0;i<30;i++){left.add(i);right.add(i);}
        Baseline<Integer> a=new Baseline<>(7);
        CircularFifoQueue<Integer> b=new CircularFifoQueue<>(7);
        equalsThrown(a,b,left);
        equalsState(a,b);
        b.clear();a.clear();
        check(left.iterated>0);
        check(right.iterated==0);
        b.addAll(right);
        check(right.iterated>0);
    }
    @org.junit.jupiter.api.Test
    public void millionItems() throws Exception {
        // Inputs vastly larger than capacity, including a failure at the final position.
        ArrayList<Integer> values=new ArrayList<>();
        for(int i=0;i<100000;i++)values.add(i);
        Baseline<Integer> a=new Baseline<>(17);
        CircularFifoQueue<Integer> b=new CircularFifoQueue<>(17);
        check(a.addAll(values)==b.addAll(values));
        equalsState(a,b);
        values.set(99999,null);
        equalsThrown(a,b,values);
        equalsState(a,b);
        values.set(99999,99999);
        a.clear();b.clear();
        check(a.addAll(values)==b.addAll(values));
        equalsState(a,b);
    }
    public static void main(String[] args) throws Exception {
        CircularFifoQueueBulkArrayTest test = new CircularFifoQueueBulkArrayTest();
        test.stress();
        test.fallback();
        test.millionItems();
        System.out.println("COMMONS_FIFO_BULK_ARRAY_JUNIT_ORACLE_PASS checks=" + checks);
    }
}
