// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package com.synexia.m3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Maven-built, exact-source-admission recipe for two new Java 21 files.
 *
 * <p>Source templates are SHA-256 sealed. All templates and occupied target
 * paths are checked before any serial CREATE_NEW write. Existing paths must
 * be the exact expected postimage; unexpected bytes or symlinks refuse. This
 * recipe is additive: existing-source transformations belong to the canonical
 * Synexia OpenRewrite owner, not to manual postimage patches.
 */
public final class M3LongPathInputBudgetRecipe {
    private static final String[] NAMES = {
            "M3LongIndexPathBudget.java",
            "M3LongIndexPathBudgetSelfTest.java"
    };
    private static final String[] SHA256 = {
            "1018c6481706dc38960aa7145b24b0274e2645ade85dc36eaef26a3a6c660e0c",
            "5f6008e5c8c88025bb998afe7a0701711c010e88a4163c5bb960ac52f7d35920"
    };
    private static final String[] RECEIVERS = {
            "m3/collections/src/main/java/com/m3/collections/M3LongIndexPathBudget.java",
            "m3/collections/src/test/java/com/m3/collections/M3LongIndexPathBudgetSelfTest.java"
    };

    private M3LongPathInputBudgetRecipe() { }

    /** Usage: --apply|--check CHECKOUT_ROOT SOURCE_TEMPLATE_DIRECTORY. */
    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 3
                || !("--apply".equals(arguments[0]) || "--check".equals(arguments[0]))) {
            throw new IllegalArgumentException("usage: --apply|--check checkout templates");
        }
        Path root = Path.of(arguments[1]).toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path templates = Path.of(arguments[2]).toRealPath(LinkOption.NOFOLLOW_LINKS);
        byte[][] sealed = new byte[NAMES.length][];
        Path[] targets = new Path[NAMES.length];
        boolean checkOnly = "--check".equals(arguments[0]);
        for (int i = 0; i < NAMES.length; i++) {
            Path source = templates.resolve(NAMES[i]);
            refuseLinkedSegments(templates, source);
            sealed[i] = Files.readAllBytes(source);
            if (!SHA256[i].equals(sha256(sealed[i]))) {
                throw new IOException("template digest changed: " + NAMES[i]);
            }
            Path target = root.resolve(RECEIVERS[i]);
            refuseLinkedSegments(root, target);
            targets[i] = target;
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Arrays.equals(Files.readAllBytes(target), sealed[i])) {
                    throw new IOException("occupied or drifted receiver: " + RECEIVERS[i]);
                }
            } else if (checkOnly) {
                throw new IOException("missing receiver: " + RECEIVERS[i]);
            }
        }
        if (!checkOnly) {
            for (int i = 0; i < targets.length; i++) {
                if (!Files.exists(targets[i], LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(targets[i].getParent());
                    // All admissions were preflighted; never replace existing files.
                    Files.write(targets[i], sealed[i], StandardOpenOption.CREATE_NEW);
                }
            }
        }
        System.out.println("M3_LONG_PATH_INPUT_BUDGET_RECIPE_PASS|" + arguments[0]
                + "|files=" + targets.length);
    }

    private static void refuseLinkedSegments(Path root, Path path) throws IOException {
        if (Files.isSymbolicLink(root) || !path.normalize().startsWith(root)) {
            throw new IOException("untrusted source root or path");
        }
        Path current = root;
        for (Path segment : root.relativize(path)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("symbolic path component: " + current);
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new InternalError(impossible);
        }
    }
}
