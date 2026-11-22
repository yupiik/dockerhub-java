package io.yupiik.internal.java;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QuickStartTest {
    private static final Pattern BLOCK_DELIMITER = Pattern.compile("----");
    private static final Pattern LOG_DELIMITER = Pattern.compile("\\|");
    private static final String STUB = """
            #!/bin/sh
            case "${0##*/}:$*" in
              'gh:auth token --hostname github.com') kind=token ;;
              'gh:workflow run java-update.yml --repo yupiik/dockerhub-java --ref main -f dry-run=false') kind=dispatch ;;
              'git:fetch origin --tags') kind=fetch ;;
              'java:-jar _internal/target/java-updater.jar update --update-dry-run false --update-limit 100') kind=apply ;;
              *) printf 'Unexpected command: %s\\n' "$0 $*" >&2; exit 99 ;;
            esac
            printf '%s|%s|%s|%s|%s|%s|%s|%s|%s\\n' \
              "$kind" "${GH_TOKEN-}" "${GIT_CONFIG_VALUE_1-}" \
              "${GIT_TERMINAL_PROMPT-}" "${GIT_CONFIG_COUNT-}" \
              "${GIT_CONFIG_KEY_0-}" "${GIT_CONFIG_VALUE_0-}" "${GIT_CONFIG_KEY_1-}" \
              "${GH_TOKEN+x}" >> "$COMMAND_LOG"
            if [ "$FAIL_AT" = "$kind" ]; then
              exit 17
            fi
            if [ "$kind" = token ]; then
              if [ "$FAIL_AT" = empty-token ]; then
                printf '\\n'
              else
                printf 'offline-test-token\\n'
              fi
            fi
            """;

    public static Stream<Arguments> scenarios() {
        return Stream.of("README.adoc", "src/main/minisite/content/getting-started.adoc")
                .flatMap(document -> Stream.of("", "token", "empty-token", "fetch", "apply", "dispatch")
                        .map(failure -> Arguments.of(document, failure)));
    }

    @ParameterizedTest(name = "{0}: failure=[{1}]")
    @MethodSource("scenarios")
    public void documentedApplyStopsAtFailureAndInheritsCredentials(
            final String document, final String failure, @TempDir final Path directory) throws Exception {
        final var module = Files.isRegularFile(Path.of("src/main/minisite/content/getting-started.adoc")) ?
                Path.of("") : Path.of("_internal");
        final var snippet = BLOCK_DELIMITER.splitAsStream(Files.readString(module.resolve(document)))
                .filter(block -> block.contains("GIT_CONFIG_KEY_1"))
                .findFirst().orElseThrow(() -> new IllegalStateException("No apply snippet in " + document));
        for (final var command : List.of("gh", "git", "java")) {
            final var executable = Files.writeString(directory.resolve(command), STUB);
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        }

        final var log = directory.resolve("commands.log");
        final var output = directory.resolve("output.log");
        final var builder = new ProcessBuilder("/bin/sh", "-c", snippet)
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        final var environment = builder.environment();
        environment.keySet().removeIf(key -> key.equals("GH_TOKEN") || key.startsWith("GIT_CONFIG") ||
                key.equals("GIT_TERMINAL_PROMPT"));
        // Only stubs are reachable through PATH; none can contact GitHub or invoke the real Git/JVM.
        environment.put("PATH", directory.toString());
        environment.put("COMMAND_LOG", log.toString());
        environment.put("FAIL_AT", failure);
        final var process = builder.start();
        try {
            final var completed = process.waitFor(10, TimeUnit.SECONDS);
            final var diagnostic = Files.readString(output);
            assertTrue(completed, "Snippet timed out: " + document + "\n" + diagnostic);
            final var calls = Files.readAllLines(log).stream()
                    .map(line -> LOG_DELIMITER.split(line, -1)).toList();
            final var stages = List.of("token", "fetch", "apply", "dispatch");
            final var expected = failure.isEmpty() ? stages :
                    failure.equals("empty-token") ? List.of("token") :
                            stages.subList(0, stages.indexOf(failure) + 1);
            assertEquals(expected, calls.stream().map(call -> call[0]).toList(), diagnostic);
            assertEquals(failure.isEmpty(), process.exitValue() == 0, diagnostic);
            assertEquals(List.of("token", "", "", "", "", "", "", "", ""),
                    List.of(calls.getFirst()), diagnostic);
            for (final var call : calls.subList(1, calls.size())) {
                assertEquals(List.of(call[0], "offline-test-token", "!gh auth git-credential", "0", "2",
                                "credential.helper", "", "credential.https://" + "github.com" + ".helper", "x"),
                        List.of(call), diagnostic);
            }
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(child -> child.destroyForcibly());
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Timed-out shell did not terminate");
            }
        }
    }
}
