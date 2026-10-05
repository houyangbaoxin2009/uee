package org.uee;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.uee.config.ExportConfig;
import org.uee.config.Tokens;
import org.uee.datapack.LootTableFile;
import org.uee.model.DebugSection;
import org.uee.model.Dependency;
import org.uee.model.ElementKind;
import org.uee.model.ItemElement;
import org.uee.model.ModElement;
import org.uee.pipeline.ExportReport;
import org.uee.pipeline.Exporter;
import org.uee.spi.ElementSink;
import org.uee.spi.LoaderAdapter;
import org.uee.spi.LoaderInfo;
import org.uee.util.OrderedWork;

/**
 * Checks loot-table parsing and the parallel skeleton the collection now runs on.
 *
 * <h2>Two jobs, one file</h2>
 *
 * <p>The parser is checked against every entry shape the shipped vanilla data actually contains. Those
 * shapes were read out of the game's own jar rather than imagined, and two of them would have been missed
 * by a reasonable guess: a {@code tag} entry carries its id under {@code name}, and a {@code loot_table}
 * entry's {@code value} is sometimes a name and sometimes an entire inline table.
 *
 * <p>Then the skeleton. Parallel collection is only safe to switch on if its output does not depend on the
 * thread count, so that is asserted directly: the same work run on one thread and on eight must produce
 * the same sequence. The bound on in-flight results is asserted too, because it is the reason memory stays
 * flat — an unbounded queue would give the same output while quietly accumulating the whole input.
 *
 * <p>What this cannot check is that the real adapter's collection is thread-count-independent, since that
 * needs a resource manager and therefore a game. A game test does that with the loaded datapacks.
 */
public final class LootTableTest {

    private static int failures;

