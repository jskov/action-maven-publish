package dk.mada.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dk.mada.action.BundleCollector;
import dk.mada.action.BundleCollector.Bundle;
import dk.mada.action.BundlePublisher;
import dk.mada.action.BundlePublisher.TargetAction;
import dk.mada.fixture.TestInstances;

/**
 * The operations against OSSRH require credentials, so these can only be tested locally.
 *
 * If you want to run the tests yourself (after reviewing the code, naturally), see
 * ActionArgumentsFixture:readOssrhCreds for how to provide the credentials.
 */
public class BundlePublisherTest {
    @TempDir
    Path workDir;

    @Test
    void canGo() throws IOException {
        String pomName = "action-maven-publish-test-0.0.0.pom";
        Files.copy(Paths.get("src/test/data").resolve(pomName), workDir.resolve(pomName));

        BundleCollector bundleCollector = TestInstances.bundleCollector();
        List<Bundle> bundles = bundleCollector.collectBundles(workDir, List.of());

        BundlePublisher sut = TestInstances.bundlePublisher();

        sut.publish(bundles, TargetAction.LEAVE);
    }
}