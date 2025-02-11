package dk.mada.action;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * Collects POMs and companion assets from disk, packs them into signed bundles.
 */
public final class BundleCollector {
    /** The GPG signer. */
    private final GpgSigner signer;

    /**
     * Creates a new instance.
     *
     * @param signer the signer to use when creating the bundles
     */
    public BundleCollector(GpgSigner signer) {
        this.signer = signer;
    }

    /**
     * Collects bundles for publishing.
     *
     * @param searchDir         the search directory
     * @param companionSuffixes the suffixes to use for finding bundle assets
     * @return the collected bundles
     */
    public List<Bundle> collectBundles(Path searchDir, List<String> companionSuffixes) {
        return findBundleSources(searchDir, companionSuffixes).stream()
                .map(this::signBundleFiles)
                .map(this::packageBundle)
                .toList();
    }

    /**
     * Collects bundle sources in and below the search directory.
     *
     * @param searchDir         the search directory
     * @param companionSuffixes the suffixes to use for finding bundle assets
     * @return the collected bundles
     */
    public List<BundleSource> findBundleSources(Path searchDir, List<String> companionSuffixes) {
        try (Stream<Path> files = Files.walk(searchDir)) {
            // First find the POMs
            List<Path> poms = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".pom"))
                    .toList();

            // Then make bundles with the companions
            return poms.stream()
                    .map(p -> makeSourceBundle(p, companionSuffixes))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build bundles in " + searchDir, e);
        }
    }

    /**
     * Makes a bundle based on the main POM file.
     *
     * Finds companion files for the bundle based on the provided suffixes.
     *
     * @param pomFile           the main POM file
     * @param companionSuffixes the suffixes to find companion assets from
     * @return the resulting bundle source
     */
    private BundleSource makeSourceBundle(Path pomFile, List<String> companionSuffixes) {
        Path dir = pomFile.getParent();
        String basename = pomFile.getFileName().toString().replace(".pom", "");
        List<Path> companions = companionSuffixes.stream()
                .map(suffix -> dir.resolve(basename + suffix))
                .filter(Files::isRegularFile)
                .toList();
        return new BundleSource(pomFile, companions);
    }

    /**
     * Signs bundle source files.
     *
     * @param bundleSrc the bundle source
     * @return all files to be included in the bundle (source + signatures)
     */
    private BundleFiles signBundleFiles(BundleSource bundleSrc) {
        List<Path> signatures = Stream.concat(Stream.of(bundleSrc.pom()), bundleSrc.assets().stream())
                .map(signer::sign)
                .toList();
        return new BundleFiles(bundleSrc, signatures);
    }

    /**
     * Packages the bundle content into a jar-file.
     *
     * @param bundleFiles the files to include in the jar-file
     * @return the completed bundle
     */
    private Bundle packageBundle(BundleFiles bundleFiles) {
        Path pom = bundleFiles.bundleSource.pom();
        Path bundleJar = pom.getParent().resolve(pom.getFileName().toString().replace(".pom", "_bundle.jar"));

        List<Path> allBundleFiles = new ArrayList<>();
        allBundleFiles.add(pom);
        allBundleFiles.addAll(bundleFiles.bundleSource().assets());
        allBundleFiles.addAll(bundleFiles.signatures());

        try (OutputStream os = Files.newOutputStream(bundleJar);
                BufferedOutputStream bos = new BufferedOutputStream(os);
                JarOutputStream jos = new JarOutputStream(bos)) {
            for (Path f : allBundleFiles) {
                JarEntry entry = new JarEntry(f.getFileName().toString());
                jos.putNextEntry(entry);
                Files.copy(f, jos);
                jos.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to package bundles into " + bundleJar, e);
        }

        return new Bundle(bundleJar, bundleFiles);
    }

    /**
     * The packaged bundle.
     *
     * @param bundleJar the packaged bundle jar, containing all source and signature files
     * @param files     the bundle constituents
     */
    public record Bundle(Path bundleJar, BundleFiles files) {}

    /**
     * All files in the bundle (sources and signatures).
     *
     * @param bundleSource the original bundle source
     * @param signatures   a list of created signatures for the assets
     */
    public record BundleFiles(BundleSource bundleSource, List<Path> signatures) {}

    /**
     * The original source files of a bundle.
     *
     * @param pom    the main POM file
     * @param assets a list of additional assets (may be empty)
     */
    public record BundleSource(Path pom, List<Path> assets) {}
}
