package dk.mada.action;

import dk.mada.action.BundleCollector.Bundle;
import dk.mada.action.PortalProxy.DeploymentState;
import dk.mada.action.PortalProxy.RepositoryStateInfo;

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
