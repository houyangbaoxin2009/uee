package org.uee;

import java.util.List;
import java.util.Set;
import org.uee.config.CommandSurface;
import org.uee.config.ConfigFile;
import org.uee.config.ConfigResolver;
import org.uee.config.ExportConfig;
import org.uee.model.ElementKind;

/**
 * Checks the command surface's logic.
 *
 * <h2>What this replaces</h2>
 *
 * <p>None of this could be verified before: the parsing lived in the class that also sends replies,
 * and that class needs Brigadier and a chat channel to load at all. So the command vocabulary — every
 * token, every rejection, every session override — was the one part of the system with no coverage
 * whatsoever, which is a poor place for that to be true: it is the surface users touch first and the
 * one whose mistakes are hardest to attribute.
 *
 * <p>The tests are deliberately about wording as well as behaviour. A refusal is only useful if it
 * says which word was wrong and what to type instead, and message text is exactly the kind of thing
 * that rots silently once nothing asserts it.
 *
 * <h2>Why a fresh surface per section</h2>
 *
 * <p>The session is instance state. That is the reason it was moved out: as static mutable state it
 * could not be reset between tests, so a test could not tell its own settings from a previous one's.
 */
public final class CommandSurfaceTest {

    private static int failures;

    public static void main(String[] args) {
        staticInputs();
        settingVerbs();
        rejectionMessages();
        everyPersistableKeyIsSettable();
        sessionStack();
        sessionPrecedence();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- the export verbs

    private static void staticInputs() {
        section("export verb inputs");

        // Categories: a token, a list, both separators, and case.
        check("a category token resolves",
                applied(CommandSurface.kinds("items")).kinds().equals(Set.of(ElementKind.ITEM)));
        check("a plural resolves to the same thing",
                applied(CommandSurface.kinds("items")).kinds()
                        .equals(applied(CommandSurface.kinds("item")).kinds()));
        check("a comma list resolves",
                applied(CommandSurface.kinds("items,blocks")).kinds()
                        .equals(Set.of(ElementKind.ITEM, ElementKind.BLOCK)));
        check("a space list resolves",
                applied(CommandSurface.kinds("items blocks entities")).kinds().size() == 3);
        check("case is accepted",
                applied(CommandSurface.kinds("ITEMS")).kinds().equals(Set.of(ElementKind.ITEM)));
        check("a group token resolves to more than one",
                applied(CommandSurface.kinds("common")).kinds().size() > 3);
        check("empty means nothing was asked for",
                CommandSurface.kinds("").isEmpty() && CommandSurface.kinds(null).isEmpty());

        check("a format token resolves",
                applied(CommandSurface.formats("json")).formats().equals(Set.of(ExportConfig.JSON)));
        check("a format list resolves",
                applied(CommandSurface.formats("json,ndjson")).formats().size() == 2);
        check("empty formats mean nothing was asked for",
                CommandSurface.formats("  ").isEmpty());

        // The combined form: both halves must land, and one absent must not erase the other.
        ExportConfig both = applied(CommandSurface.kindsAndFormats("items", "td"));
        check("both halves land",
                both.kinds().equals(Set.of(ElementKind.ITEM))
                        && both.formats().equals(Set.of(ExportConfig.TD)));
        check("kinds alone leaves formats at their default, not unset",
                applied(CommandSurface.kindsAndFormats("items", null)).formats()
                        .equals(ExportConfig.builder().build().formats()));
        check("kinds alone still changes the categories",
                !applied(CommandSurface.kindsAndFormats("items", null)).kinds()
                        .equals(ExportConfig.builder().build().kinds()));
        check("formats alone leaves kinds at their default",
                applied(CommandSurface.kindsAndFormats(null, "td")).kinds()
                        .equals(ExportConfig.builder().build().kinds()));
        check("neither leaves an empty description",
                CommandSurface.kindsAndFormats(null, null).isEmpty());
        // An empty string and an absent argument both mean "not asked for", and that is deliberate:
        // a blank where a list belongs is a typo, and treating it as "select nothing" would produce a
        // run with no content instead of the defaults.
        check("a blank list means the same as no list",
                CommandSurface.kindsAndFormats("", "").isEmpty()
                        && CommandSurface.kindsAndFormats(null, null).isEmpty());
    }


    // ---------------------------------------------------------------- the set verbs

    private static void settingVerbs() {
        section("setting verbs");

        CommandSurface s = new CommandSurface();

        // Fields, both directions.
        CommandSurface.Setting keep = s.setFields("tags name", false);
        check("include fields becomes a mask", applied(keep).fields().include().size() == 2);
        check("include fields counts what it was given", keep.count() == 2);
        check("the description names what was kept",
                keep.description().contains("tags") && keep.description().contains("name"));
        check("the description says identity is safe",
                keep.description().contains("identity"));

        CommandSurface.Setting drop = new CommandSurface().setFields("tags", true);
        check("exclude fields says so", drop.description().contains("dropping"));

        // Tags.
        CommandSurface.Setting tags = new CommandSurface().setTags("c:gems example:gems", false);
        check("tags are kept as typed, case and colon intact",
                applied(tags).includeTags().contains("c:gems")
                        && applied(tags).includeTags().contains("example:gems"));
        check("tag description warns about untagged elements",
                tags.description().contains("untagged"));
        CommandSurface.Setting skipTags = new CommandSurface().setTags("c:dirt", true);
        check("skip-tags lands on the exclude side",
                applied(skipTags).excludeTags().contains("c:dirt"));

        // Namespaces and mods.
        CommandSurface.Setting ns = new CommandSurface().setNamespaces("example minecraft", false);
        check("namespaces are preserved verbatim",
                applied(ns).acceptsNamespace("example")
                        && applied(ns).acceptsNamespace("minecraft"));
        check("skip-namespaces lands on the exclude side",
                !applied(new CommandSurface().setNamespaces("example", true))
                        .acceptsNamespace("example"));
        CommandSurface.Setting mods = new CommandSurface().setSkipMods("loner");
        check("skip-mods lands on the exclude side",
                applied(mods).excludeMods().contains("loner"));

        // Numbers.
        check("shard size lands", applied(new CommandSurface().setShards(500)).shardSize() == 500);
        check("a byte budget lands",
                applied(new CommandSurface().setMaxFileMb(4)).maxFileBytes() == 4L * 1024 * 1024);
        // Zero is a setting, not a mistake: it means "split by count".
        CommandSurface.Setting none = new CommandSurface().setMaxFileMb(0);
        check("a zero byte budget is accepted and explained",
                applied(none).maxFileBytes() == 0 && none.description().contains("record count"));

        // Output path.
        check("output lands",
                applied(new CommandSurface().setOutput("exports/here")).outputDir()
                        .toString().replace('\\', '/').equals("exports/here"));

        // Flags.
        check("quiet can be turned on",
                applied(new CommandSurface().setFlag("quiet", true)).quiet());
        check("quiet can be turned off",
                new CommandSurface().setFlag("quiet", false).description().contains("verbose"));
        check("dry-run can be turned on",
                new CommandSurface().setFlag("dry_run", true).description().contains("dry run"));

        // Every setting must be one the config file also understands, or the two interfaces differ.
        CommandSurface.Setting[] all = {
                new CommandSurface().setFields("tags", false),
                new CommandSurface().setFields("tags", true),
                new CommandSurface().setTags("c:gems", false),
                new CommandSurface().setTags("c:dirt", true),
                new CommandSurface().setNamespaces("example", false),
                new CommandSurface().setNamespaces("example", true),
                new CommandSurface().setSkipMods("loner"),
                new CommandSurface().setShards(10),
                new CommandSurface().setMaxFileMb(2),
                new CommandSurface().setOutput("out"),
                new CommandSurface().setFlag("quiet", true),
                new CommandSurface().setFlag("dry_run", true),
                new CommandSurface().setFlag("icons", true),
        };
        boolean allKnown = true;
        for (CommandSurface.Setting setting : all) {
            for (String key : setting.layer().setKeys()) {
                if (!ConfigFile.knownKeys().contains(key)) {
                    allKnown = false;
                    System.out.println("      not a config key: " + key);
                }
            }
        }
        check("every command setting is also a config file key", allKnown);

        // And each one survives a config round trip, which is what "the same setting" has to mean.
        boolean roundTrips = true;
        for (CommandSurface.Setting setting : all) {
            ConfigFile parsed = ConfigFile.parse(ConfigFile.render(setting.layer()
                    .applyTo(ExportConfig.builder().build())));
            if (!parsed.unknownKeys().isEmpty()) {
                roundTrips = false;
                System.out.println("      unreadable keys: " + parsed.unknownKeys());
            }
        }
        check("every command setting round-trips through a written config", roundTrips);
    }

    // ---------------------------------------------------------------- refusals

    /**
     * Every key that can be kept must also be settable from the surface.
     *
     * <p>The property this catches is the one that made this check necessary: {@code assets}, {@code delta},
     * {@code auto_run}, {@code persist} and {@code user_dir} each had a field, a parser branch and a place in
     * the generated configuration, and no verb to set them — so the documentation described options a user
     * could not actually turn on, and the only way to use them was to edit a file by hand. That is
     * indistinguishable from the option not existing, until someone tries.
     *
     * <p>It is the same shape as the check that every nameable key can be written, one layer up: there the
     * question was whether the writer knew the key, here it is whether the user can reach it.
     */
    private static void everyPersistableKeyIsSettable() {
        section("every key can be reached");

        CommandSurface surface = new CommandSurface();

        // The boolean keys, each of which has a flag verb. The list is written out rather than derived,
        // because deriving it would be another table to keep in step; what is checked is that each one works.
        for (String key : new String[] {"quiet", "dry_run", "icons", "assets", "delta", "auto_run"}) {
            boolean[] accepted = {true};
            try {
                surface.setFlag(key, true);
                surface.setFlag(key, false);
            } catch (IllegalArgumentException e) {
                accepted[0] = false;
            }
            check("the '" + key + "' flag can be set both ways", accepted[0]);
        }

        // And the ones that take a value.
        boolean persist = true;
        try {
            surface.setPersist("formats, output");
            surface.setPersist("");
        } catch (RuntimeException e) {
            persist = false;
        }
        check("the persistence list can be set, and cleared", persist);

        boolean userDir = true;
        try {
            surface.setUserDir("/somewhere");
            surface.setUserDir("");
        } catch (RuntimeException e) {
            userDir = false;
        }
        check("the state directory can be set, and reset to the default", userDir);

        // A key nothing recognises is still refused, so the surface did not become a catch-all.
        expectRefusal("an unknown flag is still refused", () -> surface.setFlag("nonsense", true));
    }

    private static void rejectionMessages() {
        section("refusals");

        expectRefusal("an unknown category", () -> CommandSurface.kinds("nonsense"));
        expectRefusalSaying("an unknown category names the word", () -> CommandSurface.kinds("nonsense"),
                "nonsense");
        expectRefusalSaying("an unknown category says where to look",
                () -> CommandSurface.kinds("nonsense"), "/uee kinds");
        expectRefusal("an unknown format", () -> CommandSurface.formats("nonsense"));
        // The listing verb, not the setting verb: /uee formats takes an argument and sets, so pointing a
        // reader at it would tell them to do the thing that just failed.
        expectRefusalSaying("an unknown format says where to look",
                () -> CommandSurface.formats("nonsense"), "/uee listformats");

        // A partially wrong list must be refused, not silently narrowed to the valid part -- a run
        // that quietly exports less than asked is the worst outcome.
        expectRefusal("one bad token in a list refuses the whole list",
                () -> CommandSurface.kinds("items,nonsense"));

        expectRefusal("fields with nothing named",
                () -> new CommandSurface().setFields("   ", false));
        expectRefusal("tags with nothing named",
                () -> new CommandSurface().setTags("", false));
        expectRefusal("namespaces with nothing named",
                () -> new CommandSurface().setNamespaces(null, false));
        expectRefusal("mods with nothing named",
                () -> new CommandSurface().setSkipMods(" , "));
        expectRefusal("output with nothing named",
                () -> new CommandSurface().setOutput("  "));
        expectRefusalSaying("output refusal shows the syntax",
                () -> new CommandSurface().setOutput(null), "/uee set output");
        expectRefusal("a shard size of zero", () -> new CommandSurface().setShards(0));
        expectRefusal("a negative byte budget", () -> new CommandSurface().setMaxFileMb(-1));
        expectRefusal("an unknown flag", () -> new CommandSurface().setFlag("nonsense", true));
        expectRefusalSaying("an unknown flag names it",
                () -> new CommandSurface().setFlag("nonsense", true), "nonsense");

        // A refused setting must not have been recorded: a session carrying a half-applied setting
        // would apply it to every later run with nothing to explain it.
        CommandSurface s = new CommandSurface();
        try {
            s.setShards(0);
        } catch (IllegalArgumentException expected) {
            // as designed
        }
        check("a refused setting is not recorded", s.size() == 0 && s.layers().isEmpty());
    }

    // ---------------------------------------------------------------- the session

    private static void sessionStack() {
        section("the session stack");

        CommandSurface s = new CommandSurface();
        check("a new session is empty", s.size() == 0 && s.layers().isEmpty() && s.log().isEmpty());

        s.setTags("c:gems", false);
        s.setShards(50);
        check("two settings make two layers", s.size() == 2);
        check("the log describes both", s.log().size() == 2);
        check("layers keep the first", applied(s.layers().get(0)).includeTags().contains("c:gems"));
        check("layers keep the second", applied(s.layers().get(1)).shardSize() == 50);
        // Oldest first is what makes a later setting win once the resolver applies them in order.
        check("the log is in the order they were made",
                s.log().get(0).contains("tagged") && s.log().get(1).contains("shard size"));

        // Clearing is why the state had to stop being static.
        s.clear();
        check("clearing empties the session", s.size() == 0 && s.log().isEmpty());
        check("and a cleared session no longer contributes layers", s.layers().isEmpty());

        // Two surfaces are independent, which was impossible with static state.
        CommandSurface a = new CommandSurface();
        CommandSurface b = new CommandSurface();
        a.setTags("c:gems", false);
        check("one session does not see another's settings",
                a.size() == 1 && b.size() == 0);
    }

    private static void sessionPrecedence() {
        section("where session settings sit");

        ExportConfig defaults = ExportConfig.builder().build();

        // The whole point of the long form: a session setting applies to later runs, and a setting
        // named on the run beats it. Verified through the resolver, which is the real consumer.
        CommandSurface s = new CommandSurface();
        s.setShards(11);
        ConfigFile perRun = ConfigFile.builder().shardSize(99).build();

        List<ConfigFile> inner = List.of(s.layers().get(0));
        ConfigResolver.Resolved runOverride = ConfigResolver.resolve(defaults,
                List.of(inner.get(0), perRun));
        check("the run's own setting wins over the session",
                runOverride.config().shardSize() == 99);

        ConfigResolver.Resolved sessionOnly = ConfigResolver.resolve(defaults, inner);
        check("with nothing on the run, the session setting applies",
                sessionOnly.config().shardSize() == 11);

        ConfigResolver.Resolved neither = ConfigResolver.resolve(defaults, List.of());
        check("with no session, the default applies",
                neither.config().shardSize() == defaults.shardSize());

        // Later session settings beat earlier ones, which is what typing one after another means.
        CommandSurface ordered = new CommandSurface();
        ordered.setShards(7);
        ordered.setShards(13);
        ConfigResolver.Resolved last = ConfigResolver.resolve(defaults, ordered.layers());
        check("the newest session setting wins", last.config().shardSize() == 13);

        // A session setting and the config file: the explicit one must win, or a user who set
        // something for one session would be silently overruled by a file they forgot about.
        ConfigFile file = ConfigFile.parse("""
                type tie<data>

                uee = [
                  shard_size = 5
                ]
                """);
        ConfigResolver.Resolved overFile = ConfigResolver.resolve(defaults,
                List.of(file, ordered.layers().get(0)));
        check("the session beats the file, being the more recent intent",
                overFile.config().shardSize() == 7);
    }

    // ---------------------------------------------------------------- harness

    private static void expectRefusal(String what, Runnable work) {
        boolean refused = false;
        try {
            work.run();
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        check(what + " is refused", refused);
    }

    private static void expectRefusalSaying(String what, Runnable work, String needle) {
        String message = null;
        try {
            work.run();
        } catch (IllegalArgumentException e) {
            message = e.getMessage();
        }
        check(what, message != null && message.contains(needle));
        if (message == null || !message.contains(needle)) {
            System.out.println("      got: " + message);
        }
    }

    /**
     * Applies a setting and returns the configuration it produces.
     *
     * <p>The assertion target, rather than the partial description the setting builds: the description
     * is a write-only intermediate with no accessors, and "what configuration does this produce" is
     * both the real question and the thing a user would notice being wrong.
     */
    private static ExportConfig applied(CommandSurface.Setting setting) {
        return applied(setting.layer());
    }

    private static ExportConfig applied(ConfigFile layer) {
        return layer.applyTo(ExportConfig.builder().build());
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
