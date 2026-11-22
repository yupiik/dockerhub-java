package io.yupiik.internal.java;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class Models {
    private Models() {
        // No instance.
    }

    public record Version(List<Integer> parts) implements Comparable<Version> {
        private static final Pattern DOT = Pattern.compile("\\.");
        private static final Pattern VALID = Pattern.compile("[0-9]+(?:\\.[0-9]+)*");

        public Version {
            parts = List.copyOf(parts);
            if (parts.isEmpty() || parts.stream().anyMatch(it -> it < 0)) {
                throw new IllegalArgumentException("Invalid version: " + parts);
            }
        }

        public static Version parse(final String value) {
            if (!VALID.matcher(value).matches()) {
                throw new IllegalArgumentException("Not a numeric version: " + value);
            }
            return new Version(DOT.splitAsStream(value).map(Integer::parseInt).toList());
        }

        public int major() {
            return parts.getFirst();
        }

        @Override
        public int compareTo(final Version other) {
            for (int i = 0; i < Math.max(parts.size(), other.parts.size()); i++) {
                final var comparison = Integer.compare(i < parts.size() ? parts.get(i) : 0,
                        i < other.parts.size() ? other.parts.get(i) : 0);
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }

        @Override
        public String toString() {
            return String.join(".", parts.stream().map(String::valueOf).toList());
        }
    }

    public record Package(String id, String javaVersion, String distroVersion, int build,
                          String architecture, String url) {
    }

    public record Alpine(String version, String digest) {
    }

    public record Published(String tag, Set<String> architectures) {
        public boolean complete() {
            return architectures.containsAll(Set.of("amd64", "arm64"));
        }
    }

    @JsonModel
    public record Archive(String url, String sha256) {
    }

    @JsonModel
    public record Manifest(int schema, String sourceRevision, String repository, String imageTag,
                           String javaVersion, String alpineVersion, String alpineImage,
                           Map<String, Archive> archives) {
        public String tag() {
            return "java/" + imageTag;
        }
    }
}
