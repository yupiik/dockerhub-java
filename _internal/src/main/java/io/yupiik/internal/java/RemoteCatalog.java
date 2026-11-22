package io.yupiik.internal.java;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.httpclient.core.listener.impl.CurlLoggerListener;
import io.yupiik.fusion.httpclient.core.listener.impl.DefaultTimeout;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.internal.java.Models.Alpine;
import io.yupiik.internal.java.Models.Archive;
import io.yupiik.internal.java.Models.Package;
import io.yupiik.internal.java.Models.Published;
import io.yupiik.internal.java.Models.Version;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.toSet;

/** Public, read-only catalogs. No credentials are sent to either service. */
public final class RemoteCatalog implements AutoCloseable {
    private static final Pattern JRE = Pattern.compile("zulu[0-9.]+-ca-jre([0-9.]+)-linux_musl_(x64|aarch64)\\.tar\\.gz");
    private static final Pattern STABLE_ALPINE = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private final JsonMapper json;
    private final ExtendedHttpClient client;
    private final URI azul;
    private final URI hub;

    public RemoteCatalog(final JsonMapper json, final Duration timeout, final URI azul, final URI hub) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("HTTP timeout must be positive");
        }
        this.json = json;
        this.azul = azul;
        this.hub = hub;
        client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration()
                .setCloseTimeout(Duration.ofSeconds(5))
                .setRequestListeners(List.of(new DefaultTimeout(timeout),
                        new CurlLoggerListener(new CurlLoggerListener.Configuration(Logger.getLogger(RemoteCatalog.class.getName()))
                                .setLogPayload(false)))));
    }

    public List<Package> packages() {
        final var result = new ArrayList<Package>();
        for (final var arch : List.of("x64", "aarch64")) {
            for (int page = 1; ; page++) {
                final var response = get(azul.resolve("packages/?os=linux-musl&arch=" + arch +
                        "&archive_type=tar.gz&java_package_type=jre&javafx_bundled=false&release_status=ga&availability_types=CA&page_size=1000&page=" + page));
                final var entries = list(json.fromString(Object.class, response.body()));
                for (final var entry : entries) {
                    final var item = object(entry);
                    final var name = string(item, "name");
                    final var matcher = JRE.matcher(name);
                    if (!matcher.matches() || !matcher.group(2).equals(arch)) {
                        continue; // Excludes JDK, CRaC, JavaFX, glibc and other specialized distributions.
                    }
                    final var version = version(item.get("java_version"));
                    if (!version.equals(matcher.group(1)) || Version.parse(version).major() < 17) {
                        continue;
                    }
                    final var url = string(item, "download_url");
                    if (!url.equals("https://cdn.azul.com/zulu/bin/" + name)) {
                        throw new IllegalStateException("Unexpected Azul download URL");
                    }
                    result.add(new Package(string(item, "package_uuid"), version, version(item.get("distro_version")),
                            ((Number) item.get("openjdk_build_number")).intValue(), arch.equals("x64") ? "amd64" : "arm64", url));
                }
                final var pagination = response.headers().firstValue("x-pagination");
                if (pagination.isPresent()) {
                    final var last = ((Number) object(json.fromString(Object.class, pagination.get())).get("last_page")).intValue();
                    if (page >= last) {
                        break;
                    }
                } else if (entries.size() < 1000) {
                    break;
                }
                if (page >= 10000) {
                    throw new IllegalStateException("Azul pagination did not terminate");
                }
            }
        }
        return result;
    }

    public Archive archive(final Package pkg) {
        if (!Pattern.matches("[a-fA-F0-9-]{36}", pkg.id())) {
            throw new IllegalArgumentException("Invalid Azul package id");
        }
        final var detail = object(json.fromString(Object.class, get(azul.resolve("packages/" + pkg.id())).body()));
        if (!pkg.url().equals(string(detail, "download_url")) || !"jre".equals(string(detail, "java_package_type")) ||
                !"musl".equals(string(detail, "lib_c_type")) || !"ga".equals(string(detail, "release_status")) ||
                !"CA".equals(string(detail, "availability_type"))) {
            throw new IllegalStateException("Inconsistent JRE metadata: " + pkg.id());
        }
        final var sha = string(detail, "sha256_hash");
        if (!Pattern.matches("[a-f0-9]{64}", sha)) {
            throw new IllegalStateException("Missing/invalid Azul SHA-256: " + pkg.id());
        }
        return new Archive(pkg.url(), sha);
    }

    public Alpine alpine() {
        return tags("library/alpine").stream()
                .filter(it -> STABLE_ALPINE.matcher(string(it, "name")).matches())
                .filter(it -> architectures(it).containsAll(Set.of("amd64", "arm64")))
                .map(it -> new Alpine(string(it, "name"), string(it, "digest")))
                .max((a, b) -> Version.parse(a.version()).compareTo(Version.parse(b.version())))
                .orElseThrow(() -> new IllegalStateException("No stable Alpine supporting both architectures"));
    }

    public List<Published> published(final String repository) {
        if (!Pattern.matches("[a-z0-9_-]+/[a-z0-9_-]+", repository)) {
            throw new IllegalArgumentException("Invalid Docker Hub repository");
        }
        return tags(repository).stream().map(it -> new Published(string(it, "name"), architectures(it))).toList();
    }

    private Set<String> architectures(final Map<String, Object> tag) {
        return list(tag.get("images")).stream().map(RemoteCatalog::object)
                .filter(it -> "linux".equals(it.get("os")))
                .map(it -> string(it, "architecture"))
                .filter(it -> Set.of("amd64", "arm64").contains(it)).collect(toSet());
    }

    private List<Map<String, Object>> tags(final String repository) {
        final var result = new ArrayList<Map<String, Object>>();
        final var seen = new HashSet<URI>();
        var next = hub.resolve("repositories/" + repository + "/tags?page_size=100");
        while (next != null) {
            if (!sameOrigin(hub, next) || !next.getPath().startsWith(hub.getPath() + "repositories/" + repository + "/tags") || !seen.add(next)) {
                throw new IllegalStateException("Invalid/cyclic Docker Hub pagination");
            }
            final var body = object(json.fromString(Object.class, get(next).body()));
            list(body.get("results")).stream().map(RemoteCatalog::object).forEach(result::add);
            final var value = body.get("next");
            next = value == null ? null : next.resolve(value.toString());
        }
        return result;
    }

    private boolean sameOrigin(final URI a, final URI b) {
        return a.getScheme().equals(b.getScheme()) && a.getAuthority().equals(b.getAuthority());
    }

    private HttpResponse<String> get(final URI uri) {
        final var response = client.send(HttpRequest.newBuilder(uri).header("Accept", "application/json").GET().build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + uri);
        }
        return response;
    }

    private static String version(final Object value) {
        return String.join(".", list(value).stream().map(it -> String.valueOf(((Number) it).intValue())).toList());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    private static String string(final Map<String, Object> value, final String key) {
        return (String) value.get(key);
    }

    @Override
    public void close() {
        client.close();
    }
}
