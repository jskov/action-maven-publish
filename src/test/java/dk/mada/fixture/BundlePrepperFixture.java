package dk.mada.fixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Fixture for preparing a valid POM file and its checksums.
 */
public final class BundlePrepperFixture {
    /** Constructs new instance. */
    private BundlePrepperFixture() {
        // empty
    }

    /**
     * Add test POM files to directory.
     *
     * @param dir the target directory
     * @throws IOException if there is a failure when copying the files
     */
    public static void addTestPomFiles(Path dir, String pomName) throws IOException {
        Path srcDir = Paths.get("src/test/data");
        String srcPomName = "action-maven-publish-test-0.0.0.pom";
        Files.copy(srcDir.resolve(srcPomName), dir.resolve(pomName));
        Files.copy(srcDir.resolve(srcPomName + ".sha1"), dir.resolve(pomName + ".sha1"));
        Files.copy(srcDir.resolve(srcPomName + ".md5"), dir.resolve(pomName + ".md5"));
    }
}
