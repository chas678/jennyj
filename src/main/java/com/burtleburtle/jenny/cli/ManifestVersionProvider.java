package com.burtleburtle.jenny.cli;

import picocli.CommandLine.IVersionProvider;

/**
 * Supplies {@code jenny --version} output from the jar manifest's
 * {@code Implementation-Version} (populated by the shade plugin at build time,
 * see pom.xml). When run from unpackaged classes (tests, IDE) the manifest has
 * no version, so we fall back to a development marker.
 */
public final class ManifestVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        String version = ManifestVersionProvider.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = "(development build)";
        }
        return new String[] { "jenny " + version };
    }
}
