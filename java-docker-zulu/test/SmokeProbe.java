/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0.
 */
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Comparator;
import java.util.Set;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public class SmokeProbe {
    public static void main(final String[] args) throws Exception {
        require(Runtime.version().feature() == 25, "Expected Java 25");
        require(Runtime.version().interim() == 0 && Runtime.version().update() == 5,
                "Expected Java 25.0.5");
        require(System.getProperty("java.vendor", "").contains("Azul"), "Expected Azul vendor");
        require("/tmp".equals(System.getenv("HOME")), "Expected HOME=/tmp");
        require(javax.tools.ToolProvider.getSystemJavaCompiler() == null, "Unexpected Java compiler");
        final var mounts = Files.readAllLines(Path.of("/proc/mounts"));
        require(mounts.stream().anyMatch(line -> {
            final var fields = line.split(" ");
            return fields[1].equals("/") && Set.of(fields[3].split(",")).contains("ro");
        }), "Root mount must be read-only");
        require(mounts.stream().anyMatch(line -> {
            final var fields = line.split(" ");
            return fields[1].equals("/tmp") && fields[2].equals("tmpfs") &&
                    Set.of(fields[3].split(",")).contains("rw");
        }), "Expected writable /tmp tmpfs");
        require(mounts.stream().anyMatch(line -> {
            final var fields = line.split(" ");
            return fields[1].equals("/work") && Set.of(fields[3].split(",")).contains("rw");
        }), "Expected writable /work bind mount");
        final var status = Files.readString(Path.of("/proc/self/status"));
        require(status.lines().anyMatch(line -> line.matches("Uid:\\s+10001\\s+10001\\s+10001\\s+10001")),
                "Expected UID 10001");
        require(status.lines().anyMatch(line -> line.matches("Gid:\\s+10001\\s+10001\\s+10001\\s+10001")),
                "Expected GID 10001");
        require(status.lines().anyMatch(line -> line.matches("CapEff:\\s+0+")), "Unexpected capabilities");
        require(status.lines().anyMatch(line -> line.matches("NoNewPrivs:\\s+1")), "Expected no-new-privileges");

        final var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init((KeyStore) null);
        var issuers = 0;
        for (final var manager : trust.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                issuers += x509.getAcceptedIssuers().length;
            }
        }
        require(issuers > 0, "Empty Java CA trust store");
        System.out.println("Java " + Runtime.version() + ", vendor: " + System.getProperty("java.vendor") +
                ", trusted issuers: " + issuers);
        secureHandles(Path.of("/tmp"));
        secureHandles(Path.of("/work"));
        if (args.length > 0) {
            final var uri = URI.create(args[0]);
            require("https".equals(uri.getScheme()), "TLS smoke target must use HTTPS");
            try (final var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
                final var response = client.send(HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.discarding());
                require(response.sslSession().isPresent(), "Missing TLS session");
                System.out.println("HTTPS status: " + response.statusCode());
            }
        }
        System.out.println("Read-only root, UID/GID 10001, dropped capabilities and no-new-privileges: PASS");
    }

    private static void secureHandles(final Path filesystem) throws Exception {
        final var root = Files.createTempDirectory(filesystem, "secure-directory-");
        try {
            final var original = Files.createDirectory(root.resolve("original"));
            Files.writeString(original.resolve("payload"), "descriptor-relative content");
            Files.createDirectory(original.resolve("child"));
            Files.createSymbolicLink(original.resolve("file-link"), Path.of("payload"));
            Files.createSymbolicLink(original.resolve("directory-link"), Path.of("child"));
            try (final var directory = Files.newDirectoryStream(original)) {
                require(directory instanceof SecureDirectoryStream<?>,
                        "SecureDirectoryStream unavailable on " + filesystem + ": " + directory.getClass());
                final var secure = (SecureDirectoryStream<Path>) directory;
                // Replace the pathname after opening: reads must use the directory descriptor.
                Files.move(original, root.resolve("moved"));
                Files.createDirectory(original);
                Files.writeString(original.resolve("payload"), "wrong pathname content");
                try (final var channel = secure.newByteChannel(Path.of("payload"),
                        Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                    final var bytes = ByteBuffer.allocate(128);
                    while (channel.read(bytes) != -1) {
                        require(bytes.hasRemaining(), "Unexpected payload size");
                    }
                    bytes.flip();
                    require(StandardCharsets.UTF_8.decode(bytes).toString().equals("descriptor-relative content"),
                            "Read did not use the original directory descriptor");
                }
                try (final var child = secure.newDirectoryStream(Path.of("child"), LinkOption.NOFOLLOW_LINKS)) {
                    require(!child.iterator().hasNext(), "Expected an empty child directory");
                }
                var fileRejected = false;
                try (final var ignored = secure.newByteChannel(Path.of("file-link"),
                        Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                    throw new IllegalStateException("NOFOLLOW_LINKS accepted a file symlink");
                } catch (final IOException expected) {
                    fileRejected = true;
                }
                require(fileRejected, "File symlink must be rejected");
                var directoryRejected = false;
                try (final var ignored = secure.newDirectoryStream(Path.of("directory-link"), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("NOFOLLOW_LINKS accepted a directory symlink");
                } catch (final IOException expected) {
                    directoryRejected = true;
                }
                require(directoryRejected, "Directory symlink must be rejected");
                System.out.println(filesystem + ": " + directory.getClass().getName() +
                        "; descriptor-relative read after rename + file/directory symlink rejection: PASS");
            }
        } finally {
            try (final var paths = Files.walk(root)) {
                for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
