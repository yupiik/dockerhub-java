package io.yupiik.internal.java;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.internal.java.Models.Archive;
import io.yupiik.internal.java.Models.Package;
import io.yupiik.internal.java.Models.Published;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

public class RemoteCatalogTest {
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private static final String NAME = "zulu21.40.17-ca-jre21.0.10-linux_musl_x64.tar.gz";
    private static final String URL = "https://cdn.azul.com/zulu/bin/" + NAME;
    private static final String SHA = "a".repeat(64);

    @Test
    void readsBothAzulPagesForBothArchitecturesAndFiltersNonStandardJres() throws IOException {
        try (final var container = ConfiguringContainer.of().start();
             final var lookup = container.lookup(JsonMapper.class);
             final var server = new Fixture();
             final var catalog = catalog(lookup.instance(), server)) {
            final var json = lookup.instance();
            server.respond(request -> {
                final var query = request.getRequestURI().getRawQuery();
                final var arch = query.contains("arch=x64&") ? "x64" : "aarch64";
                final var entries = new ArrayList<Map<String, Object>>();
                if (query.endsWith("page=1")) {
                    entries.add(packageEntry("zulu21.40.17-ca-jre21.0.9-linux_musl_" + arch + ".tar.gz", List.of(21, 0, 9)));
                    entries.add(packageEntry("zulu21.40.17-ca-jdk21.0.10-linux_musl_" + arch + ".tar.gz", List.of(21, 0, 10)));
                    entries.add(packageEntry("zulu21.40.17-ca-crac-jre21.0.10-linux_musl_" + arch + ".tar.gz", List.of(21, 0, 10)));
                    entries.add(packageEntry("zulu21.40.17-ca-fx-jre21.0.10-linux_musl_" + arch + ".tar.gz", List.of(21, 0, 10)));
                    entries.add(packageEntry("zulu21.40.17-ca-jre21.0.10-linux_" + arch + ".tar.gz", List.of(21, 0, 10)));
                    entries.add(packageEntry("zulu11.40.17-ca-jre11.0.29-linux_musl_" + arch + ".tar.gz", List.of(11, 0, 29)));
                    entries.add(packageEntry("zulu21.40.17-ca-jre21.0.8-linux_musl_" + arch + ".tar.gz", List.of(21, 0, 7)));
                    final var other = arch.equals("x64") ? "aarch64" : "x64";
                    entries.add(packageEntry("zulu21.40.17-ca-jre21.0.10-linux_musl_" + other + ".tar.gz", List.of(21, 0, 10)));
                } else {
                    entries.add(packageEntry("zulu21.40.17-ca-jre21.0.10-linux_musl_" + arch + ".tar.gz", List.of(21, 0, 10)));
                }
                return new Reply(200, json.toString(entries), Map.of("x-pagination", "{\"last_page\":2}"));
            });
            final var packages = catalog.packages();
            assertEquals(4, packages.size());
            assertEquals(List.of("21.0.9", "21.0.10", "21.0.9", "21.0.10"), packages.stream().map(Package::javaVersion).toList());
            assertEquals(List.of("amd64", "amd64", "arm64", "arm64"), packages.stream().map(Package::architecture).toList());
            assertTrue(packages.stream().allMatch(it -> ID.equals(it.id()) && it.build() == 7 && it.distroVersion().equals("21.40.17")));
            assertEquals(4, server.requests.size());
            assertTrue(server.requests.stream().allMatch(it -> it.startsWith("/azul/packages/?os=linux-musl&arch=") &&
                    it.contains("java_package_type=jre") && it.contains("release_status=ga") && it.contains("availability_types=CA")));
        }
    }

    @Test
    void rejectsUnexpectedAzulDownloadHost() throws IOException {
        try (final var container = ConfiguringContainer.of().start();
             final var lookup = container.lookup(JsonMapper.class);
             final var server = new Fixture();
             final var catalog = catalog(lookup.instance(), server)) {
            final var entry = new HashMap<>(packageEntry(NAME, List.of(21, 0, 10)));
            entry.put("download_url", "https://example.invalid/" + NAME);
            server.respond(ignored -> Reply.ok(lookup.instance().toString(List.of(entry))));
            assertTrue(assertThrows(IllegalStateException.class, catalog::packages).getMessage().contains("Unexpected Azul download URL"));
        }
    }

