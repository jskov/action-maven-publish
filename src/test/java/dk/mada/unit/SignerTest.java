package dk.mada.unit;

import static org.assertj.core.api.Assertions.assertThat;

import dk.mada.action.GpgSigner;
import dk.mada.action.util.ExternalCmdRunner;
import dk.mada.action.util.ExternalCmdRunner.CmdInput;
import dk.mada.action.util.ExternalCmdRunner.CmdResult;
import dk.mada.fixture.ArgumentsFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Signer tests.
 */
class SignerTest {
    /** The subject under test - the gpg signer */
    private final GpgSigner sut = new GpgSigner(ArgumentsFixture.gpgCert());

    /**
     * Tests that the certificate can be loaded (from test resources) and is ultimately trusted.
     */
    @Test
    void canLoadCertificate() {
        String fingerprint = sut.loadSigningCertificate();
        CmdResult result = runCmd("gpg", "-K", fingerprint);

        assertThat(result.output()).contains("sec#").contains("[ultimate]");
    }

    private CmdResult runCmd(String... args) {
        var env = Map.of("GNUPGHOME", sut.getGnupgHome().toAbsolutePath().toString());
        var input = new CmdInput(List.of(args), sut.getGnupgHome(), null, env, 2);
        CmdResult res = ExternalCmdRunner.runCmd(input);
        return res;
    }
}
