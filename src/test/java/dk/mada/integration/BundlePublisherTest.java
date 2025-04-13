package dk.mada.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import dk.mada.action.BundleCollector;
import dk.mada.action.BundleCollector.Bundle;
import dk.mada.action.BundlePublisher;
import dk.mada.action.BundlePublisher.ExecutedAction;
import dk.mada.action.BundlePublisher.PublishingResult;
import dk.mada.action.BundlePublisher.TargetAction;
import dk.mada.fixture.TestInstances;

/**
 * The operations against Portal require credentials, so these can only be tested if
 * you provide credentials.
 *
 * Do that via a file containing user:token and point to it via the environment variable 
 * PORTAL_CREDENTIALS_PATH.
 */
@EnabledIfEnvironmentVariable(named = "PORTAL_CREDENTIALS_PATH", matches = ".*", disabledReason = "Only runs when provided with credentials")
public class BundlePublisherTest {
    /** Temporary directory to use for the test data. */
    @TempDir
    private Path workDir;

    /**
     * Tests that a pom bundle can be published and dropped (without causing an explosion).
     */
    @Test
    void canPublishAndDrop() throws IOException {
        String pomName = "action-maven-publish-test-0.0.0.pom";
        Files.copy(Paths.get("src/test/data").resolve(pomName), workDir.resolve(pomName));

        BundleCollector bundleCollector = TestInstances.bundleCollector();
        List<Bundle> bundles = bundleCollector.collectBundles(workDir, List.of());

        BundlePublisher sut = TestInstances.bundlePublisher();

        PublishingResult result = sut.publish(bundles, TargetAction.DROP);

        assertThat(result.finalStates()).isNotEmpty();
        assertThat(result.allReposValid()).isFalse();
        assertThat(result.executedAction()).isEqualTo(ExecutedAction.DROPPED);
    }
}
