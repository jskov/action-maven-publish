package dk.mada.action;

import dk.mada.action.ActionArguments.TargetAction;
import dk.mada.action.BundleCollector.Bundle;
import dk.mada.action.util.XmlExtractor;
import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
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

    /** The expected prefix in response when creating new repository. */
    private static final String RESPONSE_REPO_URI_PREFIX =
            "{\"repositoryUris\":[\"https://s01.oss.sonatype.org/content/repositories/";
    /** Dummy id for unassigned repository. */
    private static final String REPO_ID_UNASSIGNED = "_unassigned_";
    /** The OSSRH proxy. */
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
                bundles.stream().map(this::uploadBundle).toList();

        logger.info(() -> "Uploaded bundles:\n" + makeSummary(initialBundleStates));

        logger.info(() -> "Waiting for " + bundles.size() + " repositories to settle...");
        List<BundleRepositoryState> finalBundleStates = waitForRepositoriesToSettle(initialBundleStates);

        logger.info(() -> "Processed bundles:\n" + makeSummary(finalBundleStates));

        List<String> repoIds =
                finalBundleStates.stream().map(brs -> brs.assignedId).toList();

        boolean allSucceeded = finalBundleStates.stream().allMatch(brs -> brs.status() == Status.VALIDATED);

        ExecutedAction executedAction;
        if (allSucceeded && action == TargetAction.PROMOTE_OR_KEEP) {
            logger.info("Promoting repositories...");
            proxy.stagingAction("/service/local/staging/bulk/promote", repoIds);
            executedAction = ExecutedAction.PROMOTED;
        } else if (action == TargetAction.DROP) {
            logger.info("Dropping repositories...");
            proxy.stagingAction("/service/local/staging/bulk/drop", repoIds);
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
        return " "
                + initialBundleStates.stream()
                        .map(bs -> bs.bundle().bundleJar().getFileName() + " repo:" + bs.assignedId() + ", status: "
                                + bs.status)
                        .collect(Collectors.joining("\n "));
    }

    private BundleRepositoryState uploadBundle(Bundle bundle) {
        HttpResponse<String> response = proxy.uploadBundle(bundle);
        return extractRepoId(bundle, response);
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
            long bundlesInTransition = updatedStates.stream()
                    .filter(rs -> rs.status().isTransitioning())
                    .count();
            delay = loopPause.multipliedBy(bundlesInTransition);
            logger.info(() -> " (" + bundlesInTransition + " bundles still processing)");

            keepWaiting = bundlesInTransition > 0;
        } while (keepWaiting);

        return updatedStates;
    }

    private BundleRepositoryState updateRepoState(BundleRepositoryState currentState) {
        if (!currentState.status.isTransitioning()) {
            return currentState;
        }

        String repoId = currentState.assignedId;
        HttpResponse<String> response = proxy.get("/service/local/staging/repository/" + repoId);
        RepositoryStateInfo repoState = parseRepositoryState(response);

        Status newStatus;
        if (repoState.transitioning) {
            newStatus = currentState.status();
        } else {
            if (repoState.notifications == 0) {
                newStatus = Status.VALIDATED;
            } else {
                newStatus = Status.FAILED_VALIDATION;
            }
        }
        return new BundleRepositoryState(currentState.bundle(), newStatus, currentState.assignedId(), repoState);
    }

    private record RepositoryStateInfo(int notifications, boolean transitioning, String info) {}

    private RepositoryStateInfo parseRepositoryState(HttpResponse<String> response) {
        int status = response.statusCode();
        String body = response.body();
        if (status != HttpURLConnection.HTTP_OK) {
            return new RepositoryStateInfo(
                    -1, false, "Failed repository probe; status: " + status + ", message: " + body);
        }
        XmlExtractor xe = new XmlExtractor(body);
        return new RepositoryStateInfo(xe.getInt("notifications"), xe.getBool("transitioning"), body);
    }

    /**
     * OSSRH repository states.
     */
    public enum Status {
        /** The upload failed. Terminal state. */
        FAILED_UPLOAD,
        /** The bundle was uploaded. Should transition to FAILED_VALIDATION or VALIDATED. */
        UPLOADED,
        /** The validation failed. Terminal state. */
        FAILED_VALIDATION,
        /** The validation succeeded. Terminal state. */
        VALIDATED;

        boolean isTransitioning() {
            return this == UPLOADED;
        }
    }

    /**
     * The bundle's repository state.
     *
     * @param bundle          the bundle
     * @param status          the current repository status
     * @param assignedId      the assigned repository id
     * @param latestStateInfo the latest returned state information (note, may be from emptyStateInfo())
     */
    public record BundleRepositoryState(
            Bundle bundle, Status status, String assignedId, RepositoryStateInfo latestStateInfo) {}

    /**
     * Crudely extracts the assigned repository id from returned JSON.
     *
     * @param bundle   the bundle that was uploaded
     * @param response the HTTP response from OSRRH
     * @return the resulting bundle repository state
     */
    private BundleRepositoryState extractRepoId(Bundle bundle, HttpResponse<String> response) {
        int status = response.statusCode();
        String body = response.body();

        if (status == HttpURLConnection.HTTP_CREATED && body.startsWith(RESPONSE_REPO_URI_PREFIX)) {
            String repoId = body.substring(RESPONSE_REPO_URI_PREFIX.length()).replace("\"]}", "");
            return new BundleRepositoryState(bundle, Status.UPLOADED, repoId, emptyStateInfo("Assigned id: " + repoId));
        } else {
            return new BundleRepositoryState(
                    bundle,
                    Status.FAILED_UPLOAD,
                    REPO_ID_UNASSIGNED,
                    emptyStateInfo("Upload status: " + status + ", message: " + body));
        }
    }

    private RepositoryStateInfo emptyStateInfo(String info) {
        return new RepositoryStateInfo(-1, false, info);
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
