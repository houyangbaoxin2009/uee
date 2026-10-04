package org.uee.neoforge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;
import org.uee.mc.AbstractMinecraftAdapter;
import org.uee.mc.ResourceManagerTranslator;
import org.uee.mc.Translator;
import org.uee.model.ModElement;

/** NeoForge adapter. Shared logic lives in {@link AbstractMinecraftAdapter}. */
public final class NeoForgeAdapter extends AbstractMinecraftAdapter {

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
        return "neoforge";
    }

    @Override
    protected String loaderVersion() {
        return ModList.get().getModContainerById("neoforge")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    protected String minecraftVersion() {
        return ModList.get().getModContainerById("minecraft")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    protected String gameDirectory() {
        return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().toString();
    }

    @Override
    protected boolean isClient() {
        return net.neoforged.fml.loading.FMLEnvironment.dist.isClient();
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
        List<IModInfo> infos = ModList.get().getMods();
        List<ModElement> out = new ArrayList<>(infos.size());
        for (IModInfo info : infos) {
            out.add(new ModElement(
                    info.getModId(),
                    info.getDisplayName(),
                    info.getVersion().toString(),
                    info.getModId(),
                    "neoforge",
                    minecraftVersion(),
                    info.getAuthors().map(a -> a.split(",")).orElse(new String[0]),
                    info.getLicense().orElse(null),
                    info.getDescription(),
                    info.getDependencies().stream()
                            .map(d -> d.getModId() + "@" + d.getVersionRange())
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
