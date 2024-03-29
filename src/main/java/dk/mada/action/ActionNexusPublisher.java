package dk.mada.action;

import java.util.List;
import java.util.logging.Logger;

import dk.mada.action.BundleCollector.Bundle;
import dk.mada.action.util.LoggerConfig;

/**
 * Action uploading and publishing Maven artifacts to OSSRH (MavenCentral).
 */
public final class ActionNexusPublisher {
    private static Logger logger = Logger.getLogger(ActionNexusPublisher.class.getName());

    /**
     * Runs action, taking instructions from environment.
     */
    private void run() {
        ActionArguments args = ActionArguments.fromEnv();
        LoggerConfig.loadDefaultConfig(args.logLevel());
        logger.config(() -> args.toString());

        boolean failed = false;
        try (GpgSigner signer = new GpgSigner(args.gpgCertificate())) {
            BundleCollector bundleBuilder = new BundleCollector(signer);
            OssrhProxy proxy = new OssrhProxy(args.ossrhCredentials());
            BundlePublisher bundlePublisher = new BundlePublisher(proxy);
            signer.loadSigningCertificate();

            List<Bundle> bundles = bundleBuilder.collectBundles(args.searchDir(), args.companionSuffixes());
            bundlePublisher.publish(bundles, args.targetAction());
        } catch (Exception e) {
            logger.warning("Publisher failed initialization: " + e.getMessage());
            failed = true;
        }
        if (failed) {
            // keep the System.exit out of the try-with so GpgSigner gets to close correctly
            System.exit(1);
        }
    }

    /**
     * Action main method.
     *
     * @param args the arguments from the command line, ignored
     */
    public static void main(String[] args) {
        new ActionNexusPublisher().run();
    }
}
