package org.uee.globalpack;

import java.util.List;
import java.util.Locale;

/**
 * Mods that already provide global datapacks, and where they keep them.
 *
 * <p>Kept as data rather than as a chain of {@code if}s so that adding a provider is one row, and so
 * the list is readable by someone deciding whether their setup is already covered.
 *
 * <p>Detection is by <b>mod id</b>, not by directory. The risk this whole mechanism exists to avoid
 * is double registration, and only a loaded mod can register anything — a leftover directory with no
 * mod behind it is not handling anything. Directory presence is still recorded, because it explains
 * why a mod is listed and gives a user somewhere to look, but it never decides on its own.
 */
public final class KnownProviders {

    /**
     * One mod that provides global datapacks.
     *
     * @param modId the mod id as the loader reports it
     * @param name the name people know it by
     * @param directories configuration directories it conventionally reads, relative to the game dir
     */
    public record Provider(String modId, String name, List<String> directories) {

        public Provider {
            directories = directories == null ? List.of() : List.copyOf(directories);
        }
    }

    /**
     * The known providers.
     *
     * <p>Only mods whose global-datapack support is their documented purpose are listed. A mod that
     * happens to load a resource pack is not a provider: treating it as one would make UEE stand down
     * for no reason, which is a worse failure than the one being avoided.
     */
    private static final List<Provider> PROVIDERS = List.of(
            new Provider("openloader", "OpenLoader", List.of(
                    "config/openloader/data",
                    "config/openloader/resources")),
            new Provider("paxi", "Paxi", List.of(
                    "config/paxi/datapacks",
                    "config/paxi/datapacks-default",
                    "config/paxi/data")),
            new Provider("global_packs", "Global Packs", List.of(
                    "config/global_packs/data",
                    "global_packs/required_data",
                    "global_packs/optional_data")),
            new Provider("globalpack", "Global Pack", List.of(
                    "config/globalpack",
                    "globalpack")));

    private KnownProviders() {
    }

    /** Every known provider, in declaration order. */
    public static List<Provider> all() {
        return PROVIDERS;
    }

    /** The provider for a mod id, or {@code null} when the id is not a known provider. */
    public static Provider byModId(String modId) {
        if (modId == null || modId.isEmpty()) {
            return null;
        }
        String id = modId.trim().toLowerCase(Locale.ROOT);
        for (Provider p : PROVIDERS) {
            if (p.modId().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** Mod ids of every known provider, for a caller that only needs to test membership. */
    public static List<String> modIds() {
        return PROVIDERS.stream().map(Provider::modId).toList();
    }
}