    @Test
    void followsDockerHubPaginationAndIgnoresProvenanceAndNonLinuxImages() throws IOException {
        try (final var container = ConfiguringContainer.of().start();
             final var lookup = container.lookup(JsonMapper.class);
             final var server = new Fixture();
             final var catalog = catalog(lookup.instance(), server)) {
            server.respond(request -> request.getRequestURI().getRawQuery().contains("page=2") ?
                    Reply.ok("{\"next\":null,\"results\":[" + lookup.instance().toString(tag("21.0.10", true)) + "]}") :
                    Reply.ok(lookup.instance().toString(Map.of("results", List.of(tag("21.0.9", true)),
                            "next", server.uri("hub/repositories/example/java/tags?page=2").toString()))));
            assertEquals(List.of(new Published("21.0.9", Set.of("amd64", "arm64")),
                    new Published("21.0.10", Set.of("amd64", "arm64"))), catalog.published("example/java"));
            assertEquals(List.of("/hub/repositories/example/java/tags?page_size=100", "/hub/repositories/example/java/tags?page=2"), server.requests);
        }
    }

    @Test
    void choosesExactNumericStableAlpineMaximumSupportingBothArchitectures() throws IOException {
        try (final var container = ConfiguringContainer.of().start();
             final var lookup = container.lookup(JsonMapper.class);
             final var server = new Fixture();
             final var catalog = catalog(lookup.instance(), server)) {
            server.respond(request -> request.getRequestURI().getRawQuery().contains("page=2") ?
                    Reply.ok("{\"next\":null,\"results\":" + lookup.instance().toString(List.of(tag("3.10.10", true),
                            tag("3.11.0", false), tag("3.12", true), tag("3.13.0-rc1", true), tag("latest", true), tag("edge", true))) + "}") :
                    Reply.ok(lookup.instance().toString(Map.of("results", List.of(tag("3.9.99", true), tag("3.10.9", true)), "next", "tags?page=2"))));
            final var alpine = catalog.alpine();
            assertEquals("3.10.10", alpine.version());
            assertEquals("sha256:" + SHA, alpine.digest());
            assertEquals(2, server.requests.size());
        }
    }

    @Test
    void failsWhenNoStableAlpineSupportsBothArchitectures() throws IOException {
        try (final var container = ConfiguringContainer.of().start();
             final var lookup = container.lookup(JsonMapper.class);
             final var server = new Fixture();
             final var catalog = catalog(lookup.instance(), server)) {
            server.respond(ignored -> Reply.ok("{\"next\":null,\"results\":" + lookup.instance().toString(List.of(tag("3.23.2", false), tag("edge", true))) + "}"));
            assertTrue(assertThrows(IllegalStateException.class, catalog::alpine).getMessage().contains("No stable Alpine"));
        }
    }

    @Test
    void rejectsCyclicExternalAndWrongRepositoryPaginationBeforeFollowingIt() throws IOException {
        try (final var container = ConfiguringContainer.of().start();
             final var lookup = container.lookup(JsonMapper.class);
             final var server = new Fixture();
             final var catalog = catalog(lookup.instance(), server)) {
            for (final var next : List.of(server.uri("hub/repositories/example/java/tags?page_size=100").toString(), "https://example.invalid/tags?page=2",
                    "//example.invalid/tags?page=2", server.uri("hub/repositories/other/java/tags?page=2").toString())) {
                server.requests.clear();
                server.respond(ignored -> Reply.ok(lookup.instance().toString(Map.of("results", List.of(), "next", next))));
                assertTrue(assertThrows(IllegalStateException.class, () -> catalog.published("example/java"))
                        .getMessage().contains("Invalid/cyclic Docker Hub pagination"), next);
                assertEquals(1, server.requests.size(), next);
            }
        }
    }

