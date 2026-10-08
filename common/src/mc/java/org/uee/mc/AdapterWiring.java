package org.uee.mc;

import org.uee.Uee;
import org.uee.spi.Diagnostics;

/**
 * Supplies an adapter with the two things the shared base class asks for.
 *
 * <h2>Why this exists rather than a call in the base class</h2>
 *
 * <p>The base class is the shared half of every loader, so it cannot name the exporter — and it used to, for
 * a failure sink and a declaration table, which is what stopped a second mod from reusing it. The two are
 * now asked for, and this is where the mod that owns them answers.
 *
 * <p>One place rather than four constructors: the adapters are per loader, the exporter is one, and four
 * copies of the same two lines is four places to remember when a third collaboration appears.
 */
public final class AdapterWiring {

    private AdapterWiring() {
    }

    /**
     * Gives an adapter the exporter's failure sink and declaration reader.
     *
     * @return the same adapter, so a caller can bind it in one expression
     */
    public static <T extends AbstractMinecraftAdapter> T wireExporter(T adapter) {
        Diagnostics.reporter(Uee::reportFailure);
        adapter.declarationReader(Uee::declared);
        return adapter;
    }
}
