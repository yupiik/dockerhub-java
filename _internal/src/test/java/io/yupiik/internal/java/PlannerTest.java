package io.yupiik.internal.java;

import io.yupiik.internal.java.Models.Alpine;
import io.yupiik.internal.java.Models.Archive;
import io.yupiik.internal.java.Models.Manifest;
import io.yupiik.internal.java.Models.Package;
import io.yupiik.internal.java.Models.Published;
import io.yupiik.internal.java.Models.Version;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class PlannerTest {
    private static final Alpine ALPINE = new Alpine("3.23.2", "sha256:" + "a".repeat(64));
    private static final String REPOSITORY = "example/java";
    private final Planner planner = new Planner();

    @Test
    void comparesNumericVersionsAndPadsMissingComponents() {
        assertTrue(Version.parse("21.0.10").compareTo(Version.parse("21.0.9")) > 0);
        assertTrue(Version.parse("3.10.0").compareTo(Version.parse("3.9.9")) > 0);
        assertEquals(0, Version.parse("21").compareTo(Version.parse("21.0.0")));
        assertEquals(17, Version.parse("17.0.18").major());
        assertThrows(IllegalArgumentException.class, () -> Version.parse("21-ea"));
    }

    @Test
    void selectsOnlyLatestPatchAndBuildPerMajorAtLeast17() {
        final var packages = new ArrayList<Package>();
        for (final var version : List.of("8.0.462", "11.0.29", "16.0.2", "17.0.9", "17.0.10", "21.0.9", "21.0.10", "25.0.1")) {
            packages.addAll(pair(version, 1));
        }
        packages.addAll(pair("21.0.10", 2));
        final var resolved = new ArrayList<Package>();
        final var manifests = planner.plan(packages, ALPINE, List.of(), Map.of(), "revision", REPOSITORY, pkg -> {
            resolved.add(pkg);
            return archive(pkg);
        });
        assertEquals(List.of("17.0.10-alpine3.23.2", "21.0.10-alpine3.23.2", "25.0.1-alpine3.23.2"),
                manifests.stream().map(Manifest::imageTag).toList());
        assertEquals(6, resolved.size());
        assertTrue(resolved.stream().filter(it -> it.javaVersion().equals("21.0.10")).allMatch(it -> it.build() == 2));
        for (final var manifest : manifests) {
            assertEquals(Set.of("amd64", "arm64"), manifest.archives().keySet());
            assertEquals("alpine:3.23.2@" + ALPINE.digest(), manifest.alpineImage());
            assertEquals("revision", manifest.sourceRevision());
            assertEquals(REPOSITORY, manifest.repository());
        }
    }

    @Test
    void choosesNumericLatestDistroForEachArchitecture() {
        final var packages = new ArrayList<>(pair("21.0.10", 7));
        packages.add(new Package("00000000-0000-0000-0000-000000000003", "21.0.10", "21.10.0", 7,
                "amd64", "https://cdn.azul.com/zulu/bin/new.tar.gz"));
        final var result = plan(packages, List.of(), Map.of());
        assertEquals("https://cdn.azul.com/zulu/bin/new.tar.gz", result.getFirst().archives().get("amd64").url());
    }

    @Test
    void neverFallsBackWhenLatestPatchOrBuildLacksAnArchitecture() {
        for (final var latest : List.of(pkg("21.0.11", 1, "amd64"), pkg("21.0.10", 2, "arm64"))) {
            final var packages = new ArrayList<>(pair("21.0.10", 1));
            packages.add(latest);
            assertTrue(planner.plan(packages, ALPINE, List.of(), Map.of(), "revision", REPOSITORY,
                    ignored -> fail("Incomplete latest release must not resolve any archives")).isEmpty());
        }
    }

    @Test
    void bootstrapsLegacyFloatingAlpineTagAndIncompleteExactTag() {
        for (final var published : List.of(new Published("21.0.10", Set.of("amd64", "arm64")),
                new Published("21.0.10-alpine3.23.2", Set.of("amd64")))) {
            final var result = plan(pair("21.0.10", 1), List.of(published), Map.of());
            assertEquals(List.of("21.0.10-alpine3.23.2"), result.stream().map(Manifest::imageTag).toList());
        }
    }

    @Test
    void neverDowngradesJavaOrAlpineEvenWhenNewerTagIsIncomplete() {
        for (final var tag : List.of("21.0.11", "21.0.11-alpine3.22.0-amd64", "21.0.10-alpine3.24.0",
                "21.0.10-alpine3.24.0-rabcdef123456-arm64")) {
            assertTrue(planner.plan(pair("21.0.10", 1), ALPINE, List.of(new Published(tag, Set.of("amd64"))),
                    Map.of(), "revision", REPOSITORY, ignored -> fail("No archive lookup on downgrade")).isEmpty(), tag);
        }
        assertEquals(1, plan(pair("21.0.10", 1), List.of(new Published("25.0.1", Set.of("amd64", "arm64"))), Map.of()).size());
    }

    @Test
    void rejectsAlpineDowngradeEvenWhenCandidateJavaIsNewer() {
        assertTrue(planner.plan(pair("21.0.11", 1), ALPINE,
                List.of(new Published("21.0.10-alpine3.24.0", Set.of("amd64", "arm64"))), Map.of(),
                "revision", REPOSITORY, ignored -> fail("No archive lookup on Alpine downgrade")).isEmpty());
    }

    @Test
    void rejectsAlpineDowngradeFromArchitectureSpecificTagsEvenWhenCandidateJavaIsNewer() {
        for (final var arch : List.of("amd64", "arm64")) {
            for (final var suffix : List.of("", "-rabcdef123456")) {
                final var tag = "21.0.10-alpine3.24.0" + suffix + '-' + arch;
                assertTrue(planner.plan(pair("21.0.11", 1), ALPINE, List.of(new Published(tag, Set.of(arch))),
                        Map.of(), "revision", REPOSITORY,
                        ignored -> fail("No archive lookup on Alpine downgrade")).isEmpty(), tag);
            }
        }
    }

    @Test
    void permitsNewerJavaUnlessSameMajorHasExplicitNewerAlpine() {
        for (final var tag : List.of("21.0.10", "21.0.10-amd64", "21.0.10-alpine3.23.2",
                "21.0.10-alpine3.22.0-arm64", "17.0.18-alpine3.24.0-amd64")) {
            assertEquals(List.of("21.0.11-alpine3.23.2"),
                    plan(pair("21.0.11", 1), List.of(new Published(tag, Set.of("amd64"))), Map.of())
                            .stream().map(Manifest::imageTag).toList(), tag);
        }
    }

    @Test
    void completedUnmanagedReleaseNeedsNoArchiveLookup() {
        assertTrue(planner.plan(pair("21.0.10", 1), ALPINE,
                List.of(new Published("21.0.10-alpine3.23.2", Set.of("amd64", "arm64"))), Map.of(),
                "revision", REPOSITORY, ignored -> fail("Completed release must not resolve archives")).isEmpty());
    }

    @Test
    void completedManagedReleaseCreatesNothingWhenInputsAreUnchanged() {
        final var manifest = plan(pair("21.0.10", 1), List.of(), Map.of()).getFirst();
        assertTrue(plan(pair("21.0.10", 1), completed(manifest), Map.of(manifest.tag(), manifest)).isEmpty());
    }

    @Test
    void retriesPendingManifestWithOriginalPinnedInputsEvenAfterUpstreamDisappears() {
        final var manifest = plan(pair("21.0.10", 1), List.of(), Map.of()).getFirst();
        final var result = planner.plan(List.of(), new Alpine("3.24.0", "sha256:" + "b".repeat(64)),
                List.of(new Published(manifest.imageTag(), Set.of("amd64"))), Map.of(manifest.tag(), manifest),
                "new-revision", REPOSITORY, ignored -> fail("Pending inputs are already pinned"));
        assertEquals(List.of(manifest), result);
        assertSame(manifest, result.getFirst());
    }

    @Test
    void retriesUnchangedPendingManifestWithoutReplacingItsSourceRevision() {
        final var manifest = plan(pair("21.0.10", 1), List.of(), Map.of()).getFirst();
        final var result = planner.plan(pair("21.0.10", 1), ALPINE, List.of(), Map.of(manifest.tag(), manifest),
                "new-revision", REPOSITORY, PlannerTest::archive);
        assertEquals(List.of(manifest), result);
        assertSame(manifest, result.getFirst());
    }

    @Test
    void respinsChangedArchiveInputsDeterministicallyWithoutOverwritingOriginal() {
        final var original = plan(pair("21.0.10", 1), List.of(), Map.of()).getFirst();
        final var managed = Map.of(original.tag(), original);
        final var published = completed(original);
        final var changed = planner.plan(pair("21.0.10", 1), ALPINE, published, managed, "new-revision", REPOSITORY,
                pkg -> new Archive(pkg.url(), "b".repeat(64))).getFirst();
        assertTrue(changed.imageTag().matches("21\\.0\\.10-alpine3\\.23\\.2-r[a-f0-9]{12}"));
        assertNotEquals(original.imageTag(), changed.imageTag());
        assertEquals("a".repeat(64), original.archives().get("amd64").sha256());
        assertEquals("b".repeat(64), changed.archives().get("amd64").sha256());
        final var retry = planner.plan(pair("21.0.10", 1), ALPINE, published,
                Map.of(original.tag(), original, changed.tag(), changed), "third-revision", REPOSITORY,
                pkg -> new Archive(pkg.url(), "b".repeat(64)));
        assertEquals(List.of(changed), retry);
        assertSame(changed, retry.getFirst());
        assertTrue(planner.plan(pair("21.0.10", 1), ALPINE, completed(original, changed),
                Map.of(original.tag(), original, changed.tag(), changed), "third-revision", REPOSITORY,
                pkg -> new Archive(pkg.url(), "b".repeat(64))).isEmpty());
    }

    @Test
    void changedArchiveUrlCreatesARespinAndKeepsPendingOriginal() {
        final var original = plan(pair("21.0.10", 1), List.of(), Map.of()).getFirst();
        final var result = planner.plan(pair("21.0.10", 1), ALPINE, List.of(), Map.of(original.tag(), original),
                "revision", REPOSITORY, pkg -> new Archive(pkg.url() + "?revision=2", "a".repeat(64)));
        assertEquals(2, result.size());
        assertSame(original, result.getFirst());
        final var respin = result.get(1);
        assertTrue(respin.imageTag().startsWith(original.imageTag() + "-r"));
        assertEquals(original.archives().get("amd64").url() + "?revision=2", respin.archives().get("amd64").url());
        assertEquals(original.archives().get("amd64").sha256(), respin.archives().get("amd64").sha256());
    }

    @Test
    void changedAlpineDigestCreatesARespin() {
        final var original = plan(pair("21.0.10", 1), List.of(), Map.of()).getFirst();
        final var result = planner.plan(pair("21.0.10", 1), new Alpine(ALPINE.version(), "sha256:" + "b".repeat(64)),
                completed(original), Map.of(original.tag(), original), "revision", REPOSITORY, PlannerTest::archive);
        assertEquals(1, result.size());
        assertNotEquals(original.imageTag(), result.getFirst().imageTag());
        assertEquals("alpine:3.23.2@sha256:" + "b".repeat(64), result.getFirst().alpineImage());
    }

    private List<Manifest> plan(final List<Package> packages, final List<Published> published, final Map<String, Manifest> managed) {
        return planner.plan(packages, ALPINE, published, managed, "revision", REPOSITORY, PlannerTest::archive);
    }

    @Test
    void refusesToMixManagedTagsFromDifferentRepositories() {
        final var manifest = plan(pair("21.0.10", 1), List.of(), Map.of()).getFirst();
        assertThrows(IllegalArgumentException.class, () -> planner.plan(List.of(), ALPINE, List.of(),
                Map.of(manifest.tag(), manifest), "revision", "other/java", ignored -> fail("No network")));
    }

    @Test
    void repairsMissingArchitectureTagEvenWhenIndexIsComplete() {
        final var manifest = plan(pair("21.0.10", 1), List.of(), Map.of()).getFirst();
        final var published = completed(manifest).stream().filter(it -> !it.tag().endsWith("-arm64")).toList();
        assertEquals(List.of(manifest), plan(pair("21.0.10", 1), published, Map.of(manifest.tag(), manifest)));
    }

    private static List<Published> completed(final Manifest... manifests) {
        final var result = new ArrayList<Published>();
        for (final var manifest : manifests) {
            result.add(new Published(manifest.imageTag(), Set.of("amd64", "arm64")));
            result.add(new Published(manifest.imageTag() + "-amd64", Set.of("amd64")));
            result.add(new Published(manifest.imageTag() + "-arm64", Set.of("arm64")));
        }
        return result;
    }

    private static List<Package> pair(final String version, final int build) {
        return List.of(pkg(version, build, "amd64"), pkg(version, build, "arm64"));
    }

    private static Package pkg(final String version, final int build, final String arch) {
        return new Package("00000000-0000-0000-0000-00000000000" + (arch.equals("amd64") ? "1" : "2"),
                version, "21.9.0", build, arch, "https://cdn.azul.com/zulu/bin/" + version + '-' + build + '-' + arch + ".tar.gz");
    }

    private static Archive archive(final Package pkg) {
        return new Archive(pkg.url(), "a".repeat(64));
    }
}
