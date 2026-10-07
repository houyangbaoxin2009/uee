package org.uee.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The command surface's logic, with no dependency on the game.
 *
 * <h2>Why this is separate from the command tree</h2>
 *
 * <p>Everything here is a pure function of the tokens someone typed: parse them into a partial
 * description, or record it for the rest of the session. None of it needs a command source, a chat
 * channel or a server — only the wiring that reads arguments and sends replies does.
 *
 * <p>Splitting it out is what makes the surface testable. A command class that both parses and
 * replies cannot be exercised without a running game, so in practice its logic is never exercised at
 * all; and a Minecraft mod's command layer is exactly where a mistake is cheapest to miss and most
 * annoying to find. Here the whole vocabulary — every token, every rejection, every session override
 * — is verifiable with no game present.
 *
 * <h2>Why the session is instance state</h2>
 *
 * <p>The accumulated overrides were static mutable state on the command class, which made them
 * impossible to reset between tests and shared across everything that touched the class. An instance
 * is the honest model: a session belongs to whoever is driving it, and a test can hold its own.
 *
 * <h2>Failure is signalled, not printed</h2>
 *
 * <p>A bad token throws {@link IllegalArgumentException} carrying the message a user should see. The
 * command layer catches it and sends it wherever the source happens to point. That keeps the wording
 * in one place — testable, and identical from a player, a function and the console — instead of
 * spread across a dozen reply callbacks.
 */
public final class CommandSurface {

    /**
     * The outcome of a {@code set} verb.
     *
     * <p>Carries the catalogue key and its arguments rather than a finished sentence, so whoever says it
     * can say it in the reader's language. The English rendering is available here as well, because a log
     * line and a test have no reader and would otherwise need the whole resolution chain to be reachable
     * from them.
     *
     * @param layer the partial description to add to the session
     * @param key the catalogue key for what changed
     * @param args the key's arguments
     * @param count how many things the verb acted on, for the command's return value
     */
    public record Setting(ConfigFile layer, String key, Object[] args, int count) {

        public Setting {
            if (layer == null) {
                throw new IllegalArgumentException(
                        org.uee.text.UiText.get(null, "uee.err.settingMustCarryLayer"));
            }
        }

        /** What changed, in English; for a log line or a test rather than for a reader. */
        public String description() {
            return org.uee.text.UiText.format(null, key, args);
        }
    }

    private final List<ConfigFile> session = new ArrayList<>(8);
    private final List<String> log = new ArrayList<>(8);

    // ---------------------------------------------------------------- export verbs

    /**
     * Parses a category list.
     *
     * <p>Lower-cases its tokens: the category vocabulary is lower case by convention, and accepting
     * {@code ITEMS} costs nothing.
     *
     * @throws IllegalArgumentException when a token names no category
     */
    public static ConfigFile kinds(String arg) {
        if (arg == null || arg.isBlank()) {
            return ConfigFile.empty();
        }
        Set<org.uee.model.ElementKind> kinds = Tokens.kinds(lowerTokens(arg));
        if (kinds == null) {
            throw new IllegalArgumentException(org.uee.text.UiText.format(null, "uee.err.unknownCategory", arg));
        }
        return ConfigFile.builder().kinds(kinds).build();
    }

    /** Parses a format list. */
    public static ConfigFile formats(String arg) {
        if (arg == null || arg.isBlank()) {
            return ConfigFile.empty();
        }
        Set<String> formats = Tokens.formats(lowerTokens(arg));
        if (formats == null) {
            throw new IllegalArgumentException(org.uee.text.UiText.format(null, "uee.err.unknownFormat", arg));
        }
        return ConfigFile.builder().formats(formats).build();
    }

