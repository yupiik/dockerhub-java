package io.yupiik.internal.java;

import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

@Command(name = "update", description = "Plan or publish immutable latest-per-major Zulu JRE release tags.")
public final class Update implements Runnable {
    private final Configuration configuration;
    private final JsonMapper json;

    public Update(final Configuration configuration, final JsonMapper json) {
        this.configuration = configuration;
        this.json = json;
    }

    @Override
    public void run() {
        if (configuration.limit() < 1 || configuration.limit() > 100) {
            throw new IllegalArgumentException("Batch limit must be between 1 and 100");
        }
        final var root = Path.of(configuration.root()).toAbsolutePath().normalize();
        final var git = new GitTags(root, json);
        final var revision = configuration.revision().isBlank() ? git.head() : configuration.revision();
        if (!revision.equals(git.head())) {
            throw new IllegalArgumentException("Requested revision must be HEAD");
        }
        try (final var catalog = new RemoteCatalog(json, Duration.parse(configuration.timeout()),
                URI.create("https://api.azul.com/metadata/v1/zulu/"), URI.create("https://hub.docker.com/v2/"))) {
            final var managed = git.manifests();
            final var all = new Planner().plan(catalog.packages(), catalog.alpine(), catalog.published(configuration.repository()),
                    managed, revision, configuration.repository(), catalog::archive);
            final var batch = all.stream().limit(configuration.limit()).toList();
            batch.forEach(git::validate);
            if (!configuration.dryRun()) {
                git.createAndPush(batch, revision);
            }
            final var output = Map.of("dryRun", configuration.dryRun(), "releases", batch,
                    "matrix", Map.of("include", batch.stream().map(it -> Map.of("tag", it.tag())).toList()),
                    "remaining", all.size() - batch.size());
            final var path = root.resolve(configuration.output()).normalize();
            Files.createDirectories(path.getParent());
            Files.writeString(path, json.toString(output) + '\n');
            System.out.println((configuration.dryRun() ? "DRY-RUN: " : "Queued: ") + batch.size() +
                    " JRE release(s), " + (all.size() - batch.size()) + " remaining. Plan: " + path);
            batch.forEach(it -> System.out.println("  " + it.tag() + (managed.containsKey(it.tag()) ? " (retry)" : " (new)")));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @RootConfiguration("update")
    public record Configuration(
            @Property(documentation = "Repository checkout root.", defaultValue = "\".\"") String root,
            @Property(value = "dry-run", documentation = "Never create or push tags when true.", defaultValue = "true") boolean dryRun,
            @Property(documentation = "JSON plan output relative to the checkout.", defaultValue = "\"_internal/target/plan.json\"") String output,
            @Property(documentation = "Source commit, defaults to HEAD.", defaultValue = "\"\"") String revision,
            @Property(documentation = "Docker Hub repository.", defaultValue = "\"ossyupiik/java\"") String repository,
            @Property(documentation = "Default HTTP request timeout (ISO-8601 duration).", defaultValue = "\"PT1M\"") String timeout,
            @Property(documentation = "Maximum releases to queue in this run.", defaultValue = "10") int limit) {
    }
}
