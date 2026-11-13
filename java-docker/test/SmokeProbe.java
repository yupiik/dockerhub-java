/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0.
 */
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public class SmokeProbe {
    public static void main(final String[] args) throws Exception {
        if (Runtime.version().feature() != 25) {
            throw new IllegalStateException("Expected Java 25");
        }
        final var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init((java.security.KeyStore) null);
        var issuers = 0;
        for (final var manager : trust.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                issuers += x509.getAcceptedIssuers().length;
            }
        }
        if (issuers == 0) {
            throw new IllegalStateException("Empty Java CA trust store");
        }
        final var temporary = Files.createTempFile("java-docker-", ".tmp");
        Files.delete(temporary);
        System.out.println("Java " + Runtime.version() + ", trusted issuers: " + issuers);
        if (args.length > 0) {
            final var uri = URI.create(args[0]);
            if (!"https".equals(uri.getScheme())) {
                throw new IllegalArgumentException("TLS smoke target must use HTTPS");
            }
            try (final var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()) {
                final var response = client.send(HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.sslSession().isEmpty()) {
                    throw new IllegalStateException("Missing TLS session");
                }
                System.out.println("HTTPS status: " + response.statusCode());
            }
        }
    }
}
