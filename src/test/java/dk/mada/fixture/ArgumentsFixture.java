package dk.mada.fixture;

import dk.mada.action.ActionArguments;
import dk.mada.action.ActionArguments.GpgCertificate;
import dk.mada.action.ActionArguments.PortalCredentials;
import dk.mada.action.BundlePublisher.TargetAction;
import dk.mada.action.util.LoggerConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;

/**
 * Fixture for creating action arguments for tests.
 */
public final class ArgumentsFixture {
    /** The initial delay before polling for change during testing. */
    private static final long INITIAL_DELAY = 30;
    /** The loop delay before polling for change during testing. */
    private static final long LOOP_DELAY = 10;

    private ArgumentsFixture() {
        // empty
    }

    /** {@return action arguments based on test certificate} */
    public static ActionArguments withGpg() {
        LoggerConfig.loadConfig("/test-logging.properties");

        PortalCredentials portalCreds = portalCreds();
        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        List<String> emptySuffixes = List.of();
        return new ActionArguments(
                gpgCert(),
                tmpDir,
                emptySuffixes,
                Level.FINEST,
                portalCreds,
                TargetAction.DROP,
                INITIAL_DELAY,
                LOOP_DELAY);
    }

    /** {@return GPG test certificate} */
    public static GpgCertificate gpgCert() {
        return new GpgCertificate(readResource("/gpg-testkey.txt"), readResource("/gpg-testkey-password.txt"));
    }

    /**
     * Reads Portal user:token from PORTAL_CREDENTIALS_PATH if the file is available.
     *
     * @return the loaded Portal credentials or dummy credentials
     */
    public static PortalCredentials portalCreds() {
        try {
            String credendialsPath = System.getenv("PORTAL_CREDENTIALS_PATH");
            if (credendialsPath != null) {
                Path credsFile = Paths.get(credendialsPath);
                if (Files.isRegularFile(credsFile)) {
                    String creds = Files.readString(credsFile).trim();
                    return new PortalCredentials(creds.replaceFirst(":.*", ""), creds.replaceFirst("[^:]*:", ""));
                }
            }
            return new PortalCredentials("no_portal_user", "no_portal_token");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Portal creds", e);
        }
    }

    private static String readResource(String path) {
        try (InputStream is = ArgumentsFixture.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalArgumentException("Failed to find resource from '" + path + "'");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read resource " + path, e);
        }
    }
}
