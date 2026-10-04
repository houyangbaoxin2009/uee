package org.uee.quilt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.ModMetadata;
import org.quiltmc.loader.api.QuiltLoader;
import org.uee.mc.AbstractMinecraftAdapter;
import org.uee.mc.ResourceManagerTranslator;
import org.uee.debug.ModDescriptorReader;
import org.uee.mc.Translator;
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

    private volatile ResourceManager resources;
    private volatile Collection<RecipeHolder<?>> recipes = List.of();
    private volatile Translator translator;

    public void bindResources(ResourceManager resourceManager) {
        this.resources = resourceManager;
        this.translator = null;
    }

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

    @Override
    protected boolean isClient() {
        return QuiltLoader.getEnvironmentType() == org.quiltmc.loader.api.EnvironmentType.CLIENT;
    }

    @Override
    protected Translator translator() {
        Translator current = translator;
        if (current == null) {
            ResourceManager manager = resources;
            current = manager == null ? Translator.none() : new ResourceManagerTranslator(manager);
            translator = current;
        }
        return current;
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
                            .map(c -> c.name() == null ? c.id() : c.name())
                            .toArray(String[]::new),
                    meta.license().isEmpty() ? null : String.join(",", meta.license()),
                    meta.description(),
                    descriptor != null ? descriptor.dependencies() : new Dependency[0],
                    descriptor != null ? descriptor.providers() : new String[0],
                    path == null ? null : path.getFileName().toString(),
                    path == null ? null : path.toString()));
        }
        return out;
    }

    private Path containerFile(ModContainer container) {
        try {
            List<Path> paths = container.rootPaths();
            return paths.isEmpty() ? null : paths.get(0);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected Collection<RecipeHolder<?>> recipes() {
        return recipes;
    }
}
