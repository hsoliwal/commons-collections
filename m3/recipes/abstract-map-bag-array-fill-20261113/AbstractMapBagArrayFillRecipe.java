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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Reproducible, source-sealed transform for the two AbstractMapBag array exports.
 * Kept outside src/ because the production artifact still supports Java 8.
 */
public final class AbstractMapBagArrayFillRecipe {
    private static final String BASELINE_SHA256 =
            "7855a845d887a86dd2c0bcb2676dc2177bfb9e2b35f49300e0701baf49bae11e";
    private static final String RESULT_SHA256 =
            "32fe4764cf4aa265bf4c838cc1e9efe13f1914945985b303af1d22d13a5282e1";

    private static final String BEFORE_IMPORT = "import java.lang.reflect.Array;\n";
    private static final String AFTER_IMPORT = BEFORE_IMPORT + "import java.util.Arrays;\n";

    private static final String BEFORE_OBJECT =
            "        for (final E current : map.keySet()) {\n"
            + "            for (int index = getCount(current); index > 0; index--) {\n"
            + "                result[i++] = current;\n"
            + "            }\n"
            + "        }\n";
    private static final String AFTER_OBJECT =
            "        for (final E current : map.keySet()) {\n"
            + "            final int count = getCount(current);\n"
            + "            if (count > 0) {\n"
            + "                Arrays.fill(result, i, i + count, current);\n"
            + "                i += count;\n"
            + "            }\n"
            + "        }\n";

    private static final String BEFORE_TYPED =
            "        for (final E current : map.keySet()) {\n"
            + "            for (int index = getCount(current); index > 0; index--) {\n"
            + "                // unsafe, will throw ArrayStoreException if types are not compatible, see Javadoc\n"
            + "                @SuppressWarnings(\"unchecked\")\n"
            + "                final T unchecked = (T) current;\n"
            + "                array[i++] = unchecked;\n"
            + "            }\n"
            + "        }\n"
            + "        while (i < array.length) {\n"
            + "            array[i++] = null;\n"
            + "        }\n";
    private static final String AFTER_TYPED =
            "        for (final E current : map.keySet()) {\n"
            + "            final int count = getCount(current);\n"
            + "            if (count > 0) {\n"
            + "                // Object[] stores retain the original runtime ArrayStoreException behavior.\n"
            + "                Arrays.fill(array, i, i + count, current);\n"
            + "                i += count;\n"
            + "            }\n"
            + "        }\n"
            + "        Arrays.fill(array, i, array.length, null);\n";

    private AbstractMapBagArrayFillRecipe() {
    }

    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static int occurrences(final String text, final String fragment) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(fragment, index)) >= 0) {
            count++;
            index += fragment.length();
        }
        return count;
    }

    private static String rewrite(final String input) {
        if (occurrences(input, BEFORE_IMPORT) != 1
                || occurrences(input, BEFORE_OBJECT) != 1
                || occurrences(input, BEFORE_TYPED) != 1
                || occurrences(input, AFTER_IMPORT) != 0
                || occurrences(input, AFTER_OBJECT) != 0
                || occurrences(input, AFTER_TYPED) != 0) {
            throw new IllegalStateException("SOURCE_SEAL_ANCHORS_CHANGED");
        }
        return input.replace(BEFORE_IMPORT, AFTER_IMPORT)
                .replace(BEFORE_OBJECT, AFTER_OBJECT)
                .replace(BEFORE_TYPED, AFTER_TYPED);
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 2 || !("--preview".equals(args[0])
                || "--apply".equals(args[0]) || "--check".equals(args[0]))) {
            throw new IllegalArgumentException("usage: --preview|--apply|--check <AbstractMapBag.java>");
        }
        final Path target = Path.of(args[1]).toAbsolutePath().normalize();
        if (!"AbstractMapBag.java".equals(target.getFileName().toString())) {
            throw new IllegalArgumentException("EXPECTED_ABSTRACT_MAP_BAG_FILE");
        }
        final byte[] before = Files.readAllBytes(target);
        final String preimage = sha256(before);
        final String result;
        if (BASELINE_SHA256.equals(preimage)) {
            result = rewrite(new String(before, StandardCharsets.UTF_8));
        } else if (RESULT_SHA256.equals(preimage)) {
            final String source = new String(before, StandardCharsets.UTF_8);
            if (occurrences(source, AFTER_IMPORT) != 1
                    || occurrences(source, AFTER_OBJECT) != 1
                    || occurrences(source, AFTER_TYPED) != 1
                    || occurrences(source, BEFORE_OBJECT) != 0
                    || occurrences(source, BEFORE_TYPED) != 0) {
                throw new IllegalStateException("POSTIMAGE_ANCHORS_CHANGED");
            }
            result = source;
        } else {
            throw new IllegalStateException("SOURCE_SEAL_SHA256_MISMATCH:" + preimage);
        }
        final byte[] after = result.getBytes(StandardCharsets.UTF_8);
        final String postimage = sha256(after);
        final boolean changed = !preimage.equals(postimage);
        System.out.println("recipe=abstract-map-bag-array-fill/1 changed=" + changed
                + " input=" + preimage + " output=" + postimage);
        if ("--check".equals(args[0]) && changed) {
            throw new IllegalStateException("RECIPE_NOT_APPLIED");
        }
        if ("--apply".equals(args[0])) {
            if (!RESULT_SHA256.equals(postimage)) {
                throw new IllegalStateException("UNPINNED_RECIPE_RESULT_SHA256:" + postimage);
            }
            if (changed) {
                final Path temporary = Files.createTempFile(target.getParent(), ".map-bag-array-fill-", ".tmp");
                try {
                    Files.write(temporary, after);
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        }
    }
}
