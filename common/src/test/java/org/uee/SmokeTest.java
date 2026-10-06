package org.uee;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import org.uee.analysis.AnalysisEngine;
import org.uee.config.ExportConfig;
import org.uee.datapack.DatapackCatalog;
import org.uee.datapack.Selector;
import org.uee.datapack.TargetDefinition;
import org.uee.config.WikiOptions;
import org.uee.model.BlockElement;
import org.uee.model.DebugSection;
import org.uee.model.Dependency;
import org.uee.model.ElementKind;
import org.uee.model.EntityElement;
import org.uee.model.Ingredient;
import org.uee.model.ItemElement;
import org.uee.model.ModElement;
import org.uee.model.RecipeElement;
import org.uee.pipeline.ExportReport;
import org.uee.pipeline.Exporter;
import org.uee.spi.ElementSink;
import org.uee.spi.LoaderAdapter;
import org.uee.spi.LoaderInfo;

/**
 * End-to-end smoke test with a synthetic adapter.
 *
 * <p>Runs the whole pipeline — collection, sharding, every writer — with no Minecraft dependency at
 * all. That is only possible because the SPI never mentions a game type, and it is how the output
 * contract is verified: the bytes produced here are the bytes the real adapters will produce.
 */
public final class SmokeTest {

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "build/smoke");
        if (Files.exists(root)) {
            deleteRecursively(root);
        }
        ExportConfig config = ExportConfig.builder()
                .outputDir(root)
                .formats(ExportConfig.NDJSON, ExportConfig.JSON, ExportConfig.TD,
                        ExportConfig.YAML, ExportConfig.TOML, ExportConfig.XML, ExportConfig.ZD,
                        org.uee.write.WriterFactory.WIKI)
                .kinds(org.uee.model.ElementKind.values())
                .kinds(org.uee.model.ElementKind.values())
                .kinds(org.uee.model.ElementKind.values())
                .icons(true)
                .wiki(WikiOptions.builder().enabled(true).icons(true).build())
                .build();

        // A datapack target, so the fourth interface is exercised end to end rather than only
        // against an in-memory context: it must produce its own shard, in its own directory, without
        // disturbing the category's own output.
        DatapackCatalog catalog = DatapackCatalog.ofDefinitions(List.of(),
                List.of(new TargetDefinition("gems", "smoke", ElementKind.ITEM,
                                new Selector(null, null, "c:gems", null, null, null),
                                "gem-tagged items"),
                        new TargetDefinition("exampleblocks", "smoke", ElementKind.BLOCK,
                                new Selector("example", null, null, null, null, null),
                                "blocks in one namespace"),
                        // Matches nothing in this fixture. A target that selects an empty set must
                        // produce no file at all, rather than an empty one: an empty artifact is
                        // indistinguishable from a category that produced nothing.
                        new TargetDefinition("vanillaonly", "smoke", ElementKind.BLOCK,
                                new Selector("minecraft", null, null, null, null, null),
                                "vanilla blocks")),
                List.of());
        config = config.toBuilder()
                .targets(java.util.Set.of("gems", "exampleblocks", "vanillaonly")).build();

        ExportReport report = new Exporter(new FakeAdapter(), config, root,
                AnalysisEngine.standard(), catalog).run();
        int problems = validate(report, root);
        System.out.println("== report ==");
        System.out.println(report.summary());
        for (ExportReport.Artifact a : report.artifacts()) {
            System.out.printf("  %-10s %-8s %-7s %6d bytes %5d records  %s%n",
                    a.namespace(), a.kind(), a.format(), a.bytes(), a.records(),
                    root.relativize(a.path()));
        }
        if (!report.failures().isEmpty()) {
            System.out.println("== failures ==");
            report.failures().forEach(f -> System.out.println("  " + f));
        }
        List<ExportReport.Artifact> fromTargets = report.artifacts().stream()
                .filter(ExportReport.Artifact::fromTarget).toList();
        System.out.println("targets: " + fromTargets.size() + " artifact(s) from "
                + fromTargets.stream().map(ExportReport.Artifact::target).distinct().toList());
        java.util.Set<String> firingTargets = fromTargets.stream()
                .map(ExportReport.Artifact::target).collect(java.util.stream.Collectors.toSet());
        System.out.println("targets firing: " + firingTargets);
        if (firingTargets.size() < 2) {
            System.out.println("  PROBLEM expected at least two targets to produce output, got "
                    + firingTargets);
            problems++;
        }
        // A target that matches nothing must leave nothing behind.
        if (firingTargets.contains("smoke:vanillaonly")) {
            System.out.println("  PROBLEM a target matching no element still produced output");
            problems++;
        }
        for (ExportReport.Artifact a : fromTargets) {
            if (!a.path().toString().contains("targets")) {
                System.out.println("  PROBLEM a target artifact is not under a targets/ directory: "
                        + a.path());
                problems++;
            }
        }

        for (ExportReport.Artifact a : report.artifacts()) {
            System.out.println("\n---- " + root.relativize(a.path()) + " ----");
            if (ExportConfig.ZD.equals(a.format())) {
                // Binary; dumping it as text would only be noise. The round-trip check decodes it.
                System.out.println("(binary, " + a.bytes() + " bytes; see the round-trip check)");
                continue;
            }
            String text = Files.readString(a.path());
            System.out.println(text.length() > 900 ? text.substring(0, 900) + "\n...[truncated]" : text);
        }

        problems += deltaRuns(root, config, catalog);

        System.out.println();
        System.out.println(problems == 0 ? "STRUCTURE: OK" : "STRUCTURE: " + problems + " PROBLEM(S)");
        if (problems != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- the delta, end to end

    /**
     * Runs the same export again with a delta asked for, and checks that it does nothing.
     *
     * <p>End to end on purpose. The delta decisions are checked in their own harness against synthetic
     * files, and that cannot catch the mistakes that matter here: whether the pipeline gives the plan the
     * path a consumer will actually read, whether the rename lands where the next run looks, and whether a
     * run over identical input really does leave every byte alone. Those are properties of the wiring, and
     * the only way to see them is to run the whole thing twice.
     *
     * @return the number of problems found
     */
    private static int deltaRuns(Path root, ExportConfig base, DatapackCatalog catalog)
            throws IOException {
        System.out.println();
        System.out.println("== the delta, over the real pipeline ==");
        int problems = 0;

        Path snapshot = root.resolve(org.uee.delta.Snapshot.FILE_NAME);
        ExportConfig deltaConfig = base.toBuilder().delta(true).build();

        // The first incremental run has nothing to compare against, so it writes in full and leaves a
        // manifest behind for the next one.
        ExportReport first = new Exporter(new FakeAdapter(), deltaConfig, root,
                AnalysisEngine.standard(), catalog).run();
        System.out.println("  first delta run:  " + first.deltaSummary());
        if (!Files.isRegularFile(snapshot)) {
            System.out.println("  FAIL no snapshot was written, so the next run has nothing to compare");
            problems++;
        } else {
            System.out.println("  a snapshot was left for the next run");
        }

        // The second run has identical input. Every artifact should be recognised and left alone.
        long filesBefore = countFiles(root);
        Map<String, Long> sizesBefore = sizesOf(root);
        ExportReport second = new Exporter(new FakeAdapter(), deltaConfig, root,
                AnalysisEngine.standard(), catalog).run();
        String summary = second.deltaSummary();
        System.out.println("  second delta run: " + summary);

        if (summary == null || !summary.contains("nothing to do")) {
            System.out.println("  FAIL a run over identical input did not report nothing to do");
            problems++;
        }
        if (countFiles(root) != filesBefore) {
            System.out.println("  FAIL the artifact count changed on a run that had nothing to write");
            problems++;
        }
        if (!sizesOf(root).equals(sizesBefore)) {
            System.out.println("  FAIL an artifact changed on a run that had nothing to write");
            problems++;
        }
        // Whichever way the decision went, the temporaries must not survive it.
        int parts = countPartFiles(root);
        if (parts != 0) {
            System.out.println("  FAIL " + parts + " temporary file(s) were left behind");
            problems++;
        } else {
            System.out.println("  no temporary files were left behind");
        }

        System.out.println(problems == 0 ? "  delta: OK" : "  delta: " + problems + " problem(s)");
        return problems;
    }

    private static long countFiles(Path root) {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return -1;
        }
    }

    private static int countPartFiles(Path root) {
        try (var walk = Files.walk(root)) {
            return (int) walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".part")).count();
        } catch (IOException e) {
            return -1;
        }
    }

    /** Every file's size, so a rewrite that changed nothing is still visible as a change. */
    private static Map<String, Long> sizesOf(Path root) {
        Map<String, Long> out = new java.util.TreeMap<>();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.put(root.relativize(p).toString().replace('\\', '/'), Files.size(p));
            }
        } catch (IOException e) {
            out.put("error", -1L);
        }
        return out;
    }

    // ---------------------------------------------------------------- structural validation

    /**
     * Checks that every artifact is structurally sound and that all encodings of the same shard carry
     * the same number of records.
     *
     * <p>This exists because a writer can be wrong in a way that only a structural check catches. A
     * missing closing brace produces a file of plausible size whose every line fails to parse, and a
     * size-based assertion passes it happily. The cross-format count check is the general form of the
     * same idea: the same records are encoded several ways, so the counts must agree, and any writer
     * that drops or truncates a record breaks the agreement.
     *
     * @return the number of problems found
     */
    private static int validate(ExportReport report, Path root) throws IOException {
        int problems = 0;
        java.util.Map<String, java.util.Map<String, Integer>> counts = new java.util.TreeMap<>();

        for (ExportReport.Artifact a : report.artifacts()) {
            if (ExportConfig.ZD.equals(a.format())) {
                // Binary: validated by decoding it back through the codec, not by reading text.
                continue;
            }
            String text = Files.readString(a.path());
            String shard = a.namespace() + "|" + a.kind()
                    + (a.fromTarget() ? "|target:" + a.target() : "");
            int records = -1;

            switch (a.format()) {
                case ExportConfig.NDJSON -> {
                    records = 0;
                    for (String line : text.split("\n")) {
                        if (line.isBlank()) {
                            continue;
                        }
                        try {
                            Object parsed = org.uee.util.JsonReader.parse(line);
                            if (!(parsed instanceof java.util.Map)) {
                                System.out.println("  PROBLEM not an object: " + a.path());
                                problems++;
                            }
                            records++;
                        } catch (RuntimeException e) {
                            System.out.println("  PROBLEM unparseable NDJSON line in "
                                    + root.relativize(a.path()) + ": " + e.getMessage());
                            problems++;
                            records++;
                        }
                    }
                }
                case ExportConfig.JSON -> {
                    try {
                        Object parsed = org.uee.util.JsonReader.parse(text);
                        if (parsed instanceof java.util.Map<?, ?> map) {
                            records = 0;
                            for (Object v : map.values()) {
                                if (v instanceof java.util.List<?> list) {
                                    records += list.size();
                                }
                            }
                        }
                    } catch (RuntimeException e) {
                        System.out.println("  PROBLEM unparseable JSON in "
                                + root.relativize(a.path()) + ": " + e.getMessage());
                        problems++;
                    }
                }
                case ExportConfig.TD -> {
                    records = countOccurrences(text, "\n  [");
                    problems += balance(text, '[', ']', "td", a.path());
                    problems += evenQuotes(text, "td", a.path());
                }
                case ExportConfig.TOML -> {
                    records = countOccurrences(text, "[[");
                    problems += balance(text, '[', ']', "toml", a.path());
                }
                case ExportConfig.YAML -> {
                    records = countOccurrences(text, "\n  - ");
                }
                case ExportConfig.XML -> {
                    // Count element openers only: "\n  </item>" also contains the needle, so a plain
                    // occurrence count would double every record.
                    records = 0;
                    for (int i = text.indexOf("\n  <"); i >= 0; i = text.indexOf("\n  <", i + 1)) {
                        if (i + 4 < text.length() && text.charAt(i + 4) != '/') {
                            records++;
                        }
                    }
                    problems += xmlBalance(text, a.path());
                }
                default -> {
                    // zd is binary and is verified by decoding it, not by counting text.
                }
            }

            if (records > 0) {
                counts.computeIfAbsent(shard, k -> new java.util.TreeMap<>())
                        .put(a.format(), records);
            }
        }

        for (java.util.Map.Entry<String, java.util.Map<String, Integer>> e : counts.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            java.util.Set<Integer> distinct = new java.util.TreeSet<>(e.getValue().values());
            if (distinct.size() > 1) {
                System.out.println("  PROBLEM record count disagrees for shard " + e.getKey()
                        + ": " + e.getValue());
                problems++;
            }
        }
        return problems;
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        int i = text.indexOf(needle);
        while (i >= 0) {
            n++;
            i = text.indexOf(needle, i + needle.length());
        }
        return n;
    }

    private static int balance(String text, char open, char close, String format, Path file) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth < 0) {
                    System.out.println("  PROBLEM unbalanced " + format + " in " + file);
                    return 1;
                }
            }
        }
        if (depth != 0) {
            System.out.println("  PROBLEM unbalanced " + format + " (" + depth + " unclosed) in " + file);
            return 1;
        }
        return 0;
    }

    private static int evenQuotes(String text, String format, Path file) {
        int quotes = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                quotes++;
            }
        }
        if ((quotes & 1) != 0) {
            System.out.println("  PROBLEM odd number of quotes in " + format + " " + file);
            return 1;
        }
        return 0;
    }

    /** Checks that every opened element is closed, ignoring self-closing tags and the XML prolog. */
    private static int xmlBalance(String text, Path file) {
        int depth = 0;
        int i = 0;
        while (true) {
            int open = text.indexOf('<', i);
            if (open < 0) {
                break;
            }
            int close = text.indexOf('>', open);
            if (close < 0) {
                System.out.println("  PROBLEM unterminated tag in " + file);
                return 1;
            }
            String tag = text.substring(open + 1, close);
            i = close + 1;
            if (tag.startsWith("?") || tag.startsWith("!")) {
                continue;
            }
            if (tag.endsWith("/")) {
                continue;
            }
            if (tag.startsWith("/")) {
                depth--;
                if (depth < 0) {
                    System.out.println("  PROBLEM unbalanced XML in " + file);
                    return 1;
                }
            } else {
                depth++;
            }
        }
        if (depth != 0) {
            System.out.println("  PROBLEM unbalanced XML (" + depth + " unclosed) in " + file);
            return 1;
        }
        return 0;
    }

    private static void deleteRecursively(Path p) throws IOException {
        try (var walk = Files.walk(p)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (IOException ignored) {
                    // best effort: the smoke test only needs a clean directory
                }
            });
        }
    }

    /** An adapter with a handful of hand-built records, exercising every element category. */
    static final class FakeAdapter implements LoaderAdapter {

        @Override
        public LoaderInfo info() {
            return new LoaderInfo("fabric", "0.16.0", "1.21.1", ".", true, false,
                    System.getProperty("java.version"), System.getProperty("os.name"),
                    System.getProperty("os.arch"));
        }

        @Override
        public List<ModElement> mods() {
            return List.of(
                    new ModElement("example", "Example Mod", "1.0.0", "example", "fabric", "1.21.1",
                            new String[] {"Someone"}, "TPL-2.3", "A demo mod for the smoke test.",
                            new Dependency[] {
                                new Dependency("fabricloader", ">=0.15.0", Dependency.Kind.REQUIRED),
                                new Dependency("absentlib", null, Dependency.Kind.REQUIRED),
                                new Dependency("jei", null, Dependency.Kind.OPTIONAL),
                                new Dependency("brokenmod", null, Dependency.Kind.INCOMPATIBLE)},
                            new String[] {"example_api"}, "example-1.0.0.jar"),
                    new ModElement("minecraft", "Minecraft", "1.21.1", "minecraft", "vanilla", "1.21.1",
                            new String[0], null, null, new Dependency[0], new String[0], null));
        }

        @Override
        public void collectRegistries(ExportConfig config, Collection<ElementKind> wanted,
                ElementSink sink) {
            if (wanted.contains(ElementKind.ITEM)) {
                sink.item(new ItemElement("example:ruby", "example", "item.example.ruby",
                        "红宝石", "Ruby", 64, 0,
                        new String[] {"c:gems", "example:gems"}, new String[] {"misc"},
                        null, null, false));
                sink.item(new ItemElement("example:ruby_block", "example", "block.example.ruby_block",
                        "红宝石块", "Block of Ruby", 64, 0,
                        new String[] {"c:storage_blocks"}, new String[] {"building_blocks"},
                        null, null, true));
                sink.item(new ItemElement("minecraft:stone", "minecraft", "block.minecraft.stone",
                        "石头", "Stone", 64, 0,
                        new String[] {"minecraft:base_stone_overworld"}, new String[] {"building_blocks"},
                        null, null, true));
                // A deliberately broken record, to prove crash isolation is real.
                try {
                    sink.item(new ItemElement("", "example", null, null, null, 0, 0, null, null, null, null, false));
                } catch (RuntimeException e) {
                    sink.failure(ElementKind.ITEM, "example:broken", e);
                }
            }
            if (wanted.contains(ElementKind.ENTITY)) {
                sink.entity(new EntityElement("example:ruby_golem", "example", "entity.example.ruby_golem",
                        "红宝石傀儡", "Ruby Golem", "creature", null));
            }
            if (wanted.contains(ElementKind.BLOCK)) {
                sink.block(new BlockElement("example:ruby_block", "example", "红宝石块", "Block of Ruby",
                        5.0f, 6.0f, 0, true, "metal", new String[] {"c:storage_blocks"}));
            }
            if (wanted.contains(ElementKind.EFFECT)) {
                sink.generic(ElementKind.EFFECT, "example", "example:shiny", "闪光", "Shiny",
                        new String[0], new String[] {"amplifierMax", "3"});
            }
        }

        @Override
        public void collectDatapacks(ExportConfig config, Collection<ElementKind> wanted,
                ElementSink sink) {
            if (!wanted.contains(ElementKind.RECIPE)) {
                return;
            }
            sink.recipe(new RecipeElement("example:ruby_block", "minecraft:crafting_shaped", "example",
                    new String[] {"1", "2", "3", "4", "5", "6", "7", "8", "9"},
                    new Ingredient[] {
                            Ingredient.ofItem("example:ruby"), Ingredient.ofItem("example:ruby"),
                            Ingredient.ofItem("example:ruby"), Ingredient.ofItem("example:ruby"),
                            Ingredient.ofItem("example:ruby"), Ingredient.ofItem("example:ruby"),
                            Ingredient.ofItem("example:ruby"), Ingredient.ofItem("example:ruby"),
                            Ingredient.ofItem("example:ruby")},
                    new String[] {"1"}, new String[] {"example:ruby_block"}, new int[] {1},
                    new String[] {null}, null, null));
            sink.recipe(new RecipeElement("example:ruby_from_smelting", "minecraft:smelting", "example",
                    new String[] {"1"}, new Ingredient[] {Ingredient.ofTag("c:ruby_ores")},
                    new String[] {"1"}, new String[] {"example:ruby"}, new int[] {1},
                    new String[] {null}, 0.35, 200));
        }

        @Override
        public List<DebugSection> debugSections() {
            return List.of(
                    DebugSection.of("environment",
                            "loader", "fabric",
                            "minecraftVersion", "1.21.1",
                            "javaVersion", System.getProperty("java.version")),
                    DebugSection.of("counts", "mods", "2", "registries", "4"));
        }

        @Override
        public boolean supports(String capability) {
            return switch (capability) {
                case CAP_REGISTRY_FROZEN, CAP_MOD_DEPENDENCIES, CAP_DATA_COMPONENTS -> true;
                default -> false;
            };
        }
    }
}
