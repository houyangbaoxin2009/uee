package org.uee.forge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import org.uee.mc.AbstractMinecraftAdapter;
import org.uee.mc.ResourceManagerTranslator;
import org.uee.mc.Translator;
import org.uee.model.Dependency;
import org.uee.model.ModElement;

/**
 * Forge adapter. Shared logic lives in {@link AbstractMinecraftAdapter}.
 *
 * <p><b>Compile-unverified.</b> Written against the stable parts of {@code forgespi}; the mod list and
 * container paths are the only Forge-specific surface, and it has not been through a compiler.
 */
public final class ForgeAdapter extends AbstractMinecraftAdapter {

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
        return "forge";
    }

    @Override
    protected String loaderVersion() {
        return ModList.get().getModContainerById("forge")
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
        return net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().toString();
    }

    @Override
    protected boolean isClient() {
        return net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient();
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
        String mcVersion = minecraftVersion();
        for (IModInfo info : infos) {
            Path container = containerOf(info.getModId());
            out.add(new ModElement(
                    info.getModId(),
                    info.getDisplayName(),
                    info.getVersion().toString(),
                    info.getModId(),
                    "forge",
                    mcVersion,
                    info.getAuthors().map(a -> a.split(",")).orElse(new String[0]),
                    info.getLicense().orElse(null),
                    info.getDescription(),
                    dependenciesOf(info),
                    new String[0],
                    container == null ? null : container.getFileName().toString(),
                    container == null ? null : container.toString()));
        }
        return out;
    }

    /** Maps declared dependencies, distinguishing only required from optional. */
    private static Dependency[] dependenciesOf(IModInfo info) {
        List<IModInfo.ModVersion> versions = info.getDependencies();
        if (versions == null || versions.isEmpty()) {
            return new Dependency[0];
        }
        Dependency[] out = new Dependency[versions.size()];
        int n = 0;
        for (IModInfo.ModVersion dep : versions) {
            out[n++] = new Dependency(dep.getModId(),
                    dep.getVersionRange() == null ? null : dep.getVersionRange().toString(),
                    dep.isMandatory() ? Dependency.Kind.REQUIRED : Dependency.Kind.OPTIONAL);
        }
        return out;
    }

    /** Resolves a mod's container file, or {@code null} when it cannot be located. */
    private static Path containerOf(String modId) {
        var fileInfo = ModList.get().getModFileById(modId);
        if (fileInfo == null || fileInfo.getFile() == null) {
            return null;
        }
        return fileInfo.getFile().getFilePath();
    }

    @Override
    protected Collection<RecipeHolder<?>> recipes() {
        return recipes;
    }
}