    /**
     * Parses the two optional lists of the export verb together.
     *
     * <p>Combined rather than layered by the caller so "nothing was typed" stays distinguishable from
     * "an empty category list was typed" — the first means defaults, the second would mean nothing.
     */
    public static ConfigFile kindsAndFormats(String kindsArg, String formatsArg) {
        boolean asksKinds = kindsArg != null && !kindsArg.isBlank();
        boolean asksFormats = formatsArg != null && !formatsArg.isBlank();
        Set<org.uee.model.ElementKind> kinds = asksKinds
                ? Tokens.kinds(lowerTokens(kindsArg)) : null;
        if (asksKinds && kinds == null) {
            throw new IllegalArgumentException(org.uee.text.UiText.format(null, "uee.err.unknownCategory", kindsArg));
        }
        Set<String> formats = asksFormats ? Tokens.formats(lowerTokens(formatsArg)) : null;
        if (asksFormats && formats == null) {
            throw new IllegalArgumentException(org.uee.text.UiText.format(null, "uee.err.unknownFormat", formatsArg));
        }
        if (kinds == null && formats == null) {
            return ConfigFile.empty();
        }
        // One description carrying both, rather than one layered on the other: the two describe
        // disjoint settings, and layering them would make the order matter for no reason.
        ConfigFile.Builder b = ConfigFile.builder();
        if (kinds != null) {
            b.kinds(kinds);
        }
        if (formats != null) {
            b.formats(formats);
        }
        return b.build();
    }

    // ---------------------------------------------------------------- set verbs

