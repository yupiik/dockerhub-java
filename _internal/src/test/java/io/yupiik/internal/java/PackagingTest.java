package io.yupiik.internal.java;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run explicitly with the packaging-tests profile; child builds skip tests and never clean. */
public class PackagingTest {
    private static final String SCHEMAS = "META-INF/fusion/json/schemas.json";
    private static final String DOCUMENTATION = "META-INF/fusion/configuration/documentation.json";

    @Test
    public void repeatedPackagingPreservesThinInputAndMergedMetadata() throws Exception {
        final var module = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        final var target = module.resolve("target");
        try (final var container = ConfiguringContainer.of().start();
             final var lookup = container.lookup(JsonMapper.class)) {
            Snapshot previous = null;
            for (int run = 1; run <= 2; run++) {
                final var log = run(module, target.resolve("packaging-" + run + ".log"), Duration.ofMinutes(5),
                        List.of("mvn", "-B", "-Dmaven.logger.defaultLogLevel=INFO", "-f",
                                module.resolve("pom.xml").toString(), "-DskipTests", "package"));
                assertFalse(log.contains("overlapping classes"), log);
                assertFalse(log.contains("overlapping resource"), log);
                final var current = checkPackage(module, target, lookup.instance(), run);
                if (previous != null) {
                    assertEquals(previous, current, "Repeated packaging changed metadata or JAR entries");
                }
                previous = current;
            }
        }
    }

    private Snapshot checkPackage(final Path module, final Path target, final JsonMapper json, final int run)
            throws IOException, InterruptedException {
        final Map<?, ?> applicationSchemas;
        final Map<?, ?> applicationDocs;
        try (final var thin = new ZipFile(target.resolve("original-java-updater.jar").toFile())) {
            assertFalse(thin.stream().anyMatch(entry -> entry.getName().startsWith("io/yupiik/fusion/")),
                    "Shade consumed its previous output instead of a fresh thin JAR");
            applicationSchemas = metadata(thin, SCHEMAS, "schemas", json);
            applicationDocs = metadata(thin, DOCUMENTATION, "classes", json);
        }
        final Snapshot snapshot;
        try (final var shaded = new ZipFile(target.resolve("java-updater.jar").toFile())) {
            final var names = shaded.stream().map(entry -> entry.getName()).sorted().toList();
            assertEquals(names.size(), new HashSet<>(names).size(), "Duplicate JAR entries");
            final var schemas = metadata(shaded, SCHEMAS, "schemas", json);
            assertFalse(applicationSchemas.isEmpty(), "Missing application schema fixture");
            applicationSchemas.forEach((name, schema) ->
                    assertEquals(schema, schemas.get(name), "Lost application schema: " + name));
            assertTrue(schemas.keySet().stream().anyMatch(name -> name.toString().startsWith("io.yupiik.fusion.httpclient.")),
                    "Dependency JSON schemas were not merged with application schemas");
            final var docs = metadata(shaded, DOCUMENTATION, "classes", json);
            assertFalse(applicationDocs.isEmpty(), "Missing application documentation fixture");
            applicationDocs.forEach((name, doc) ->
                    assertEquals(doc, docs.get(name), "Lost configuration documentation: " + name));
            snapshot = new Snapshot(schemas, docs, names);
        }
        final var completion = run(module, target.resolve("packaging-" + run + "-completion.log"), Duration.ofSeconds(30),
                List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-jar", target.resolve("java-updater.jar").toString(), "completion", "bash"));
        assertTrue(completion.contains("release") && completion.contains("update"), completion);
        return snapshot;
    }

    private Map<?, ?> metadata(final ZipFile jar, final String resource, final String key, final JsonMapper json)
            throws IOException {
        final var entry = jar.getEntry(resource);
        assertNotNull(entry, "Missing metadata: " + resource);
        try (final var input = jar.getInputStream(entry)) {
            final var document = json.fromString(Object.class, new String(input.readAllBytes(), UTF_8));
            assertTrue(document instanceof Map<?, ?>, "Invalid metadata: " + resource);
            final var content = ((Map<?, ?>) document).get(key);
            assertTrue(content instanceof Map<?, ?>, "Missing metadata object: " + key);
            return (Map<?, ?>) content;
        }
    }

    private String run(final Path directory, final Path output, final Duration timeout, final List<String> command)
            throws IOException, InterruptedException {
        final var process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertTrue(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS),
                    () -> "Timed out running " + command + "; see " + output);
            final var log = Files.readString(output, UTF_8);
            assertEquals(0, process.exitValue(), () -> "Command failed: " + command + "\n" + log);
            return log;
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(child -> child.destroyForcibly());
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private record Snapshot(Map<?, ?> schemas, Map<?, ?> documentation, List<String> entries) {
    }
}
