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
package com.m3.collections.apache.iterators;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Random;
import java.util.NoSuchElementException;

/** Real-owner primitive/reference array traversal, mutation and failure parity. */
public final class PrimitiveDirectArrayContract {
    private static long checks;
    private static long digest=1;

    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why + " check=" + checks);
    }

    private static void snapshot(Object array, int start, int end) {
        final ArrayIterator<Object> a=new ArrayIterator<>(array,start,end);
        final ArrayListIterator<Object> b=new ArrayListIterator<>(array,start,end);
        check(a.getArray()==array && a.getStartIndex()==start && a.getEndIndex()==end,"stored config");
        check(b.getArray()==array && b.getStartIndex()==start && b.getEndIndex()==end,"list config");
        for (int i=start;i<end;i++) {
            check(a.hasNext() && b.hasNext(),"hasNext");
            final Object expected=Array.get(array,i);
            final Object actual=a.next(), other=b.next();
            check(Objects.equals(expected,actual) && Objects.equals(expected,other),"forward values at="+i);
            check(actual==null || actual.getClass()==expected.getClass(),"boxing runtime type");
            check(b.nextIndex()==i-start+1 && b.previousIndex()==i-start,"relative indexes");
            digest=digest*31+(expected==null?0:expected.hashCode());
        }
        check(!a.hasNext() && !b.hasNext(),"exhausted");
        for (int i=end-1;i>=start;i--) {
            check(b.hasPrevious(),"hasPrevious");
            check(Objects.equals(b.previous(),Array.get(array,i)),"backward values");
            check(b.nextIndex()==i-start,"reverse index");
        }
        check(!b.hasPrevious(),"backward exhausted");
        a.reset();b.reset();
        check(a.hasNext()==(end>start) && b.hasNext()==(end>start),"reset cursor");
        if (end>start) check(Objects.equals(a.next(),b.next()),"reset value");
        try {
            new ArrayIterator<>(array,start,end).remove();
            throw new AssertionError("unexpected remove support");
        } catch (final UnsupportedOperationException e) {
            check("remove() method is not supported".equals(e.getMessage()),"remove message");
        }
    }

    private static void primitives() {
        final Random r=new Random(0xA7E3L);
        for (int n:new int[]{0,1,2,7,64,65,127,255,4096}) {
            final boolean[] zs=new boolean[n];final byte[] bs=new byte[n];
            final short[] ss=new short[n];final char[] cs=new char[n];
            final int[] is=new int[n];final long[] ls=new long[n];
            final float[] fs=new float[n];final double[] ds=new double[n];
            for(int i=0;i<n;i++) {
                zs[i]=r.nextBoolean();bs[i]=(byte)r.nextInt();ss[i]=(short)r.nextInt();
                cs[i]=(char)r.nextInt();is[i]=r.nextInt();ls[i]=r.nextLong();
                fs[i]=Float.intBitsToFloat(r.nextInt());ds[i]=Double.longBitsToDouble(r.nextLong());
            }
            for(Object a:new Object[]{zs,bs,ss,cs,is,ls,fs,ds,
                    new Object[n], new String[n],new int[n][]}) {
                snapshot(a,0,n);
                if(n>3) {
                    snapshot(a,1,n-1);
                    snapshot(a,n/2,n);
                }
            }
        }
    }

    private static void writes() {
        final Object[] arrays={new boolean[]{true,false},new byte[]{1,2},
                new short[]{1,2},new char[]{'a','b'},new int[]{11,12},
                new long[]{13,14},new float[]{15,16},new double[]{17,18},
                new Object[]{11L,12L}};
        for(final Object array:arrays) {
            final ArrayListIterator<Object> li=new ArrayListIterator<>(array);
            check(Objects.equals(li.next(),Array.get(array,0)),"write preflight");
            final Object replacement=Array.get(array,1);
            li.set(replacement);
            check(Objects.equals(Array.get(array,0),replacement),"written slot");
            li.previous();
            li.set(replacement);
            check(Objects.equals(Array.get(array,0),replacement),"reverse set");
            try {li.remove();throw new AssertionError("list remove allowed");}
            catch(final UnsupportedOperationException expected){checks++;}
        }
    }

    private static String reflectiveBoundsMessage(final Object array, final int index) {
        try {
            Array.get(array, index);
            throw new AssertionError("expected reflective bounds failure");
        } catch (final ArrayIndexOutOfBoundsException expected) {
            return expected.getMessage();
        }
    }

    private static void boundaryExceptions() {
        for(Object array:new Object[]{new int[]{1},new long[]{1},new float[]{1},
                new double[]{1},new boolean[]{true},new byte[]{1},
                new char[]{'x'},new short[]{1},new Object[]{1}}){
            ArrayIterator<Object> next=new ArrayIterator<Object>(array) {
                @Override public boolean hasNext(){return true;}
            };
            next.next();
            try{next.next();throw new AssertionError("overridden hasNext bypasses bound");}
            catch(ArrayIndexOutOfBoundsException ex){check(Objects.equals(ex.getMessage(),reflectiveBoundsMessage(array,1)),"native Array.get message");}
            ArrayListIterator<Object> list=new ArrayListIterator<Object>(array) {
                @Override public boolean hasNext(){return true;}
                @Override public boolean hasPrevious(){return true;}
            };
            list.next();
            try{list.next();throw new AssertionError("list overridden next bound");}
            catch(ArrayIndexOutOfBoundsException ex){check(Objects.equals(ex.getMessage(),reflectiveBoundsMessage(array,1)),"list next reflect message");}
            list.reset();
            try{list.previous();throw new AssertionError("list overridden previous bound");}
            catch(ArrayIndexOutOfBoundsException ex){check(Objects.equals(ex.getMessage(),reflectiveBoundsMessage(array,-1)),"list previous reflect message");}
        }
    }

    public static void main(String[] args) {
        checks=0;digest=1;
        primitives();writes();boundaryExceptions();
        System.out.println("PRIMITIVE_ARRAY_CONTRACT_PASS checks="+checks+" digest="+Long.toHexString(digest));
    }
}
