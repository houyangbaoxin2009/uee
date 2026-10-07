package org.uee;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.uee.config.ConfigFile;
import org.uee.config.ConfigResolver;
import org.uee.config.ExportConfig;

/**
 * Checks that the configuration file a command says it wrote is actually there and actually usable.
 *
 * <h2>The claim being tested</h2>
 *
 * <p>Two commands report that they wrote the configuration. Both reported it on the strength of no
 * exception being thrown, which is not the same claim: a message that says a file was written should be
 * based on the file being readable afterwards. A user who is told a path and finds nothing there has been
 * given a false statement, and the cost is not just confusion — the thing they were about to edit is not
 * there to edit.
 *
 * <h2>What else these checks cover</h2>
 *
 * <p>A generated file that cannot be loaded is worse than one that was never written, so the template and
 * the saved configuration are both read back and required to parse with no problems reported. That is the
 * property that makes the file useful rather than merely present.
 */
public final class ConfigWriteTest {

    private static int failures;

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "build/config-write-test");
        deleteRecursively(root);
        Files.createDirectories(root);

        whereItGoes(root);
        theTemplateIsWrittenAndReadable(root.resolve("template"));
        theSavedConfigurationIsWrittenAndReadable(root.resolve("save"));
        aWriteIsVerifiedRatherThanAssumed(root.resolve("verify"));

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- where

    private static void whereItGoes(Path root) {
        section("where the file goes");

        Path file = ConfigResolver.configFile(root);
        check("the config file is under the game directory's config folder",
                file.equals(root.resolve("config").resolve(ConfigFile.FILE_NAME)));
        check("and the name is the one the reader looks for",
                ConfigFile.FILE_NAME.equals("uee.data.tie"));
        check("the directory and the file agree",
                ConfigResolver.configDir(root).resolve(ConfigFile.FILE_NAME).equals(file));
        // A directory that does not exist yet is the normal case on a fresh instance, so writing has to
        // create it rather than fail.
        check("nothing is there to begin with", !Files.exists(file));
    }

    // ---------------------------------------------------------------- the template

    private static void theTemplateIsWrittenAndReadable(Path gameDir) throws IOException {
        section("the template");

        Path file = ConfigResolver.configFile(gameDir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, ConfigFile.template());

        check("the file exists after writing", Files.isRegularFile(file));
        long size = Files.size(file);
        check("and is not empty (" + size + " bytes)", size > 0);

        // The file has to be loadable, or the user edits a file that then refuses to load. Read back
        // through the same reader the program uses, so this is the real question and not a close one.
        ConfigFile parsed = ConfigFile.readFrom(ConfigResolver.configDir(gameDir));
        check("it reads back", parsed != null);
        check("with every key the template documents understood",
                parsed.unknownKeys().isEmpty());
        check("the template documents a useful number of keys", parsed.setKeys().size() > 20);

        // And it describes the default configuration, so generating it changes nothing.
        ExportConfig fromTemplate = ConfigResolver.resolve(ExportConfig.builder()
                .outputDir(gameDir.resolve("exports")).build(), List.of(parsed)).config();
        ExportConfig defaults = ConfigResolver.resolve(ExportConfig.builder()
                .outputDir(gameDir.resolve("exports")).build(), List.of()).config();
        check("and it agrees with the defaults it documents",
                fromTemplate.formats().equals(defaults.formats())
                        && fromTemplate.kinds().equals(defaults.kinds()));
    }

    // ---------------------------------------------------------------- the saved configuration

    private static void theSavedConfigurationIsWrittenAndReadable(Path gameDir) throws IOException {
        section("the saved configuration");

        ExportConfig config = ConfigResolver.resolve(ExportConfig.builder()
                .outputDir(gameDir.resolve("exports"))
                .formats("json", "ndjson")
                .kinds(org.uee.model.ElementKind.ITEM, org.uee.model.ElementKind.BLOCK)
                .build(), List.of()).config();

        Path file = ConfigResolver.configFile(gameDir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, ConfigFile.renderAnnotated(config));

        check("the file exists after writing", Files.isRegularFile(file));
        check("and is not empty", Files.size(file) > 0);

        ConfigFile parsed = ConfigFile.readFrom(ConfigResolver.configDir(gameDir));
        check("with every key understood", parsed.unknownKeys().isEmpty());

        // The saved file describes the configuration it was made from: writing it and resolving again must
        // give the same answers, or "save" would quietly change the settings it recorded.
        ExportConfig again = ConfigResolver.resolve(ExportConfig.builder()
                .outputDir(gameDir.resolve("exports")).build(), List.of(parsed)).config();
        check("and it describes what it was made from",
                again.formats().equals(config.formats()) && again.kinds().equals(config.kinds()));
        check("including the output directory",
                again.outputDir().equals(config.outputDir()));
    }

    // ---------------------------------------------------------------- verifying rather than assuming

    private static void aWriteIsVerifiedRatherThanAssumed(Path gameDir) throws IOException {
        section("a write is verified, not assumed");

        Path file = ConfigResolver.configFile(gameDir);

        // The check a command should make before saying it wrote something. Reporting success because no
        // exception was thrown is a weaker claim than it sounds: anything that stops the bytes persisting
        // -- a full disk, a permissions change, a read-only mount -- leaves the message untrue and the user
        // looking for a file that is not there.
        check("nothing there yet", !Files.isRegularFile(file));
        check("so a verification would fail before writing", !isWritten(file, 1));

        Files.createDirectories(file.getParent());
        Files.writeString(file, ConfigFile.template());
        check("and pass after", isWritten(file, 1));

        // An empty file is not a written file for this purpose: the point of generating one is that there
        // is something to read and edit.
        Path empty = gameDir.resolve("empty.tie");
        Files.writeString(empty, "");
        check("an empty file does not count as written", !isWritten(empty, 1));
    }

    /** Whether a write can be seen to have happened: the file is there and has content. */
    private static boolean isWritten(Path file, long minimumBytes) {
        try {
            return Files.isRegularFile(file) && Files.size(file) >= minimumBytes;
        } catch (IOException e) {
            return false;
        }
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
