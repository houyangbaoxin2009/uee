package org.uee.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.uee.debug.ModContainerScanner;
import org.uee.model.ElementKind;
import org.uee.model.ModElement;

/**
 * Checks mod containers: ones that could not be read, and ones whose shape is worth noticing.
 *
 * <p>Reads containers on disk, so it needs no registry access and runs with or without collection.
 *
 * <p>An unreadable container is worth reporting even though it does not stop anything: the mod list
 * came from the loader and is fine, but everything derived from that container — its mixin configs,
 * its declared dependencies — is silently missing from this run. Without this check, that absence
 * looks identical to "the mod has none", which is the sort of quiet gap that leads to a wrong
 * conclusion later.
 */
public final class ContainerAnalysis implements Analysis {

    /** A container this large is unusual and worth a look when scanning is slow. */
    private static final long LARGE_CONTAINER_BYTES = 64L * 1024 * 1024;

    @Override
    public String id() {
        return "containers";
    }

    @Override
    public String description() {
        return "containers that could not be read, and containers unusually large for a mod";
    }

    @Override
    public Stage stage() {
        return Stage.PRE_COLLECTION;
    }

    @Override
    public void run(AnalysisContext ctx, AnalysisOutput out) {
        for (Map.Entry<String, ModContainerScanner.ContainerInfo> e : ctx.containers().entrySet()) {
            String modId = e.getKey();
            ModContainerScanner.ContainerInfo info = e.getValue();

            String readError = info.manifestAttributes().get("uee.readError");
            if (readError != null) {
                out.finding(new Finding("unreadable_container", Finding.Severity.WARN, modId,
                        "container of " + modId + " could not be read; its mixins and manifest"
                                + " attributes are missing from this run",
                        new String[] {"container", info.fileName(), "error", readError}));
                continue;
            }

            if (info.sizeBytes() >= LARGE_CONTAINER_BYTES) {
                out.finding(new Finding("large_container", Finding.Severity.INFO, modId,
                        modId + " is " + (info.sizeBytes() / (1024 * 1024)) + " MiB",
                        new String[] {"container", info.fileName(),
                                "bytes", Long.toString(info.sizeBytes()),
                                "entries", Integer.toString(info.entryCount())}));
            }

            // A jar declaring mixin configs that contains none of them usually means the config list
            // was built for a different packaging step.
            if (info.mixinConfigPaths().isEmpty()
                    && info.manifestAttributes().containsKey("MixinConfigs")) {
                out.finding(new Finding("dangling_mixin_declaration", Finding.Severity.WARN, modId,
                        modId + " declares " + ModContainerScanner.MANIFEST_MIXIN_CONFIGS
                                + " in its manifest but no such config was found",
                        new String[] {"declared",
                                info.manifestAttributes().get(ModContainerScanner.MANIFEST_MIXIN_CONFIGS)}));
            }
        }

        // Mods with no container at all: they exist in the loader's list but nothing on disk was
        // located for them, so nothing about their contents can be reported.
        for (ModElement m : ctx.mods()) {
            if (!ctx.containers().containsKey(m.id()) && ctx.containers().size() > 0) {
                out.finding(new Finding("no_container", Finding.Severity.INFO, m.id(),
                        m.id() + " has no readable container; its mixins and manifest are unavailable",
                        null));
            }
        }
    }

    /** Distinct namespaces across the containers, for callers that want an inventory. */
    static TreeSet<String> sortedIds(AnalysisContext ctx) {
        return new TreeSet<>(ctx.containers().keySet());
    }

    /** Mods whose container was read successfully. */
    static List<String> readable(AnalysisContext ctx) {
        List<String> out = new ArrayList<>(ctx.containers().size());
        for (Map.Entry<String, ModContainerScanner.ContainerInfo> e : ctx.containers().entrySet()) {
            if (!e.getValue().manifestAttributes().containsKey("uee.readError")) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** The category this analysis files its records under, when it emits any. */
    static final ElementKind RECORD_KIND = ElementKind.MOD;
}
