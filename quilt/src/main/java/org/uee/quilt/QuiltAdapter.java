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
import org.uee.mc.Translator;
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

    @Override
    protected List<ModElement> loadedMods() {
        Collection<ModContainer> containers = QuiltLoader.getAllMods();
        List<ModElement> out = new ArrayList<>(containers.size());
        for (ModContainer container : containers) {
            ModMetadata meta = container.metadata();
            out.add(new ModElement(
                    meta.id(),
                    meta.name(),
                    meta.version().raw(),
                    meta.id(),
                    "quilt",
                    minecraftVersion(),
                    meta.contributors().stream()
                            .map(c -> c.name() == null ? c.id() : c.name())
                            .toArray(String[]::new),
                    meta.license().isEmpty() ? null : String.join(",", meta.license()),
                    meta.description(),
                    meta.depends().entrySet().stream()
                            .map(e -> e.getKey() + "@" + e.getValue())
                            .toArray(String[]::new),
                    new String[0],
                    null));
        }
        return out;
    }

    @Override
    protected Collection<RecipeHolder<?>> recipes() {
        return recipes;
    }
}