    /**
     * Sets which fields records carry.
     *
     * @param exclude false to name what to keep, true to name what to drop
     */
    public Setting setFields(String arg, boolean exclude) {
        Set<String> fields = rawTokens(arg);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException(
                    org.uee.text.UiText.get(null, "uee.err.badFields"));
        }
        ConfigFile.Builder b = ConfigFile.builder();
        if (exclude) {
            b.excludeFields(fields);
        } else {
            b.includeFields(fields);
        }
        return remember(new Setting(b.build(),
                exclude ? "uee.set.fields.dropping" : "uee.set.fields.only",
                new Object[] {String.join(", ", fields)}, fields.size()));
    }

    /** Sets a tag filter. */
    public Setting setTags(String arg, boolean exclude) {
        Set<String> tags = rawTokens(arg);
        if (tags.isEmpty()) {
            throw new IllegalArgumentException(
                    org.uee.text.UiText.get(null, "uee.err.badTags"));
        }
        ConfigFile.Builder b = ConfigFile.builder();
        if (exclude) {
            b.excludeTags(tags);
        } else {
            b.includeTags(tags);
        }
        return remember(new Setting(b.build(),
                exclude ? "uee.set.tags.skipping" : "uee.set.tags.only",
                new Object[] {String.join(", ", tags)}, tags.size()));
    }

    /** Sets a namespace filter. */
    public Setting setNamespaces(String arg, boolean exclude) {
        Set<String> namespaces = rawTokens(arg);
        if (namespaces.isEmpty()) {
            throw new IllegalArgumentException(
                    org.uee.text.UiText.get(null, "uee.err.badNamespaces"));
        }
        ConfigFile.Builder b = ConfigFile.builder();
        for (String ns : namespaces) {
            if (exclude) {
                b.excludeNamespace(ns);
            } else {
                b.includeNamespace(ns);
            }
        }
        return remember(new Setting(b.build(),
                exclude ? "uee.set.namespaces.skipping" : "uee.set.namespaces.only",
                new Object[] {String.join(", ", namespaces)}, namespaces.size()));
    }

    /** Sets a mod exclusion. */
    public Setting setSkipMods(String arg) {
        Set<String> mods = rawTokens(arg);
        if (mods.isEmpty()) {
            throw new IllegalArgumentException(
                    org.uee.text.UiText.get(null, "uee.err.badMods"));
        }
        ConfigFile.Builder b = ConfigFile.builder();
        for (String id : mods) {
            b.excludeMod(id);
        }
        return remember(new Setting(b.build(), "uee.set.mods.skipping",
                new Object[] {String.join(", ", mods)}, mods.size()));
    }

    /** Sets the record count per shard. */
    public Setting setShards(int records) {
        if (records <= 0) {
            throw new IllegalArgumentException(
                    org.uee.text.UiText.get(null, "uee.err.badShards"));
        }
        return remember(new Setting(ConfigFile.builder().shardSize(records).build(),
                "uee.set.shards", new Object[] {records}, records));
    }

    /**
     * Sets the byte budget per file, where zero disables it.
     *
     * <p>Zero is a meaningful setting rather than a mistake — it means "split by record count" — so it
     * is accepted where a negative value is not.
     */
    public Setting setMaxFileMb(int mb) {
        if (mb < 0) {
            throw new IllegalArgumentException(
                    org.uee.text.UiText.get(null, "uee.err.badMaxFile"));
        }
        return remember(new Setting(ConfigFile.builder().maxFileMb(mb).build(),
                mb == 0 ? "uee.set.maxFile.none" : "uee.set.maxFile.limit",
                mb == 0 ? new Object[0] : new Object[] {mb}, mb));
    }

    /** Sets the output directory. */
    public Setting setOutput(String dir) {
        if (dir == null || dir.isBlank()) {
            throw new IllegalArgumentException(
                    org.uee.text.UiText.get(null, "uee.err.badOutput"));
        }
        return remember(new Setting(ConfigFile.builder().output(Paths.get(dir.trim())).build(),
                "uee.set.output", new Object[] {dir.trim()}, 1));
    }

    /**
     * Sets a boolean toggle.
     *
     * @throws IllegalArgumentException when the key is not a settable flag
     */
    public Setting setFlag(String key, boolean value) {
        ConfigFile.Builder b = ConfigFile.builder();
        String catalogKey;
        Object[] args = new Object[0];
        switch (key) {
            case "quiet" -> {
                b.quiet(value);
                catalogKey = value ? "uee.set.quiet" : "uee.set.verbose";
            }
            case "dry_run" -> {
                b.dryRun(value);
                catalogKey = value ? "uee.set.dryRun" : "uee.set.writing";
            }
            case "icons" -> {
                b.icons(value);
                catalogKey = "uee.set.icons";
                args = new Object[] {value};
            }
            case "assets" -> {
                b.assets(value);
                catalogKey = value ? "uee.set.assets.on" : "uee.set.assets.off";
            }
            case "delta" -> {
                b.delta(value);
                catalogKey = value ? "uee.set.delta.on" : "uee.set.delta.off";
            }
            case "auto_run" -> {
                b.autoRun(value);
                catalogKey = value ? "uee.set.autoRun.on" : "uee.set.autoRun.off";
            }
            default -> throw new IllegalArgumentException(
                    org.uee.text.UiText.format(null, "uee.set.unknown", key));
        }
        return remember(new Setting(b.build(), catalogKey, args, 1));
    }

    /**
     * Sets the whitelist of settings that are kept between runs.
     *
     * <p>An empty argument clears it rather than being an error, because "keep nothing" is a legitimate
     * thing to want and the alternative — a separate verb for it — would be a second way to say one thing.
     * Names that cannot be kept are dropped by the writer and reported there, not here: this verb's job is
     * to record what was asked for, and the place that knows which names are usable is the place that writes
     * them.
     */
    public Setting setPersist(String arg) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        if (arg != null) {
            for (String token : arg.split("[,\\s]+")) {
                if (!token.isBlank()) {
                    keys.add(token.trim());
                }
            }
        }
        return remember(new Setting(ConfigFile.builder().persist(keys).build(),
                keys.isEmpty() ? "uee.set.persist.none" : "uee.set.persist",
                keys.isEmpty() ? new Object[0] : new Object[] {String.join(", ", keys)},
                keys.size()));
    }

    /**
     * Sets where the portable state lives.
     *
     * <p>An empty argument means the default rather than the current directory, since a user clearing a
     * path is asking for the normal place and not for whatever directory the game happens to be in.
     */
    public Setting setUserDir(String arg) {
        String trimmed = arg == null ? "" : arg.trim();
        ConfigFile.Builder b = ConfigFile.builder();
        if (trimmed.isEmpty()) {
            b.userDir(null);
            return remember(new Setting(b.build(), "uee.set.userDir.default", new Object[0], 1));
        }
        b.userDir(java.nio.file.Path.of(trimmed));
        return remember(new Setting(b.build(), "uee.set.userDir", new Object[] {trimmed}, 1));
    }

    /**
     * Sets the language messages are said in, or follows the reader when given nothing.
     *
     * <p>Refused when no catalogue exists for the name. A language with no catalogue would fall back to
     * English and look as though the setting had been applied, which is the failure this refuses rather
     * than accepts: the answer to "is it in effect" has to be visible in what is said next.
     */
    public Setting setLanguage(String arg) {
        String wanted = arg == null ? "" : arg.trim().toLowerCase(java.util.Locale.ROOT);
        if (wanted.isEmpty()) {
            return remember(new Setting(ConfigFile.builder().language(null).build(),
                    "uee.set.language.game", new Object[0], 1));
        }
        if (!org.uee.text.UiText.isAvailable(wanted)) {
            throw new IllegalArgumentException(org.uee.text.UiText.format(null,
                    "uee.err.languageUnknown", wanted,
                    String.join(", ", org.uee.text.UiText.availableLocales())));
        }
        return remember(new Setting(ConfigFile.builder().language(wanted).build(),
                "uee.set.language", new Object[] {wanted}, 1));
    }

    // ---------------------------------------------------------------- the session

    /**
     * Adds a setting to the session and returns it.
     *
     * <p>Each setting is kept as its own layer rather than merged into one. The resolver already
     * applies a stack, so a session override is not a special case there; merging here would be a
     * second implementation of layering, and the two would drift.
     */
    public Setting remember(Setting setting) {
        session.add(setting.layer());
        log.add(setting.description());
        return setting;
    }

    /**
     * The layers this session has accumulated, oldest first.
     *
     * <p>Ordered oldest first so the newest setting wins, which is what someone typing one
     * {@code set} after another expects.
     */
    public List<ConfigFile> layers() {
        return List.copyOf(session);
    }

    /** What has been set this session, in order, for reporting. */
    public List<String> log() {
        return List.copyOf(log);
    }

    /** How many settings are in effect. */
    public int size() {
        return session.size();
    }

    /** Forgets every session setting. Used when a test or a reload wants a clean slate. */
    public void clear() {
        session.clear();
        log.clear();
    }

    // ---------------------------------------------------------------- tokenising

    /**
     * Splits on commas and whitespace and lower-cases, for the case-insensitive vocabularies.
     *
     * <p>Both separators are accepted because a command line and a config file read differently: a
     * config file spells a list {@code ["a","b"]}, and someone typing a command naturally writes
     * {@code a b} or {@code a,b}.
     */
    static List<String> lowerTokens(String arg) {
        List<String> out = new ArrayList<>(4);
        if (arg == null) {
            return out;
        }
        for (String piece : arg.split("[,\\s]+")) {
            if (!piece.isBlank()) {
                out.add(piece.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * Splits on commas and whitespace, preserving case.
     *
     * <p>Used where case is meaningful: a tag id and a namespace are not lower-cased by convention,
     * and a field name must match the writer's spelling exactly.
     */
    static Set<String> rawTokens(String arg) {
        Set<String> out = new LinkedHashSet<>();
        if (arg == null) {
            return out;
        }
        for (String piece : arg.split("[,\\s]+")) {
            if (!piece.isBlank()) {
                out.add(piece.trim());
            }
        }
        return out;
    }

    /** Where a relative output path is rooted, for reporting. Kept here so nothing else hard-codes it. */
    public static Path resolveOutput(Path gameDirectory, String dir) {
        Path candidate = Paths.get(dir);
        return candidate.isAbsolute() || gameDirectory == null
                ? candidate : gameDirectory.resolve(candidate);
    }
}
