// SPDX-License-Identifier: Apache-2.0
// Source-time recipe tool. Deliberately outside src/ so Java 8 production builds are unchanged.
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Exact-source, idempotent Java 8 switch-statement correction for the relocated queue proof.
 * Refuses drift instead of rewriting a different semantic structure.
 */
public final class Java8CircularQueueSwitchRecipe {
    private static final String BEFORE = """
                switch (choice) {
                    case 0, 1, 2 -> {
                        int value = random.nextInt(21) - 10;
                        same(donor.add(value), relocated.add(value), "add");
                    }
                    case 3 -> same(donor.poll(), relocated.poll(), "poll");
                    case 4 -> {
                        if (!donor.isEmpty()) {
                            same(donor.remove(), relocated.remove(), "remove");
                        }
                    }
                    case 5 -> {
                        if (random.nextInt(11) == 0) {donor.clear();relocated.clear();}
                    }
                    case 6 -> {
                        if (!donor.isEmpty()) {
                            int which = random.nextInt(donor.size());
                            removeOccurrence(donor, relocated, which);
                        }
                    }
                    default -> { if (!donor.isEmpty()) same(donor.element(), relocated.element(), "element"); }
                }
                """;
    private static final String AFTER = """
                switch (choice) {
                    case 0:
                    case 1:
                    case 2: {
                        int value = random.nextInt(21) - 10;
                        same(donor.add(value), relocated.add(value), "add");
                        break;
                    }
                    case 3:
                        same(donor.poll(), relocated.poll(), "poll");
                        break;
                    case 4:
                        if (!donor.isEmpty()) {
                            same(donor.remove(), relocated.remove(), "remove");
                        }
                        break;
                    case 5:
                        if (random.nextInt(11) == 0) {donor.clear();relocated.clear();}
                        break;
                    case 6:
                        if (!donor.isEmpty()) {
                            int which = random.nextInt(donor.size());
                            removeOccurrence(donor, relocated, which);
                        }
                        break;
                    default:
                        if (!donor.isEmpty()) same(donor.element(), relocated.element(), "element");
                        break;
                }
                """;

    private Java8CircularQueueSwitchRecipe() {}

    static String rewrite(String source) {
        // Java text blocks remove common indentation; restore the 16-column source anchor.
        String before = BEFORE.indent(16);
        String after = AFTER.indent(16);
        int oldCount = count(source, before);
        int newCount = count(source, after);
        if (oldCount == 1 && newCount == 0) {
            return source.replace(before, after);
        }
        if (oldCount == 0 && newCount == 1) {
            return source; // mechanical fixed point
        }
        throw new IllegalArgumentException("SOURCE_SEAL_MISMATCH:before=" + oldCount
                + ":after=" + newCount);
    }

    private static int count(String value, String fragment) {
        int result = 0;
        int from = 0;
        while ((from = value.indexOf(fragment, from)) >= 0) {
            result++;
            from += fragment.length();
        }
        return result;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || (!"--check".equals(args[0]) && !"--apply".equals(args[0]))) {
            throw new IllegalArgumentException("usage: Java8CircularQueueSwitchRecipe --check|--apply <proof.java>");
        }
        Path path = Path.of(args[1]).toAbsolutePath().normalize();
        byte[] inputBytes = Files.readAllBytes(path);
        String input = new String(inputBytes, StandardCharsets.UTF_8);
        String output = rewrite(input);
        byte[] outputBytes = output.getBytes(StandardCharsets.UTF_8);
        boolean changed = !input.equals(output);
        if ("--check".equals(args[0]) && changed) {
            throw new IllegalStateException("RECIPE_NOT_APPLIED");
        }
        if ("--apply".equals(args[0]) && changed) {
            Path temporary = Files.createTempFile(path.getParent(), ".java8-switch-", ".tmp");
            try {
                Files.write(temporary, outputBytes);
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        System.out.println("recipe=java8-circular-queue-switch/1"
                + " changed=" + changed
                + " preimage=" + sha256(inputBytes)
                + " postimage=" + sha256(outputBytes));
    }
}
