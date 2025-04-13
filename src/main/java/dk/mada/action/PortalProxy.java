package dk.mada.action;

import dk.mada.action.ActionArguments.OssrhCredentials;
import dk.mada.action.BundleCollector.Bundle;
import dk.mada.action.PortalProxy.RepositoryStateInfo;
import dk.mada.action.util.EphemeralCookieHandler;
import dk.mada.action.util.JsonExtractor;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * A proxy for Maven Central <a href="https://central.sonatype.org/publish/publish-portal-api/">Repository Portal Publisher API</a> web service.
 *
 * The first call will cause authentication which will provide a cookie token used for following calls.
 *
 * Note: this will have to change when the new Central Portal publishing goes live.
 */
public class PortalProxy {
    /** The base URL for the Publisher Api. */
    private static final String PUBLISHER_API_BASE_URL = "https://central.sonatype.com";
    /** The resource path for uploading a bundle. */
    private static final String UPLOAD_RESOURCE_PATH = "/api/v1/publisher/upload";
    /** The resource path for getting status of a bundle deployment. */
    private static final String STATUS_RESOURCE_PATH = "/api/v1/publisher/status";
    /** The resource path for publishing/dropping a bundle. */
    private static final String DEPLOYMENT_RESOURCE_PATH = "/api/v1/publisher/deployment";

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
     * The deployment status from the Publisher API
     */
    public enum DeploymentState {
        /** A deployment is uploaded and waiting for processing by the validation service. */
        PENDING(true),
        /** A deployment is being processed by the validation service. */
        VALIDATING(true),
        /** A deployment has passed validation and is waiting on a user to manually publish via the Central Portal UI. */
        VALIDATED(false),
        /** A deployment has been either automatically or manually published and is being uploaded to Maven Central. */
        PUBLISHING(true),
        /** A deployment has successfully been uploaded to Maven Central. */
        PUBLISHED(false),
        /** A deployment has encountered an error (additional context will be present in an errors field). */
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

    /**
     * The current repository state.
     *
     * @param state the current deployment state
     * @param info any additional information
     */
    public record RepositoryStateInfo(DeploymentState state, String info) {

        public static RepositoryStateInfo empty(String info) {
            return new RepositoryStateInfo(DeploymentState.PENDING, info);
        }

        public static RepositoryStateInfo failed(String info) {
            return new RepositoryStateInfo(DeploymentState.FAILED, info);
        }
    }

    /**
     * Constructs new instance.
     *
     * @param credentials the Portal credentials
     */
    public PortalProxy(OssrhCredentials credentials) {
        uploadTimeout = Duration.ofSeconds(DEFAULT_UPLOAD_TIMEOUT_SECONDS);
        shortCallTimeout = Duration.ofSeconds(DEFAULT_SHORT_CALL_TIMEOUT_SECONDS);
        authorizationHeader = new String[] {"Authorization", credentials.asAuthenticationValue()};

        CookieHandler cookieHandler = EphemeralCookieHandler.newAcceptAll();
        client = HttpClient.newBuilder()
                .followRedirects(Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(CONNECTION_TIMEOUT_SECONDS))
                .cookieHandler(cookieHandler)
                .build();
    }

    public RepositoryStateInfo getDeploymentStatus(String deploymentId) {
        HttpResponse<String> response = get(STATUS_RESOURCE_PATH + "?" + deploymentId);
        int status = response.statusCode();
        String body = response.body();

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
     * @see https://www.w3.org/TR/html4/interact/forms.html#h-17.13.4.2
     *
     * @param bundle the bundle file to upload
     * @return the http response
     */
    public HttpResponse<String> uploadBundle(Bundle bundle) {
        Path file = bundle.bundleJar();
        try {
            // As per the MIME spec, the marker should be ASCII and not match anything in the encapsulated sections.
            // Just using a random string (similar to the one in the forms spec).
            String boundaryMarker = "AaB03xyz30BaA";
            String mimeNewline = "\r\n";
            String formIntro = ""
                    + "--" + boundaryMarker + mimeNewline
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getFileName() + "\""
                    + mimeNewline
                    + "Content-Type: " + Files.probeContentType(file) + mimeNewline
                    + mimeNewline; // (empty line between form instructions and the data)

            String formOutro = ""
                    + mimeNewline // for the binary data
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
            return client.send(request, BodyHandlers.ofString());
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
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(PUBLISHER_API_BASE_URL + path))
                    .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                    .headers(USER_AGENT)
                    .headers(authorizationHeader);
            if (headers.length > 0) {
                builder.headers(headers);
            }
            HttpRequest request = builder.GET().build();
            return client.send(request, BodyHandlers.ofString());
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
            return client.send(request, BodyHandlers.ofString());
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
            return client.send(request, BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while DELETE to " + path, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed while DELETE to " + path, e);
        }
    }
}
