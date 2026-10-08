package org.uee.mc;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import org.uee.Ueea;
import org.uee.text.UiText;

/**
 * Everything this mod says, in the reader's language.
 *
 * <p>The exporter has a layer of its own and this is not it, for two reasons. That one builds sentences
 * about exporting, and it consults the exporter's configuration to decide which language to use -- neither
 * of which belongs to a mod that does not export anything.
 *
 * <p>What is shared is the catalogue mechanism, which is where the translations live and which is in the
 * core. The keys are prefixed with this mod's id, so the two catalogues sit beside each other and neither
 * can answer for the other.
 */
public final class UeeaUi {

    private UeeaUi() {
    }

    /** The key prefix, so a lookup cannot land in the other mod's entries. */
    public static final String PREFIX = Ueea.MOD_ID + ".";

    /** The locale to say things in: the client's, since there is nothing else to consult yet. */
    public static String locale() {
        String client = clientLocale();
        return client.isEmpty() ? "en_us" : client;
    }

    /** The client's own locale, or an empty string when it cannot be read. */
    public static String clientLocale() {
        try {
            String selected = net.minecraft.client.Minecraft.getInstance().options.languageCode;
            return selected == null ? "" : selected;
        } catch (Throwable t) {
            // Nothing is running, which is the case in a test and during early loading.
            return "";
        }
    }

    /** A translated string, with the mod's own prefix applied. */
    public static String get(String key, Object... args) {
        return UiText.format(locale(), PREFIX + key, args);
    }

    /** A translatable component, for a caller that wants one. */
    public static MutableComponent t(String key, Object... args) {
        return Component.literal(get(key, args));
    }

    /** The heading line, so both commands start the same way. */
    public static Component headline() {
        String version = Ueea.modVersion();
        return Component.literal(Ueea.NAME + (version.isEmpty() ? "" : " " + version))
                .withStyle(ChatFormatting.WHITE);
    }
}
