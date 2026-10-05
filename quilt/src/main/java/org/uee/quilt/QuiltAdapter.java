package org.uee.quilt;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.ModMetadata;
import org.quiltmc.loader.api.QuiltLoader;
import org.uee.mc.AbstractMinecraftAdapter;
import org.uee.debug.ModDescriptorReader;
import org.uee.model.Dependency;
import org.uee.model.ModElement;

/**
 * Quilt adapter.
 *
 * <p>Quilt's loader API mirrors Fabric's closely enough that the mod list is the only place needing
 * different calls; resources, recipes and command registration all arrive through the Fabric API
 * that Quilt runs for compatibility.
 */
public final class QuiltAdapter extends AbstractMinecraftAdapter {
    private volatile Collection<RecipeHolder<?>> recipes = List.of();

    public void bindRecipes(Collection<RecipeHolder<?>> current) {
        this.recipes = current == null ? List.of() : current;
    }

    @Override
    protected String loaderName() {
        return "quilt";
    }

    @Override
    protected String loaderVersion() {
        return QuiltLoader.getModContainer("quilt_loader")
                .map(c -> c.metadata().version().raw())
                .orElse("unknown");
    }

    @Override
    protected String minecraftVersion() {
        return QuiltLoader.getModContainer("minecraft")
                .map(c -> c.metadata().version().raw())
                .orElse("unknown");
    }

    @Override
    protected String gameDirectory() {
        return QuiltLoader.getGameDir().toString();
    }

    /**
     * Whether this is a client.
     *
     * <p>Asked of Fabric's loader, because Quilt has no environment accessor of its own in this
     * version: it runs Fabric mods natively, so the Fabric entry points and loader are the interfaces
     * a mod actually implements and queries here.
     */
    @Override
    protected boolean isClient() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                == net.fabricmc.api.EnvType.CLIENT;
    }

    /** Builds the mod list, reading dependencies from {@code quilt.mod.json} rather than the API. */
    @Override
    protected List<ModElement> loadedMods() {
        Collection<ModContainer> containers = QuiltLoader.getAllMods();
        List<ModElement> out = new ArrayList<>(containers.size());
        String mcVersion = minecraftVersion();
        for (ModContainer container : containers) {
            ModMetadata meta = container.metadata();
            Path path = containerFile(container);
            ModDescriptorReader.Descriptor descriptor = ModDescriptorReader.read(path);
            out.add(new ModElement(
                    meta.id(),
                    meta.name(),
                    meta.version().raw(),
                    meta.id(),
                    "quilt",
                    mcVersion,
                    meta.contributors().stream()
                            .map(QuiltAdapter::contributorName)
                            .toArray(String[]::new),
                    licenseNames(meta),
                    meta.description(),
                    descriptor != null ? descriptor.dependencies() : new Dependency[0],
                    descriptor != null ? descriptor.providers() : new String[0],
                    path == null ? null : path.getFileName().toString(),
                    path == null ? null : path.toString()));
        }
        return out;
    }

    /**
     * A contributor's display name.
     *
     * <p>Quilt's contributor type carries a name and a set of roles and has no id, so the name is the
     * only thing to fall back on — and a contributor without one is skipped rather than recorded as an
     * empty string, which would look like a mod that declared a blank author.
     */
    private static String contributorName(org.quiltmc.loader.api.ModContributor contributor) {
        String name = contributor.name();
        return name == null || name.isBlank() ? null : name;
    }

    /** A mod's declared licenses, joined, or {@code null} when it declared none. */
    private static String licenseNames(ModMetadata meta) {
        if (meta.licenses().isEmpty()) {
            return null;
        }
        return meta.licenses().stream()
                .map(org.quiltmc.loader.api.ModLicense::name)
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * The file a mod was loaded from.
     *
     * <p>{@code rootPath()} is the mod's root, which for a jar mod is the jar itself. Read through a
     * container that may throw, because a mod whose source cannot be reached is still a mod and the
     * export must continue without it.
     */
    private Path containerFile(ModContainer container) {
        try {
            return container.rootPath();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected Collection<RecipeHolder<?>> recipes() {
        return recipes;
    }
}
