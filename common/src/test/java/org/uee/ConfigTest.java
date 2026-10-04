package org.uee;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.uee.config.ConfigFile;
import org.uee.config.ConfigResolver;
import org.uee.config.ExportConfig;
import org.uee.config.Tokens;
import org.uee.model.ElementKind;

/**
 * Checks the configuration surface: the td syntax, the comments, and the layering.
 *
 * <p>Runs with no game present, because configuration is decided before anything touches Minecraft.
 *
 * <p>The comment assertions carry the most weight. A comment-capable format is only an advantage if
 * the comments survive the round trip — a written config whose comments have been dropped is a file
 * the user cannot read, whatever the parser is capable of.
 */
public final class ConfigTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path tmp = Path.of(args.length > 0 ? args[0] : "build/config-test");
        Files.createDirectories(tmp);

        syntax();
        comments();
        layering();
        vocabulary();
        errors();
        rendering(tmp);

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    /**
     * Wraps key/value lines into a td document.
     *
     * <p>Needed because a td document <em>is</em> a table: the top level is {@code [ ... ]}, optionally
     * named. Flat {@code key = value} lines with no surrounding table are not valid td, which is
     * asserted separately so the constraint is on the record rather than only in a comment.
     */
    private static String doc(String body) {
        return "type tie<data>\n\nuee = [\n" + body + "\n]\n";
    }

    // ---------------------------------------------------------------- syntax

    private static void syntax() {
        section("td syntax");

        check("named table is accepted",
                !ConfigFile.parse(doc("analyze = true")).isEmpty());
        check("bare table is accepted",
                !ConfigFile.parse("[\n analyze = true\n]\n").isEmpty());
        check("header line is accepted",
                !ConfigFile.parse(doc("output = \"x\"\nanalyze = true")).isEmpty());

        // A td document is one table. Flat keys are not td, and accepting them would mean UEE reads a
        // dialect no other tie tool does.
        boolean flatRejected = false;
        try {
            ConfigFile.parse("output = \"x\"\n");
        } catch (RuntimeException e) {
            flatRejected = true;
        }
        check("flat top-level keys are rejected (a td document is a table)", flatRejected);

        // // is td's comment, and the only one.
        ConfigFile commented = ConfigFile.parse(doc("""
                    // a full-line comment
                    output = "out"        // a trailing comment
                    analyze = true"""));
        ExportConfig c = commented.applyTo(ExportConfig.builder().build());
        check("full-line comment ignored", c.outputDir().toString().equals("out"));
        check("trailing comment ignored", c.analyze());
        check("comment carries no data", commented.unknownKeys().isEmpty());

        // A comment marker inside a string is content, not a comment. Checked on a plain string
        // rather than on `output`, which is a path and would be normalised.
        check("// inside a string is content",
                packageOf(doc("package = \"a//b\"")) .equals("a//b"));
        check("# inside a string is content",
                packageOf(doc("package = \"a#b\"")).equals("a#b"));

        // The syntax is tie's, unextended: '#' is not a comment character, so a line using it fails
        // rather than being silently ignored.
        boolean hashRejected = false;
        try {
            ConfigFile.parse(doc("# not a td comment\noutput = \"x\""));
        } catch (RuntimeException e) {
            hashRejected = true;
        }
        check("'#' is not treated as a comment (tie syntax only)", hashRejected);

        // Array values, which is how formats and kinds are written.
        ExportConfig multi = ConfigFile.parse(doc("formats = [\"ndjson\", \"wiki\"]"))
                .applyTo(ExportConfig.builder().build());
        check("array value resolves", multi.formats().equals(
                Set.of(ExportConfig.NDJSON, ExportConfig.WIKI)));

        // A single scalar where a one-element list was meant is tolerated.
        ExportConfig single = ConfigFile.parse(doc("formats = \"td\""))
                .applyTo(ExportConfig.builder().build());
        check("scalar where a list was meant is accepted",
                single.formats().equals(Set.of(ExportConfig.TD)));
    }

    /** Reads a plain string key back, for assertions on values that must not be normalised. */
    private static String packageOf(String td) {
        return ConfigFile.parse(td).applyTo(ExportConfig.builder().build()).packageName();
    }

    // ---------------------------------------------------------------- comments

    private static void comments() {
        section("comments in generated config");

        ExportConfig config = ExportConfig.builder()
                .outputDir(Path.of("exports/uee"))
                .formats(ExportConfig.JSON, ExportConfig.NDJSON)
                .kinds(ElementKind.ITEM, ElementKind.ENTITY)
                .analyze(true)
                .threads(4)
                .build();

        String annotated = ConfigFile.renderAnnotated(config);
        check("generated config carries comments", annotated.contains("//"));
        check("comments explain keys",
                annotated.contains("// 是否分析") || annotated.contains("// run the analysis"));
        check("no '#' comments emitted", !annotated.contains("#"));
        check("bilingual guidance present", annotated.contains("// UEE 配置"));
        check("explanatory sections present", annotated.contains("输出在哪")
                && annotated.contains("百科投影"));
        check("generated config is a table", annotated.contains("uee = ["));

        // The specific thing that is easy to get wrong: patching a value must not eat the comment
        // that documents it.
        check("trailing comment survives the value patch",
                commentOnLine(annotated, "analyze = true", "// 是否分析"));
        check("a changed value keeps its comment",
                commentOnLine(annotated, "threads = 4", "// 并发度"));
        check("an unchanged default keeps its comment",
                commentOnLine(annotated, "shard_size = 20000", "// 每片元素上限"));
        check("comments are aligned to a stable column", aligned(annotated));

        // The point of the whole exercise: it parses back.
        ConfigFile parsed = ConfigFile.parse(annotated);
        check("generated config parses", parsed.unknownKeys().isEmpty());
        ExportConfig roundTripped = parsed.applyTo(ExportConfig.builder().build());
        check("output survives round trip", roundTripped.outputDir().equals(config.outputDir()));
        check("formats survive round trip", roundTripped.formats().equals(config.formats()));
        check("kinds survive round trip", roundTripped.kinds().equals(config.kinds()));
        check("analyze survives round trip", roundTripped.analyze() == config.analyze());
        check("threads survive round trip", roundTripped.threads() == config.threads());
        check("layout survives round trip",
                roundTripped.packagePerKind() == config.packagePerKind());

        // A full-set selection renders as the group token rather than seventeen lines.
        check("full category set renders as the group token",
                ConfigFile.renderAnnotated(
                        ExportConfig.builder().kinds(ElementKind.values()).build())
                        .contains("\"all\""));
        check("common set renders as the group token",
                ConfigFile.renderAnnotated(ExportConfig.builder()
                        .kinds(Tokens.commonKinds().toArray(new ElementKind[0])).build())
                        .contains("\"common\""));
    }

    // ---------------------------------------------------------------- layering

    private static void layering() {
        section("layering: defaults <- file <- caller");

        ExportConfig defaults = ExportConfig.builder().build();
        check("default format is json", defaults.formats().equals(Set.of(ExportConfig.JSON)));
        check("default analysis is on", defaults.analyze());
        check("default bundle layout is per category", defaults.packagePerKind());
        // The one-key run produces n data packages plus one analysis package, so the analysis gets
        // its own directory by default.
        check("default analysis is its own package", defaults.analysisSeparate());
        check("default categories are the common set",
                defaults.kinds().equals(Tokens.commonKinds()));
        check("default output is a sharded tree", defaults.shardByNamespace());

        ConfigFile file = ConfigFile.parse(doc("""
                    output = "from-file"
                    formats = ["td"]
                    analyze = false"""));
        ConfigFile caller = ConfigFile.builder().packageName("from-caller").build();

        ConfigResolver.Resolved r = ConfigResolver.resolve(file, caller, defaults);
        check("resolves without errors", r.ok());
        check("file overrides the default output",
                r.config().outputDir().toString().equals("from-file"));
        check("file overrides the default formats",
                r.config().formats().equals(Set.of(ExportConfig.TD)));
        check("file overrides the default analysis", !r.config().analyze());
        check("caller overrides the file where it speaks",
                r.config().packageName().equals("from-caller"));
        check("a key nobody named keeps its default", r.config().shardSize() == defaults.shardSize());

        // A partial file must not reset the keys it does not mention. This is the property that makes
        // a config file usable: set two things without silently undoing the other twelve.
        ConfigFile partial = ConfigFile.parse(doc("analyze = true"));
        ExportConfig afterPartial = partial.applyTo(defaults);
        check("partial file leaves formats alone",
                afterPartial.formats().equals(defaults.formats()));
        check("partial file leaves layout alone",
                afterPartial.packagePerKind() == defaults.packagePerKind());
        check("partial file leaves output alone",
                afterPartial.outputDir().equals(defaults.outputDir()));
        check("partial file applies what it names", afterPartial.analyze());

        // Contradiction is named rather than silently satisfied.
        ConfigResolver.Resolved warn = ConfigResolver.resolve(
                ConfigFile.parse(doc("analyze = false\nkinds = [\"analysis\"]")), null, defaults);
        check("analysis categories without analysis is warned",
                warn.warnings().stream().anyMatch(w -> w.contains("analyze = false")));
        check("analysis categories without analysis still runs", warn.ok());

        // A typo is reported, not ignored: a setting that silently does nothing is worse than an
        // error, because the user believes it took effect.
        ConfigResolver.Resolved typo = ConfigResolver.resolve(
                ConfigFile.parse(doc("anaylze = true")), null, defaults);
        check("unknown key reported",
                typo.warnings().stream().anyMatch(w -> w.contains("anaylze")));
        check("unknown key does not fail the run", typo.ok());
    }

    // ---------------------------------------------------------------- vocabulary

    private static void vocabulary() {
        section("shared vocabulary");

        // Both interfaces resolve through the same table, so a token cannot work in one and not the
        // other. These assertions lock that in.
        check("singular and plural both resolve",
                Tokens.kinds("item").equals(Tokens.kinds("items")));
        check("group token 'all' resolves",
                Tokens.kinds("all").equals(Set.of(ElementKind.values())));
        check("group token 'data' excludes analysis categories",
                !Tokens.kinds("data").contains(ElementKind.CONFLICT));
        check("group token 'analysis' is only analysis categories",
                Tokens.kinds("analysis").equals(ElementKind.ANALYSIS_KINDS));
        check("group token 'common' is a subset of all",
                Set.of(ElementKind.values()).containsAll(Tokens.kinds("common")));
        check("every category has a resolvable token", allCategoriesResolve());
        check("case is ignored", Tokens.kinds("ITEMS").equals(Tokens.kinds("items")));
        check("comma list resolves", Tokens.kinds(java.util.List.of("items,blocks")).size() == 2);
        check("space-separated list resolves",
                Tokens.kinds(java.util.List.of("items blocks entities")).size() == 3);
        check("unknown category is rejected", Tokens.kinds("nonsense") == null);
        check("every format resolves", allFormatsResolve());
        check("unknown format is rejected", !Tokens.isFormat("nonsense"));
        check("analysis categories are flagged on the enum",
                ElementKind.CONFLICT.isAnalysis() && !ElementKind.ITEM.isAnalysis());
    }

    /** True when the key and the comment end up on the same line. */
    private static boolean commentOnLine(String text, String key, String comment) {
        for (String line : text.split("\n")) {
            if (line.contains(key) && line.contains(comment)) {
                return true;
            }
        }
        return false;
    }

    /** True when every trailing comment starts at the same column. */
    private static boolean aligned(String text) {
        Integer column = null;
        for (String line : text.split("\n")) {
            int at = line.indexOf("//");
            if (at <= 0) {
                continue;
            }
            // Skip whole-line comments, which are indented but not aligned to the value column.
            if (line.stripLeading().startsWith("//")) {
                continue;
            }
            if (column == null) {
                column = at;
            } else if (!column.equals(at)) {
                return false;
            }
        }
        return column != null;
    }

    private static boolean allCategoriesResolve() {
        for (ElementKind k : ElementKind.values()) {
            if (Tokens.kinds(k.singular()) == null || Tokens.kinds(k.plural()) == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean allFormatsResolve() {
        for (String f : ExportConfig.ALL_FORMATS) {
            if (!Tokens.isFormat(f)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- errors

    private static void errors() {
        section("error handling");

        boolean formatRejected = false;
        try {
            ConfigFile.parse(doc("formats = [\"nonsense\"]")).applyTo(ExportConfig.builder().build());
        } catch (RuntimeException e) {
            formatRejected = true;
        }
        check("unknown format is rejected", formatRejected);

        boolean kindRejected = false;
        try {
            ConfigFile.parse(doc("kinds = [\"nonsense\"]"));
        } catch (RuntimeException e) {
            kindRejected = true;
        }
        check("unknown category is rejected at parse", kindRejected);
    }

    // ---------------------------------------------------------------- files

    private static void rendering(Path tmp) throws Exception {
        section("file writing");

        Path missing = tmp.resolve("does-not-exist.data.tie");
        check("missing file yields an empty description", ConfigFile.read(missing).isEmpty());

        Path file = tmp.resolve("uee.data.tie");
        ExportConfig config = ExportConfig.builder()
                .outputDir(Path.of("exports/uee"))
                .formats(ExportConfig.NDJSON)
                .kinds(ElementKind.ITEM)
                .build();
        ConfigFile.write(config, file);
        check("file written", Files.isRegularFile(file));

        String text = Files.readString(file);
        check("written file has comments", text.contains("//"));
        check("written file is a table", text.contains("uee = ["));

        ConfigFile reread = ConfigFile.read(file);
        check("written file reads back", reread.unknownKeys().isEmpty());
        ExportConfig again = reread.applyTo(ExportConfig.builder().build());
        check("written file reproduces the formats", again.formats().equals(config.formats()));
        check("written file reproduces the categories", again.kinds().equals(config.kinds()));
        check("written file reproduces the output", again.outputDir().equals(config.outputDir()));

        // The template is the first thing a user sees, so it has to be valid.
        ConfigFile template = ConfigFile.parse(ConfigFile.template());
        check("shipped template parses", template.unknownKeys().isEmpty());
        ExportConfig fromTemplate = template.applyTo(ExportConfig.builder().build());
        check("template yields working defaults", fromTemplate.analyze()
                && !fromTemplate.formats().isEmpty() && !fromTemplate.kinds().isEmpty());
        check("template agrees with the code defaults on the format",
                fromTemplate.formats().equals(ExportConfig.builder().build().formats()));
        check("template agrees with the code defaults on the analysis layout",
                fromTemplate.analysisSeparate()
                        == ExportConfig.builder().build().analysisSeparate());
        check("template is commented", ConfigFile.template().contains("// "));
        check("template documents the group tokens",
                ConfigFile.template().contains("common") && ConfigFile.template().contains("analysis"));
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
}
