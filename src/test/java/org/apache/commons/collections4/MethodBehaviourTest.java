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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * One test per method listed in {@code method-behaviour.tsv}: the method is called with the recorded inputs and
 * must give the recorded outcome (see {@link MethodBehaviour}).
 * <p>
 * The table was recorded by running the methods, not written by hand. Rows marked {@code base} were recorded on the
 * sources before long methods were split into a short method and private helpers, so they show that the split did
 * not change what those methods do; rows marked {@code result} pin the helpers themselves.
 * </p>
 */
class MethodBehaviourTest {

    private static final String TABLE = "method-behaviour.tsv";

    private static final int COLUMNS = 6;

    private static List<String[]> rows() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                MethodBehaviourTest.class.getResourceAsStream(TABLE), StandardCharsets.UTF_8))) {
            return reader.lines().skip(1).filter(line -> !line.isEmpty()).map(line -> line.split("\t", COLUMNS))
                    .collect(Collectors.toList());
        }
    }

    @TestFactory
    Stream<DynamicTest> recordedOutcomes() throws IOException {
        final Map<String, List<String[]>> methods = rows().stream().collect(Collectors.groupingBy(
            row -> row[1] + '#' + row[2] + '(' + row[3] + ')', LinkedHashMap::new, Collectors.toList()));
        assertFalse(methods.isEmpty(), "no recorded outcomes");
        return methods.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            for (final String[] row : entry.getValue()) {
                assertEquals(row[5], MethodBehaviour.outcome(row[1], row[2], row[3], Integer.parseInt(row[4])),
                        () -> entry.getKey() + " input " + row[4] + " (recorded on " + row[0] + ")");
            }
        }));
    }
}
