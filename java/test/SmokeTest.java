/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0.
 */
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

// Host JDK >= 21: java java/test/SmokeTest.java IMAGE JAVA_VERSION ALPINE_VERSION ARCH
// JAVA_VERSION is the full release (e.g. 25.0.2), optionally including +build (25.0.2+10).
// Run on the native Docker host; no --platform override or emulation is requested.
public class SmokeTest {
    private static final Pattern VERSION = Pattern.compile("[1-9][0-9]*(?:\\.[0-9]+)*(?:\\+[0-9]+(?:\\.[0-9]+)*)?");
    private static final Pattern ALPINE_VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Pattern PROPERTY = Pattern.compile(
            "^\\h*(java\\.runtime\\.version|java\\.version|java\\.vendor|os\\.arch)\\h*=\\h*(.*?)\\h*$",
            Pattern.MULTILINE);

    public static void main(final String[] args) throws Exception {
        if (args.length != 4 || args[0].isBlank() || args[0].startsWith("-") ||
                !VERSION.matcher(args[1]).matches() || !ALPINE_VERSION.matcher(args[2]).matches() ||
                !List.of("amd64", "arm64").contains(args[3])) {
            throw new IllegalArgumentException(
                    "Usage: java java/test/SmokeTest.java IMAGE JAVA_VERSION ALPINE_VERSION amd64|arm64");
        }
        final var image = args[0];
        final var version = args[1];
        final var architecture = args[3];

        // Exercise the image's default CMD as well as inspecting the JVM properties.
        System.out.print(capture(List.of("docker", "run", "--rm", image)));
        final var output = capture(List.of("docker", "run", "--rm", "--entrypoint", "java", image,
                "-XshowSettings:properties", "-version"));
        final var properties = new HashMap<String, String>();
        final var matcher = PROPERTY.matcher(output);
        while (matcher.find()) {
            if (properties.putIfAbsent(matcher.group(1), matcher.group(2)) != null) {
                throw new IllegalStateException("Duplicate runtime property: " + matcher.group(1));
            }
        }
        requireVersion(properties.get("java.runtime.version"), version, "java.runtime.version");
        // java.version normally omits the build number present in java.runtime.version.
        final var build = version.indexOf('+');
        requireVersion(properties.get("java.version"), build < 0 ? version : version.substring(0, build), "java.version");
        final var vendor = properties.get("java.vendor");
        if (vendor == null || !vendor.contains("Azul")) {
            throw new IllegalStateException("Expected Azul java.vendor, got: " + vendor);
        }
        final var actualArchitecture = properties.get("os.arch");
        final var allowedArchitectures = architecture.equals("amd64") ? List.of("amd64", "x86_64") : List.of("aarch64");
        if (actualArchitecture == null || !allowedArchitectures.contains(actualArchitecture)) {
            throw new IllegalStateException("Expected " + architecture + " os.arch, got: " + actualArchitecture);
        }
        final var release = capture(List.of("docker", "run", "--rm", "--entrypoint", "sh", image, "-ec", """
                if command -v javac || [ -e "$JAVA_HOME/bin/javac" ]; then
                    echo 'Unexpected javac in runtime image' >&2
                    exit 1
                fi
                cat /etc/alpine-release
                """)).strip();
        if (!args[2].equals(release)) {
            throw new IllegalStateException("Expected Alpine " + args[2] + ", got: " + release);
        }
        System.out.println("Smoke tests: PASS (Java " + version + ", Alpine " + release + ", " + architecture + ")");
    }

    private static void requireVersion(final String actual, final String expected, final String property) {
        // A release can be followed by +build or -metadata, never another release digit/component.
        // A requested +10 must not match +100 or +10.1.
        if (actual == null || !(actual.equals(expected) || actual.startsWith(expected + "-") ||
                (!expected.contains("+") && actual.startsWith(expected + "+")))) {
            throw new IllegalStateException("Expected " + property + " " + expected + ", got: " + actual);
        }
    }

    private static String capture(final List<String> command) throws Exception {
        final var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output;
        try (final var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        final var exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Command failed (" + exit + "): " + command + "\n" + output);
        }
        return output;
    }
}
