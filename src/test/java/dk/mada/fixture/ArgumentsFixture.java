package dk.mada.fixture;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;

import dk.mada.action.ActionArguments;
import dk.mada.action.ActionArguments.GpgCertificate;
import dk.mada.action.ActionArguments.OssrhCredentials;
import dk.mada.action.util.LoggerConfig;

/**
 * Fixture for creating action arguments for tests.
 */
public final class ArgumentsFixture {
    private ArgumentsFixture() {
    }

    /** {@return action arguments based on test certificate} */
    public static ActionArguments withGpg() {
        LoggerConfig.loadConfig("/test-logging.properties");

        OssrhCredentials ossrhCreds = readOssrhCreds();
        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        List<String> emptySuffixes = List.of();
        return new ActionArguments(gpgCert(), tmpDir, emptySuffixes, Level.FINEST, ossrhCreds);
    }

    /** {@return GPG test certificate} */
    public static GpgCertificate gpgCert() {
        return new GpgCertificate(readResource("/gpg-testkey.txt"), readResource("/gpg-testkey-password.txt"));
    }

    /**
     * Reads OSSRH user:token from $XDG_RUNTIME_DIR/ossrh-creds.txt if the file is available.
     *
     * @return the loaded OSSRH credentials or dummy credentials
     */
    private static OssrhCredentials readOssrhCreds() {
        try {
            String runtimePath = System.getenv("XDG_RUNTIME_DIR");
            if (runtimePath != null) {
                Path credsFile = Paths.get(runtimePath).resolve("ossrh-creds.txt");
                if (Files.isRegularFile(credsFile)) {
                    String creds = Files.readString(credsFile).trim();
                    return new OssrhCredentials(creds.replaceFirst(":.*", ""), creds.replaceFirst("[^:]*:", ""));
                }
            }
            return new OssrhCredentials("no_ossrh_user", "no_ossrh_token");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read OSSRH creds", e);
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
