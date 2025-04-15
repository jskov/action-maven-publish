package dk.mada.action.portal;

import dk.mada.action.ActionArguments.PortalCredentials;
import dk.mada.action.BundleCollector.Bundle;
import dk.mada.action.BundleRepositoryState;
import dk.mada.action.util.JsonExtractor;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

/**
 * A proxy for Maven Central
 * <a href="https://central.sonatype.org/publish/publish-portal-api/">Repository
 * Portal Publisher API</a> web service.
 */
public final class PortalProxy {
    private static Logger logger = Logger.getLogger(PortalProxy.class.getName());

    /** The base URL for the Publisher Api. */
    private static final String PUBLISHER_API_BASE_URL = "https://central.sonatype.com";
    /** The resource path for uploading a bundle. */
    private static final String UPLOAD_RESOURCE_PATH =
            "/api/v1/publisher/upload"; // NOSONAR - not concerned with Android configuration
    /** The resource path for getting status of a bundle deployment. */
    private static final String STATUS_RESOURCE_PATH =
            "/api/v1/publisher/status"; // NOSONAR - not concerned with Android configuration
    /** The resource path for publishing/dropping a bundle. */
    private static final String DEPLOYMENT_RESOURCE_PATH =
            "/api/v1/publisher/deployment"; // NOSONAR - not concerned with Android configuration
    /** Dummy id for unassigned repository. */
    private static final String REPO_ID_UNASSIGNED = "_unassigned_";

    /** The User Agent used by the proxy calls. */
    private static final String[] USER_AGENT = new String[] {"User-Agent", "jskov_action-maven-publish"};
    /** Default timeout for uploading an artifact. */
    private static final int DEFAULT_UPLOAD_TIMEOUT_SECONDS = 90;
    /** Default timeout for short remote calls. */
    private static final int DEFAULT_SHORT_CALL_TIMEOUT_SECONDS = 30;
    /** Connection timeout. */
    private static final int CONNECTION_TIMEOUT_SECONDS = 10;
    /** Download timeout. */
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 30;
    /** The http client. */
    private final HttpClient client;
    /** The timeout to use when uploading bundles. */
    private final Duration uploadTimeout;
    /** The timeout to use for short calls. */
    private final Duration shortCallTimeout;
    /** The authorization header. */
    private final String[] authorizationHeader;

    /**
     * Constructs new instance.
     *
     * @param credentials the Portal credentials
     */
    public PortalProxy(PortalCredentials credentials) {
        uploadTimeout = Duration.ofSeconds(DEFAULT_UPLOAD_TIMEOUT_SECONDS);
        shortCallTimeout = Duration.ofSeconds(DEFAULT_SHORT_CALL_TIMEOUT_SECONDS);
        authorizationHeader = new String[] {"Authorization", credentials.asAuthenticationValue()};

        client = HttpClient.newBuilder()
                .followRedirects(Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(CONNECTION_TIMEOUT_SECONDS))
                .build();
    }

    /**
     * Get the repository deployment state,
     *
     * @param deploymentId the deployment id to query
     * @return the repository state
     */
    public RepositoryStateInfo getDeploymentStatus(String deploymentId) {
        HttpResponse<String> response = get(STATUS_RESOURCE_PATH + "?id=" + deploymentId);
        int status = response.statusCode();
        String body = response.body();

        logger.fine(() -> "Status response (" + status + "): " + body);

        if (status != HttpURLConnection.HTTP_OK) {
            return new RepositoryStateInfo(
                    DeploymentState.FAILED, "Failed repository probe; status: " + status + ", message: " + body);
        }

        try {
            var data = new JsonExtractor(body);
            DeploymentState state = DeploymentState.valueOf(data.get("deploymentState"));
            return new RepositoryStateInfo(state, "");
        } catch (Exception e) {
            return new RepositoryStateInfo(DeploymentState.FAILED, "Failed parsing response  message: " + body);
        }
    }

    /**
     * Gets response from the service.
     *
     * @param path    the url path to read from
     * @param headers headers to use (paired values)
     * @return the http response
     */
    private HttpResponse<String> get(String path, String... headers) {
        return doGet(path, headers);
    }

