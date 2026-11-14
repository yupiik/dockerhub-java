/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0.
 */
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Run with a host JDK >= 25: java java-docker-zulu/test/SmokeTest.java IMAGE [amd64|arm64]
public class SmokeTest {
    public static void main(final String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: java java-docker-zulu/test/SmokeTest.java IMAGE [amd64|arm64]");
        }
        final var image = args[0];
        final var architecture = args.length == 2 ? args[1] : "amd64";
        if (!List.of("amd64", "arm64").contains(architecture)) {
            throw new IllegalArgumentException("Unsupported architecture: " + architecture);
        }
        final var format = "{{.Os}}|{{.Architecture}}|{{.Config.User}}|{{json .Config.Entrypoint}}|" +
                "{{json .Config.Cmd}}|{{json .Config.Volumes}}|{{json .Config.ExposedPorts}}|{{.Config.WorkingDir}}";
        final var config = capture(List.of("docker", "image", "inspect", "--format", format, image)).strip();
        if (!config.matches("linux\\|" + architecture + "\\|10001:10001\\|(?:null|\\[\\])\\|" +
                "\\[\"java\",\"--version\"\\]\\|(?:null|\\{\\})\\|(?:null|\\{\\})\\|/tmp")) {
            throw new IllegalStateException("Unexpected image config: " + config);
        }
        final var environment = capture(List.of("docker", "image", "inspect", "--format",
                "{{range .Config.Env}}{{println .}}{{end}}", image));
        if (!environment.lines().anyMatch("HOME=/tmp"::equals) ||
                environment.lines().anyMatch(value -> value.startsWith("DOCKER_"))) {
            throw new IllegalStateException("Unexpected environment: " + environment);
        }
        System.out.println("Image config: PASS (" + architecture + ")");
        final var temporary = Files.createTempDirectory("java-docker-zulu-smoke-");
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwxr-xr-x"));
            final var classes = Files.createDirectory(temporary.resolve("classes"));
            final var writable = Files.createDirectory(temporary.resolve("writable"));
            // Only disposable test data is world-writable; no root initialization is required.
            Files.setPosixFilePermissions(writable, PosixFilePermissions.fromString("rwxrwxrwx"));
            final var source = Path.of(System.getProperty("jdk.launcher.sourcefile")).toAbsolutePath()
                    .getParent().resolve("SmokeProbe.java");
            run(List.of(Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
                    "--release", "25", "-d", classes.toString(), source.toString()));
            final var checks = container(image, architecture, classes, writable, false);
            checks.addAll(List.of("sh", "-ec", """
                    test "$(id -u)" = 10001
                    test "$(id -g)" = 10001
                    test "$(command -v docker)" = /usr/bin/docker
                    test -s /etc/ssl/certs/ca-certificates.crt
                    test -s "$JAVA_HOME/lib/security/cacerts"
                    test ! -e /var/run/docker.sock
                    test ! -e /var/lib/docker
                    for unwanted in dockerd containerd containerd-shim runc docker-compose docker-buildx javac gcc g++ cc; do
                        if command -v "$unwanted"; then
                            echo "Unexpected compiler, daemon or plugin: $unwanted" >&2
                            exit 1
                        fi
                    done
                    for plugins in /usr/local/lib/docker/cli-plugins /usr/local/libexec/docker/cli-plugins /usr/lib/docker/cli-plugins /usr/libexec/docker/cli-plugins; do
                        if [ -d "$plugins" ] && [ -n "$(ls -A "$plugins")" ]; then
                            echo "Unexpected CLI plugins: $plugins" >&2
                            exit 1
                        fi
                    done
                    test ! -d "$HOME/.docker/cli-plugins"
                    test ! -e "$JAVA_HOME/jmods"
                    if touch /usr/bin/runtime-smoke 2>/dev/null; then
                        echo 'Root filesystem is writable' >&2
                        exit 1
                    fi
                    ldd --version | head -1 | grep 'GLIBC'
                    ldd /usr/bin/docker 2>&1 | grep -E 'not a dynamic executable|statically linked'
                    docker --version | grep '^Docker version 29.9.2,'
                    java --version
                    echo 'Runtime-only, static Docker client, system/Java CA stores, no daemon/plugins: PASS'
                    """));
            run(checks);
            final var probe = container(image, architecture, classes, writable, false);
            probe.addAll(List.of("java", "-cp", "/probe", "SmokeProbe"));
            run(probe);
            final var https = System.getenv("SMOKE_HTTPS_URL");
            if (https != null && !https.isBlank()) {
                final var networkProbe = container(image, architecture, classes, writable, true);
                networkProbe.addAll(List.of("java", "-cp", "/probe", "SmokeProbe", https));
                run(networkProbe);
            }
            System.out.println("Smoke tests: PASS");
        } finally {
            try (final var paths = Files.walk(temporary)) {
                for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static List<String> container(final String image, final String architecture,
                                          final Path classes, final Path writable, final boolean network) {
        return new ArrayList<>(List.of("docker", "run", "--rm", "--platform", "linux/" + architecture,
                "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--user", "10001:10001", "--network", network ? "bridge" : "none",
                "--pids-limit", "128", "--memory", "256m", "--cpus", "1",
                "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m,mode=1777",
                "--mount", "type=bind,source=" + classes + ",target=/probe,readonly",
                "--mount", "type=bind,source=" + writable + ",target=/work", image));
    }

    private static void run(final List<String> command) throws Exception {
        final var process = new ProcessBuilder(command).inheritIO().start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("Command failed: " + command);
        }
    }

    private static String capture(final List<String> command) throws Exception {
        final var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        final String output;
        try (final var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (process.waitFor() != 0) {
            throw new IllegalStateException("Command failed: " + command);
        }
        return output;
    }
}
