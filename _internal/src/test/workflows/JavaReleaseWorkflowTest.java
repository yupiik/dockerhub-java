/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0.
 */
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

// Run from the repository root: java _internal/src/test/workflows/JavaReleaseWorkflowTest.java
// Requires Java >= 21, bash and jq on PATH. No registry, Docker daemon, YAML library or network.
// Executes the actual workflow run blocks, relocating only their absolute /tmp/ paths.
public class JavaReleaseWorkflowTest {
    private static final String AMD = "sha256:" + "a".repeat(64);
    private static final String ARM = "sha256:" + "b".repeat(64);
    private static final String OTHER = "sha256:" + "c".repeat(64);
    private static final String REUSE = "Reuse a published architecture instead of overwriting it on retry";
    private static final String MERGE = "Merge immutable digests without floating aliases";
    private static final String SMOKE = "Smoke test on native architecture before any push";
    private static final String DOCKER = """
            #!/usr/bin/env bash
            set -euo pipefail
            printf '%s\n' "$*" >> "$CASE_DIR/docker.log"
            if [[ "$1 ${2:-} ${3:-}" == 'buildx imagetools inspect' ]]; then
              if [[ "${@: -1}" == *-arm64 ]]; then
                file="$CASE_DIR/alias.json"
              else
                file="$CASE_DIR/registry-index.json"
              fi
              if [[ -f "$CASE_DIR/auth" ]]; then
                echo unauthorized >&2
                exit 1
              fi
              if [[ ! -f "$file" ]]; then
                echo 'manifest unknown' >&2
                exit 1
              fi
              cat "$file"
            elif [[ "$1 ${2:-} ${3:-}" == 'buildx imagetools create' ]]; then
              cp "$CASE_DIR/created.json" "$CASE_DIR/registry-index.json"
            elif [[ "$1 $2" == 'image inspect' ]]; then
              cat "$CASE_DIR/labels.json"
            elif [[ "$1" == run ]]; then
              if [[ -f "$CASE_DIR/smoke-failure" ]]; then
                exit 1
              fi
              if [[ "$*" == *-XshowSettings:properties* ]]; then
                printf '%s\n' 'java.runtime.version = 25.0.2+10' 'java.version = 25.0.2' 'java.vendor = Azul Systems, Inc.' 'os.arch = aarch64'
              elif [[ "$*" == *alpine-release* ]]; then
                echo 3.23.0
              else
                echo 'openjdk version "25.0.2"'
              fi
            elif [[ "$1" != pull && "$1" != tag ]]; then
              echo "Unexpected Docker command: $*" >&2
              exit 1
            fi
            """;

