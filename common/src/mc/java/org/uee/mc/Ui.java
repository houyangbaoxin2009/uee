package org.uee.mc;

import org.uee.Uee;
import org.uee.config.CommandSurface;
import org.uee.text.UiText;

import net.minecraft.network.chat.Component;

/**
 * Says a catalogue entry in whichever language the reader is using.
 *
 * <h2>Two ways to say the same thing, and when each is right</h2>
 *
 * <p>With nothing configured, a message is sent as a translation <em>key</em> plus its arguments and the
 * game resolves it on the client, in the language that client already chose. That is why following the game
 * is the default and costs nothing: every player reads their own language, a server with players of several
 * languages needs no configuration, and the game's own resource-pack machinery means a pack can correct or
 * extend the wording without this mod knowing.
 *
 * <p>When a language is named in the configuration, the text is resolved here instead and sent as ordinary
 * text. That is for the readers who are not a client with a setting: a log file, a command typed at the
 * console, and a server whose operator wants the same words in the log and on the screen. The two paths use
 * one catalogue, so a message cannot say one thing here and another there.
 *
 * <h2>Why the resolved form is not the only form</h2>
 *
 * <p>Resolving here would be simpler and would be wrong: the sender does not know who is reading, and
 * choosing a language for them is what the setting exists to override rather than to impose.
 */
public final class Ui {

    private Ui() {
    }

    /**
     * A message, in the reader's language or in the configured one.
     *
     * @param key the catalogue key; must exist in the English catalogue
     * @param args the key's arguments, in the order the entry expects them
     */
    public static Component t(String key, Object... args) {
        String locale = Uee.messageLanguage();
        args = UiText.translatable(args);
        if (locale == null || locale.isBlank()) {
            // The client resolves it. Passing the key means a client that has no translation shows the key,
            // so the English entry is asserted to exist rather than hoped for -- see the completeness check.
            return Component.translatable(key, args);
        }
        return Component.literal(UiText.format(locale, key, args));
    }

    /**
     * One catalogue entry as plain text, for a caller that is building a sentence around it.
     *
     * <p>Separate from {@link #t} because a word that goes inside another message cannot be a Component:
     * the outer entry has to hold it as an argument, and the argument has to be text.
     */
    /**
     * This program's own name, in the reader's language.
     *
     * <p>A translation of the name rather than the name itself, because a Chinese reader who is told
     * "Universal Element Exporter: ..." is being addressed in a language the rest of the sentence is not
     * in. The metadata keeps the English name, which is what tools read; this is what a person reads.
     */
    public static String appName() {
        return lookup("uee.app.name");
    }

    public static String lookup(String key) {
        String locale = Uee.messageLanguage();
        return UiText.get(locale == null || locale.isBlank() ? null : locale, key);
    }

    /**
     * A catalogue entry as text, formatted, in the reader's language.
     *
     * <p>For a caller that is about to hand the string to something which takes text rather than a
     * component -- a log line, or the report helper. The component form is {@link #t}, and the difference
     * matters only in that this one has already resolved the language.
     */
    public static String format(String key, Object... args) {
        String locale = Uee.messageLanguage();
        args = UiText.translatable(args);
        return UiText.format(locale == null || locale.isBlank() ? null : locale, key, args);
    }

    
    /**
     * The language a client is using, or empty when there is no client to ask.
     *
     * <p>Asked because "follows the game" is not an answer a reader can act on: someone checking which
     * language the next line will be in wants the name, and the name is knowable. On a dedicated server
     * there is no client, and that is reported as such rather than guessed at.
     *
     * <p>The client class is named only inside this method and only once the side is known to have a
     * client, so a dedicated server never resolves it -- which matters, because there it does not exist.
     */
    public static String clientLocale() {
        try {
            var adapter = Uee.adapter();
            if (adapter == null || adapter.info() == null || !adapter.info().client()) {
                return "";
            }
            var client = net.minecraft.client.Minecraft.getInstance();
            if (client == null || client.getLanguageManager() == null) {
                return "";
            }
            String selected = client.getLanguageManager().getSelected();
            return selected == null ? "" : selected;
        } catch (Throwable t) {
            return "";
        }
    }

    /** What a setting did, in the reader's language. */
    public static Component setting(CommandSurface.Setting setting) {
        return t(setting.key(), setting.args());
    }

    /**
     * A path, as text with nothing attached.
     *
     * <p>Separate from {@link UeeCommand}'s clickable form because a message assembled on this side is
     * already a finished component and the click has to be attached to the part that is a path. Callers
     * that have a source use the clickable one; this is for the rest.
     */
    public static Component pathText(Object path) {
        return Component.literal(String.valueOf(path));
    }
}
