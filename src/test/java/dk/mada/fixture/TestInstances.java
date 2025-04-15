package dk.mada.fixture;

import dk.mada.action.BundleCollector;
import dk.mada.action.BundlePublisher;
import dk.mada.action.GpgSigner;
import dk.mada.action.portal.PortalProxy;

/**
 * Provides test instances of the domain classes.
 */
public final class TestInstances {
    /** The GPG signer test instance. */
    private static GpgSigner signer;
    /** The bundle collector test instance. */
    private static BundleCollector bundleCollector;
    /** The Portal proxy test instance. */
    private static PortalProxy portalProxy;
    /** The bundle publisher test instance. */
    private static BundlePublisher bundlePublisher;

    private TestInstances() {
        // empty
    }

    /** @{return an initialized GPG signer instance} */
    public static GpgSigner signer() {
        if (signer == null) {
            signer = new GpgSigner(ArgumentsFixture.gpgCert());
            signer.loadSigningCertificate();
        }
        return signer;
    }

    /** @{return an initialized bundle collector instance} */
    public static BundleCollector bundleCollector() {
        if (bundleCollector == null) {
            bundleCollector = new BundleCollector(signer());
        }
        return bundleCollector;
    }

    /** {@return an initialized Portal proxy instance} */
    public static PortalProxy portalProxy() {
        if (portalProxy == null) {
            portalProxy = new PortalProxy(ArgumentsFixture.portalCreds());
        }
        return portalProxy;
    }

    /** {@return an initialized bundle publisher instance} */
    public static BundlePublisher bundlePublisher() {
        if (bundlePublisher == null) {
            bundlePublisher = new BundlePublisher(ArgumentsFixture.withGpg(), portalProxy());
        }
        return bundlePublisher;
    }
}
