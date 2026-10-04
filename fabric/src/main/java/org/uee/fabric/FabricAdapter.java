package org.uee.fabric;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.uee.mc.AbstractMinecraftAdapter;
import org.uee.mc.ResourceManagerTranslator;
import org.uee.mc.Translator;
import org.uee.model.ModElement;

/**
 * Fabric adapter.
 *
 * <p>Everything shared lives in {@link AbstractMinecraftAdapter}; this class supplies only what is
 * Fabric-specific: the mod list from Fabric Loader, the loader identity, and the resource/recipe
 * handles once the game has started.
 */
public final class FabricAdapter extends AbstractMinecraftAdapter {

    private final FabricLoader loader = FabricLoader.getInstance();
    private volatile ResourceManager resources;
    private volatile Collection<RecipeHolder<?>> recipes = List.of();
    private volatile Translator translator;

    /** Called once the resource manager exists (client start or server start). */
    public void bindResources(ResourceManager resourceManager) {
        this.resources = resourceManager;
    }

    /** Called once recipes are available; a dedicated server has them after loading finishes. */
    public void bindRecipes(Collection<RecipeHolder<?>> current) {
        this.recipes = current == null ? List.of() : current;
    }

    @Override
    protected String loaderName() {
        return "fabric";
    }

    @Override
    protected String loaderVersion() {
        return loader.getModContainer("fabricloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    protected String minecraftVersion() {
        return loader.getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    protected String gameDirectory() {
        Path dir = loader.getGameDir();
        return dir == null ? "." : dir.toString();
    }

    @Override
    protected boolean isClient() {
        return loader.getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;
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
        List<ModElement> out = new ArrayList<>(loader.getAllMods().size());
        for (ModContainer container : loader.getAllMods()) {
            ModMetadata meta = container.getMetadata();
            out.add(new ModElement(
                    meta.getId(),
                    meta.getName(),
                    meta.getVersion().getFriendlyString(),
                    meta.getId(),
                    "fabric",
                    minecraftVersion(),
                    meta.getAuthors().stream().map(Person::getName).toArray(String[]::new),
                    meta.getLicense().isEmpty() ? null : String.join(",", meta.getLicense()),
                    meta.getDescription(),
                    meta.getDepends().entrySet().stream()
                            .map(e -> e.getKey() + "@" + e.getValue().getVersion().getFriendlyString())
                            .toArray(String[]::new),
                    new String[0],
                    sourceFile(container)));
        }
        return out;
    }

    private String sourceFile(ModContainer container) {
        try {
            return container.getOrigin().getPaths().isEmpty()
                    ? null
                    : container.getOrigin().getPaths().get(0).getFileName().toString();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected Collection<RecipeHolder<?>> recipes() {
        return recipes;
    }
}
