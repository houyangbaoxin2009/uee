package org.uee;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.uee.config.ConfigFile;
import org.uee.config.ExportConfig;
import org.uee.state.SettingsMember;
import org.uee.state.UserStore;

/**
 * Checks the portable state's rules: where it goes, what it will and will not keep, and what it does by
 * default.
 *
 * <h2>What earns a place here</h2>
 *
 * <p>Three things, none of which a round trip of a well-formed file would catch. <b>The default is off</b> —
 * the whole feature is opt-in, and a default that drifted would mean files appearing on other people's disks
 * unasked. <b>The controlling keys cannot be kept</b>, because a setting that makes itself permanent through
 * the mechanism it controls has no way to be corrected. And <b>every key that can be named can actually be
 * written</b>: a whitelist naming a key the renderer does not know would silently keep nothing, which reads
 * exactly like the setting not working.
 */
public final class PortableStateTest {

    private static int failures;

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "build/portable-state-test");
        deleteRecursively(root);
        Files.createDirectories(root);

        defaults();
        whatMayBeKept();
        everyNamedKeyCanBeWritten();
        keepingAndNotKeeping(root);

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- defaults

    private static void defaults() {
        section("what it does without being asked");

        ExportConfig bare = ExportConfig.builder().build();

        // The whole feature is opt-in. A default that drifted here would mean exports appearing on other
        // people's disks without them asking, which is the one outcome the option exists to avoid.
        check("an export is not run at startup unless asked", !bare.autoRun());
        check("and nothing is declared to stick", bare.persist().isEmpty());
        check("and the state directory is left to the environment and the home directory",
                bare.userDir() == null);

        // Absent differs from empty: only an absent directory setting should fall through to the
        // environment variable and then the home directory. An explicit empty string means "the default",
        // which is the same destination but reached deliberately.
        check("a directory can be set", ExportConfig.builder().userDir(Path.of("x")).build()
                .userDir() != null);
        check("and cleared again", ExportConfig.builder().userDir(Path.of("x")).userDir(null).build()
                .userDir() == null);
    }

    // ---------------------------------------------------------------- the whitelist

    private static void whatMayBeKept() {
        section("what may be kept, and what may never be");

        ExportConfig named = ExportConfig.builder().persist(Set.of("formats", "kinds", "assets")).build();
        List<String> kept = Uee.persistableKeys(named);
        check("the named keys are kept", kept.size() == 3 && kept.contains("formats"));

        // The controlling keys are excluded by rule, not by default. A user who set one wrongly would have
        // made it permanent through the mechanism it controls, and the surface that would let them correct
        // it reads the same file -- so there would be no way back.
        check("the persistence controls are named as such",
                Uee.PERSISTENCE_CONTROLS.contains("persist")
                        && Uee.PERSISTENCE_CONTROLS.contains("user_dir"));
        ExportConfig sneaky = ExportConfig.builder()
                .persist(Set.of("persist", "user_dir", "formats")).build();
        List<String> filtered = Uee.persistableKeys(sneaky);
        check("asking for them anyway is refused", !filtered.contains("persist")
                && !filtered.contains("user_dir"));
        check("while the ordinary key in the same list still goes through",
                filtered.equals(List.of("formats")));

        // A name nothing recognises is dropped rather than stored: a state file holding a key the reader
        // does not know fails its own validation, turning a typo into a file that no longer loads.
        ExportConfig typo = ExportConfig.builder().persist(Set.of("formats", "no_such_key")).build();
        check("an unrecognised name is dropped rather than stored",
                Uee.persistableKeys(typo).equals(List.of("formats")));

        check("nothing declared keeps nothing",
                Uee.persistableKeys(ExportConfig.builder().build()).isEmpty());
        check("and null is handled", Uee.persistableKeys(null).isEmpty());
    }

    /**
     * Every key that may be named must be one the renderer can actually write.
     *
     * <p>The subtle failure this catches: a whitelist naming a key the renderer does not know would keep
     * nothing for that key, and the result would be indistinguishable from the setting not working at all.
     * It is the same shape as the registry table's check that every declared category has a collector, and
     * it earns its place the same way — a declaration nothing can act on looks exactly like one that works,
     * until something tries.
     */
    private static void everyNamedKeyCanBeWritten() {
        section("a name that can be declared can be written");

        List<String> known = ConfigFile.renderableKeys();
        check("there are keys to name", !known.isEmpty());

        ExportConfig configured = ExportConfig.builder()
                .formats("json", "ndjson")
                .build();
        String rendered = ConfigFile.renderKeys(configured, known);
        check("rendering every known key produces text", !rendered.isEmpty());

        // Each key must appear, which is the property a whitelist depends on. One missing would make that
        // key silently unkeepable.
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String key : known) {
            String single = ConfigFile.renderKeys(configured, List.of(key));
            if (!single.startsWith(key + " = ")) {
                missing.add(key);
            }
        }
        check("and every single key renders on its own" + (missing.isEmpty() ? "" : ": " + missing),
                missing.isEmpty());

        check("an empty name list renders nothing",
                ConfigFile.renderKeys(configured, List.of()).isEmpty());
        check("and a null one too", ConfigFile.renderKeys(configured, null).isEmpty());
    }

    // ---------------------------------------------------------------- keeping

    private static void keepingAndNotKeeping(Path dir) throws IOException {
        section("keeping, and not keeping");

        Path file = UserStore.fileIn(dir);
        ExportConfig config = ExportConfig.builder()
                .outputDir(dir)
                .userDir(dir)
                .persist(Set.of("formats"))
                .build();
        check("the state file follows the directory setting",
                Uee.userStateFile(config).equals(file));

        int kept = Uee.saveUserState(config);
        check("the declared key count is reported", kept == 1);
        check("the file is written", Files.isRegularFile(file));
        String text = SettingsMember.textOf(UserStore.read(file));
        check("and holds the declared key", text.contains("formats"));
        check("and not the controlling ones",
                !text.contains("persist") && !text.contains("user_dir"));

        // A store may already hold accumulated tables. Keeping a setting must not throw them away.
        java.util.Map<String, byte[]> members =
                new java.util.LinkedHashMap<>(UserStore.read(file).members());
        members.put(UserStore.TABLE_PREFIX + "assetKinds",
                org.tielang.zd.ZdDocWriter.writeIndexed(0, java.util.List.of(
                        new org.tielang.zd.ZdRow(1, "v", 0L, 0.0, "models", 0))));
        UserStore.write(file, members);
        Uee.saveUserState(config);
        UserStore after = UserStore.read(file);
        check("keeping a setting does not discard the accumulated tables",
                after.member(UserStore.TABLE_PREFIX + "assetKinds") != null);
        check("and the settings are still there too",
                SettingsMember.textOf(after).contains("formats"));

        // Nothing declared: the settings member is removed rather than left describing an older choice,
        // which would otherwise be applied on the next run regardless of the whitelist.
        ExportConfig none = ExportConfig.builder().outputDir(dir).userDir(dir).build();
        check("nothing declared keeps nothing", Uee.saveUserState(none) == 0);
        UserStore emptied = UserStore.read(file);
        check("and the stale settings member is gone",
                emptied.member(UserStore.SETTINGS) == null);
        check("while the tables remain", emptied.member(UserStore.TABLE_PREFIX + "assetKinds") != null);

        // A directory that does not exist yet is created, since the default one will not on a fresh machine.
        Path deep = dir.resolve("a").resolve("b");
        ExportConfig elsewhere = ExportConfig.builder().userDir(deep).persist(Set.of("formats")).build();
        Uee.saveUserState(elsewhere);
        check("a state directory that does not exist is created",
                Files.isRegularFile(UserStore.fileIn(deep)));
    }

    // ---------------------------------------------------------------- harness

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

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }
}
