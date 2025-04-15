package dk.mada.action.portal;

/**
 * The deployment status from the Publisher Portal API
 */
public enum DeploymentState {
    /**
     * A deployment is uploaded and waiting for processing by the validation
     * service.
     */
    PENDING(true),
    /** A deployment is being processed by the validation service. */
    VALIDATING(true),
    /**
     * A deployment has passed validation and is waiting on a user to manually
     * publish via the Central Portal UI.
     */
    VALIDATED(false),
    /**
     * A deployment has been either automatically or manually published and is being
     * uploaded to Maven Central.
     */
    PUBLISHING(true),
    /** A deployment has successfully been uploaded to Maven Central. */
    PUBLISHED(false),
    /**
     * A deployment has encountered an error (additional context will be present in
     * an errors field).
     */
    FAILED(false);

    /** Flag for deployment process still transitioning. */
    private boolean transitioning;

    DeploymentState(boolean transitioning) {
        this.transitioning = transitioning;
    }

    /** {@return true if the deployment process is still transitioning} */
    public boolean isTransitioning() {
        return transitioning;
    }
}
