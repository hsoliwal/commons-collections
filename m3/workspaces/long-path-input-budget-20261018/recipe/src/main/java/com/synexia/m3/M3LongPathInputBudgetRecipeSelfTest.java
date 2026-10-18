// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package com.synexia.m3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Self-contained source admission, idempotence, refusal and seal proof. */
public final class M3LongPathInputBudgetRecipeSelfTest {
    private static long assertions;

    private M3LongPathInputBudgetRecipeSelfTest() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("supply template directory");
        Path templates = Path.of(args[0]).toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path temp = Files.createTempDirectory("m3-budget-recipe-");
        try {
            Path target = temp.resolve("m3/collections/src/main/java/com/m3/collections/M3LongIndexPathBudget.java");
            Path test = temp.resolve("m3/collections/src/test/java/com/m3/collections/M3LongIndexPathBudgetSelfTest.java");
            fail(() -> run("--check", temp, templates));
            check(!Files.exists(target), "check mode does not write");
            run("--apply", temp, templates);
            check(Files.isRegularFile(target) && Files.isRegularFile(test), "both added");
            byte[] first = Files.readAllBytes(target);
            byte[] second = Files.readAllBytes(test);
            run("--check", temp, templates);
            run("--apply", temp, templates);
            check(Arrays.equals(first, Files.readAllBytes(target)), "fixed point main");
            check(Arrays.equals(second, Files.readAllBytes(test)), "fixed point test");
            Files.writeString(target, "tampered\n");
            fail(() -> run("--apply", temp, templates));
            check(Arrays.equals(second, Files.readAllBytes(test)), "refusal did not modify peer");
            Files.write(target, first);
            // A mutated template is not an admissible alternate source.
            Path bad = temp.resolve("changed-templates");
            Files.createDirectory(bad);
            Files.write(bad.resolve("M3LongIndexPathBudget.java"), first);
            Files.writeString(bad.resolve("M3LongIndexPathBudgetSelfTest.java"), "tampered template\n");
            fail(() -> run("--apply", temp, bad));
            check(Arrays.equals(first, Files.readAllBytes(target)), "template drift blocked");
            System.out.println("M3_LONG_PATH_BUDGET_RECIPE_SELFTEST_PASS|assertions=" + assertions);
        } finally {
            try (var all = Files.walk(temp)) {
                for (Path p : all.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    private static void run(String mode, Path root, Path templates) throws IOException {
        M3LongPathInputBudgetRecipe.main(new String[] {mode, root.toString(), templates.toString()});
    }
    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
    private static void fail(Checked operation) {
        assertions++;
        try { operation.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("source recipe should refuse invalid inputs");
    }
    @FunctionalInterface
    private interface Checked { void run() throws IOException; }
}
