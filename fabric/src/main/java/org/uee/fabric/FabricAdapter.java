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
import org.uee.debug.ModContainerScanner;
import org.uee.debug.ModDescriptorReader;
import org.uee.model.Dependency;
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
    private volatile Collection<RecipeHolder<?>> recipes = List.of();

    /** Called once the resource manager exists (client start or server start). */
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

    /**
     * Builds the mod list.
     *
     * <p>Identity and container path come from Fabric Loader, which is stable. Dependencies come from
     * the mod's own {@code fabric.mod.json} rather than from {@code ModMetadata#getDepends}: the
     * descriptor is a documented format that does not change shape between loader versions, and it is
     * the only source that distinguishes {@code recommends} and {@code breaks} from {@code depends}.
     * Reading the file also makes that logic testable without a game.
     */
    @Override
    protected List<ModElement> loadedMods() {
        List<ModElement> out = new ArrayList<>(loader.getAllMods().size());
        String mcVersion = minecraftVersion();
        for (ModContainer container : loader.getAllMods()) {
            ModMetadata meta = container.getMetadata();
            Path path = containerFile(container);
            ModDescriptorReader.Descriptor descriptor = ModDescriptorReader.read(path);

            String[] authors = descriptor != null && descriptor.authors().length > 0
                    ? descriptor.authors()
                    : meta.getAuthors().stream().map(Person::getName).toArray(String[]::new);
            Dependency[] dependencies = descriptor != null
                    ? descriptor.dependencies() : new Dependency[0];
            String[] providers = descriptor != null ? descriptor.providers() : new String[0];
            String license = descriptor != null && descriptor.license() != null
                    ? descriptor.license()
                    : (meta.getLicense().isEmpty() ? null : String.join(",", meta.getLicense()));

            out.add(new ModElement(
                    meta.getId(),
                    meta.getName(),
                    meta.getVersion().getFriendlyString(),
                    meta.getId(),
                    "fabric",
                    mcVersion,
                    authors,
                    license,
                    meta.getDescription(),
                    dependencies,
                    providers,
                    path == null ? null : path.getFileName().toString(),
                    path == null ? null : path.toString()));
        }
        return out;
    }

    /** Declared mixin configs for every loaded mod, read from the descriptors. */
    public List<org.uee.debug.MixinConfig> declaredMixinConfigs() {
        List<org.uee.debug.MixinConfig> out = new ArrayList<>();
        for (ModContainer container : loader.getAllMods()) {
            Path path = containerFile(container);
            ModDescriptorReader.Descriptor descriptor = ModDescriptorReader.read(path);
            if (descriptor == null) {
                continue;
            }
            for (String resource : descriptor.mixinConfigs()) {
                String text = ModContainerScanner.readText(path, resource);
                if (text == null) {
                    continue;
                }
                try {
                    out.add(org.uee.debug.MixinConfig.parse(resource, descriptor.id(), text));
                } catch (RuntimeException e) {
                    // A config that will not parse is skipped rather than failing the whole scan.
                    continue;
                }
            }
        }
        return out;
    }

    private Path containerFile(ModContainer container) {
        try {
            List<Path> paths = container.getOrigin().getPaths();
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
