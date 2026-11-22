package io.yupiik.internal.java;

import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Command(name = "release", description = "Validate and export one architecture of a pinned annotated release tag.")
public final class Release implements Runnable {
    private final Configuration configuration;
    private final JsonMapper json;

    public Release(final Configuration configuration, final JsonMapper json) {
        this.configuration = configuration;
        this.json = json;
    }

    @Override
    public void run() {
        final var root = Path.of(configuration.root()).toAbsolutePath().normalize();
        final var manifest = new GitTags(root, json).read(configuration.tag());
        final var archive = manifest.archives().get(configuration.architecture());
        if (archive == null) {
            throw new IllegalArgumentException("Architecture must be amd64 or arm64");
        }
        final var output = root.resolve(configuration.output()).normalize();
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, json.toString(Map.of("sourceRevision", manifest.sourceRevision(),
                    "imageTag", manifest.imageTag(), "repository", manifest.repository(),
                    "alpineImage", manifest.alpineImage(), "url", archive.url(), "sha256", archive.sha256(),
                    "javaVersion", manifest.javaVersion(), "alpineVersion", manifest.alpineVersion())) + '\n');
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @RootConfiguration("release")
    public record Configuration(
            @Property(documentation = "Repository checkout root.", defaultValue = "\".\"") String root,
            @Property(documentation = "Annotated java release tag.") String tag,
            @Property(documentation = "Native architecture: amd64 or arm64.") String architecture,
            @Property(documentation = "Validated JSON build arguments.", defaultValue = "\"_internal/target/build.json\"") String output) {
    }
}
