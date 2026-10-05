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
import org.uee.model.Dependency;
import org.uee.model.ModElement;

/**
 * NeoForge adapter. Shared logic lives in {@link AbstractMinecraftAdapter}.
 *
 * <p><b>Compile-unverified.</b> This file cannot be built without a NeoForge toolchain, so its use of
 * the loader API is written against the stable parts of {@code neoforgespi} and has not been through
 * a compiler. The mod list and container paths are the only NeoForge-specific surface UEE touches;
 * everything downstream is shared and tested.
 */
public final class NeoForgeAdapter extends AbstractMinecraftAdapter {

    private volatile ResourceManager resources;
    private volatile Collection<RecipeHolder<?>> recipes = List.of();
    private volatile Translator translator;

    public void bindResources(ResourceManager resourceManager) {
        this.resources = resourceManager;
        // Language tables are captured once per resource reload.
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
        String mcVersion = minecraftVersion();
        for (IModInfo info : infos) {
            Path container = containerOf(info.getModId());
            out.add(new ModElement(
                    info.getModId(),
                    info.getDisplayName(),
                    info.getVersion().toString(),
                    info.getModId(),
                    "neoforge",
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

    /**
     * Maps declared dependencies to their kinds.
     *
     * <p>{@code isMandatory()} is the reliable distinction this loader exposes, so an entry is either
     * required or optional. A declared incompatibility is not distinguished here, which is why the
     * analyzer's incompatibility checks stay silent on this loader rather than guessing.
     */
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

    /**
     * Function resource paths in the active datapacks, found through the server's resource manager.
     *
     * <p><b>Compile-unverified like the rest of this adapter.</b> The enumeration below is the vanilla
     * shape — ask the resource manager for everything under {@code function/} ending in
     * {@code .mcfunction} — which is why no loader-specific API is needed for it. What has to be
     * checked against a real toolchain is the resource manager accessor and the map's value type, not
     * the query.
     *
     * <p>Returns an empty list rather than failing when there is no server yet: a pack's flow
     * functions are only meaningful once a world is loaded, and the rest of the command surface works
     * without them.
     */
    @Override
    protected java.util.List<String> datapackFunctionPaths() {
        net.minecraft.server.MinecraftServer server = server();
        if (server == null) {
            return java.util.List.of();
        }
        java.util.List<String> paths = new java.util.ArrayList<>(16);
        for (net.minecraft.resources.ResourceLocation id : server.getResourceManager()
                .listResources("function", p -> p.getPath().endsWith(".mcfunction"))
                .keySet()) {
            paths.add(id.toString());
        }
        return paths;
    }

    /** The running server, or {@code null} before one exists. Supplied by the entry point. */
    private static volatile net.minecraft.server.MinecraftServer server;

    /** Records the running server, called once the server has started. */
    public static void bindServer(net.minecraft.server.MinecraftServer running) {
        server = running;
    }

    private static net.minecraft.server.MinecraftServer server() {
        return server;
    }
}