    public static void main(final String[] args) throws Exception {
        final var root = Path.of("").toAbsolutePath();
        final var workflow = Files.readAllLines(root.resolve(".github/workflows/java-release.yml"));
        final var reuse = step(workflow, REUSE);
        final var merge = step(workflow, MERGE);
        final var smoke = step(workflow, SMOKE);
        require(!header(workflow, SMOKE).contains("if:"), "Smoke must also run for reused images");
        require(header(workflow, "Build native image locally with pinned manifest inputs")
                .contains("if: steps.existing.outputs.reuse != 'true'"), "Reuse must skip rebuilding");
        require(workflow.indexOf("      - name: " + SMOKE) <
                workflow.indexOf("      - name: Push smoke-tested architecture tag"), "Smoke must precede push");
        final var tmp = Files.createTempDirectory("java-release-workflow-");
        try {
            final var bin = Files.createDirectories(tmp.resolve("bin"));
            final var docker = bin.resolve("docker");
            Files.writeString(docker, DOCKER);
            require(docker.toFile().setExecutable(true), "Cannot make Docker stub executable");
            final var classes = Files.createDirectories(tmp.resolve("classes"));
            final var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            require(compiler != null, "A full JDK is required");
            require(compiler.run(null, null, null, "--release", "21", "-d", classes.toString(),
                    root.resolve("java/test/SmokeTest.java").toString(),
                    root.resolve("java/test/SmokeTestTest.java").toString()) == 0,
                    "Smoke sources must compile for the CI host JDK 21");
            final var smokeDir = setup(tmp, "smoke-regressions", null, "{}");
            final var smokeRegression = run("java -cp ../classes" +
                    " SmokeTestTest example/java:smoke 25.0.2+10 3.23.0 arm64", smokeDir, bin, root);
            require(smokeRegression.code() == 0, "Smoke runner regressions: " + smokeRegression);
            require(smokeRegression.output().contains("Smoke runner regression tests: PASS"),
                    "Smoke runner regression tests did not finish");
            System.out.println("PASS smoke runner regressions (compiled for Java 21, stubbed Docker)");
            final var full = index(descriptor("linux", "amd64", AMD), descriptor("linux", "arm64", ARM));
            final var partial = index(descriptor("linux", "amd64", AMD));
            final var swapped = index(descriptor("linux", "amd64", ARM), descriptor("linux", "arm64", AMD));
            final var duplicate = index(descriptor("linux", "amd64", AMD), descriptor("linux", "amd64", AMD));
            final var wrong = index(descriptor("linux", "amd64", OTHER), descriptor("linux", "arm64", ARM));
            final var wrongOs = index(descriptor("windows", "amd64", AMD), descriptor("linux", "arm64", ARM));
            final var extra = index(descriptor("linux", "amd64", AMD), descriptor("linux", "arm64", ARM),
                    descriptor("unknown", "unknown", OTHER));
            int count = 0;
            for (final var fixture : List.of(
                    new MergeCase("absent", null, full, true, true),
                    new MergeCase("empty", index(), full, true, true),
                    new MergeCase("partial", partial, full, true, true),
                    new MergeCase("full-no-overwrite", full, full, true, false),
                    new MergeCase("reordered-no-overwrite", index(descriptor("linux", "arm64", ARM),
                            descriptor("linux", "amd64", AMD)), full, true, false),
                    new MergeCase("swapped-mapping", swapped, full, false, false),
                    new MergeCase("wrong-digest", wrong, full, false, false),
                    new MergeCase("wrong-os", wrongOs, full, false, false),
                    new MergeCase("duplicate-count", duplicate, full, false, false),
                    new MergeCase("duplicate-extra", index(descriptor("linux", "amd64", AMD),
                            descriptor("linux", "arm64", ARM), descriptor("linux", "arm64", ARM)), full, false, false),
                    new MergeCase("unexpected-descriptor", extra, full, false, false),
                    new MergeCase("malformed-index", "{}", full, false, false),
                    new MergeCase("final-swapped", null, swapped, false, true),
                    new MergeCase("final-wrong-digest", partial, wrong, false, true),
                    new MergeCase("final-duplicate", null, duplicate, false, true),
                    new MergeCase("final-extra", null, extra, false, true),
                    new MergeCase("auth", full, full, false, false))) {
                final var dir = setup(tmp, fixture.name(), fixture.existing(), fixture.created());
                final var result = run(merge, dir, bin, root);
                require((result.code() == 0) == fixture.success(), fixture.name() + ": " + result);
                final var log = Files.readString(dir.resolve("docker.log"));
                require(log.contains("buildx imagetools create") == fixture.create(), fixture.name() + ": " + log);
                if (fixture.create()) {
                    require(log.contains("example/java@" + AMD + " example/java@" + ARM), "Use artifact digests: " + log);
                } else if (fixture.existing() != null) {
                    require(Files.readString(dir.resolve("registry-index.json")).equals(fixture.existing()), "Index overwritten");
                }
                require(!Files.readString(dir.resolve("summary")).isEmpty() == fixture.success(), "False publication summary");
                System.out.println("PASS merge/" + fixture.name());
                count++;
            }
            for (final var name : List.of("alias", "fallback", "partial", "absent", "auth", "malformed",
                    "duplicate", "revision", "version", "alpine", "archive-sha256", "missing-labels", "smoke-failure")) {
                final var dir = setup(tmp, "native-" + name,
                        name.equals("absent") ? null : name.equals("partial") ? partial :
                                name.equals("malformed") ? "{}" : name.equals("duplicate") ?
                                        index(descriptor("linux", "arm64", ARM), descriptor("linux", "arm64", ARM)) : full, full);
                if (List.of("alias", "revision", "version", "alpine", "archive-sha256", "missing-labels", "smoke-failure").contains(name)) {
                    Files.writeString(dir.resolve("alias.json"), "{}");
                }
                Files.writeString(dir.resolve("labels.json"), labels(name));
                if (name.equals("smoke-failure")) {
                    Files.createFile(dir.resolve("smoke-failure"));
                }
                final var success = List.of("alias", "fallback", "partial", "absent", "smoke-failure").contains(name);
                final var result = run(reuse, dir, bin, root);
                require((result.code() == 0) == success, name + ": " + result);
                if (success) {
                    final var reused = !List.of("partial", "absent").contains(name);
                    require(Files.readString(dir.resolve("output")).equals("reuse=" + reused + "\n"), name + ": reuse output");
                    if (reused) {
                        final var log = Files.readString(dir.resolve("docker.log"));
                        require(log.contains("image inspect java-release:smoke"), "Labels not inspected");
                        require(log.contains(name.equals("fallback") ? "pull example/java@" + ARM :
                                "pull example/java:25.0.2-alpine3.23.0-arm64"), "Wrong image pulled: " + log);
                        final var smokeResult = run(smoke, dir, bin, root);
                        require((smokeResult.code() == 0) != name.equals("smoke-failure"), name + ": " + smokeResult);
                        require(Files.readString(dir.resolve("docker.log")).contains("run --rm java-release:smoke"), "No native smoke run");
                    }
                }
                System.out.println("PASS native/" + name);
                count++;
            }
            System.out.println("PASS: " + count + " offline workflow regression cases (real bash/jq and native smoke script)");
        } finally {
            try (final var paths = Files.walk(tmp)) {
                for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static Path setup(final Path tmp, final String name, final String existing, final String created) throws Exception {
        final var dir = Files.createDirectories(tmp.resolve(name));
        final var artifacts = Files.createDirectories(dir.resolve("_internal/target/digests"));
        for (final var arch : List.of("amd64", "arm64")) {
            Files.writeString(artifacts.resolve(arch + ".json"), "{\"repository\":\"example/java\",\"architecture\":\"" +
                    arch + "\",\"digest\":\"" + (arch.equals("amd64") ? AMD : ARM) + "\"}");
        }
        if (existing != null) {
            Files.writeString(dir.resolve("registry-index.json"), existing);
        }
        Files.writeString(dir.resolve("created.json"), created);
        Files.writeString(dir.resolve("output"), "");
        Files.writeString(dir.resolve("summary"), "");
        Files.writeString(dir.resolve("docker.log"), "");
        if (name.endsWith("auth")) {
            Files.createFile(dir.resolve("auth"));
        }
        return dir;
    }

    private static Result run(final String script, final Path dir, final Path bin, final Path root) throws Exception {
        final var builder = new ProcessBuilder("bash", "-c", script.replace("/tmp/", dir + "/")
                .replace("java java/test/SmokeTest.java", "java '" + root.resolve("java/test/SmokeTest.java") + "'"))
                .directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().putAll(Map.ofEntries(
                Map.entry("PATH", bin + ":" + System.getenv("PATH")), Map.entry("CASE_DIR", dir.toString()),
                Map.entry("GITHUB_OUTPUT", dir.resolve("output").toString()),
                Map.entry("GITHUB_STEP_SUMMARY", dir.resolve("summary").toString()),
                Map.entry("REPOSITORY", "example/java"), Map.entry("IMAGE_TAG", "25.0.2-alpine3.23.0"),
                Map.entry("SOURCE_REVISION", "0".repeat(40)), Map.entry("ARCHITECTURE", "arm64"),
                Map.entry("ALPINE_IMAGE", "alpine:3.23.0@" + AMD), Map.entry("ARCHIVE_SHA256", "a".repeat(64)),
                Map.entry("JAVA_VERSION", "25.0.2"), Map.entry("ALPINE_VERSION", "3.23.0")));
        final var process = builder.start();
        final String output;
        try (final var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return new Result(process.waitFor(), output);
    }

    private static String header(final List<String> lines, final String name) {
        final var start = lines.indexOf("      - name: " + name);
        require(start >= 0, "Missing workflow step: " + name);
        final var result = new StringBuilder();
        for (int i = start + 1; i < lines.size(); i++) {
            final var line = lines.get(i);
            if (line.equals("        run: |")) {
                return result.toString();
            }
            if (line.startsWith("      - ")) {
                return result.toString();
            }
            result.append(line).append('\n');
        }
        throw new IllegalStateException("Missing run block: " + name);
    }

    private static String step(final List<String> lines, final String name) {
        header(lines, name); // Fail clearly if the expected literal-block structure changes.
        final var start = lines.indexOf("      - name: " + name);
        final var result = new StringBuilder();
        boolean body = false;
        for (int i = start + 1; i < lines.size(); i++) {
            final var line = lines.get(i);
            if (!body) {
                body = line.equals("        run: |");
            } else if (line.startsWith("          ")) {
                result.append(line.substring(10)).append('\n');
            } else if (line.isBlank()) {
                result.append('\n');
            } else {
                break;
            }
        }
        require(!result.isEmpty(), "Empty run block: " + name);
        return result.toString();
    }

    private static String labels(final String bad) {
        if (bad.equals("missing-labels")) {
            return "[{\"Config\":{\"Labels\":null}}]";
        }
        return "[{\"Config\":{\"Labels\":{" +
                "\"org.opencontainers.image.revision\":\"" + (bad.equals("revision") ? "wrong" : "0".repeat(40)) + "\"," +
                "\"org.opencontainers.image.version\":\"" + (bad.equals("version") ? "wrong" : "25.0.2-alpine3.23.0") + "\"," +
                "\"io.yupiik.java.alpine\":\"" + (bad.equals("alpine") ? "wrong" : "alpine:3.23.0@" + AMD) + "\"," +
                "\"io.yupiik.java.archive-sha256\":\"" + (bad.equals("archive-sha256") ? "wrong" : "a".repeat(64)) + "\"}}}]";
    }

    private static String descriptor(final String os, final String architecture, final String digest) {
        return "{\"platform\":{\"os\":\"" + os + "\",\"architecture\":\"" + architecture + "\"},\"digest\":\"" + digest + "\"}";
    }

    private static String index(final String... descriptors) {
        return "{\"manifests\":[" + String.join(",", descriptors) + "]}";
    }

    private static void require(final boolean valid, final String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }

    private record MergeCase(String name, String existing, String created, boolean success, boolean create) {
    }

    private record Result(int code, String output) {
    }
}
