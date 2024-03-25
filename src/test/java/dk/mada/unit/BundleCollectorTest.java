package dk.mada.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.mada.action.BundleCollector;
import dk.mada.action.BundleCollector.Bundle;
import dk.mada.action.BundleCollector.BundleSource;
import dk.mada.fixture.TestInstances;

/**
 * Tests bundle collection - really the search for files.
 */
class BundleCollectorTest {
    /** Temporary test directory. */
    private @TempDir Path testDir;

    /** The subject under test - the collector. */
    private final BundleCollector sut = TestInstances.bundleCollector();

    /**
     * Tests that files can be signed.
     */
    @Test
    void canSignFiles() throws IOException {
        Files.copy(Paths.get("gradle/wrapper/gradle-wrapper.jar"), testDir.resolve("bundle.jar"));
        Files.createFile(testDir.resolve("bundle.pom"));

        List<Bundle> bundles = sut.collectBundles(testDir, List.of(".jar"));

        assertThat(bundles)
                .first()
                .satisfies(bundle -> {
                    assertThat(bundle.files().signatures())
                            .map(testDir::relativize)
                            .map(Path::toString)
                            .containsExactlyInAnyOrder("bundle.pom.asc", "bundle.jar.asc");
                    assertThat(bundle.files().signatures())
                            .allSatisfy(p -> assertThat(p).isNotEmptyFile());
                    assertThat(filesIn(bundle.bundleJar()))
                            .containsExactlyInAnyOrder("bundle.pom", "bundle.jar", "bundle.pom.asc", "bundle.jar.asc");
                });
    }

    /**
     * Extracts filenames in jar-file
     *
     * @param jar the jar-file to list files in
     * @return the files in the jar-file
     */
    private List<String> filesIn(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.stream()
                    .map(je -> je.getName())
                    .toList();
        }
    }

    /**
     * Tests that bundle asset filtering works.
     */
    @Test
    void canCollectRelevantBundleAssets() throws IOException {
        setupFileTree(
                "root.jar", // ignored
                "dir/a.pom",
                "dir/a.jar", // ignored
                "dir/a-sources.jar",
                "dir/a.module");

        List<BundleSource> foundBundles = new BundleCollector(null).findBundleSources(testDir, List.of(".module", "-sources.jar"));
        List<String> foundPaths = foundBundles.stream()
                .flatMap(b -> toPaths(b).stream())
                .toList();
        assertThat(foundPaths)
                .containsExactlyInAnyOrder("dir/a.pom", "dir/a-sources.jar", "dir/a.module");
    }

    private List<String> toPaths(BundleSource bs) {
        List<Path> files = new ArrayList<>();
        files.add(bs.pom());
        files.addAll(bs.assets());
        return files.stream()
                .map(p -> testDir.relativize(p).toString())
                .toList();
    }

    private void setupFileTree(String... files) throws IOException {
        for (String path : files) {
            Path file = testDir.resolve(path);
            Files.createDirectories(file.getParent());
            Files.createFile(file);
        }
    }
}
