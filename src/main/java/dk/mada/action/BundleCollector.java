package dk.mada.action;

import dk.mada.action.util.XmlExtractor;
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
            List<Pom> poms = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".pom"))
                    .map(this::readPomMetadata)
                    .toList();

            // Then make bundles with the companions
            return poms.stream()
                    .map(pom -> makeSourceBundle(pom, companionSuffixes))
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
     * @param pom               the main POM file
     * @param companionSuffixes the suffixes to find companion assets from
     * @return the resulting bundle source
     */
    private BundleSource makeSourceBundle(Pom pom, List<String> companionSuffixes) {
        Path pomFile = pom.pomFile();
        Path dir = pomFile.getParent();
        String basename = pomFile.getFileName().toString().replace(".pom", "");
        List<Path> companions = companionSuffixes.stream()
                .map(suffix -> dir.resolve(basename + suffix))
                .filter(Files::isRegularFile)
                .toList();
        return new BundleSource(pom, companions);
    }

    /**
     * Signs bundle source files.
     *
     * @param bundleSrc the bundle source
     * @return all files to be included in the bundle (source + signatures)
     */
    private BundleFiles signBundleFiles(BundleSource bundleSrc) {
        List<Path> signatures = Stream.concat(Stream.of(bundleSrc.pom().pomFile()), bundleSrc.assets().stream())
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
        Pom pom = bundleFiles.bundleSource.pom();
        Path pomFile = pom.pomFile();
        Path bundleJar =
                pomFile.getParent().resolve(pomFile.getFileName().toString().replace(".pom", "_bundle.jar"));

        List<Path> allBundleFiles = new ArrayList<>();
        allBundleFiles.add(pomFile);
        allBundleFiles.addAll(bundleFiles.bundleSource().assets());

        List<Path> checksumFiles = assertPresenceOfAssociatedChecksumFiles(allBundleFiles);

        allBundleFiles.addAll(bundleFiles.signatures());
        allBundleFiles.addAll(checksumFiles);

        String jarDirPath = pom.group().replace('.', '/') + "/" + pom.artifact() + "/" + pom.version() + "/";

        try (OutputStream os = Files.newOutputStream(bundleJar);
                BufferedOutputStream bos = new BufferedOutputStream(os);
                JarOutputStream jos = new JarOutputStream(bos)) {
            for (Path f : allBundleFiles) {
                String jarPath = jarDirPath + f.getFileName().toString();
                JarEntry entry = new JarEntry(jarPath);
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
     * Make a list of expected checksum files for the assets, and assert that they
     * are present.
     *
     * @param assets the list of assets
     * @return the list of asset checksum files
     */
    private List<Path> assertPresenceOfAssociatedChecksumFiles(List<Path> assets) {
        List<Path> checksumFiles = new ArrayList<>();
        for (Path f : assets) {
            String name = f.getFileName().toString();
            checksumFiles.add(f.getParent().resolve(name + ".md5"));
            checksumFiles.add(f.getParent().resolve(name + ".sha1"));
        }

        List<Path> missingChecksumFiles =
                checksumFiles.stream().filter(f -> !Files.isRegularFile(f)).toList();
        if (!missingChecksumFiles.isEmpty()) {
            throw new IllegalStateException("Did not find required checksum files: " + missingChecksumFiles);
        }

        return checksumFiles;
    }

    /**
     * Read metadata from the POM file.
     *
     * @param pomFile the pom file to read
     * @return the read POM data
     */
    private Pom readPomMetadata(Path pomFile) {
        try {
            String pomXml = Files.readString(pomFile);
            XmlExtractor xex = new XmlExtractor(pomXml);
            String group = xex.get("groupId");
            String artifact = xex.get("artifactId");
            String version = xex.get("version");

            return new Pom(pomFile, group, artifact, version);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read POM data from " + pomFile, e);
        }
    }

    /**
     * Information about a POM.
     *
     * @param pomFile  the POM file
     * @param group    the artifact group
     * @param artifact the artifact name
     * @param version    the artifact version
     */
    public record Pom(Path pomFile, String group, String artifact, String version) {}

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
     * @param pom    the main POM
     * @param assets a list of additional assets (may be empty)
     */
    public record BundleSource(Pom pom, List<Path> assets) {}
}
