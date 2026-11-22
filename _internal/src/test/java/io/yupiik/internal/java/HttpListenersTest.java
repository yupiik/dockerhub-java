package io.yupiik.internal.java;

import io.yupiik.fusion.httpclient.core.listener.impl.CurlLoggerListener;
import io.yupiik.fusion.httpclient.core.listener.impl.DefaultTimeout;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpListenersTest {
    @Test
    void oneMinuteDefaultPreservesExplicitTimeout() {
        final var listener = new DefaultTimeout(Duration.ofMinutes(1));
        final var request = HttpRequest.newBuilder(URI.create("https://example.com")).GET().build();
        assertEquals(Duration.ofMinutes(1), listener.before(1, request).request().timeout().orElseThrow());
        final var explicit = HttpRequest.newBuilder(request.uri()).timeout(Duration.ofSeconds(3)).GET().build();
        assertEquals(Duration.ofSeconds(3), listener.before(2, explicit).request().timeout().orElseThrow());
    }

    @Test
    void curlLogsMaskCredentialsAndDoNotLogPayloads() {
        final var lines = new ArrayList<String>();
        final var logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(final LogRecord record) {
                lines.add(record.getMessage());
            }

            @Override
            public void flush() {
                // Nothing to flush.
            }

            @Override
            public void close() {
                // Nothing to close.
            }
        });
        final var listener = new CurlLoggerListener(new CurlLoggerListener.Configuration(logger).setLogPayload(false)) {
            private void log(final HttpRequest request) {
                final var state = before(1, request);
                after(state.state(), request, null, null);
            }
        };
        final var request = HttpRequest.newBuilder(URI.create("https://example.com/catalog"))
                .header("Authorization", "Bearer private-token").header("Cookie", "private-cookie").GET().build();
        listener.log(request);
        final var output = String.join("\n", lines);
        assertTrue(output.contains("curl -X GET"));
        assertTrue(output.contains("******"));
        assertFalse(output.contains("private-token"));
        assertFalse(output.contains("private-cookie"));
    }
}
