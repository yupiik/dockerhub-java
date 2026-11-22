/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0.
 */

// Observable integration tests, using the real Docker output rather than private parser access.
// Host JDK >= 21: compile both java/test/*.java into a temporary classes directory, then run
// java -cp CLASSES SmokeTestTest IMAGE JAVA_VERSION+BUILD ALPINE_VERSION ARCH
public class SmokeTestTest {
    public static void main(final String[] args) throws Exception {
        if (args.length != 4 || !args[1].contains("+")) {
            throw new IllegalArgumentException("Expected IMAGE JAVA_VERSION+BUILD ALPINE_VERSION ARCH");
        }
        final var version = args[1];
        final var release = version.substring(0, version.indexOf('+'));
        final var build = version.substring(version.indexOf('+') + 1);
        SmokeTest.main(args);
        SmokeTest.main(new String[] {args[0], release, args[2], args[3]});

        // A partial release must not match a longer dotted release.
        if (release.contains(".")) {
            reject(args, 1, release.substring(0, release.lastIndexOf('.')), "java.runtime.version");
        }
        // A partial numeric build must not match (e.g. +1 versus actual +10).
        if (build.length() > 1) {
            reject(args, 1, release + "+" + build.substring(0, build.length() - 1), "java.runtime.version");
        }
        reject(args, 1, version + "0", "java.runtime.version");
        reject(args, 1, release + "0", "java.runtime.version");
        reject(args, 2, args[2] + "0", "Expected Alpine");
        reject(args, 3, args[3].equals("amd64") ? "arm64" : "amd64", "os.arch");
        reject(args, 3, "riscv64", "Usage:");
        reject(args, 1, "", "Usage:");
        reject(args, 2, "3", "Usage:");
        System.out.println("Smoke runner regression tests: PASS");
    }

    private static void reject(final String[] args, final int index, final String value, final String diagnostic)
            throws Exception {
        final var invalid = args.clone();
        invalid[index] = value;
        try {
            SmokeTest.main(invalid);
        } catch (final IllegalArgumentException | IllegalStateException expected) {
            if (expected.getMessage() != null && expected.getMessage().contains(diagnostic)) {
                return;
            }
            throw new AssertionError("Expected diagnostic '" + diagnostic + "' for " + value, expected);
        }
        throw new AssertionError("Smoke runner accepted incorrect argument " + index + ": " + value);
    }
}
