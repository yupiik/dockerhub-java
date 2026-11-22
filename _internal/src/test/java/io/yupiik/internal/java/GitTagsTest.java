package io.yupiik.internal.java;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.internal.java.Models.Archive;
import io.yupiik.internal.java.Models.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitTagsTest {
    @TempDir
    private Path directory;

    @Test
    void annotatedTagRoundTripAndRetryNeverMutatesTag() throws Exception {
        final var checkout = checkout();
        try (final var container = ConfiguringContainer.of().start(); final var mapper = container.lookup(JsonMapper.class)) {
            final var json = mapper.instance();
            final var tags = new GitTags(checkout, json);
            final var manifest = manifest(tags.head());
            assertTrue(tags.manifests().isEmpty());
            tags.createAndPush(List.of(manifest), tags.head());
            assertEquals(manifest, tags.read(manifest.tag()));
            final var objectId = git(checkout, "rev-parse", manifest.tag());
            tags.createAndPush(List.of(manifest), tags.head());
            assertEquals(objectId, git(checkout, "rev-parse", manifest.tag()));
            assertEquals("tag", git(directory, "--git-dir=" + directory.resolve("remote.git"), "cat-file", "-t", "refs/tags/" + manifest.tag()).strip());
            final var changed = new Manifest(1, manifest.sourceRevision(), manifest.repository(), manifest.imageTag(),
                    manifest.javaVersion(), manifest.alpineVersion(), "alpine:3.20.0@sha256:" + "b".repeat(64), manifest.archives());
            assertThrows(IllegalStateException.class, () -> tags.createAndPush(List.of(changed), tags.head()));
            assertEquals(objectId, git(checkout, "rev-parse", manifest.tag()));
        }
    }

    @Test
    void rejectsUnpinnedJdkWrongArchitectureAndUntrustedDownloads() throws Exception {
        final var checkout = checkout();
        try (final var container = ConfiguringContainer.of().start(); final var mapper = container.lookup(JsonMapper.class)) {
            final var tags = new GitTags(checkout, mapper.instance());
            final var valid = manifest(tags.head());
            tags.validate(valid);
            for (final var url : List.of("https://example.com/jre.tar.gz",
                    valid.archives().get("amd64").url().replace("-jre", "-jdk"),
                    valid.archives().get("amd64").url().replace("_x64", "_aarch64"))) {
                final var invalid = new Manifest(1, valid.sourceRevision(), valid.repository(), valid.imageTag(),
                        valid.javaVersion(), valid.alpineVersion(), valid.alpineImage(),
                        Map.of("amd64", new Archive(url, "a".repeat(64)), "arm64", valid.archives().get("arm64")));
                assertThrows(IllegalArgumentException.class, () -> tags.validate(invalid));
            }
            assertThrows(IllegalArgumentException.class, () -> tags.read("--help"));
            assertThrows(IllegalArgumentException.class, () -> tags.read(valid.tag()));
        }
    }

    @Test
    void releaseExportsPinnedArgumentsAndRequiresExactCheckout() throws Exception {
        final var checkout = checkout();
        try (final var container = ConfiguringContainer.of().start(); final var mapper = container.lookup(JsonMapper.class)) {
            final var json = mapper.instance();
            final var tags = new GitTags(checkout, json);
            final var manifest = manifest(tags.head());
            tags.createAndPush(List.of(manifest), tags.head());
            new Release(new Release.Configuration(checkout.toString(), manifest.tag(), "arm64", "build.json"), json).run();
            final var output = json.fromString(Object.class, Files.readString(checkout.resolve("build.json")));
            assertTrue(output instanceof Map<?, ?>);
            assertEquals(manifest.archives().get("arm64").url(), ((Map<?, ?>) output).get("url"));
            assertEquals(manifest.alpineImage(), ((Map<?, ?>) output).get("alpineImage"));
            assertEquals(manifest.sourceRevision(), ((Map<?, ?>) output).get("sourceRevision"));
            assertThrows(IllegalArgumentException.class, () -> new Release(
                    new Release.Configuration(checkout.toString(), manifest.tag(), "riscv64", "build.json"), json).run());
            git(checkout, "commit", "--allow-empty", "-m", "Next revision");
            assertThrows(IllegalStateException.class, () -> tags.read(manifest.tag()));
        }
    }

    @Test
    void dirtyCheckoutCannotPublishAndLegacyTagsAreNotReinterpreted() throws Exception {
        final var checkout = checkout();
        try (final var container = ConfiguringContainer.of().start(); final var mapper = container.lookup(JsonMapper.class)) {
            final var tags = new GitTags(checkout, mapper.instance());
            git(checkout, "tag", "java/21.0.1-alpine3");
            git(checkout, "-c", "tag.gpgSign=false", "tag", "-a", "java/21.0.1-alpine3.19.0", "-m", "Legacy release");
            assertTrue(tags.manifests().isEmpty());
            Files.writeString(checkout.resolve("untracked.txt"), "not committed");
            assertThrows(IllegalStateException.class, () -> tags.createAndPush(List.of(manifest(tags.head())), tags.head()));
            Files.delete(checkout.resolve("untracked.txt"));
            Files.writeString(checkout.resolve("tracked.txt"), "modified");
            assertThrows(IllegalStateException.class, () -> tags.createAndPush(List.of(manifest(tags.head())), tags.head()));
            assertFalse(git(checkout, "tag", "--list").contains("java/21.0.2-alpine3.20.0"));
        }
    }

    private Manifest manifest(final String revision) {
        return new Manifest(1, revision, "ossyupiik/java", "21.0.2-alpine3.20.0", "21.0.2", "3.20.0",
                "alpine:3.20.0@sha256:" + "a".repeat(64), Map.of(
                "amd64", new Archive("https://cdn.azul.com/zulu/bin/zulu21.32.17-ca-jre21.0.2-linux_musl_x64.tar.gz", "a".repeat(64)),
                "arm64", new Archive("https://cdn.azul.com/zulu/bin/zulu21.32.17-ca-jre21.0.2-linux_musl_aarch64.tar.gz", "b".repeat(64))));
    }

    private Path checkout() throws Exception {
        git(directory, "init", "--bare", directory.resolve("remote.git").toString());
        final var checkout = Files.createDirectory(directory.resolve("checkout"));
        git(checkout, "init");
        git(checkout, "config", "user.name", "Test");
        git(checkout, "config", "user.email", "test@example.com");
        git(checkout, "config", "commit.gpgSign", "false");
        Files.writeString(checkout.resolve("tracked.txt"), "initial");
        git(checkout, "add", "tracked.txt");
        git(checkout, "commit", "-m", "Initial");
        git(checkout, "remote", "add", "origin", directory.resolve("remote.git").toString());
        return checkout;
    }

    private String git(final Path root, final String... arguments) throws Exception {
        final var command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        final var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        final String output;
        try (final var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
