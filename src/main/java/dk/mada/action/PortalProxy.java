package dk.mada.action;

import dk.mada.action.ActionArguments.PortalCredentials;
import dk.mada.action.BundleCollector.Bundle;
import dk.mada.action.util.EphemeralCookieHandler;
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
import java.util.stream.Collectors;

/**
 * A proxy for OSSRH web service.
 *
 * The first call will cause authentication which will provide a cookie token used for following calls.
 *
 * Note: this will have to change when the new Central Portal publishing goes live.
 */
public class PortalProxy {
    /** The base URL for OSSRH. */
    private static final String OSSRH_BASE_URL = "https://s01.oss.sonatype.org";
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
    /** The credentials to use for login to OSSRH. */
    private final PortalCredentials credentials;
    /** The http client. */
    private final HttpClient client;
    /** Flag for successful authentication with OSSRH. */
    private boolean isAuthenticated;
    /** The timeout to use when uploading bundles. */
    private final Duration uploadTimeout;
    /** The timeout to use for short calls. */
    private final Duration shortCallTimeout;

    /**
     * Constructs new instance.
     *
     * @param credentials the OSSRH credentials
     */
    public PortalProxy(PortalCredentials credentials) {
        this.credentials = credentials;

        uploadTimeout = Duration.ofSeconds(DEFAULT_UPLOAD_TIMEOUT_SECONDS);
        shortCallTimeout = Duration.ofSeconds(DEFAULT_SHORT_CALL_TIMEOUT_SECONDS);

        CookieHandler cookieHandler = EphemeralCookieHandler.newAcceptAll();
        client = HttpClient.newBuilder()
                .followRedirects(Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(CONNECTION_TIMEOUT_SECONDS))
                .cookieHandler(cookieHandler)
                .build();
    }

    /**
     * Gets response from OSSHR service.
     *
     * @param path    the url path to read from
     * @param headers headers to use (paired values)
     * @return the http response
     */
    public HttpResponse<String> get(String path, String... headers) {
        authenticate();
        return doGet(path, headers);
    }

    /**
     * Uploads bundle.
     *
     * @param bundle the bundle to upload
     * @return the http response
     */
    public HttpResponse<String> uploadBundle(Bundle bundle) {
        authenticate();
        return doUpload(bundle.bundleJar());
    }

    /**
     * Run action on staging repositories.
     *
     * Used to drop and release repositories. The path decides the action.
     *
     * @param path    the url path to push to
     * @param repoIds a list of repositories IDs to include in the payload
     * @return the http response
     */
    public HttpResponse<String> stagingAction(String path, List<String> repoIds) {
        String idList = repoIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));

        String json = "{\"data\":{\"stagedRepositoryIds\":[" + idList + "]}}";

        authenticate();
        return doPost(path, json);
    }

    /**
     * Authenticate with the server which will provide a cookie used in the remaining calls.
     */
    private void authenticate() {
        if (isAuthenticated) {
            return;
        }

        HttpResponse<String> response =
                doGet("/service/local/authentication/login", "Authorization", credentials.asBasicAuth());
        if (response.statusCode() != HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException("Failed authenticating: " + response.body());
        }
        isAuthenticated = true;
    }

    /**
     * Gets data from a OSSRH path.
     *
     * @param path    the url path to read from
     * @param headers headers to use (paired values)
     * @return the http response
     */
    private HttpResponse<String> doGet(String path, String... headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(OSSRH_BASE_URL + path))
                    .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                    .headers(USER_AGENT);
            if (headers.length > 0) {
                builder.headers(headers);
            }
            HttpRequest request = builder.GET().build();
            return client.send(request, BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading from " + path, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed while reading from " + path, e);
        }
    }

    /**
     * Posts json payload message.
     *
     * @param path the path to post to
     * @param json the json payload to push
     * @return the http response
     */
    private HttpResponse<String> doPost(String path, String json) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OSSRH_BASE_URL + path))
                    .timeout(shortCallTimeout)
                    .headers(USER_AGENT)
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(json))
                    .build();
            return client.send(request, BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while posting to " + path + " : " + json, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed while posting to " + path + " : " + json, e);
        }
    }

    /**
     * Uploads file to OSSRH using POST multipart/form-data.
     *
     * @see https://www.w3.org/TR/html4/interact/forms.html#h-17.13.4.2
     *
     * @param bundle the bundle file to upload
     * @return the http response
     */
    private HttpResponse<String> doUpload(Path bundle) {
        try {
            // As per the MIME spec, the marker should be ASCII and not match anything in the encapsulated sections.
            // Just using a random string (similar to the one in the forms spec).
            String boundaryMarker = "AaB03xyz30BaA";
            String mimeNewline = "\r\n";
            String formIntro = ""
                    + "--" + boundaryMarker + mimeNewline
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + bundle.getFileName() + "\""
                    + mimeNewline
                    + "Content-Type: " + Files.probeContentType(bundle) + mimeNewline
                    + mimeNewline; // (empty line between form instructions and the data)

            String formOutro = ""
                    + mimeNewline // for the binary data
                    + "--" + boundaryMarker + "--" + mimeNewline;

            BodyPublisher body = BodyPublishers.concat(
                    BodyPublishers.ofString(formIntro),
                    BodyPublishers.ofFile(bundle),
                    BodyPublishers.ofString(formOutro));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OSSRH_BASE_URL + "/service/local/staging/bundle_upload"))
                    .timeout(uploadTimeout)
                    .headers(USER_AGENT)
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
}
