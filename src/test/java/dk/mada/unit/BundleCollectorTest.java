package dk.mada.unit;

import static org.assertj.core.api.Assertions.assertThat;

import dk.mada.action.BundleCollector;
import dk.mada.action.BundleCollector.Bundle;
import dk.mada.action.BundleCollector.BundleSource;
import dk.mada.fixture.BundlePrepperFixture;
import dk.mada.fixture.TestInstances;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests bundle collection - really the search for files.
 */
class BundleCollectorTest {
    /** Temporary test directory. */
    private @TempDir(cleanup = CleanupMode.ALWAYS) Path testDir;

    /** The subject under test - the collector. */
    private final BundleCollector sut = TestInstances.bundleCollector();

    /**
     * Tests that files can be signed.
     */
    @Test
    void canSignFiles() throws IOException {
        BundlePrepperFixture.addTestPomFiles(testDir, "bundle.pom");
        Files.copy(Paths.get("gradle/wrapper/gradle-wrapper.jar"), testDir.resolve("bundle.jar"));
        Files.createFile(testDir.resolve("bundle.jar.sha1"));
        Files.createFile(testDir.resolve("bundle.jar.md5"));

        List<Bundle> bundles = sut.collectBundles(testDir, List.of(".jar"));

        assertThat(bundles).first().satisfies(bundle -> {
            assertThat(bundle.files().signatures())
                    .map(testDir::relativize)
                    .map(Path::toString)
                    .containsExactlyInAnyOrder("bundle.pom.asc", "bundle.jar.asc");
            assertThat(bundle.files().signatures())
                    .allSatisfy(p -> assertThat(p).isNotEmptyFile());
            assertThat(filesIn(bundle.bundleJar()))
                    .containsExactlyInAnyOrder(
                            "dk/mada/action-maven-publish-test/0.0.0/bundle.pom",
                            "dk/mada/action-maven-publish-test/0.0.0/bundle.pom.asc",
                            "dk/mada/action-maven-publish-test/0.0.0/bundle.pom.sha1",
                            "dk/mada/action-maven-publish-test/0.0.0/bundle.pom.md5",
                            "dk/mada/action-maven-publish-test/0.0.0/bundle.jar",
                            "dk/mada/action-maven-publish-test/0.0.0/bundle.jar.asc",
                            "dk/mada/action-maven-publish-test/0.0.0/bundle.jar.sha1",
                            "dk/mada/action-maven-publish-test/0.0.0/bundle.jar.md5");
        });
    }

    /**
     * Tests that bundle asset filtering works.
     */
    @Test
    void canCollectRelevantBundleAssets() throws IOException {
        setupFileTree(
                "root.jar", // ignored
                "dir/a.jar", // ignored
                "dir/a-sources.jar",
                "dir/a.module");
        BundlePrepperFixture.addTestPomFiles(testDir.resolve("dir"), "a.pom");

        List<BundleSource> foundBundles =
                new BundleCollector(null).findBundleSources(testDir, List.of(".module", "-sources.jar"));
        List<String> foundPaths =
                foundBundles.stream().flatMap(b -> toPaths(b).stream()).toList();
        assertThat(foundPaths).containsExactlyInAnyOrder("dir/a.pom", "dir/a-sources.jar", "dir/a.module");
    }

    private List<String> toPaths(BundleSource bs) {
        List<Path> files = new ArrayList<>();
        files.add(bs.pom().pomFile());
        files.addAll(bs.assets());
        return files.stream().map(p -> testDir.relativize(p).toString()).toList();
    }

    private void setupFileTree(String... files) throws IOException {
        for (String path : files) {
            Path file = testDir.resolve(path);
            Files.createDirectories(file.getParent());
            Files.createFile(file);
        }
    }

    /**
     * Extracts filenames in jar-file
     *
     * @param jar the jar-file to list files in
     * @return the files in the jar-file
     */
    private List<String> filesIn(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.stream().map(JarEntry::getName).toList();
        }
    }
}
