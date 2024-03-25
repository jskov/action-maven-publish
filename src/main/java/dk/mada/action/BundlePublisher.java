package dk.mada.action;

import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import dk.mada.action.BundleCollector.Bundle;
import dk.mada.action.util.XmlExtractor;

/**
 * Publishes bundles and follow their state until stable.
 */
public class BundlePublisher {
    private static Logger logger = Logger.getLogger(BundlePublisher.class.getName());

    /** The expected prefix in response when creating new repository. */
    private static final String RESPONSE_REPO_URI_PREFIX = "{\"repositoryUris\":[\"https://s01.oss.sonatype.org/content/repositories/";
    /** Dummy id for unassigned repository. */
    private static final String REPO_ID_UNASSIGNED = "_unassigned_";
    /** The OSSRH proxy. */
    private final OssrhProxy proxy;
    /** The initial timeout to use for each bundle. */
    private final Duration initialProcessingPause;
    /** The timeout to use in each loop after the initial delay. */
    private final Duration loopPause;

    public BundlePublisher(OssrhProxy proxy) {
        this.proxy = proxy;
        initialProcessingPause = Duration.ofSeconds(120);
        loopPause = Duration.ofSeconds(15);
    }

    /**
     * The action to apply on the repositories when they have settled.
     */
    public enum TargetAction {
        /** Drop (delete). */
        DROP,
        /** Leave repositories - you can use the listed URLs for testing. You must drop manually. */
        LEAVE,
        /** Promote repositories if they all pass validation. Otherwise leave (so you can inspect and drop manually). */
        PROMOTE_OR_LEAVE
    }

    public List<BundleRepositoryState> publish(List<Bundle> bundles, TargetAction action) {
        List<BundleRepositoryState> initialBundleStates = bundles.stream()
                .map(this::uploadBundle)
                .toList();

        logger.info(() -> "Uploaded bundles:\n" + makeSummary(initialBundleStates));

        logger.info("Waiting for repositories to settle...");
        List<BundleRepositoryState> finalBundleStates = waitForRepositoriesToSettle(initialBundleStates);

        logger.info(() -> "Processed bundles:\n" + makeSummary(finalBundleStates));

        List<String> repoIds = finalBundleStates.stream()
                .map(brs -> brs.assignedId)
                .toList();

        boolean allSucceeded = finalBundleStates.stream()
                .allMatch(brs -> brs.status() == Status.VALIDATED);

        if (action == TargetAction.LEAVE
                || (action == TargetAction.PROMOTE_OR_LEAVE && !allSucceeded)) {
            logger.info("Leaving repositories");
            if (!allSucceeded) {
                logger.warning("NOTICE: not all repositories validated successfully!");
            }
            return finalBundleStates;
        }

        if (action == TargetAction.DROP) {
            logger.info("Dropping repositories...");
            proxy.stagingAction("/service/local/staging/bulk/drop", repoIds);
        } else {
            logger.info("Publishing repositories...");
            logger.warning("TODO: promote");
            // https://s01.oss.sonatype.org/service/local/staging/bulk/promote
        }

        logger.info("Done");
        return finalBundleStates;
    }
    // FIXME: include maven repo paths (repositoryURI from status)

    private String makeSummary(List<BundleRepositoryState> initialBundleStates) {
        return " " + initialBundleStates.stream()
                .map(bs -> bs.bundle().bundleJar().getFileName() + " repo:" + bs.assignedId() + ", status: " + bs.status)
                .collect(Collectors.joining("\n "));
    }

    private BundleRepositoryState uploadBundle(Bundle bundle) {
        HttpResponse<String> response = proxy.uploadBundle(bundle);
        return extractRepoId(bundle, response);
    }

    private List<BundleRepositoryState> waitForRepositoriesToSettle(List<BundleRepositoryState> bundleStates) {
        int waitMillis = (int) initialProcessingPause.toMillis() * bundleStates.size();
        List<BundleRepositoryState> updatedStates = bundleStates;
        do {
            sleep(waitMillis);
            updatedStates = updatedStates.stream()
                    .map(this::updateRepoState)
                    .toList();

            // Pause period for next loop depending on how
            // many bundles are actively being processed
            long stillActive = updatedStates.stream()
                    .filter(rs -> rs.status().isTransitioning())
                    .count();
            waitMillis = (int) (loopPause.toMillis() * stillActive);
        } while (updatedStates.stream().anyMatch(rs -> rs.status().isTransitioning()));

        return updatedStates;
    }

    private void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for repository state change", e);
        }
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

    private record RepositoryStateInfo(int notifications, boolean transitioning, String info) {
    }

    // TODO: get status update time
    private RepositoryStateInfo parseRepositoryState(HttpResponse<String> response) {
        int status = response.statusCode();
        String body = response.body();
        if (status != HttpURLConnection.HTTP_OK) {
            return new RepositoryStateInfo(-1, false, "Failed repository probe; status: " + status + ", message: " + body);
        }
        XmlExtractor xe = new XmlExtractor(body);
        return new RepositoryStateInfo(xe.getInt("notifications"), xe.getBool("transitioning"), body);
    }

    public enum Status {
        // Terminal states
        FAILED_UPLOAD,
        FAILED_VALIDATION,
        UPLOADED,
        VALIDATED;

        boolean isTransitioning() {
            return this == UPLOADED || this == VALIDATED;
        }
    }

    /**
     * The bundle's repository state.
     *
     * @param bundle          the bundle
     * @param status          the current repository status
     * @param assignedInd     the assigned repository id
     * @param latestStateInfo the latest returned state information (note, may be from emptyStateInfo())
     */
    public record BundleRepositoryState(Bundle bundle, Status status, String assignedId, RepositoryStateInfo latestStateInfo) {
    }

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
            return new BundleRepositoryState(bundle, Status.FAILED_UPLOAD, REPO_ID_UNASSIGNED,
                    emptyStateInfo("Upload status: " + status + ", message: " + body));
        }
    }

    private RepositoryStateInfo emptyStateInfo(String info) {
        return new RepositoryStateInfo(-1, false, info);
    }
}
