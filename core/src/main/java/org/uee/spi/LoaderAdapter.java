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

    /**
     * Offers the pack's assets for copying, when the run asked for them.
     *
     * <p>Separate from the collection calls because it collects nothing: an asset has no element to
     * describe, only bytes to place. An adapter that cannot see assets — a dedicated server, whose resource
     * manager only sees {@code data/} — offers none and reports why through the sink, since "this pack has
     * no assets" and "this side cannot see assets" are different answers and only one is worth acting on.
     *
     * <p>A default method, so a test adapter that has no assets to offer compiles without saying so.
     */
    default void collectAssets(ExportConfig config, ElementSink sink) {
    }

    /** Debug/environment sections. Cheap to produce and never load-bearing for the export. */
    List<DebugSection> debugSections();

    /**
     * Raw facts the analyses read, as opposed to conclusions.
     *
     * <p>The adapter's job stops at gathering: it reports which containers it found and what mixin
     * configs those containers declare, and it draws no inferences from them. Deciding that two mods
     * patching one class is a problem is the analysis module's business, and keeping that split means
     * a new check never requires touching four loader modules.
     *
     * <p>Both default to empty, so an adapter that cannot locate containers still compiles and still
     * exports — it simply contributes fewer facts.
     */
    default java.util.Map<String, org.uee.debug.ModContainerScanner.ContainerInfo> containers() {
        return java.util.Map.of();
    }

    /** Mixin configs found in the mods' containers, in discovery order. */
    default List<org.uee.debug.MixinConfig> mixinConfigs() {
        return List.of();
    }

    /**
     * Ids of the datapacks that are active in this instance.
     *
     * <p>UEE reads its own definitions out of datapacks, and a datapack reaches the game through the
     * loader's resource system — so the loader is the only thing that knows which datapacks exist. A
     * mod's bundled definitions arrive this way too, which is why there is no separate mod path.
     *
     * <p>Only ids are needed, not contents: the definitions are read from the same files the game
     * loaded them from. Default is empty, so an adapter that cannot enumerate datapacks simply
     * contributes no datapack-defined flows.
     */
    default List<String> datapackIds() {
        return List.of();
    }

    /**
     * Flows defined as MC functions in the active datapacks.
     *
     * <p>Only the loader can enumerate datapack functions, so discovery happens there and the result
     * is handed to UEE. UEE does not run these itself — the game already knows how — it only needs to
     * know they exist so one name reaches a flow of either kind.
     *
     * <p>Default is empty, so an adapter that cannot enumerate functions simply offers no function
     * flows while configuration flows keep working.
     */
    default List<org.uee.datapack.FunctionFlow> functionFlows() {
        return List.of();
    }

    /**
     * The directory UEE should read global datapacks from, or {@code null} for the default.
     *
     * <p>An override rather than the directory itself, because the default is derived from the game
     * directory and an adapter should not have to reconstruct it.
     */
    default String globalPackDirectory() {
        return null;
    }

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
