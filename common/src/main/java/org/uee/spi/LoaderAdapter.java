package org.uee.spi;

import java.util.Collection;
import java.util.List;
import org.uee.config.ExportConfig;
import org.uee.model.DebugSection;
import org.uee.model.ElementKind;
import org.uee.model.ModElement;

/**
 * The thin loader abstraction: one implementation per loader, nothing more.
 *
 * <p><b>This interface never mentions a Minecraft type.</b> Implementing it is entirely a matter of
 * turning loader/game objects into {@code org.uee.model} records. Two consequences:
 *
 * <ul>
 *   <li>the core (model, writers, pipeline) compiles and is testable without any Minecraft
 *       dependency at all — which is how the format contract can be verified in isolation;
 *   <li>the adapter surface stays small enough to hand-write four times, so UEE does not need a
 *       third-party abstraction layer or an extra prerequisite for users to install.
 * </ul>
 *
 * <p>Implementations must be tolerant: a failure while collecting one element is reported through
 * {@link ElementSink#failure} and must not propagate, because aborting a whole export over one bad
 * entry is one of the defects this project exists to fix.
 */
public interface LoaderAdapter {

    /** Loader identity and build information. */
    LoaderInfo info();

    /**
     * Layer-A mod metadata: the loaded mod list. This path reads loader metadata only and calls no
     * game code, which is what makes it work across every game version.
     */
    List<ModElement> mods();

    /**
     * Layer-B registry collection. Called from the main thread with frozen registries.
     *
     * @param config the resolved export configuration
     * @param wanted the categories to collect; the adapter must not collect unrequested categories
     * @param sink where collected elements go
     */
    void collectRegistries(ExportConfig config, Collection<ElementKind> wanted, ElementSink sink);

    /**
     * Datapack-driven content (recipes, loot tables, advancements, tags, worldgen, functions,
     * language). Separated from registries because it is the half that registry-only exporters miss
     * entirely.
     */
    void collectDatapacks(ExportConfig config, Collection<ElementKind> wanted, ElementSink sink);

    /** Debug/environment sections. Cheap to produce and never load-bearing for the export. */
    List<DebugSection> debugSections();

    /**
     * Capability probe used instead of version comparison.
     *
     * @param capability a stable capability key, e.g. {@code "data_components"} or
     *     {@code "registry_frozen"}
     * @return whether the running build supports it
     */
    boolean supports(String capability);

    /** Capability key: item stacks expose structured data components (1.20.5+ era). */
    String CAP_DATA_COMPONENTS = "data_components";
    /** Capability key: registries are frozen and safe to read from worker threads. */
    String CAP_REGISTRY_FROZEN = "registry_frozen";
    /** Capability key: the loader can enumerate every mod's declared dependencies. */
    String CAP_MOD_DEPENDENCIES = "mod_dependencies";
    /** Capability key: a resource manager can be read without a running client. */
    String CAP_HEADLESS_RESOURCES = "headless_resources";
}
