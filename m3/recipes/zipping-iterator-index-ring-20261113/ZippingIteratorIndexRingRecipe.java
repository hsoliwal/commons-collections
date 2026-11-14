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

/** Deterministic source-sealed Java 21 recipe for the ZippingIterator storage atom. */
public final class ZippingIteratorIndexRingRecipe {
    private static final String BASELINE_SHA256 =
            "75e0faa835583fdb7285cd7de29ec5af06b6914e91241e024fd71a62ed9e4418";
    private static final String RESULT_SHA256 =
            "d8ca6656713d8501bfab42409c81d8eaa8f2a0611c14ddc9e532fcd2f38a24e9";

    private static final String OLD_IMPORTS = lines(
            "import java.util.ArrayList;", "import java.util.Iterator;",
            "import java.util.List;", "import java.util.NoSuchElementException;",
            "import java.util.Objects;", "", "import com.m3.collections.apache.FluentIterable;");
    private static final String NEW_IMPORTS = lines(
            "import java.util.Iterator;", "import java.util.NoSuchElementException;",
            "import java.util.Objects;");

    private static final String OLD_FIELDS = lines(
            "    /** The {@link Iterator}s to evaluate. */",
            "    private final Iterator<Iterator<? extends E>> iterators;");
    private static final String NEW_FIELDS = lines(
            "    /** Snapshot of the child iterator slots, in caller order. */",
            "    private final Iterator<? extends E>[] iterators;", "",
            "    /** Primitive circular links for the still-live iterator slots. */",
            "    private final int[] successor;",
            "    private final int[] predecessor;", "",
            "    /** Next live slot to inspect, or -1 when all children are exhausted. */",
            "    private int cursor;", "",
            "    /** Number of live children in the circular index lanes. */",
            "    private int live;");

    private static final String OLD_CONSTRUCTOR = lines(
            "        // create a mutable list to be able to remove exhausted iterators",
            "        final List<Iterator<? extends E>> list = new ArrayList<>();",
            "        for (final Iterator<? extends E> iterator : iterators) {",
            "            Objects.requireNonNull(iterator, \"iterator\");",
            "            list.add(iterator);",
            "        }",
            "        this.iterators = FluentIterable.of(list).loop().iterator();");
    private static final String NEW_CONSTRUCTOR = lines(
            "        this.iterators = iterators.clone();",
            "        this.successor = new int[iterators.length];",
            "        this.predecessor = new int[iterators.length];",
            "        for (int i = 0; i < iterators.length; i++) {",
            "            Objects.requireNonNull(this.iterators[i], \"iterator\");",
            "            this.successor[i] = i + 1 == iterators.length ? 0 : i + 1;",
            "            this.predecessor[i] = i == 0 ? iterators.length - 1 : i - 1;",
            "        }",
            "        this.live = iterators.length;",
            "        this.cursor = iterators.length == 0 ? -1 : 0;");

    private static final String OLD_HAS_NEXT = lines(
            "        while (iterators.hasNext()) {",
            "            final Iterator<? extends E> childIterator = iterators.next();",
            "            if (childIterator.hasNext()) {",
            "                nextIterator = childIterator;",
            "                return true;",
            "            }",
            "            // iterator is exhausted, remove it",
            "            iterators.remove();",
            "        }",
            "        return false;");
    private static final String NEW_HAS_NEXT = lines(
            "        while (live > 0) {",
            "            final int index = cursor;",
            "            cursor = successor[index];",
            "            final Iterator<? extends E> childIterator = iterators[index];",
            "            if (childIterator.hasNext()) {",
            "                nextIterator = childIterator;",
            "                return true;",
            "            }",
            "            // Unlink the exhausted slot in O(1), without shifting a list.",
            "            if (live == 1) {",
            "                cursor = -1;",
            "            } else {",
            "                final int before = predecessor[index];",
            "                final int after = successor[index];",
            "                successor[before] = after;",
            "                predecessor[after] = before;",
            "            }",
            "            iterators[index] = null; // release exhausted child, preserve lastReturned",
            "            live--;",
            "        }",
            "        return false;");

    private ZippingIteratorIndexRingRecipe() {
    }

    private static String lines(final String... values) {
        return String.join("\n", values) + "\n";
    }

    private static String sha256(final byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static String replaceExactlyOnce(final String source, final String from, final String to) {
        final int position = source.indexOf(from);
        if (position < 0 || source.indexOf(from, position + from.length()) >= 0) {
            throw new IllegalStateException("SOURCE_SEAL_ANCHOR_MISMATCH");
        }
        return source.substring(0, position) + to + source.substring(position + from.length());
    }

    private static String rewrite(final String source) {
        return replaceExactlyOnce(
                replaceExactlyOnce(
                        replaceExactlyOnce(
                                replaceExactlyOnce(source, OLD_IMPORTS, NEW_IMPORTS),
                                OLD_FIELDS, NEW_FIELDS), OLD_CONSTRUCTOR, NEW_CONSTRUCTOR),
                OLD_HAS_NEXT, NEW_HAS_NEXT);
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 2 || !("--preview".equals(args[0]) || "--apply".equals(args[0])
                || "--check".equals(args[0]))) {
            throw new IllegalArgumentException("usage: --preview|--apply|--check <ZippingIterator.java>");
        }
        final Path target = Path.of(args[1]).toAbsolutePath().normalize();
        if (!"ZippingIterator.java".equals(target.getFileName().toString())) {
            throw new IllegalArgumentException("EXPECTED_ZIPPING_ITERATOR_FILE");
        }
        final byte[] before = Files.readAllBytes(target);
        final String beforeSha = sha256(before);
        final String output;
        if (BASELINE_SHA256.equals(beforeSha)) {
            output = rewrite(new String(before, StandardCharsets.UTF_8));
        } else if (RESULT_SHA256.equals(beforeSha)) {
            output = new String(before, StandardCharsets.UTF_8);
            if (!output.contains(NEW_FIELDS) || !output.contains(NEW_HAS_NEXT)
                    || output.contains(OLD_FIELDS) || output.contains(OLD_HAS_NEXT)) {
                throw new IllegalStateException("POSTIMAGE_ANCHOR_MISMATCH");
            }
        } else {
            throw new IllegalStateException("SOURCE_SEAL_SHA256_MISMATCH:" + beforeSha);
        }
        final byte[] after = output.getBytes(StandardCharsets.UTF_8);
        final String afterSha = sha256(after);
        final boolean changed = !beforeSha.equals(afterSha);
        System.out.println("recipe=zipping-iterator-index-ring/1 changed=" + changed
                + " input=" + beforeSha + " output=" + afterSha);
        if ("--check".equals(args[0]) && changed) {
            throw new IllegalStateException("RECIPE_NOT_APPLIED");
        }
        if ("--apply".equals(args[0])) {
            if (!RESULT_SHA256.equals(afterSha)) {
                throw new IllegalStateException("UNPINNED_RECIPE_RESULT:" + afterSha);
            }
            if (changed) {
                final Path temporary = Files.createTempFile(target.getParent(), ".zipping-ring-", ".tmp");
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
