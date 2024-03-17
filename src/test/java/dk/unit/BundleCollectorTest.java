package dk.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.mada.action.BundleCollector;
import dk.mada.action.BundleCollector.Bundle;

/**
 * Tests bundle collection - really the search for files.
 */
class BundleCollectorTest {
    /** Temporary test directory. */
    private @TempDir Path testDir;

    @Test
    void canCollectBundle() throws IOException {
        setupFileTree(
                "root.jar", // ignored
                "dir/a.pom",
                "dir/a.jar", // ignored
                "dir/a-sources.jar",
                "dir/a.module");

        List<Bundle> foundBundles = BundleCollector.collectBundles(testDir, List.of(".module", "-sources.jar"));
        List<String> foundPaths = foundBundles.stream()
                .flatMap(b -> toPaths(b).stream())
                .toList();
        assertThat(foundPaths)
                .containsExactlyInAnyOrder("dir/a.pom", "dir/a-sources.jar", "dir/a.module");
    }

    private List<String> toPaths(Bundle b) {
        List<Path> files = new ArrayList<>();
        files.add(b.pom());
        files.addAll(b.assets());
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