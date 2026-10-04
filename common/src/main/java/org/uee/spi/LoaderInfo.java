package org.uee.spi;

/**
 * Identifies the loader and game build a snapshot came from.
 *
 * <p>The game version is recorded as an opaque string that is never compared numerically. Minecraft
 * has already changed its version-numbering scheme (a year-based scheme coexists with the older
 * {@code 1.x} scheme), so any logic that orders or parses version strings will silently break. All
 * adaptation decisions are made by capability detection in the adapter, not by reading this field.
 */
public record LoaderInfo(
        String loader,
        String loaderVersion,
        String minecraftVersion,
        String gameDirectory,
        boolean client,
        boolean dedicatedServer,
        String javaVersion,
        String osName,
        String osArch) {

    public LoaderInfo {
        if (loader == null || loader.isEmpty()) {
            throw new IllegalArgumentException("loader name is required");
        }
    }
}
