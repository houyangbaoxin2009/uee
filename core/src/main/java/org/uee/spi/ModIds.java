package org.uee.spi;

/**
 * The ids of the mods in this project.
 *
 * <h2>Why they are here and not on the mods</h2>
 *
 * <p>The id is needed by code that must not name a mod: the configuration decides the exporter's default
 * output directory from it, the analysis half checks whether the exporter is loaded, and neither of them can
 * refer to the other's facade without tying the two together. One place for the literals also means one
 * place to change when a third appears.
 */
public final class ModIds {

    private ModIds() {
    }

    /** The exporter. */
    public static final String EXPORTER = "uee";

    /** The analysis half. */
    public static final String ANALYSIS = "ueea";
}