    /**
     * Uploads file to Portal using POST multipart/form-data.
     *
     * @see <a href="https://www.w3.org/TR/html4/interact/forms.html#h-17.13.4.2">HTLM forms spec</a>
     *
     * @param bundle the bundle file to upload
     * @return the http response
     */
    public BundleRepositoryState uploadBundle(Bundle bundle) {
        Path file = bundle.bundleJar();
        try {
            // As per the MIME spec, the marker should be ASCII and not match anything in
            // the encapsulated sections.
            // Just using a random string (similar to the one in the forms spec).
            String boundaryMarker = "AaB03xyz30BaA";
            String mimeNewline = "\r\n";
            String formIntro = "" + "--" + boundaryMarker + mimeNewline
                    + "Content-Disposition: form-data; name=\"bundle\"; filename=\"" + file.getFileName() + "\""
                    + mimeNewline + "Content-Type: " + "application/octet-stream" + mimeNewline
                    // (empty line between form instructions and the data)
                    + mimeNewline;

            String formOutro = "" + mimeNewline // for the binary data
                    + "--" + boundaryMarker + "--" + mimeNewline;

            BodyPublisher body = BodyPublishers.concat(
                    BodyPublishers.ofString(formIntro),
                    BodyPublishers.ofFile(file),
                    BodyPublishers.ofString(formOutro));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PUBLISHER_API_BASE_URL + UPLOAD_RESOURCE_PATH))
                    .timeout(uploadTimeout)
                    .headers(USER_AGENT)
                    .headers(authorizationHeader)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundaryMarker)
                    .POST(body)
                    .build();

            logCall(request);
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            logResponse(response);

            int status = response.statusCode();
            String responseBody = response.body();

            if (status == HttpURLConnection.HTTP_CREATED) {
                String repoId = responseBody;
                return new BundleRepositoryState(bundle, repoId, RepositoryStateInfo.empty("Assigned id: " + repoId));
            } else {
                return new BundleRepositoryState(
                        bundle,
                        REPO_ID_UNASSIGNED,
                        RepositoryStateInfo.failed(
                                "Failed to upload bundle (" + status + "), message: " + responseBody));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while uploading bundle " + bundle, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed while uploading bundle " + bundle, e);
        }
    }

    /**
     * Publish staging repositories.
     *
     * @param repoIds a list of repositories IDs to publish
     */
    public void publishRepositories(List<String> repoIds) {
        for (String id : repoIds) {
            HttpResponse<String> response = doPost(DEPLOYMENT_RESOURCE_PATH + "/" + id);
            if (response.statusCode() != HttpURLConnection.HTTP_NO_CONTENT) {
                throw new IllegalStateException(
                        "Publishing " + id + " returned " + response.statusCode() + " : " + response.body());
            }
        }
    }

    /**
     * Drop staging repositories.
     *
     * @param repoIds a list of repositories IDs to drop
     */
    public void dropRepositories(List<String> repoIds) {
        for (String id : repoIds) {
            HttpResponse<String> response = doDelete(DEPLOYMENT_RESOURCE_PATH + "/" + id);
            if (response.statusCode() != HttpURLConnection.HTTP_NO_CONTENT) {
                throw new IllegalStateException(
                        "Dropping " + id + " returned " + response.statusCode() + " : " + response.body());
            }
        }
    }

    /**
     * Gets data from a Portal path.
     *
     * @param path    the url path to read from
     * @param headers headers to use (paired values)
     * @return the http response
     */
    private HttpResponse<String> doGet(String path, String... headers) {
        try {
            URI uri = URI.create(PUBLISHER_API_BASE_URL + path);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                    .headers(USER_AGENT)
                    .headers(authorizationHeader);
            if (headers.length > 0) {
                builder.headers(headers);
            }
            HttpRequest request = builder.GET().build();

            logCall(request);
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            logResponse(response);

            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while GET from " + path, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed while GET from " + path, e);
        }
    }

    /**
     * Posts to resource.
     *
     * @param path the path to post to
     * @return the http response
     */
    private HttpResponse<String> doPost(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PUBLISHER_API_BASE_URL + path))
                    .timeout(shortCallTimeout)
                    .headers(USER_AGENT)
                    .headers(authorizationHeader)
                    .POST(BodyPublishers.noBody())
                    .build();

            logCall(request);
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            logResponse(response);

            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while POST to " + path, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed while POST to " + path, e);
        }
    }

    /**
     * Delete to resource.
     *
     * @param path the path to delete to
     * @return the http response
     */
    private HttpResponse<String> doDelete(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PUBLISHER_API_BASE_URL + path))
                    .timeout(shortCallTimeout)
                    .headers(USER_AGENT)
                    .headers(authorizationHeader)
                    .DELETE()
                    .build();

            logCall(request);
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            logResponse(response);

            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while DELETE to " + path, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed while DELETE to " + path, e);
        }
    }

    /**
     * Logs a REST call.
     *
     * @param request the request to log
     */
    private void logCall(HttpRequest request) {
        logger.fine(() -> "Calling " + request.method() + " on " + request.uri());
    }

    /**
     * Logs a REST response.
     *
     * @param response the response to log
     */
    private void logResponse(HttpResponse<String> response) {
        logger.fine(() -> "Response " + response.statusCode() + " body " + response.body());
    }
}