    @Test
    void archiveRequiresMatchingMetadataAndAValidSha256() throws IOException {
        try (final var container = ConfiguringContainer.of().start();
             final var lookup = container.lookup(JsonMapper.class);
             final var server = new Fixture();
             final var catalog = catalog(lookup.instance(), server)) {
            server.respond(ignored -> Reply.ok(lookup.instance().toString(detail())));
            assertEquals(new Archive(URL, SHA), catalog.archive(pkg(ID)));
            assertEquals(List.of("/azul/packages/" + ID), server.requests);
            for (final var field : List.of("download_url", "java_package_type", "lib_c_type", "release_status", "availability_type")) {
                final var invalid = new HashMap<>(detail());
                invalid.put(field, "invalid");
                server.respond(ignored -> Reply.ok(lookup.instance().toString(invalid)));
                assertTrue(assertThrows(IllegalStateException.class, () -> catalog.archive(pkg(ID)))
                        .getMessage().contains("Inconsistent JRE metadata"), field);
            }
            for (final var sha : List.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
                final var invalid = new HashMap<>(detail());
                invalid.put("sha256_hash", sha);
                server.respond(ignored -> Reply.ok(lookup.instance().toString(invalid)));
                assertTrue(assertThrows(IllegalStateException.class, () -> catalog.archive(pkg(ID)))
                        .getMessage().contains("Missing/invalid Azul SHA-256"));
            }
            final var missing = new HashMap<>(detail());
            missing.remove("sha256_hash");
            server.respond(ignored -> Reply.ok(lookup.instance().toString(missing)));
            assertThrows(RuntimeException.class, () -> catalog.archive(pkg(ID)));
        }
    }

    @Test
    void rejectsInvalidIdentifiersWithoutAnHttpRequest() throws IOException {
        try (final var container = ConfiguringContainer.of().start();
             final var lookup = container.lookup(JsonMapper.class);
             final var server = new Fixture();
             final var catalog = catalog(lookup.instance(), server)) {
            assertThrows(IllegalArgumentException.class, () -> catalog.archive(pkg("../packages")));
            assertThrows(IllegalArgumentException.class, () -> catalog.published("../example/java"));
            assertThrows(IllegalArgumentException.class, () -> catalog.published("example/java?token=secret"));
            assertTrue(server.requests.isEmpty());
        }
    }

    @Test
    void reportsHttpErrorsAndRejectsNonPositiveTimeouts() throws IOException {
        try (final var container = ConfiguringContainer.of().start();
             final var lookup = container.lookup(JsonMapper.class);
             final var server = new Fixture();
             final var catalog = catalog(lookup.instance(), server)) {
            server.respond(ignored -> new Reply(503, "unavailable", Map.of()));
            assertTrue(assertThrows(IllegalStateException.class, catalog::packages).getMessage().contains("HTTP 503"));
            for (final var timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
                assertThrows(IllegalArgumentException.class, () -> new RemoteCatalog(lookup.instance(), timeout,
                        server.uri("azul/"), server.uri("hub/")));
            }
        }
    }

    private static RemoteCatalog catalog(final JsonMapper json, final Fixture server) {
        return new RemoteCatalog(json, Duration.ofSeconds(5), server.uri("azul/"), server.uri("hub/"));
    }

    private static Package pkg(final String id) {
        return new Package(id, "21.0.10", "21.40.17", 7, "amd64", URL);
    }

    private static Map<String, Object> packageEntry(final String name, final List<Integer> version) {
        return Map.of("package_uuid", ID, "name", name, "java_version", version,
                "distro_version", List.of(21, 40, 17), "openjdk_build_number", 7,
                "download_url", "https://cdn.azul.com/zulu/bin/" + name);
    }

    private static Map<String, Object> detail() {
        return Map.of("download_url", URL, "java_package_type", "jre", "lib_c_type", "musl",
                "release_status", "ga", "availability_type", "CA", "sha256_hash", SHA);
    }

    private static Map<String, Object> tag(final String name, final boolean bothArchitectures) {
        final var images = new ArrayList<Map<String, String>>();
        images.add(Map.of("os", "linux", "architecture", "amd64"));
        images.add(Map.of("os", "unknown", "architecture", "unknown"));
        images.add(Map.of("os", "windows", "architecture", "arm64"));
        images.add(Map.of("os", "linux", "architecture", "s390x"));
        if (bothArchitectures) {
            images.add(Map.of("os", "linux", "architecture", "arm64"));
        }
        return Map.of("name", name, "digest", "sha256:" + SHA, "images", images);
    }

    private record Reply(int status, String body, Map<String, String> headers) {
        private static Reply ok(final String body) {
            return new Reply(200, body, Map.of());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private volatile Function<HttpExchange, Reply> responder = ignored -> new Reply(404, "missing fixture", Map.of());

        private Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    requests.add(exchange.getRequestURI().toString());
                    final var reply = responder.apply(exchange);
                    reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    final var body = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(reply.status(), body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
        }

        private URI uri(final String path) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + '/' + path);
        }

        private void respond(final Function<HttpExchange, Reply> responder) {
            this.responder = responder;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
