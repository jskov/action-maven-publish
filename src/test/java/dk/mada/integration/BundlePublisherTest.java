package dk.mada.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dk.mada.action.ActionArguments.TargetAction;
import dk.mada.action.BundleCollector;
import dk.mada.action.BundleCollector.Bundle;
import dk.mada.action.BundlePublisher;
import dk.mada.action.BundlePublisher.ExecutedAction;
import dk.mada.action.BundlePublisher.PublishingResult;
import dk.mada.action.util.LoggerConfig;
import dk.mada.fixture.BundlePrepperFixture;
import dk.mada.fixture.TestInstances;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * The operations against Portal require credentials, so these can only be tested if
 * you provide credentials.
 *
 * Do that via a file containing user:token and point to it via the environment variable
 * PORTAL_CREDENTIALS_PATH.
 */
@EnabledIfEnvironmentVariable(
        named = "PORTAL_CREDENTIALS_PATH",
        matches = ".*",
        disabledReason = "Only runs when provided with credentials")
public class BundlePublisherTest {
    private static Logger logger = Logger.getLogger(BundlePublisherTest.class.getName());

    /** Temporary directory to use for the test data. */
    @TempDir(cleanup = CleanupMode.ALWAYS)
    private Path workDir;

    /**
     * Tests that a pom bundle can be published and dropped (without causing an explosion).
     */
    @Test
    void canPublishAndDrop() throws IOException {
        LoggerConfig.loadConfig("/test-logging.properties");
        logger.info("Bundle built in " + workDir);

        String pomName = "action-maven-publish-test-0.0.0.pom";
        BundlePrepperFixture.addTestPomFiles(workDir, pomName);

        BundleCollector bundleCollector = TestInstances.bundleCollector();
        List<Bundle> bundles = bundleCollector.collectBundles(workDir, List.of());

        BundlePublisher sut = TestInstances.bundlePublisher();

        PublishingResult result = sut.publish(bundles, TargetAction.DROP);

        // The publish operation will now always results in failure since the artifacts
        // are expected to be signed by a known certificate (and they are not).
        assertThat(result.finalStates()).isNotEmpty();
        assertThat(result.allReposValid()).isFalse();
        assertThat(result.executedAction()).isEqualTo(ExecutedAction.DROPPED);
    }
}
