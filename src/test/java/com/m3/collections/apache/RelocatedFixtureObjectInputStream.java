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
package com.m3.collections.apache;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;

/** Reads preserved upstream fixtures into their package-relocated test classes. */
final class RelocatedFixtureObjectInputStream extends ObjectInputStream {
    RelocatedFixtureObjectInputStream(final InputStream input) throws IOException {
        super(input);
    }

    @Override
    protected Class<?> resolveClass(final ObjectStreamClass descriptor)
            throws IOException, ClassNotFoundException {
        final String originalName = descriptor.getName();
        final String mappedName = originalName.replace(
                "org.apache.commons.collections4.", "com.m3.collections.apache.");
        if (!originalName.equals(mappedName)) {
            return Class.forName(mappedName, false, RelocatedFixtureObjectInputStream.class.getClassLoader());
        }
        return super.resolveClass(descriptor);
    }
}
