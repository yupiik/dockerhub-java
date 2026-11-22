package io.yupiik.internal.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LegacyPublishingTest {
    private static final Pattern PINNED_ACTION = Pattern.compile("^[\\w/-]+@[0-9a-f]{40}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern NUL = Pattern.compile("\\x00");
    private static final String GH_STUB = """
            #!/usr/bin/env bash
            set -euo pipefail
            # NUL-delimited arguments retain spaces and empty arguments; newline ends a call.
            printf '%s\\0' "$@" >> "$CALL_LOG"
            printf '\\n' >> "$CALL_LOG"
            if [[ "$1 ${2:-}" == 'release view' ]]; then
              if [[ -f "$RELEASE_STATE" ]]; then
                exit 0
              fi
              exit 1
            fi
            if [[ "$1 ${2:-}" == 'release create' ]]; then
              if [[ "${CREATE_MODE:-}" == failure ]]; then
                exit 17
              fi
              touch "$RELEASE_STATE"
              if [[ "${CREATE_MODE:-}" == lost-response ]]; then
                exit 17
              fi
              exit 0
            fi
            exit 99
            """;

    @TempDir
    private Path directory;

    @Test
    public void refOutputsForBranchesAndAllTagShapes() throws Exception {
        final var refs = new ArrayList<>(List.of(
                List.of("refs/heads/h2/2.3", "h2", "2.3"),
                List.of("refs/heads/java-jdk/25.0.1", "java-jdk", "25.0.1")));
        for (final var version : List.of("3.9", "3.9.12")) {
            for (final var alpine : List.of("3", "3.23", "3.23.1")) {
                refs.add(List.of("refs/tags/maven/" + version + "-alpine" + alpine,
                        "maven", version + "-alpine" + alpine));
            }
        }
        final var parser = parser();
        for (final var ref : refs) {
            final var work = Files.createTempDirectory(directory, "ref-");
            final var output = work.resolve("output");
            final var result = run(parser, Map.of("GITHUB_REF", ref.get(0), "GITHUB_OUTPUT", output.toString()), work);
            assertEquals(0, result.code(), ref + ": " + result.output());
            assertEquals("image=" + ref.get(1) + "\nversion=" + ref.get(2) + "\n", Files.readString(output), ref.toString());
        }
    }

    @Test
    public void invalidRefsCannotInjectShellCommandsPathsOrOutputs() throws Exception {
        final var parser = parser();
        for (final var name : List.of("h2/2.3/extra", "../2.3", "h2/2.3\nextra=value",
                "h2/$(touch injected)", "h2/2.3`;touch injected`", "h2")) {
            final var work = Files.createTempDirectory(directory, "invalid-");
            final var output = work.resolve("output");
            final var result = run(parser, Map.of("GITHUB_REF", "refs/tags/" + name,
                    "GITHUB_OUTPUT", output.toString()), work);
            assertNotEquals(0, result.code(), name);
            assertFalse(Files.exists(output), name);
            assertFalse(Files.exists(work.resolve("injected")), name);
        }
    }

    @Test
    public void releaseCreationAndRetries() throws Exception {
        final var script = steps(job(legacyWorkflow(), "release")).getFirst().get("run").toString();
        for (final var fixture : List.of(
                new ReleaseCase("", false, List.of("view", "create")),
                new ReleaseCase("", true, List.of("view")),
                new ReleaseCase("lost-response", false, List.of("view", "create", "view")),
                new ReleaseCase("failure", false, List.of("view", "create", "view")))) {
            final var work = Files.createTempDirectory(directory, "release-");
            final var executable = Files.writeString(work.resolve("gh"), GH_STUB);
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
            final var state = work.resolve("release");
            if (fixture.existing()) {
                Files.createFile(state);
            }
            final var log = work.resolve("calls");
            final var environment = Map.of("PATH", work + File.pathSeparator + System.getenv("PATH"),
                    "GITHUB_REF", "refs/tags/h2/2.3.1-alpine3.23", "GH_REPO", "owner/repository",
                    "GH_TOKEN", "offline-token", "CALL_LOG", log.toString(), "RELEASE_STATE", state.toString(),
                    "CREATE_MODE", fixture.mode());
            final var result = run(script, environment, work);
            final var calls = calls(log);
            assertEquals(fixture.expected(), calls.stream().map(call -> call.get(1)).toList(), fixture.toString());
            assertEquals(!fixture.mode().equals("failure"), result.code() == 0, fixture + ": " + result.output());
            for (final var call : calls) {
                assertEquals("h2/2.3.1-alpine3.23", call.get(2));
                if (call.get(1).equals("create")) {
                    assertTrue(call.contains("--verify-tag"), call.toString());
                }
            }
            if (!fixture.mode().equals("failure")) {
                Files.delete(log);
                final var retry = run(script, environment, work);
                assertEquals(0, retry.code(), retry.output());
                assertEquals(List.of("view"), calls(log).stream().map(call -> call.get(1)).toList());
            }
        }
    }

    @Test
    public void releaseDependsOnSuccessfulTagPublication() throws Exception {
        final var workflow = legacyWorkflow();
        final var docker = job(workflow, "docker");
        final var release = job(workflow, "release");
        // No always() override: GitHub applies the implicit success() dependency gate.
        assertEquals("docker", release.get("needs"));
        assertEquals("startsWith(github.ref, 'refs/tags/')", release.get("if"));
        assertEquals(Boolean.TRUE, map(step(docker, "docker_build").get("with")).get("push"));
        assertFalse(steps(docker).stream().anyMatch(step -> text(step, "run").contains("release")));
        assertEquals("${{ !startsWith(github.ref, 'refs/tags/java/') && !startsWith(github.ref, 'refs/heads/java/') }}", docker.get("if"));
    }

    @Test
    public void leastPrivilegeAndImmutableActions() throws Exception {
        final var workflow = legacyWorkflow();
        final var docker = job(workflow, "docker");
        final var release = job(workflow, "release");
        assertEquals(Map.of("contents", "read"), workflow.get("permissions"));
        assertFalse(docker.containsKey("permissions"));
        assertEquals(Map.of("contents", "write"), release.get("permissions"));
        assertEquals(Boolean.FALSE, map(steps(docker).getFirst().get("with")).get("persist-credentials"));
        for (final var step : steps(docker)) {
            if (step.containsKey("uses")) {
                assertPinned(step.get("uses").toString());
            }
        }
        assertFalse(steps(release).stream().anyMatch(step -> step.containsKey("uses")));
        assertEquals("${{ github.repository }}", map(steps(release).getFirst().get("env")).get("GH_REPO"));
        assertFalse(Files.readString(workflows().resolve("docker-image.yml")).contains("::set-output"));
        for (final var job : map(workflow.get("jobs")).values()) {
            for (final var step : steps(map(job))) {
                assertFalse(text(step, "run").contains("${{"), step.toString());
            }
        }
    }

    @Test
    public void allExternalActionsAreCommitPinned() throws Exception {
        try (final var paths = Files.list(workflows())) {
            for (final var path : paths.filter(file -> file.toString().endsWith(".yml")).toList()) {
                final var workflow = load(path);
                for (final var value : map(workflow.get("jobs")).values()) {
                    final var job = map(value);
                    final var actions = new ArrayList<String>();
                    if (job.containsKey("uses")) {
                        actions.add(job.get("uses").toString());
                    }
                    for (final var step : steps(job)) {
                        if (step.containsKey("uses")) {
                            actions.add(step.get("uses").toString());
                        }
                    }
                    for (final var action : actions) {
                        if (!action.startsWith("./")) {
                            assertTrue(PINNED_ACTION.matcher(action).matches(), path + ": " + action);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void nativePrChecksAreUnprivilegedAndUsePinnedInputs() throws Exception {
        final var path = workflows().resolve("java-verify.yml");
        final var workflow = load(path);
        // Engine uses YAML 1.2, so the "on" key is not interpreted as a boolean.
        final var triggers = map(workflow.get("on"));
        assertTrue(triggers.containsKey("pull_request"));
        assertFalse(triggers.containsKey("pull_request_target"));
        assertEquals(Map.of("contents", "read"), workflow.get("permissions"));
        assertFalse(Files.readString(path).contains("secrets."));
        final var job = job(workflow, "smoke");
        assertFalse(job.containsKey("permissions"));
        final var entries = maps(map(map(job.get("strategy")).get("matrix")).get("include"));
        assertEquals(2, entries.size());
        final var runners = new HashMap<String, String>();
        for (final var entry : entries) {
            runners.put(entry.get("architecture").toString(), entry.get("runner").toString());
        }
        assertEquals(Map.of("amd64", "ubuntu-26.04", "arm64", "ubuntu-26.04-arm"), runners);
        final var env = map(job.get("env"));
        final var imagePrefix = "alpine:" + env.get("ALPINE_VERSION") + "@sha256:";
        final var image = env.get("ALPINE_IMAGE").toString();
        assertTrue(image.startsWith(imagePrefix), image);
        assertTrue(SHA256.matcher(image.substring(imagePrefix.length())).matches(), image);
        for (final var entry : entries) {
            assertTrue(SHA256.matcher(entry.get("sha256").toString()).matches(), entry.toString());
            final var arch = entry.get("architecture").equals("amd64") ? "x64" : "aarch64";
            final var version = env.get("JAVA_VERSION").toString();
            final var javaVersion = version.contains("+") ? version.substring(0, version.indexOf('+')) : version;
            assertTrue(entry.get("archive").toString().endsWith("-ca-jre" + javaVersion + "-linux_musl_" + arch + ".tar.gz"), entry.toString());
        }
        for (final var step : steps(job)) {
            final var action = text(step, "uses");
            assertFalse(action.contains("login-action"), action);
            assertFalse(action.contains("setup-qemu-action"), action);
            if (action.startsWith("actions/checkout@")) {
                assertEquals("false", map(step.get("with")).get("persist-credentials").toString());
            }
            if (action.startsWith("docker/build-push-action@")) {
                assertEquals("false", map(step.get("with")).get("push").toString());
                assertEquals("true", map(step.get("with")).get("load").toString());
            }
        }
        assertTrue(steps(job).getLast().get("run").toString().contains("SmokeTestTest"));
    }

    @Test
    public void dependabotTracksActionsAndInternalMaven() throws Exception {
        final var config = load(workflows().getParent().resolve("dependabot.yml"));
        assertEquals(2, config.get("version"));
        final var updates = maps(config.get("updates")).stream()
                .map(update -> List.of(update.get("package-ecosystem"), update.get("directory"))).toList();
        assertTrue(updates.contains(List.of("github-actions", "/")));
        assertTrue(updates.contains(List.of("maven", "/_internal")));
    }

    private Path workflows() {
        for (var path = Path.of("").toAbsolutePath().normalize(); path != null; path = path.getParent()) {
            final var candidate = path.resolve(".github/workflows");
            if (Files.isRegularFile(candidate.resolve("docker-image.yml"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate repository workflows from " + Path.of("").toAbsolutePath());
    }

    private Map<?, ?> legacyWorkflow() throws Exception {
        return load(workflows().resolve("docker-image.yml"));
    }

    private Map<?, ?> load(final Path path) throws Exception {
        return map(new Load(LoadSettings.builder().build()).loadFromString(Files.readString(path)));
    }

    private Map<?, ?> map(final Object value) {
        return assertInstanceOf(Map.class, value);
    }

    private List<Map<?, ?>> maps(final Object value) {
        final List<?> values = assertInstanceOf(List.class, value);
        return values.stream().map(this::map).toList();
    }

    private String text(final Map<?, ?> map, final String key) {
        return map.containsKey(key) ? map.get(key).toString() : "";
    }

    private Map<?, ?> job(final Map<?, ?> workflow, final String name) {
        return map(map(workflow.get("jobs")).get(name));
    }

    private List<Map<?, ?>> steps(final Map<?, ?> job) {
        return job.containsKey("steps") ? maps(job.get("steps")) : List.of();
    }

    private Map<?, ?> step(final Map<?, ?> job, final String id) {
        return steps(job).stream().filter(step -> id.equals(step.get("id"))).findFirst().orElseThrow();
    }

    private String parser() throws Exception {
        return step(job(legacyWorkflow(), "docker"), "parsed_tag").get("run").toString();
    }

    private void assertPinned(final String action) {
        assertTrue(PINNED_ACTION.matcher(action).matches(), action);
    }

    private List<List<String>> calls(final Path log) throws Exception {
        // Drop only the final delimiter, not empty arguments such as --notes ''.
        return Files.readAllLines(log).stream().map(line -> {
            final var arguments = NUL.split(line, -1);
            return List.copyOf(Arrays.asList(arguments).subList(0, arguments.length - 1));
        }).toList();
    }

    private Result run(final String script, final Map<String, String> environment, final Path work) throws Exception {
        final var output = Files.createTempFile(work, "process-", ".log");
        final var builder = new ProcessBuilder("bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", script)
                .directory(work.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().putAll(environment);
        final var process = builder.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), () -> "Workflow shell timed out; output: " + output);
            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            assertTrue(process.waitFor(2, TimeUnit.SECONDS), "Workflow shell did not terminate");
        }
    }

    private record ReleaseCase(String mode, boolean existing, List<String> expected) {
    }

    private record Result(int code, String output) {
    }
}
