package io.yupiik.internal.java;

import io.yupiik.internal.java.Models.Alpine;
import io.yupiik.internal.java.Models.Archive;
import io.yupiik.internal.java.Models.Manifest;
import io.yupiik.internal.java.Models.Package;
import io.yupiik.internal.java.Models.Published;
import io.yupiik.internal.java.Models.Version;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Pure planning: only latest per Java major, never the patch cross-product. */
public final class Planner {
    private static final Pattern PUBLISHED = Pattern.compile("([0-9]+(?:\\.[0-9]+)*)(?:-alpine([0-9]+(?:\\.[0-9]+)*))?(?:-r[a-f0-9]{12})?(?:-(?:amd64|arm64))?");

    public List<Manifest> plan(final List<Package> packages, final Alpine alpine, final List<Published> published,
                               final Map<String, Manifest> managed, final String revision, final String repository,
                               final Function<Package, Archive> archives) {
        final var result = new LinkedHashMap<String, Manifest>();
        if (managed.values().stream().anyMatch(it -> !it.repository().equals(repository))) {
            throw new IllegalArgumentException("Managed tags belong to a different Docker Hub repository");
        }
        // Already-pinned releases are recoverable even after upstream moves forward.
        managed.values().stream().sorted(Comparator.comparing(Manifest::imageTag)).forEach(manifest -> {
            if (!complete(manifest.imageTag(), published)) {
                result.put(manifest.tag(), manifest);
            }
        });
        final var majors = new TreeMap<Integer, List<Package>>();
        packages.stream().filter(it -> Version.parse(it.javaVersion()).major() >= 17).forEach(it ->
                majors.computeIfAbsent(Version.parse(it.javaVersion()).major(), ignored -> new ArrayList<>()).add(it));
        for (final var entries : majors.values()) {
            final var latest = entries.stream().max(Comparator.comparing((Package it) -> Version.parse(it.javaVersion()))
                    .thenComparingInt(Package::build)).orElseThrow();
            final var javaVersion = latest.javaVersion();
            final var pair = new TreeMap<String, Package>();
            entries.stream().filter(it -> it.javaVersion().equals(javaVersion) && it.build() == latest.build())
                    .forEach(it -> pair.merge(it.architecture(), it, (a, b) ->
                            Version.parse(a.distroVersion()).compareTo(Version.parse(b.distroVersion())) >= 0 ? a : b));
            if (!pair.keySet().equals(Set.of("amd64", "arm64"))) {
                continue; // Do not silently publish an older patch when the newest is not ready on both architectures.
            }
            if (published.stream().anyMatch(it -> ahead(it.tag(), javaVersion, alpine.version()))) {
                continue; // Never move backwards if an API is temporarily stale.
            }
            final var baseTag = javaVersion + "-alpine" + alpine.version();
            if (!managed.containsKey("java/" + baseTag) && indexComplete(baseTag, published)) {
                continue;
            }
            final var pinned = new TreeMap<String, Archive>();
            pair.forEach((arch, pkg) -> pinned.put(arch, archives.apply(pkg)));
            final var base = new Manifest(1, revision, repository, baseTag, javaVersion, alpine.version(),
                    "alpine:" + alpine.version() + "@" + alpine.digest(), pinned);
            final var existing = managed.get(base.tag());
            final var manifest = existing == null || sameInputs(existing, base) ? base :
                    new Manifest(1, revision, repository, baseTag + "-r" + fingerprint(base), javaVersion,
                            alpine.version(), base.alpineImage(), pinned);
            final var previous = managed.get(manifest.tag());
            if (previous != null && !sameInputs(previous, manifest)) {
                throw new IllegalStateException("Immutable release collision: " + manifest.tag());
            }
            if (!complete(manifest.imageTag(), published)) {
                result.put(manifest.tag(), previous == null ? manifest : previous);
            }
        }
        return List.copyOf(result.values());
    }

    private boolean sameInputs(final Manifest a, final Manifest b) {
        return a.schema() == b.schema() && a.repository().equals(b.repository()) &&
                a.javaVersion().equals(b.javaVersion()) && a.alpineVersion().equals(b.alpineVersion()) &&
                a.alpineImage().equals(b.alpineImage()) && a.archives().equals(b.archives());
    }

    private String fingerprint(final Manifest manifest) {
        try {
            final var input = new StringBuilder(manifest.alpineImage());
            new TreeMap<>(manifest.archives()).forEach((arch, archive) -> input.append('\n').append(arch)
                    .append(':').append(archive.url()).append(':').append(archive.sha256()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean complete(final String tag, final List<Published> published) {
        return indexComplete(tag, published) && Set.of("amd64", "arm64").stream().allMatch(arch ->
                published.stream().anyMatch(it -> it.tag().equals(tag + '-' + arch) && it.architectures().contains(arch)));
    }

    private boolean indexComplete(final String tag, final List<Published> published) {
        return published.stream().anyMatch(it -> it.tag().equals(tag) && it.complete());
    }

    private boolean ahead(final String tag, final String javaVersion, final String alpineVersion) {
        final var match = PUBLISHED.matcher(tag);
        if (!match.matches()) {
            return false;
        }
        final var java = Version.parse(match.group(1));
        final var target = Version.parse(javaVersion);
        if (java.major() != target.major()) {
            return false;
        }
        return java.compareTo(target) > 0 || (match.group(2) != null &&
                Version.parse(match.group(2)).compareTo(Version.parse(alpineVersion)) > 0);
    }
}
