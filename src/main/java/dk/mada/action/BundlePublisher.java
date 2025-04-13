package dk.mada.action;

import dk.mada.action.BundleCollector.Bundle;
import dk.mada.action.PortalProxy.DeploymentState;
import dk.mada.action.PortalProxy.RepositoryStateInfo;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Uploads bundles and follow their state until stable. Then drops/keeps/publishes them.
 *
 * TODO: This class can do with some cleanup.
 */
public final class BundlePublisher {
    private static Logger logger = Logger.getLogger(BundlePublisher.class.getName());

    /** The Portal proxy. */
    private final PortalProxy proxy;
    /** The initial timeout to use for each bundle. */
    private final Duration initialProcessingPause;
    /** The timeout to use in each loop after the initial delay. */
    private final Duration loopPause;

    /**
     * Constructs a new instance.
     *
     * @param args  the action arguments
     * @param proxy the proxy to use for OSSRH access
     */
    public BundlePublisher(ActionArguments args, PortalProxy proxy) {
        this.proxy = proxy;
        initialProcessingPause = Duration.ofSeconds(args.initialPauseSeconds());
        loopPause = Duration.ofSeconds(args.loopPauseSeconds());
    }

    /**
     * The action to apply on the repositories when they have settled.
     */
    public enum TargetAction {
        /** Drop (delete). */
        DROP,
        /** Keep repositories - you can use the listed URLs for testing. You must drop manually. */
        KEEP,
        /** Promote repositories if they all pass validation. Otherwise keep (so you can inspect and drop manually). */
        PROMOTE_OR_KEEP
    }

    /**
     * Publishes bundles.
     *
     * First uploads the bundles and waits for them to settle. Then executes the desired (drop/keep/publish) action.
     *
     * @param bundles the bundles to publish
     * @param action  the action to take when the repositories have settled
     * @return the publishing result
     */
    public PublishingResult publish(List<Bundle> bundles, TargetAction action) {
        List<BundleRepositoryState> initialBundleStates =
                bundles.stream().map(proxy::uploadBundle).toList();

        logger.info(() -> "Uploaded bundles:\n" + makeSummary(initialBundleStates));

        logger.info(() -> "Waiting for " + bundles.size() + " repositories to settle...");
        List<BundleRepositoryState> finalBundleStates = waitForRepositoriesToSettle(initialBundleStates);

        logger.info(() -> "Processed bundles:\n" + makeSummary(finalBundleStates));

        List<String> repoIds =
                finalBundleStates.stream().map(brs -> brs.assignedId).toList();

        boolean allSucceeded = finalBundleStates.stream().allMatch(brs -> brs.status() == DeploymentState.VALIDATED);

        ExecutedAction executedAction;
        if (allSucceeded && action == TargetAction.PROMOTE_OR_KEEP) {
            logger.info("Promoting repositories...");
            proxy.publishRepositories(repoIds);
            executedAction = ExecutedAction.PROMOTED;
        } else if (action == TargetAction.DROP) {
            logger.info("Dropping repositories...");
            proxy.dropRepositories(repoIds);
            executedAction = ExecutedAction.DROPPED;
        } else {
            // TargetAction.KEEP *or* failure so promotion cannot happen
            logger.info("Keeping repositories");
            if (!allSucceeded) {
                logger.warning("NOTICE: not all repositories validated successfully!");
            }
            executedAction = ExecutedAction.KEPT;
        }

        logger.info("Done");
        return new PublishingResult(executedAction, allSucceeded, finalBundleStates);
    }

    /**
     * The repository action actually executed.
     */
    public enum ExecutedAction {
        /** The repositories were dropped. */
        DROPPED,
        /** The repositories were kept. */
        KEPT,
        /** The repositories were promoted. */
        PROMOTED
    }

    /**
     * The result of publishing.
     *
     * @param executedAction the action executed on the repositories
     * @param allReposValid  true if all repositories were valid
     * @param finalStates    the final states for the individual repositories
     */
    public record PublishingResult(
            ExecutedAction executedAction, boolean allReposValid, List<BundleRepositoryState> finalStates) {}

    // TODO: Should include maven repo paths (repositoryURI from status)
    private String makeSummary(List<BundleRepositoryState> initialBundleStates) {
        return " " + initialBundleStates.stream().map(this::summaryRepo).collect(Collectors.joining("\n "));
    }

    private String summaryRepo(BundleRepositoryState bs) {
        String summary =
                bs.bundle().bundleJar().getFileName() + " repo:" + bs.assignedId() + ", status: " + bs.status();
        if (bs.status() == DeploymentState.FAILED) {
            summary += " [" + bs.latestStateInfo().info() + "]";
        }
        return summary;
    }

    private List<BundleRepositoryState> waitForRepositoriesToSettle(List<BundleRepositoryState> bundleStates) {
        int bundleCount = bundleStates.size();
        Duration delay = initialProcessingPause.multipliedBy(bundleCount);
        boolean keepWaiting;
        List<BundleRepositoryState> updatedStates = bundleStates;
        do {
            String printDelay = delay.toString();
            logger.info(() -> " waiting " + printDelay + " for MavenCentral processing...");
            sleep(delay);
            updatedStates = updatedStates.stream().map(this::updateRepoState).toList();

            // Pause period for next loop depending on how
            // many bundles are actively being processed
            long bundlesInTransition =
                    updatedStates.stream().filter(rs -> rs.isTransitioning()).count();
            delay = loopPause.multipliedBy(bundlesInTransition);
            logger.info(() -> " (" + bundlesInTransition + " bundles still processing)");

            keepWaiting = bundlesInTransition > 0;
        } while (keepWaiting);

        return updatedStates;
    }

    private BundleRepositoryState updateRepoState(BundleRepositoryState currentState) {
        if (!currentState.isTransitioning()) {
            return currentState;
        }

        String deploymentId = currentState.assignedId;
        RepositoryStateInfo repoState = proxy.getDeploymentStatus(deploymentId);

        return new BundleRepositoryState(currentState.bundle(), currentState.assignedId(), repoState);
    }

    /**
     * The bundle's repository state.
     *
     * @param bundle          the bundle
     * @param assignedId      the assigned repository id
     * @param latestStateInfo the latest returned state information
     */
    public record BundleRepositoryState(Bundle bundle, String assignedId, RepositoryStateInfo latestStateInfo) {

        /** {@return true if the latest repository state still is in transition} */
        public boolean isTransitioning() {
            return status().isTransitioning();
        }

        /** {@return the latest repository status} */
        public DeploymentState status() {
            return latestStateInfo.state();
        }
    }

    /**
     * Sleep for a bit. Handles interruption (by failing).
     *
     * @param duration the duration to sleep for
     */
    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for repository state change", e);
        }
    }
}
