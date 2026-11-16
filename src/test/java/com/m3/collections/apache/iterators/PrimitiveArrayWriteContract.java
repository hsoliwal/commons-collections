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
import java.lang.reflect.Field;
import java.util.Objects;

/** Exact primitive widening and failure-prefix compatibility with Array.set. */
public final class PrimitiveArrayWriteContract {
    private static int checks;
    private static long digest=1;
    private static void check(boolean condition,String message) {
        checks++;
        if (!condition) throw new AssertionError(message+" at "+checks);
    }
    private static Object[] candidates() {
        return new Object[]{Byte.valueOf((byte)13),Short.valueOf((short)17),Character.valueOf('A'),
            Integer.valueOf(31),Long.valueOf(37),Float.valueOf(0.25f),Double.valueOf(1.5),
            Boolean.TRUE,null,"string",new Object()};
    }
    private static Object fresh(int kind) {
        switch(kind) {
        case 0:return new byte[]{11,12};
        case 1:return new short[]{11,12};
        case 2:return new char[]{'x','y'};
        case 3:return new int[]{11,12};
        case 4:return new long[]{11,12};
        case 5:return new float[]{11,12};
        case 6:return new double[]{11,12};
        case 7:return new boolean[]{true,false};
        default:return new String[]{"x","y"};
        }
    }
    private static String outcome(Runnable action) {
        try {action.run();return "OK";}
        catch(RuntimeException x){return x.getClass().getName()+":"+x.getMessage();}
    }
    private static void exercise(int kind,Object update) {
        Object expected=fresh(kind),actual=fresh(kind);
        final ArrayListIterator<Object> list=new ArrayListIterator<>(actual);
        final String original=outcome(()->Array.set(expected,0,update));
        final String beforeNext=outcome(()->list.set(update));
        check(beforeNext.equals("java.lang.IllegalStateException:must call next() or previous() before a call to set()"),"pre-next set");
        list.next();
        final String result=outcome(()->list.set(update));
        check(original.equals(result),"set exception/return "+kind+" update "+update+" "+original+" / "+result);
        check(Objects.equals(Array.get(expected,0),Array.get(actual,0)),"stored value");
        digest=digest*31+(Array.get(actual,0)==null?0:Array.get(actual,0).hashCode());
        digest=digest*31+result.hashCode();
        if("OK".equals(result)) {
            final String again=outcome(()->list.set(update));
            check("OK".equals(again),"repeated set");
            check(Objects.equals(Array.get(expected,0),Array.get(actual,0)),"repeated value");
        }
    }
    private static void invalidIndexes() {
        for(int kind=0;kind<9;kind++) {
            final Object array=fresh(kind);
            ArrayListIterator<Object> iter=new ArrayListIterator<>(array);
            iter.next();
            try {
                final Field f=ArrayListIterator.class.getDeclaredField("lastItemIndex");
                f.setAccessible(true);
                f.set(iter,6);
            } catch (final ReflectiveOperationException ex) {throw new AssertionError(ex);}
            final Object replacement=Array.get(array,0);
            final String actual=outcome(()->iter.set(replacement));
            final String expected=outcome(()->Array.set(array,6,replacement));
            check(actual.equals(expected),"invalid set index error "+kind+" "+expected+" "+actual);
        }
    }
    public static void main(String[] args) {
        checks=0;digest=1;
        for(int kind=0;kind<9;kind++)for(Object update:candidates())exercise(kind,update);
        invalidIndexes();
        System.out.println("PRIMITIVE_ARRAY_WRITE_PASS checks="+checks+" digest="+Long.toHexString(digest));
    }
}
