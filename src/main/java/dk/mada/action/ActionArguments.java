package dk.mada.action;

import dk.mada.action.BundlePublisher.TargetAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * These are the arguments accepted by the action. Arguments are provided via environment variables.
 *
 * @param gpgCertificate      the private GPG certificate used for signing
 * @param searchDir           the directory to search for POM files
 * @param companionSuffixes   the companion suffixes to include when finding a POM file
 * @param logLevel            the logging level to use
 * @param ossrhCredentials    the OSSRH credentials
 * @param targetAction        the action to take after the bundles have been validated
 * @param initialPauseSeconds the pause (in seconds) to wait initially for each bundle
 * @param loopPauseSeconds    the pause (in seconds) to wait in each loop for each bundle still being processed
 */
public record ActionArguments(
        GpgCertificate gpgCertificate,
        Path searchDir,
        List<String> companionSuffixes,
        Level logLevel,
        OssrhCredentials ossrhCredentials,
        TargetAction targetAction,
        long initialPauseSeconds,
        long loopPauseSeconds) {

    /** Argument validation. */
    public ActionArguments {
        Objects.requireNonNull(gpgCertificate, "The GPG certificate must be specified");
        Objects.requireNonNull(searchDir, "The search directory must be specified");
        Objects.requireNonNull(companionSuffixes, "The companion suffixes must not be null");
        Objects.requireNonNull(logLevel, "The logging level must not be null");
        Objects.requireNonNull(targetAction, "The target action must not be null");

        if (!Files.isDirectory(searchDir)) {
            throw new IllegalArgumentException("Not a directory: " + searchDir);
        }
    }

    @Override
    public String toString() {
        // Note: no secrets
        return "ActionArguments [searchDir=" + searchDir + ", companionSuffixes=" + companionSuffixes + ", logLevel="
                + logLevel + ", targetAction=" + targetAction + "]";
    }

    /**
     * The GPG private certificate used for signing.
     *
     * @param key    the private GPG key used for signing
     * @param secret the secret for the private GPG key
     */
    public record GpgCertificate(String key, String secret) {
        /** The PGP header expected to be in the GPG key. */
        private static final String BEGIN_PGP_PRIVATE_KEY_BLOCK = "-----BEGIN PGP PRIVATE KEY BLOCK-----";

        /** Argument validation. */
        public GpgCertificate {
            Objects.requireNonNull(key, "The private GPG key must be specified");
            Objects.requireNonNull(secret, "The private GPG secret must be specified");

            if (!key.contains(BEGIN_PGP_PRIVATE_KEY_BLOCK)) {
                throw new IllegalArgumentException(
                        "Provided GPG key does not contain GPG private header: " + BEGIN_PGP_PRIVATE_KEY_BLOCK);
            }
        }

        @Override
        public final String toString() {
            // Note: no secrets
            return "GpgCertificate[key=***, secret=***]";
        }
    }

    /**
     * The OSSRH credentials to use for uploading bundles.
     *
     * @param user  the OSSRH user
     * @param token the OSSRH user token
     */
    public record OssrhCredentials(String user, String token) {
        /** Argument validation. */
        public OssrhCredentials {
            Objects.requireNonNull(user, "The OSSRH user must not be null");
            Objects.requireNonNull(token, "The OSSRH token must not be null");
        }

        /** {@return the credentials for use in a Basic Authentication header} */
        public String asBasicAuth() {
            return "Basic "
                    + Base64.getEncoder().encodeToString((user() + ":" + token()).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public final String toString() {
            // Note: no secrets
            return "OssrhCredentials[user=***, token=***]";
        }
    }

    /**
     * Extracts action arguments from the environment.
     *
     * @return the environment-specified action arguments
     */
    public static ActionArguments fromEnv() {
        GpgCertificate gpgCert =
                new GpgCertificate(getRequiredEnv("SIGNING_KEY"), getRequiredEnv("SIGNING_KEY_SECRET"));
        OssrhCredentials ossrhCreds =
                new OssrhCredentials(getRequiredEnv("OSSRH_USERNAME"), getRequiredEnv("OSSRH_TOKEN"));
        String suffixesStr = getRequiredEnv("COMPANION_SUFFIXES");
        List<String> suffixes =
                Stream.of(suffixesStr.split(",", -1)).map(String::trim).toList();
        Path searchDir = Paths.get(getRequiredEnv("SEARCH_DIR"));
        Level logLevel = Level.parse(getRequiredEnv("LOG_LEVEL").toUpperCase(Locale.ROOT));
        long initialPause = Long.parseLong(getRequiredEnv("INITIAL_PAUSE"));
        long loopPause = Long.parseLong(getRequiredEnv("LOOP_PAUSE"));
        TargetAction targetAction =
                TargetAction.valueOf(getRequiredEnv("TARGET_ACTION").toUpperCase(Locale.ROOT));
        return new ActionArguments(
                gpgCert, searchDir, suffixes, logLevel, ossrhCreds, targetAction, initialPause, loopPause);
    }

    /**
     * {@return the value of a required environment variable}
     *
     * @param envName the name of the environment variable
     */
    private static String getRequiredEnv(String envName) {
        String value = System.getenv(envName);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Needs environment variable '" + envName
                    + "' to be defined and non-blank. See readme/action.yaml!");
        }
        return value;
    }
}
