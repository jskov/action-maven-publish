package dk.mada.action;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import dk.mada.action.BundleCollector.Bundle;
import dk.mada.action.BundlePublisher.PublishingResult;
import dk.mada.action.util.LoggerConfig;

/**
 * Action uploading and publishing Maven artifacts to OSSRH (MavenCentral).
 */
public final class ActionNexusPublisher {
    private static Logger logger = Logger.getLogger(ActionNexusPublisher.class.getName());

    /** Constructs new instance. */
    public ActionNexusPublisher() {
        // Explicit constructor to avoid javadoc warning
    }

    /**
     * Runs action, taking instructions from environment.
     */
    private void run() {
        ActionArguments args = ActionArguments.fromEnv();
        LoggerConfig.loadDefaultConfig(args.logLevel());
        logger.config(() -> args.toString());

        boolean failed;
        try (GpgSigner signer = new GpgSigner(args.gpgCertificate())) {
            BundleCollector bundleBuilder = new BundleCollector(signer);
            OssrhProxy proxy = new OssrhProxy(args.ossrhCredentials());
            BundlePublisher bundlePublisher = new BundlePublisher(args, proxy);
            signer.loadSigningCertificate();

            List<Bundle> bundles = bundleBuilder.collectBundles(args.searchDir(), args.companionSuffixes());
            PublishingResult result = bundlePublisher.publish(bundles, args.targetAction());

            // If all repositories were valid, this was a success. Regardless of the requested action.
            failed = !result.allReposValid();
        } catch (Exception e) {
            logger.log(Level.WARNING, e, () -> "Publisher failed initialization: " + e.getMessage());
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
