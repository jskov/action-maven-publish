package dk.mada.action.portal;

/**
 * The current repository state.
 *
 * @param state the current deployment state
 * @param info  any additional information
 */
public record RepositoryStateInfo(DeploymentState state, String info) {

    /**
     * Constructs an empty placeholder state (PENDING).
     *
     * @param info the information to include
     * @return an empty placeholder state
     */
    public static RepositoryStateInfo empty(String info) {
        return new RepositoryStateInfo(DeploymentState.PENDING, info);
    }

    /**
     * Constructs a failed placeholder state (FAILED)
     *
     * @param info the information to include
     * @return a failed placeholder state
     */
    public static RepositoryStateInfo failed(String info) {
        return new RepositoryStateInfo(DeploymentState.FAILED, info);
    }
}
