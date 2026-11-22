package io.yupiik.internal.java;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.internal.java.Models.Manifest;
import io.yupiik.internal.java.Models.Version;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Immutable annotated tags are the publication queue, not a CI cache. */
public final class GitTags {
    private static final Pattern TAG = Pattern.compile("java/[0-9]+(?:\\.[0-9]+)*-alpine[0-9]+\\.[0-9]+\\.[0-9]+(?:-r[a-f0-9]{12})?");
    private static final Pattern SHA = Pattern.compile("[a-f0-9]{40}");
    private static final Pattern DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
    private final Path root;
    private final JsonMapper json;

    public GitTags(final Path root, final JsonMapper json) {
        this.root = root.toAbsolutePath().normalize();
        this.json = json;
    }

    public String head() {
        return git("rev-parse", "HEAD").strip();
    }

    public Map<String, Manifest> manifests() {
        final var result = new TreeMap<String, Manifest>();
        for (final var tag : git("tag", "--list", "java/*").lines().toList()) {
            if (!TAG.matcher(tag).matches() || !"tag".equals(git("cat-file", "-t", "refs/tags/" + tag).strip())) {
                continue;
            }
            final var annotation = git("for-each-ref", "--format=%(contents)", "refs/tags/" + tag).strip();
            if (annotation.startsWith("{")) {
                final var manifest = json.fromString(Manifest.class, annotation);
                validate(manifest);
                if (!manifest.tag().equals(tag) || !manifest.sourceRevision().equals(git("rev-parse", tag + "^{commit}").strip())) {
                    throw new IllegalStateException("Tag/manifest mismatch: " + tag);
                }
                result.put(tag, manifest);
            }
        }
        return result;
    }

    public Manifest read(final String tag) {
        if (!TAG.matcher(tag).matches()) {
            throw new IllegalArgumentException("Invalid release tag");
        }
        final var manifest = manifests().get(tag);
        if (manifest == null) {
            throw new IllegalArgumentException("Missing annotated build manifest: " + tag);
        }
        if (!head().equals(manifest.sourceRevision())) {
            throw new IllegalStateException("Checkout must match the pinned release revision");
        }
        return manifest;
    }

    public void createAndPush(final List<Manifest> manifests, final String revision) {
        if (!head().equals(revision) || !git("status", "--porcelain").isBlank()) {
            throw new IllegalStateException("Apply requires a clean checkout at the requested source revision");
        }
        final var existing = manifests();
        for (final var manifest : manifests) {
            validate(manifest);
            final var previous = existing.get(manifest.tag());
            if (previous != null) {
                if (!previous.equals(manifest)) {
                    throw new IllegalStateException("Refusing to mutate annotated tag " + manifest.tag());
                }
            } else {
                if (!manifest.sourceRevision().equals(revision)) {
                    throw new IllegalStateException("New tags must pin the requested source revision");
                }
                git("-c", "tag.gpgSign=false", "tag", "-a", manifest.tag(), revision, "-m", json.toString(manifest));
            }
            // Also retries a previously-created local tag after a failed push. Never force.
            git("push", "origin", "refs/tags/" + manifest.tag());
        }
    }

    public void validate(final Manifest manifest) {
        if (manifest.schema() != 1 || !SHA.matcher(manifest.sourceRevision()).matches() ||
                !Pattern.matches("[a-z0-9_-]+/[a-z0-9_-]+", manifest.repository()) ||
                !TAG.matcher(manifest.tag()).matches() || Version.parse(manifest.javaVersion()).major() < 17 ||
                !Pattern.matches("[0-9]+\\.[0-9]+\\.[0-9]+", manifest.alpineVersion()) ||
                !Pattern.matches(Pattern.quote(manifest.javaVersion() + "-alpine" + manifest.alpineVersion()) +
                        "(?:-r[a-f0-9]{12})?", manifest.imageTag()) ||
                !manifest.archives().keySet().equals(Set.of("amd64", "arm64"))) {
            throw new IllegalArgumentException("Invalid release manifest");
        }
        final var digest = manifest.alpineImage().substring(manifest.alpineImage().lastIndexOf('@') + 1);
        if (!manifest.alpineImage().equals("alpine:" + manifest.alpineVersion() + "@" + digest) || !DIGEST.matcher(digest).matches()) {
            throw new IllegalArgumentException("Alpine must use the pinned manifest-list digest");
        }
        manifest.archives().forEach((arch, archive) -> {
            final var suffix = arch.equals("amd64") ? "x64" : "aarch64";
            if (!Pattern.matches("[a-f0-9]{64}", archive.sha256()) ||
                    !Pattern.matches("https://cdn\\.azul\\.com/zulu/bin/zulu[0-9.]+-ca-jre" + Pattern.quote(manifest.javaVersion()) +
                            "-linux_musl_" + suffix + "\\.tar\\.gz", archive.url())) {
                throw new IllegalArgumentException("Only checksum-pinned ordinary musl Zulu JRE archives are allowed");
            }
        });
    }

    private String git(final String... arguments) {
        final var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(arguments));
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("java-updater-git-", ".log");
            process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("Git operation timed out");
            }
            final var text = Files.readString(output);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Git operation failed: " + text);
            }
            return text;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

}
