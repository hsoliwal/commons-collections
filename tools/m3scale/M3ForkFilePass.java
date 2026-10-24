// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and contributors
// SPDX-License-Identifier: Apache-2.0
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * M3Scale prepass over ALL tracked files, not only selected Java classes.
 * Uses SHA-256 and a conservative problem-category catalogue. Never rewrites
 * donor files: existing Maven/OpenRewrite/JDT M3Scale recipes remain the ONLY
 * transformation authority. The file-to-atom admission trail begins here.
 */
public final class M3ForkFilePass {
    private M3ForkFilePass() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || (!args[0].equals("scan") && !args[0].equals("verify"))) {
            throw new IllegalArgumentException("scan ROOT OUT_DIR | verify ROOT OUT_DIR");
        }
        Path root = Path.of(args[1]).toRealPath();
        Path out = Path.of(args[2]).toAbsolutePath().normalize();
        if (!Files.exists(root.resolve(".git"))) throw new IOException("not a git checkout");
        List<String> files = tracked(root);
        StringBuilder inventory = new StringBuilder("path\ttype\tbytes\tsha256\tcategory\tstage\n");
        StringBuilder category = new StringBuilder("path\tproblem_category\tpattern_candidates\tdecision\n");
        int java = 0, other = 0;
        for (String name : files) {
            if (name.contains("\t") || name.contains("\n") || name.contains("\r")) {
                throw new IOException("unrepresentable tracked file path");
            }
            Path p = resolve(root, name);
            String kind;
            String hash;
            long size;
            String family;
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                kind = name.endsWith(".java") ? "JAVA" : "OTHER";
                if (kind.equals("JAVA")) java++; else other++;
                size = Files.size(p);
                hash = sha256(p);
                family = kind.equals("JAVA") ? classify(name, prefix(p)) : "FILE_PROVENANCE";
            } else if (Files.isSymbolicLink(p)) {
                kind = "SYMLINK";
                byte[] link = Files.readSymbolicLink(p).toString()
                        .getBytes(StandardCharsets.UTF_8);
                size = link.length;
                hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(link));
                family = "FILE_PROVENANCE";
                other++;
            } else if (Files.isDirectory(p)) {
                kind = "GITLINK";
                hash = "-";
                size = 0;
                family = "SUBMODULE_REQUIRES_SEPARATE_REPO_PASS";
                other++;
            } else {
                throw new IOException("tracked file missing: " + name);
            }
            inventory.append(name).append('\t').append(kind).append('\t')
                    .append(size).append('\t').append(hash).append('\t')
                    .append(family).append('\t').append("INVENTORIED_NOT_REWRITTEN").append('\n');
            category.append(name).append('\t').append(family).append('\t')
                    .append(candidates(family)).append('\t').append("RECIPE_GATE_REQUIRED").append('\n');
        }
        if (files.isEmpty()) throw new IOException("empty Git file set");
        if (args[0].equals("scan")) {
            Files.createDirectories(out);
            Files.writeString(out.resolve("all-files.tsv"), inventory, StandardCharsets.UTF_8);
            Files.writeString(out.resolve("problem-catalogue.tsv"), category, StandardCharsets.UTF_8);
            System.out.println("M3_ALL_FILES_INVENTORIED total=" + files.size()
                    + " java=" + java + " other=" + other + " code_changes=0");
        } else {
            if (!inventory.toString().equals(Files.readString(out.resolve("all-files.tsv"),
                    StandardCharsets.UTF_8))
                    || !category.toString().equals(Files.readString(out.resolve("problem-catalogue.tsv"),
                    StandardCharsets.UTF_8))) {
                throw new IllegalStateException("source SHA256 or catalogue drift: refuse promotion");
            }
            System.out.println("PASS_M3_ALL_FILES_FIXED_POINT total=" + files.size());
        }
    }

    private static List<String> tracked(Path root) throws Exception {
        Process p = new ProcessBuilder("git", "ls-files", "--cached", "-z")
                .directory(root.toFile()).start();
        byte[] data;
        try (InputStream in = p.getInputStream()) { data = in.readAllBytes(); }
        if (p.waitFor() != 0) throw new IOException("git ls-files failed");
        List<String> result = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                String name = new String(data, from, i - from, StandardCharsets.UTF_8);
                if (name.contains("\ufffd")) throw new IOException("non-UTF8 Git name");
                result.add(name);
                from = i + 1;
            }
        }
        if (from != data.length) throw new IOException("unterminated Git file list");
        Collections.sort(result);
        return result;
    }

    private static Path resolve(Path root, String name) throws IOException {
        Path relative = Path.of(name);
        if (relative.isAbsolute() || name.indexOf('\\') >= 0) throw new IOException("unsafe path");
        Path target = root;
        for (Path part : relative) {
            if (part.toString().equals("..") || part.toString().equals(".")) throw new IOException("traversal");
            target = target.resolve(part);
            if (Files.isSymbolicLink(target) && !target.equals(root.resolve(relative))) {
                throw new IOException("symlinked parent path requires review");
            }
        }
        if (!target.normalize().startsWith(root)) throw new IOException("out-of-tree path");
        return target;
    }

    private static String sha256(Path source) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(source)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) hash.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static String prefix(Path source) throws Exception {
        try (InputStream in = Files.newInputStream(source)) {
            return new String(in.readNBytes(32768), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    private static String classify(String name, String sample) {
        String text = name.toLowerCase(Locale.ROOT) + " " + sample;
        if (has(text, "mask", "bitset", "bitmap", "rankselect")) return "BIT_MASK";
        if (has(text, "sort", "comparator", "priorityqueue")) return "SORT_ORDER";
        if (has(text, "filter", "predicate", "iterator", "view")) return "FILTER_VIEW";
        if (has(text, "array", "index", "offset", "slot", "list")) return "ARRAY_INDEX";
        if (has(text, "map", "hash", "lookup", "bimap")) return "HASH_LOOKUP";
        if (has(text, "pattern", "matcher", "regex", "string")) return "REGEX_TEXT";
        if (has(text, "jni", "native ", "memorysegment")) return "JNI_FFI";
        return "GENERAL_JAVA";
    }

    private static boolean has(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private static String candidates(String category) {
        return switch (category) {
            case "BIT_MASK" -> "bit operations; rank/select; bitmap compaction";
            case "SORT_ORDER" -> "stable mergesort; radix index permutation";
            case "FILTER_VIEW" -> "stored representative; predicate cursor; late binding";
            case "ARRAY_INDEX" -> "two-pointer compaction; offsets; logical-physical index";
            case "HASH_LOOKUP" -> "array-backed open addressing; probe index";
            case "REGEX_TEXT" -> "regex precompute; segment cursor";
            case "JNI_FFI" -> "primitive bulk JNI; bounds verification";
            default -> "inventory only; no qualified replacement";
        };
    }
}
