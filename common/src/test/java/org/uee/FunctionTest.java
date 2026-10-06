package org.uee;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.uee.datapack.FunctionFile;

/**
 * Checks the function parser.
 *
 * <h2>The category where the rules are least guessable</h2>
 *
 * <p>A function is a text file, so there is no structure to read — only lines to classify, and every
 * classification rule is a thing a reasonable implementation gets wrong:
 *
 * <ul>
 *   <li>A line ending in a backslash continues onto the next, so lines and commands are different counts.
 *   <li>A line starting with {@code #} is a comment, and one real pack has functions that are nothing but
 *       a licence header — which a reader counting non-empty lines would describe as a function with a
 *       dozen commands.
 *   <li>A line starting with {@code $} is a macro rather than a command.
 *   <li>A line starting with {@code /} is an error the game refuses, not a command and not a comment.
 * </ul>
 *
 * <p>Each of those is read off the game's own reader rather than inferred, and each is checked here.
 * The parser is then run over every function in a real datapack when one can be found on the machine —
 * the same approach that caught two defects in the world-generation parser that hand-written samples had
 * not.
 */
public final class FunctionTest {

    private static int failures;

    public static void main(String[] args) throws IOException {
        comments();
        continuations();
        macros();
        theSlashMistake();
        references();
        lineEndings();
        paths();
        surveyARealDatapack();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- line classification

    private static void comments() {
        section("comments, which several real functions consist entirely of");

        // The measured case: a licence header and nothing else. Counting non-empty lines would call this
        // a function with a dozen commands.
        FunctionFile.Function header = FunctionFile.parse("""
                # Copyright (c) 2026 Someone
                # Licensed under something
                #
                # And more prose
                """);
        check("a comment-only file runs no commands", header.commands() == 0);
        check("but its comments are counted", header.comments() == 4);
        check("and it is still a record, not a failure", header.lines() == 4);

        // A blank line is neither.
        FunctionFile.Function mixed = FunctionFile.parse("""
                # a comment

                say hi

                """);
        check("blank lines are neither commands nor comments",
                mixed.commands() == 1 && mixed.comments() == 1);
        check("every line is still counted", mixed.lines() > 3);

        check("an empty file is a function that does nothing",
                FunctionFile.parse("").commands() == 0);
        check("a file of only newlines too",
                FunctionFile.parse("\n\n\n").commands() == 0);

        // A hash is only a comment at the start of a line: mid-line it is a tag.
        FunctionFile.Function notAComment = FunctionFile.parse(
                "execute if score x y matches 1 run function #minecraft:tick");
        check("a hash mid-line is a tag, not a comment",
                notAComment.comments() == 0 && notAComment.tags().equals(List.of("minecraft:tick")));

        // Whitespace before the hash still makes it a comment, because the line is trimmed first.
        check("indentation before a hash is still a comment",
                FunctionFile.parse("    # indented comment").comments() == 1);
        check("and indentation before a command is still a command",
                FunctionFile.parse("    say indented").commands() == 1);
    }

    private static void continuations() {
        section("line continuations, so lines and commands differ");

        // Measured off the game's reader: a trailing backslash joins the next line, and the command count
        // follows the joined lines rather than the raw ones.
        FunctionFile.Function joined = FunctionFile.parse("""
                say one \\
                two
                say three
                """);
        check("a continuation joins two lines into one command", joined.commands() == 2);
        check("and the raw line count is still available", joined.lines() == 3);

        // Three lines joining into one.
        FunctionFile.Function longer = FunctionFile.parse("""
                say a \\
                b \\
                c
                """);
        check("a continuation can span more than two lines", longer.commands() == 1);

        FunctionFile.Function referenceAcross = FunctionFile.parse("""
                execute \\
                run function test:inner
                """);
        check("a call split across a continuation is still found",
                referenceAcross.functions().equals(List.of("test:inner")));
        check("and it is one command", referenceAcross.commands() == 1);

        // The game refuses a file ending mid-continuation rather than loading it, and a reader should be
        // told rather than left wondering why the function never runs.
        FunctionFile.Function broken = FunctionFile.parse("say hello \\");
        check("a file ending mid-continuation is marked", broken.unterminatedContinuation());
        check("and the record says so",
                "true".equals(joined(FunctionFile.extraPairs(broken), "broken")));
        check("a well-formed file is not marked",
                !FunctionFile.parse("say hello").unterminatedContinuation());
        check("and carries no such key",
                joined(FunctionFile.extraPairs(FunctionFile.parse("say hello")), "broken") == null);
    }

    private static void macros() {
        section("macro lines");

        // Measured: a real pack has hundreds of lines starting with a dollar sign, and they are macros --
        // substituted before execution -- rather than commands.
        FunctionFile.Function macro = FunctionFile.parse("""
                $execute if score $(name) matches 1
                say plain
                """);
        check("a macro is counted as a macro, not a command", macro.macros() == 1);
        check("and the plain line is counted as a command", macro.commands() == 1);
        check("the record says the function takes parameters", macro.usesMacros());
        check("and how many, by count",
                "1".equals(joined(FunctionFile.extraPairs(macro), "macros")));

        // A macro line is still a command for the purposes of what it calls.
        FunctionFile.Function calls = FunctionFile.parse("$function test:inner");
        check("a macro line that calls a function is still followed",
                calls.functions().equals(List.of("test:inner")));

        FunctionFile.Function none = FunctionFile.parse("say hi");
        check("a function with no macros says nothing about them",
                joined(FunctionFile.extraPairs(none), "macros") == null);
    }

    private static void theSlashMistake() {
        section("the leading slash, which the game refuses");

        // The game's reader rejects this with "do not use a preceding forwards slash". Counting it as a
        // command would hide a mistake the user needs to see; counting it as a comment would too.
        FunctionFile.Function slashed = FunctionFile.parse("""
                /say hi
                say bye
                """);
        check("a leading slash is neither a command nor a comment",
                slashed.commands() == 1 && slashed.comments() == 0);
        check("the well-formed line beside it still counts", slashed.commands() == 1);
    }

    // ---------------------------------------------------------------- references

    private static void references() {
        section("the call graph");

        FunctionFile.Function calls = FunctionFile.parse("""
                function test:a
                execute as @a run function test:b
                schedule function test:c 1t
                """);
        check("a direct call is found", calls.functions().contains("test:a"));
        check("a call after run is found", calls.functions().contains("test:b"));
        check("a scheduled call is found", calls.functions().contains("test:c"));
        check("all three, in order",
                calls.functions().equals(List.of("test:a", "test:b", "test:c")));

        FunctionFile.Function tags = FunctionFile.parse("""
                function #test:everyone
                execute if score x y matches 1 run function #test:tick
                """);
        check("a tag call is recorded without the hash",
                tags.tags().equals(List.of("test:everyone", "test:tick")));
        check("and is not mistaken for a function",
                tags.functions().isEmpty());
        check("the tags reach the record",
                "test:everyone,test:tick".equals(joined(FunctionFile.extraPairs(tags), "tagRefs")));

        // The reason the keyword's position matters: the word in an argument is not a call.
        FunctionFile.Function notACall = FunctionFile.parse("""
                say the function word is in this sentence
                tellraw @a {"text":"function"}
                """);
        check("the word in prose is not a call", notACall.functions().isEmpty());
        check("nor a function named in a say", FunctionFile.parse("say function foo:bar")
                .functions().isEmpty());
        check("but a call after run is", FunctionFile.parse("execute run function a:b")
                .functions().equals(List.of("a:b")));

        // A name that is not shaped like an id is not recorded, since the game would refuse it too.
        check("a bare word after function is not an id",
                FunctionFile.parse("function plainword").functions().isEmpty());

        FunctionFile.Function repeated = FunctionFile.parse("""
                function test:a
                function test:a
                """);
        check("the same call twice is recorded once",
                repeated.functions().equals(List.of("test:a")));
        check("though both lines are commands", repeated.commands() == 2);

        // Quoted runs stay together, so a quoted word cannot move the keyword into a position it is not.
        check("a quoted argument does not create a false call",
                FunctionFile.parse("say \"run function a:b\"").functions().isEmpty());

        // Measured in a real pack, ten times: a macro line computing the name it calls. There is no id to
        // record, but dropping it would say the function calls nothing, which is a different answer.
        FunctionFile.Function computed = FunctionFile.parse(
                "$function bs.block:fill/type/mode/$(mode)");
        check("a computed target is recorded as dynamic", computed.dynamic().size() == 1);
        check("kept as written", computed.dynamic().get(0).equals("bs.block:fill/type/mode/$(mode)"));
        check("and is not claimed to be a function", computed.functions().isEmpty());
        check("it reaches the record",
                joined(FunctionFile.extraPairs(computed), "dynamicRefs") != null);
        check("a wholly computed name works too",
                FunctionFile.parse("$function $(psr)").dynamic().equals(List.of("$(psr)")));
        check("a plain function says nothing about dynamic calls",
                joined(FunctionFile.extraPairs(FunctionFile.parse("function a:b")), "dynamicRefs")
                        == null);
    }

    private static void lineEndings() {
        section("line endings");

        // A pack authored on Windows: leaving the carriage return on would break the continuation check,
        // so an author's backslash would silently stop continuing.
        FunctionFile.Function crlf = FunctionFile.parse("say one \\\r\ntwo\r\nsay three\r\n");
        check("a carriage return does not break a continuation", crlf.commands() == 2);
        check("and is not part of the line", crlf.lines() == 3);

        check("a file with no trailing newline keeps its last line",
                FunctionFile.parse("say a\nsay b").commands() == 2);
        check("a trailing newline does not add an empty line",
                FunctionFile.parse("say a\nsay b\n").lines() == 2);

        FunctionFile.Function crlfComments = FunctionFile.parse("# comment\r\nsay hi\r\n");
        check("a carriage return does not hide a comment",
                crlfComments.comments() == 1 && crlfComments.commands() == 1);
    }

    private static void paths() {
        section("resource paths");

        check("the name is the path below the directory, without the extension",
                "and/and".equals(FunctionFile.pathOf("function/and/and.mcfunction")));
        check("a one-segment name works",
                "tick".equals(FunctionFile.pathOf("function/tick.mcfunction")));
        check("a json file is not a function",
                FunctionFile.pathOf("function/tick.json") == null);
        check("a function tag is not a function",
                FunctionFile.pathOf("tags/function/tick.json") == null);
        check("an empty name yields nothing", FunctionFile.pathOf("function/.mcfunction") == null);
        check("and neither does null", FunctionFile.pathOf(null) == null);
        check("the directory is named once", FunctionFile.directory().equals("function"));
        check("and the extension, which is not json", FunctionFile.extension().equals(".mcfunction"));
    }

    // ---------------------------------------------------------------- real data

    /**
     * Runs the parser over every function in a datapack found on the machine.
     *
     * <p>A real pack is a better check than samples for the same reason as in the world-generation parser:
     * the classifications here are exactly the kind a sample encodes a belief about. A thousand functions
     * with licence headers, macro lines and continuations cannot.
     */
    private static void surveyARealDatapack() throws IOException {
        section("a survey of a real datapack");

        Path root = findFunctions();
        if (root == null) {
            System.out.println("  --   skipped: no datapack with functions found on this machine");
            return;
        }
        System.out.println("      surveying " + root);

        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".mcfunction"))
                    .forEach(files::add);
        }

        int total = 0;
        int commentOnly = 0;
        int withMacros = 0;
        int withContinuations = 0;
        int withTags = 0;
        long commands = 0;
        long comments = 0;
        int broken = 0;
        List<String> failed = new ArrayList<>();

        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            FunctionFile.Function parsed;
            try {
                parsed = FunctionFile.parse(text);
            } catch (Throwable t) {
                failed.add(file.getFileName() + ": " + t);
                continue;
            }
            total++;
            commands += parsed.commands();
            comments += parsed.comments();
            // Comments only, strictly: a file that is also all macros is a different thing, and calling
            // it comment-only would be the same kind of careless label this parser exists to avoid.
            if (parsed.commands() == 0 && parsed.macros() == 0 && parsed.comments() > 0) {
                commentOnly++;
            }
            if (parsed.usesMacros()) {
                withMacros++;
            }
            if (parsed.unterminatedContinuation()) {
                broken++;
            }
            if (!parsed.tags().isEmpty()) {
                withTags++;
            }
            if (text.contains("\\\n") || text.contains("\\\r\n")) {
                withContinuations++;
            }
        }

        System.out.println("      " + total + " functions, " + commands + " commands, " + comments
                + " comments");
        System.out.println("      " + commentOnly + " are comments only, " + withMacros
                + " use macros, " + withTags + " call a tag");

        check("there are functions to survey", total > 100);
        check("none of them throws", failed.isEmpty() || report(failed));
        // The assertion that makes the comment handling meaningful: a real pack has functions that do
        // nothing at all, so a reader that counted lines would describe them wrongly.
        check("some functions really are comments only (" + commentOnly + ")", commentOnly > 0);
        // And the one that makes the macro handling meaningful: macros really do occur.
        check("some functions really use macros (" + withMacros + ")", withMacros > 0);
        check("some really call a function tag (" + withTags + ")", withTags > 0);
        check("and the parser flags no file as broken when the pack loads (" + broken + ")",
                broken == 0);
    }

    private static boolean report(List<String> failed) {
        for (int i = 0; i < Math.min(5, failed.size()); i++) {
            System.out.println("        " + failed.get(i));
        }
        return false;
    }

    /** Looks for a directory holding functions, in the places datapacks are kept. */
    private static Path findFunctions() {
        List<Path> roots = List.of(
                Path.of("F:", "Projects", "bookshelf4.0.1", "data"),
                Path.of("F:", "Projects", "exmcofjiro", "data"));
        for (Path root : roots) {
            if (Files.isDirectory(root) && hasFunctions(root)) {
                return root;
            }
        }
        return null;
    }

    private static boolean hasFunctions(Path root) {
        try (var walk = Files.walk(root, 4)) {
            return walk.anyMatch(p -> p.getFileName().toString().endsWith(".mcfunction"));
        } catch (IOException ignored) {
            return false;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String joined(String[] pairs, String key) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i].equals(key)) {
                return pairs[i + 1];
            }
        }
        return null;
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