    public static void main(String[] args) throws IOException {
        parsing();
        references();
        paths();
        ordering();
        boundedWindow();
        failuresPropagate();
        theRecordItProduces(Path.of(args.length > 0 ? args[0] : "build/loot-table-test"));
        theCategoryItself();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- parsing

    private static void parsing() {
        section("parsing every entry shape the shipped data contains");

        // The one that was measured: 982 of 1183 vanilla tables are block drops.
        LootTableFile.Table block = LootTableFile.parse("""
                {
                  "type": "minecraft:block",
                  "random_sequence": "minecraft:blocks/stone",
                  "pools": [
                    { "rolls": 1.0,
                      "entries": [ { "type": "minecraft:item", "name": "minecraft:cobblestone" } ],
                      "conditions": [ { "condition": "minecraft:survives_explosion" } ] }
                  ]
                }""");
        check("the top-level type is read", block.type().equals("minecraft:block"));
        check("a plain item entry yields its item",
                block.items().equals(List.of("minecraft:cobblestone")));
        check("the pool is counted", block.pools() == 1);
        check("the entry is counted", block.entries() == 1);
        check("a condition on a pool is noticed", block.hasConditions());

        // alternatives -> children -> items. 77 of these exist.
        LootTableFile.Table nested = LootTableFile.parse("""
                { "type": "minecraft:entity",
                  "pools": [ { "rolls": 1.0, "entries": [
                    { "type": "minecraft:alternatives", "children": [
                        { "type": "minecraft:item", "name": "minecraft:shears" },
                        { "type": "minecraft:item", "name": "minecraft:wool" }
                    ] } ] } ] }""");
        check("children are walked", nested.items().size() == 2);
        check("and their order is preserved",
                nested.items().equals(List.of("minecraft:shears", "minecraft:wool")));
        check("the composite itself is counted as an entry", nested.entries() == 3);

        // A tag entry uses `name`, not a leading hash -- this is the shape that a reasonable guess gets
        // wrong, since a tag member in a tag file is written with a hash.
        LootTableFile.Table tagged = LootTableFile.parse("""
                { "type": "minecraft:entity",
                  "pools": [ { "entries": [
                    { "type": "minecraft:tag", "expand": true,
                      "name": "minecraft:creeper_drop_music_discs" } ] } ] }""");
        check("a tag entry is recorded as a reference", tagged.tags().size() == 1);
        check("the tag id comes from `name`",
                tagged.tags().get(0).equals("#minecraft:creeper_drop_music_discs"));
        check("and it is not mistaken for an item", tagged.items().isEmpty());

        // alternatives with `empty`, and an entry type this build does not know.
        LootTableFile.Table mixed = LootTableFile.parse("""
                { "type": "minecraft:chest",
                  "pools": [ { "entries": [
                    { "type": "minecraft:empty" },
                    { "type": "minecraft:dynamic", "name": "minecraft:contents" },
                    { "type": "minecraft:something_new", "name": "minecraft:emerald" }
                  ] } ] }""");
        check("an empty entry contributes nothing",
                !mixed.items().contains("minecraft:empty"));
        check("dynamic carries a name but is not an item id",
                !mixed.items().contains("minecraft:contents"));
        check("an unknown entry type keeps its name rather than losing it",
                mixed.items().equals(List.of("minecraft:emerald")));

        // Top-level functions, which 9 tables have. Only their effect on the record matters: nothing.
        LootTableFile.Table withFunctions = LootTableFile.parse("""
                { "type": "minecraft:block", "functions": [ { "function": "minecraft:smelt" } ],
                  "pools": [] }""");
        check("top-level functions do not break parsing",
                withFunctions.pools() == 0 && withFunctions.items().isEmpty());

        check("a table with no pools parses",
                LootTableFile.parse("{ \"type\": \"minecraft:block\" }").items().isEmpty());

        boolean refused = false;
        try {
            LootTableFile.parse("[1,2,3]");
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        check("a document that is not an object is refused", refused);

        boolean refusedBad = false;
        try {
            LootTableFile.parse("{ \"pools\": [ ");
        } catch (RuntimeException expected) {
            refusedBad = true;
        }
        check("truncated json is refused", refusedBad);

        // Duplicates are folded: the same item listed in several pools is one answer to "what can this
        // drop". The declared entry count still reports what the file wrote.
        LootTableFile.Table repeats = LootTableFile.parse("""
                { "type": "minecraft:entity",
                  "pools": [
                    { "entries": [ { "type": "minecraft:item", "name": "minecraft:bone" } ] },
                    { "entries": [ { "type": "minecraft:item", "name": "minecraft:bone" },
                                   { "type": "minecraft:item", "name": "minecraft:rotten_flesh" } ] }
                  ] }""");
        check("a repeated item is recorded once", repeats.items().size() == 2);
        check("but the declared entry count is not folded", repeats.entries() == 3);
    }

    private static void references() {
        section("references: recorded, not expanded");

        // The form that a reader assuming `value` is always a name would silently drop.
        LootTableFile.Table inline = LootTableFile.parse("""
                { "type": "minecraft:fishing",
                  "pools": [ { "entries": [
                    { "type": "minecraft:loot_table",
                      "value": { "pools": [ { "entries": [
                          { "type": "minecraft:item", "name": "minecraft:salmon" } ] } ] } } ] } ] }""");
        check("an inline table's contents are walked, since they are this file's own",
                inline.items().equals(List.of("minecraft:salmon")));
        check("and it is not also recorded as a reference", inline.tables().isEmpty());

        LootTableFile.Table byName = LootTableFile.parse("""
                { "type": "minecraft:chest",
                  "pools": [ { "entries": [
                    { "type": "minecraft:loot_table", "value": "minecraft:gameplay/fishing/fish" } ] } ] }""");
        check("a named table is recorded as a reference",
                byName.tables().equals(List.of("minecraft:gameplay/fishing/fish")));
        check("and its contents are not invented", byName.items().isEmpty());

        // Both forms in one file, which is why neither may be assumed.
        LootTableFile.Table both = LootTableFile.parse("""
                { "type": "minecraft:chest",
                  "pools": [ { "entries": [
                    { "type": "minecraft:loot_table", "value": "minecraft:chests/simple_dungeon" },
                    { "type": "minecraft:tag", "name": "minecraft:wool" },
                    { "type": "minecraft:item", "name": "minecraft:bone" } ] } ] }""");
        String[] extra = LootTableFile.extraPairs(both);
        check("references and items coexist", both.items().size() == 1 && both.tags().size() == 1
                && both.tables().size() == 1);
        check("the reference lists reach the record",
                joined(extra, "tableRefs").equals("minecraft:chests/simple_dungeon"));
        check("tag references reach the record without the hash",
                joined(extra, "tagRefs").equals("minecraft:wool"));
        check("the type is recorded",
                joined(extra, "lootType").equals("minecraft:chest"));

        // An empty list must not be emitted as an empty key: records are diffed across runs and a key
        // that comes and goes reads as a change.
        check("an absent reference list emits no key",
                joined(extra, "nope") == null);
        String[] plain = LootTableFile.extraPairs(
                LootTableFile.parse("{ \"type\": \"minecraft:block\", \"pools\": [] }"));
        check("a table with no references carries no reference keys",
                joined(plain, "tableRefs") == null && joined(plain, "tagRefs") == null);
        check("but it still carries its type and counts",
                joined(plain, "lootType").equals("minecraft:block") && joined(plain, "pools") != null);
    }

    private static void paths() {
        section("resource paths");

        check("the table name is the whole path below the directory",
                "chests/desert_pyramid".equals(LootTableFile.pathOf("loot_table/chests/desert_pyramid.json")));
        check("a one-segment name works",
                "stone".equals(LootTableFile.pathOf("loot_table/stone.json")));
        check("the folder is reported separately",
                "chests".equals(LootTableFile.folderOf("loot_table/chests/desert_pyramid.json")));
        check("a top-level table has no folder",
                LootTableFile.folderOf("loot_table/stone.json") == null);
        check("a non-loot-table path yields nothing",
                LootTableFile.pathOf("tags/item/gems.json") == null);
        check("an empty name yields nothing",
                LootTableFile.pathOf("loot_table/.json") == null);
        check("the directory is shared with the enumerating side",
                LootTableFile.directory().equals("loot_table"));
    }

    // ---------------------------------------------------------------- the skeleton

    private static void ordering() {
        section("the skeleton: output does not depend on the thread count");

        List<Integer> input = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            input.add(i);
        }

        List<Integer> single = OrderedWork.map(input, 1, i -> i * 2);
        List<Integer> many = OrderedWork.map(input, 8, i -> i * 2);
        check("eight threads produce the same sequence as one",
                single.equals(many));
        check("and it is the input order", many.get(0) == 0 && many.get(4999) == 9998);

        // The real risk: a later item finishing long before an earlier one. Without an ordering step the
        // slow first item would appear last.
        List<Integer> skewed = OrderedWork.map(List.of(0, 1, 2, 3, 4, 5, 6, 7), 4, i -> {
            if (i == 0) {
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return i;
        });
        check("a slow first item still arrives first",
                skewed.equals(List.of(0, 1, 2, 3, 4, 5, 6, 7)));

        check("an empty input runs", OrderedWork.map(List.of(), 8, i -> i).isEmpty());
        check("a single item runs without a pool",
                OrderedWork.map(List.of(7), 8, i -> i + 1).equals(List.of(8)));

        // Below four items per thread there is nothing worth spreading out, so the caller is told to use
        // one. Checked because using eight threads for four items costs more than it saves.
        check("a tiny input is not spread out", OrderedWork.usefulThreads(8, 4) == 1);
        check("a large input is", OrderedWork.usefulThreads(8, 4000) == 8);
        check("and one thread stays one", OrderedWork.usefulThreads(1, 4000) == 1);
    }

    private static void boundedWindow() {
        section("the skeleton: memory is bounded by the window, not the input");

        // Counts results produced but not yet consumed. That is exactly what would accumulate if the
        // queue were unbounded, so its high-water mark is the property under test.
        AtomicInteger alive = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        int window = 8;
        int threads = 2;

        List<Integer> input = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            input.add(i);
        }

        OrderedWork.run(input, threads, window, "bound-test", i -> {
            int now = alive.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            return i;
        }, value -> alive.decrementAndGet());

        // threads * window results may be outstanding; the point is that it is a constant, not that it is
        // exactly that number.
        int limit = threads * window;
        check("at most threads x window results are alive at once (peak " + peak.get() + ", limit "
                + limit + ")", peak.get() <= limit);
        check("and the bound is actually exercised, so the check is not vacuous", peak.get() > 1);
        check("every result was consumed", alive.get() == 0);

        // The tightest setting still has to work: a window of one serialises the work but must not
        // deadlock, since the consumer waits on a future that is always already submitted.
        List<Integer> tight = OrderedWork.map(input.subList(0, 50), 4, i -> i);
        check("a window of one per thread completes", tight.size() == 50);
    }

    private static void failuresPropagate() {
        section("the skeleton: a failure is not swallowed");

        boolean seen = false;
        try {
            OrderedWork.map(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 4, i -> {
                if (i == 6) {
                    throw new IllegalStateException("deliberate");
                }
                return i;
            });
        } catch (IllegalStateException expected) {
            seen = true;
        }
        check("an exception from a worker reaches the caller", seen);

        // Errors are not wrapped into RuntimeException either, so an Error still behaves like one.
        boolean seenError = false;
        try {
            OrderedWork.map(List.of(1, 2, 3, 4), 2, i -> {
                throw new AssertionError("deliberate");
            });
        } catch (AssertionError expected) {
            seenError = true;
        }
        check("an Error is rethrown as an Error", seenError);
    }

    // ---------------------------------------------------------------- the produced record

    private static void theRecordItProduces(Path root) throws IOException {
        section("the exported record");

        LootTableFile.Table sample = LootTableFile.parse("""
                { "type": "minecraft:block",
                  "pools": [ { "entries": [ { "type": "minecraft:item", "name": "minecraft:cobblestone" } ],
                               "conditions": [ { "condition": "minecraft:survives_explosion" } ] } ] }""");

        deleteRecursively(root);
        Files.createDirectories(root);
        Fixture fixture = new Fixture(sample);
        Uee.bind(fixture);
        ExportConfig config = ExportConfig.builder()
                .kinds(ElementKind.LOOT_TABLE)
                .analyze(false)
                .build();
        ExportReport report = new Exporter(fixture, config, root).run();
        check("the export reported the table", report.records() >= 1);

        String json = firstFileContaining(root, "example-loot_tables");
        check("the table landed in its namespace's file", json != null);
        if (json != null) {
            try {
                org.uee.util.JsonReader.parse(json);
                check("the artifact is a whole document", true);
            } catch (RuntimeException e) {
                check("the artifact is a whole document (" + e.getMessage() + ")", false);
            }
            check("the table's name is recorded", json.contains("blocks/stone"));
            check("the produced item is recorded", json.contains("minecraft:cobblestone"));
            check("the loot context type is recorded", json.contains("\"lootType\""));
            check("the condition is flagged", json.contains("\"conditional\""));
        }

        // The same export written twice must be identical, which is the property the whole diff feature
        // rests on. Threads are a config value; changing it must not change a byte.
        Path one = root.resolveSibling(root.getFileName() + "-t1");
        Path four = root.resolveSibling(root.getFileName() + "-t4");
        for (Path dir : List.of(one, four)) {
            deleteRecursively(dir);
        }
        new Exporter(fixture, config.toBuilder().threads(1).build(), one).run();
        new Exporter(fixture, config.toBuilder().threads(4).build(), four).run();
        String diff = diffTrees(one, four);
        check("one thread and four threads write identical bytes"
                + (diff.isEmpty() ? "" : " (" + diff + ")"), diff.isEmpty());
    }

    private static void theCategoryItself() {
        section("the category in the vocabulary");

        check("LOOT_TABLE is collected content, not analysis",
                !ElementKind.LOOT_TABLE.isAnalysis());
        check("its token is loot_table", ElementKind.LOOT_TABLE.singular().equals("loot_table"));
        check("its plural is loot_tables", ElementKind.LOOT_TABLE.plural().equals("loot_tables"));
        check("the plural resolves to it",
                Tokens.kinds("loot_tables").equals(Set.of(ElementKind.LOOT_TABLE)));
        check("the singular does too",
                Tokens.kinds("loot_table").equals(Set.of(ElementKind.LOOT_TABLE)));
        check("it is in the data half", Tokens.kinds(Tokens.DATA).contains(ElementKind.LOOT_TABLE));
        check("and not in the analysis half",
                !Tokens.kinds(Tokens.ANALYSIS).contains(ElementKind.LOOT_TABLE));
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

    /** Compares two directory trees by relative path and content. Returns the first difference. */
    private static String diffTrees(Path a, Path b) throws IOException {
        List<Path> fa = listFiles(a);
        List<Path> fb = listFiles(b);
        if (fa.size() != fb.size()) {
            return "file count " + fa.size() + " vs " + fb.size();
        }
        for (int i = 0; i < fa.size(); i++) {
            String ra = a.relativize(fa.get(i)).toString().replace('\\', '/');
            String rb = b.relativize(fb.get(i)).toString().replace('\\', '/');
            if (!ra.equals(rb)) {
                return "path " + ra + " vs " + rb;
            }
            String ca = Files.readString(fa.get(i), StandardCharsets.UTF_8);
            String cb = Files.readString(fb.get(i), StandardCharsets.UTF_8);
            if (!ca.equals(cb)) {
                return "content of " + ra;
            }
        }
        return "";
    }

    private static String firstFileContaining(Path root, String needle) throws IOException {
        for (Path f : listFiles(root)) {
            if (f.getFileName().toString().contains(needle)) {
                return Files.readString(f, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static List<Path> listFiles(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static final class Fixture implements LoaderAdapter {
        private final LootTableFile.Table table;

        Fixture(LootTableFile.Table table) {
            this.table = table;
        }

        @Override
        public LoaderInfo info() {
            return new LoaderInfo("test", "1.0", "1.21.1", "build/loot-table-test", false, true,
                    "21", "test", "test");
        }

        @Override
        public List<ModElement> mods() {
            return List.of(new ModElement("example", "Example Mod", "1.0.0", "example", "neoforge",
                    "1.21.1", new String[] {"Someone"}, "TPL-2.3", "demo", new Dependency[0],
                    new String[0], "example-1.0.0.jar"));
        }

        @Override
        public List<DebugSection> debugSections() {
            return List.of();
        }

        @Override
        public void collectRegistries(ExportConfig config, java.util.Collection<ElementKind> wanted,
                ElementSink sink) {
            if (wanted.contains(ElementKind.ITEM)) {
                sink.item(new ItemElement("example:cobblestone", "example", "item.example.cobblestone",
                        "圆石", "Cobblestone", 64, 0, new String[0], new String[0], null, null, false));
            }
        }

        @Override
        public void collectDatapacks(ExportConfig config, java.util.Collection<ElementKind> wanted,
                ElementSink sink) {
            if (!wanted.contains(ElementKind.LOOT_TABLE)) {
                return;
            }
            sink.generic(ElementKind.LOOT_TABLE, "example", "blocks/stone", null, null,
                    table.itemArray(), LootTableFile.extraPairs(table));
        }

        @Override
        public List<org.uee.debug.MixinConfig> mixinConfigs() {
            return List.of();
        }

        @Override
        public boolean supports(String capability) {
            return LoaderAdapter.CAP_REGISTRY_FROZEN.equals(capability);
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
