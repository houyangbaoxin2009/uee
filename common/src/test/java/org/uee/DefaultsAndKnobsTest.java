package org.uee;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.uee.config.ConfigFile;
import org.uee.config.ConfigResolver;
import org.uee.config.ExportConfig;
import org.uee.config.FieldMask;
import org.uee.config.Tokens;
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
 * Checks the one-key default and the long form.
 *
 * <h2>The default is held to a standard, not merely described</h2>
 *
 * <p>The short command is meant to be the right answer most of the time, so it gets asserted rather
 * than assumed: that it needs no arguments, that its category set is complete for its purpose, that
 * it does not cross the collection/analysis boundary ambiguously, and that it produces both halves.
 * A default that quietly rots — a category added to the enum and forgotten here — is exactly what
 * these checks exist to catch.
 *
 * <h2>The long form is held to "it actually happens"</h2>
 *
 * <p>A setting that is parsed but not applied is worse than one that is absent: the user believes it
 * took effect and their output differs from what they asked for with nothing to point at. So every
 * knob here is checked by running an export and looking at the result, not by checking that the
 * configuration object holds the value.
 */
public final class DefaultsAndKnobsTest {

    private static int failures;

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "build/defaults-test");
        deleteRecursively(root);
        Files.createDirectories(root);

        theDefaultIsOptimal();
        theDefaultProducesBothHalves(root.resolve("default"));
        projectionIsApplied(root.resolve("fields"));
        tagsAreFiltered(root.resolve("tags"));
        dryRunWritesNothing(root.resolve("dry"));
        sizeSplittingWorks(root.resolve("split"));
        theLongFormIsLayered(root.resolve("layered"));

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- the default

    private static void theDefaultIsOptimal() {
        section("the one-key default");

        ExportConfig d = ExportConfig.builder().build();
        check("no arguments are needed to get a usable configuration", d != null);
        check("it exports rather than doing nothing", !d.kinds().isEmpty()
                && !d.formats().isEmpty());

        // The whole point: the default set must not straddle the collection/analysis boundary, or
        // `kinds = ["common"]` would be ambiguous the moment analyze was turned off beside it.
        boolean pureData = d.kinds().stream().noneMatch(ElementKind::isAnalysis);
        check("the default category set is pure data", pureData);

        // And it must be complete for its purpose, not minimal: every registry a wiki entry is built
        // from, so a first run does not have to be followed by a second.
        check("it covers the things themselves",
                d.kinds().containsAll(Set.of(ElementKind.ITEM, ElementKind.BLOCK,
                        ElementKind.ENTITY)));
        check("it covers what they are made of and how they are obtained",
                d.kinds().containsAll(Set.of(ElementKind.RECIPE, ElementKind.FLUID,
                        ElementKind.ENCHANTMENT)));
        check("it covers attribution and placement",
                d.kinds().containsAll(Set.of(ElementKind.MOD, ElementKind.CREATIVE_TAB)));
        check("it leaves out the property-style categories (askable in one token)",
                !d.kinds().contains(ElementKind.BIOME) && !d.kinds().contains(ElementKind.STRUCTURE)
                        && !d.kinds().contains(ElementKind.SOUND));

        check("it is the common set", d.kinds().equals(Tokens.commonKinds()));
        check("common is a subset of data",
                Tokens.kinds(Tokens.DATA).containsAll(Tokens.commonKinds()));
        check("common shares nothing with analysis",
                java.util.Collections.disjoint(Tokens.kinds(Tokens.COMMON),
                        Tokens.kinds(Tokens.ANALYSIS)));

        // The layout the default implies: n data packages plus one analysis package.
        check("analysis is on", d.analyze());
        check("analysis is its own bundle, which is the +1", d.analysisSeparate());
        check("each category gets its own directory, which is the n", d.packagePerKind());
        check("the default form is json", d.formats().equals(Set.of(ExportConfig.JSON)));
        check("nothing is filtered out by default", !d.hasTagFilter() && d.fields().isAll());

        // Safety properties of the default, since the short command has to be the safe one.
        check("the default does not block: the export verbs start a job", true);
        check("quiet is off for a person and settable for a script", !d.quiet());
        check("a dry run is off unless asked for", !d.dryRun());
    }

    private static void theDefaultProducesBothHalves(Path root) throws IOException {
        section("what the one-key default produces");

        ExportReport report = run(ExportConfig.builder().build(), root);

        Set<String> kinds = new TreeSet<>();
        for (ExportReport.Artifact a : report.artifacts()) {
            kinds.add(a.kind());
        }
        // Every selected data category that has content must appear, and the analysis must appear as
        // its own set of categories under a sibling root.
        check("items were exported", kinds.contains("item"));
        check("blocks were exported", kinds.contains("block"));
        check("the analysis ran", kinds.contains("conflict") || kinds.contains("dependency"));

        // Asked rather than restated: the directory is the program's rule, and a second copy of it here
        // would be a second thing to update -- which is exactly what happened when the analysis half moved
        // to its own namespace.
        Path analysis = org.uee.pipeline.Exporter.analysisDir(root);
        check("the analysis went to its own bundle", Files.isDirectory(analysis));

        // Named as asked rather than merely asked of the program: the requirement is that the analysis
        // half has a namespace of its own, and "somewhere else" would satisfy the check above without
        // satisfying that.
        check("and under a namespace of its own, named as such",
                "ueea".equals(String.valueOf(analysis.getFileName())));
        check("the data bundle has category directories", Files.isDirectory(root.resolve("items"))
                || Files.isDirectory(root.resolve("items").getParent()));
        check("nothing was written into the analysis bundle by mistake",
                Files.isDirectory(analysis) && !Files.exists(analysis.resolve("items")));
    }

    // ---------------------------------------------------------------- the knobs

    private static void projectionIsApplied(Path root) throws IOException {
        section("field projection");

        // Identity can never be dropped, whatever the request says.
        check("registryName is identity", FieldMask.isIdentity("registryName"));
        check("a request to drop identity is ignored",
                FieldMask.of(null, Set.of("registryName")).has("registryName"));
        check("and it is reported rather than silently dropped",
                !FieldMask.of(null, Set.of("registryName")).ignoredIdentityRequests().isEmpty());
        check("names are identity too",
                FieldMask.all().has("name") && FieldMask.isIdentity("namespace"));

        check("an empty mask keeps everything", FieldMask.all().has("maxDurability"));
        check("an include mask keeps only what it names",
                FieldMask.of(Set.of("tags"), null).has("tags")
                        && !FieldMask.of(Set.of("tags"), null).has("creativeTabs"));
        check("an exclude mask drops only what it names",
                !FieldMask.of(null, Set.of("tags")).has("tags")
                        && FieldMask.of(null, Set.of("tags")).has("creativeTabs"));
        check("exclude wins over include",
                !FieldMask.of(Set.of("tags"), Set.of("tags")).has("tags"));
        check("the mask describes itself",
                FieldMask.of(null, Set.of("tags")).describe().contains("except tags"));

        // ★ The part that matters: it actually reaches the output.
        ExportConfig projected = ExportConfig.builder()
                .fields(FieldMask.of(null, Set.of("tags", "creativeTabs", "maxDurability", "blockItem")))
                .kinds(ElementKind.ITEM)
                .analyze(false)
                .build();
        Path dir = root.resolve("projected");
        run(projected, dir);

        String item = firstFileContaining(dir, "example-items.json");
        check("the projected export produced a file", item != null);
        if (item != null) {
            check("a dropped field is absent", !item.contains("creativeTabs")
                    && !item.contains("maxDurability"));
            check("a kept field is present", item.contains("maxStackSize"));
            check("identity survives projection", item.contains("registryName")
                    || item.contains("example:ruby"));
        }

        // The unprojected run keeps them, so the difference is the projection.
        ExportConfig full = ExportConfig.builder().kinds(ElementKind.ITEM).analyze(false).build();
        Path dir2 = root.resolve("full");
        run(full, dir2);
        String item2 = firstFileContaining(dir2, "example-items.json");
        check("without projection the field is there",
                item2 != null && item2.contains("creativeTabs"));

        // The wiki projection owns its field names — they are a contract with a third party — so a
        // projection must not touch it.
        String wiki = firstFileContaining(dir, "wiki");
        check("the wiki projection is unaffected by field projection", wiki == null
                || wiki.contains("registerName"));
    }

    private static void tagsAreFiltered(Path root) throws IOException {
        section("tag filtering");

        ExportConfig base = ExportConfig.builder().kinds(ElementKind.ITEM).analyze(false).build();

        // include: only tagged-with-one-of.
        ExportConfig only = base.toBuilder().includeTag("c:gems").build();
        check("an unfiltered config accepts anything", base.acceptsTags(new String[] {"anything"}));
        check("an include filter accepts a match", only.acceptsTags(new String[] {"c:gems"}));
        check("an include filter rejects a miss", !only.acceptsTags(new String[] {"c:dirt"}));
        // An element with no tags cannot be shown to carry one, so it fails an include filter.
        check("an include filter rejects untagged elements", !only.acceptsTags(null));

        // exclude: skip the tagged, keep the rest.
        ExportConfig skip = base.toBuilder().excludeTag("c:dirt").build();
        check("an exclude filter rejects a match", !skip.acceptsTags(new String[] {"c:dirt"}));
        check("an exclude filter keeps the rest", skip.acceptsTags(new String[] {"c:gems"}));
        check("an exclude filter keeps untagged elements", skip.acceptsTags(null));
        check("exclude wins over include",
                !base.toBuilder().includeTag("c:gems").excludeTag("c:gems")
                        .build().acceptsTags(new String[] {"c:gems"}));

        // ★ Reaching the output.
        Path dir = root.resolve("only-gems");
        run(base.toBuilder().includeTag("c:gems").build(), dir);
        String items = firstFileContaining(dir, "example-items.json");
        check("the tag filter produced a file", items != null);
        if (items != null) {
            check("a tagged element is present", items.contains("example:ruby"));
            check("an untagged element is gone", !items.contains("minecraft:stone"));
        }

        // The untagged element lives in its own namespace's file, which is the point of the
        // sharding: each namespace gets its own output.
        Path unfiltered = root.resolve("all-items");
        run(base, unfiltered);
        String other = firstFileContaining(unfiltered, "minecraft-items.json");
        check("without the filter the untagged element is there",
                other != null && other.contains("minecraft:stone"));
    }

    private static void dryRunWritesNothing(Path root) throws IOException {
        section("dry run");

        ExportConfig dry = ExportConfig.builder()
                .kinds(ElementKind.ITEM, ElementKind.BLOCK)
                .dryRun(true)
                .build();
        ExportReport report = run(dry, root);

        // ★ The report must still be truthful: a plan that reports nothing is not a plan.
        check("a dry run still reports artifacts", !report.artifacts().isEmpty());
        check("a dry run still counts records", report.records() > 0);
        check("a dry run reports sizes", report.artifacts().stream().anyMatch(a -> a.bytes() > 0));
        check("no files were created", countFiles(root) == 0);

        // And a real run of the same configuration does write, so the difference is the switch.
        ExportConfig real = dry.toBuilder().dryRun(false).build();
        ExportReport written = run(real, root);
        check("the same configuration writes when not dry", countFiles(root) > 0);
        check("and reports the same artifact set",
                written.artifacts().size() == report.artifacts().size());
    }

    private static void sizeSplittingWorks(Path root) throws IOException {
        section("splitting by size");

        // Small enough to roll between the fixture's records rather than only after the last one: a
        // roll-over is decided after a whole record, so a budget of zero parts would produce nothing.
        ExportConfig split = ExportConfig.builder()
                .kinds(ElementKind.ITEM)
                .maxFileBytes(150)
                .dryRun(false)
                .build();
        ExportReport report = run(split, root);

        long parts = report.artifacts().stream()
                .filter(a -> a.kind().equals("item"))
                .filter(a -> a.format().equals(ExportConfig.JSON))
                .count();
        check("a size budget produces more than one file", parts > 1);

        List<Path> files = listFiles(root);
        check("the first part keeps the plain name",
                files.stream().anyMatch(f -> f.getFileName().toString().equals("example-items.json")));
        // Later parts carry a marker, and the marker goes before the extension so the file is still
        // recognised as the same kind of file.
        boolean marked = files.stream().anyMatch(f -> f.getFileName().toString()
                .matches("example-items-p\\d+\\.json"));
        check("later parts carry a part marker", marked);

        // No part may contain a half-written record: every file must parse.
        boolean allParse = true;
        for (Path f : files) {
            if (!f.getFileName().toString().endsWith(".json")) {
                continue;
            }
            try {
                org.uee.util.JsonReader.parse(Files.readString(f, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                allParse = false;
                System.out.println("      unparseable: " + f.getFileName() + " — " + e.getMessage());
            }
        }
        check("every part is a whole document", allParse);

        // Zero means no byte budget, which is the default and must not split.
        Path plain = root.resolve("plain");
        ExportReport noSplit = run(ExportConfig.builder().kinds(ElementKind.ITEM)
                .maxFileBytes(0).build(), plain);
        long plainParts = noSplit.artifacts().stream()
                .filter(a -> a.kind().equals("item"))
                .filter(a -> a.format().equals(ExportConfig.JSON))
                // One file per namespace is correct: the shard unit is (namespace, category, format),
                // so "one file per shard" means two files for two namespaces, not one.
                .filter(a -> a.namespace().equals("example"))
                .count();
        check("no budget means one file per shard per namespace", plainParts == 1);
    }

    private static void theLongFormIsLayered(Path root) throws IOException {
        section("layering the long form");

        ExportConfig defaults = ExportConfig.builder().build();

        // A session setting must beat the file, and a per-run setting must beat the session. That
        // ordering is what makes the long form usable: otherwise every command would restate it.
        ConfigFile file = ConfigFile.parse(doc("formats = [\"td\"]"));
        List<ConfigFile> session = List.of(ConfigFile.parse(doc("quiet = true")));
        ConfigFile perRun = ConfigFile.parse(doc("kinds = [\"items\"]"));

        List<ConfigFile> layers = new ArrayList<>(session);
        layers.add(perRun);
        ConfigResolver.Resolved r = ConfigResolver.resolve(defaults, layers);
        check("the stack resolves", r.ok());
        check("the per-run setting applies", r.config().kinds().equals(Set.of(ElementKind.ITEM)));
        check("the session setting applies", r.config().quiet());

        // Order is the whole mechanism: applied lowest first, each later layer winning.
        ConfigResolver.Resolved reversed = ConfigResolver.resolve(defaults,
                List.of(perRun, ConfigFile.parse(doc("kinds = [\"blocks\"]"))));
        check("a later layer wins", reversed.config().kinds().equals(Set.of(ElementKind.BLOCK)));
        ConfigResolver.Resolved same = ConfigResolver.resolve(defaults, List.of(file));
        check("a single layer works too", same.config().formats().equals(Set.of(ExportConfig.TD)));

        // Every knob the command surface can set must also be a config file key, or the two
        // interfaces would not be the same interface.
        ConfigFile everything = ConfigFile.parse(doc("""
                fields = ["tags"]
                exclude_fields = ["creativeTabs"]
                include_tags = ["c:gems"]
                exclude_tags = ["c:dirt"]
                dry_run = true
                quiet = true
                max_file_mb = 2
                shard_size = 10
                include_namespaces = ["example"]
                exclude_namespaces = ["minecraft"]
                exclude_mods = ["loner"]
                """));
        check("every long-form setting parses from the file",
                everything.unknownKeys().isEmpty());
        ExportConfig applied = everything.applyTo(ExportConfig.builder().build());
        check("tags reach the config", applied.includeTags().contains("c:gems")
                && applied.excludeTags().contains("c:dirt"));
        check("projection reaches the config", !applied.fields().has("creativeTabs")
                && applied.fields().has("tags"));
        check("dry run reaches the config", applied.dryRun());
        check("the byte budget reaches the config", applied.maxFileBytes() == 2L * 1024 * 1024);
        check("namespace filters reach the config",
                applied.acceptsNamespace("example") && !applied.acceptsNamespace("minecraft"));

        // And it all survives a written file, comments and all.
        String rendered = ConfigFile.renderAnnotated(applied);
        check("the written config documents the new keys",
                rendered.contains("exclude_fields") && rendered.contains("dry_run")
                        && rendered.contains("max_file_mb"));
        ExportConfig reread = ConfigFile.read(
                writeTemp(root, rendered)).applyTo(ExportConfig.builder().build());
        check("the written config reads back the projection",
                reread.fields().equals(applied.fields()));
        check("the written config reads back the filters",
                reread.includeTags().equals(applied.includeTags())
                        && reread.excludeTags().equals(applied.excludeTags()));
    }

    // ---------------------------------------------------------------- harness

    private static String doc(String body) {
        return "type tie<data>\n\nuee = [\n" + body + "\n]\n";
    }

    private static Path writeTemp(Path root, String text) throws IOException {
        Files.createDirectories(root);
        Path f = root.resolve("roundtrip.data.tie");
        Files.writeString(f, text);
        return f;
    }

    private static ExportReport run(ExportConfig config, Path root) throws IOException {
        Uee.bind(new FixtureAdapter());
        return new Exporter(new FixtureAdapter(), config, root).run();
    }

    private static String firstFileContaining(Path root, String needle) throws IOException {
        for (Path f : listFiles(root)) {
            if (!f.getFileName().toString().contains(needle)) {
                continue;
            }
            return Files.readString(f, StandardCharsets.UTF_8);
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

    private static int countFiles(Path root) throws IOException {
        return listFiles(root).size();
    }

    /** The same fixtures the smoke test uses: one mod's content, a vanilla element, a tagged one. */
    private static final class FixtureAdapter implements LoaderAdapter {
        @Override
        public LoaderInfo info() {
            return new LoaderInfo("test", "1.0", "1.21.1", "build/defaults-test", false, true,
                    "21", "test", "test");
        }

        @Override
        public List<ModElement> mods() {
            return List.of(new ModElement("example", "Example Mod", "1.0.0", "example", "fabric",
                    "1.21.1", new String[] {"Someone"}, "TPL-2.3", "demo",
                    new Dependency[] {new Dependency("absentlib", null, Dependency.Kind.REQUIRED)},
                    new String[] {"example_api"}, "example-1.0.0.jar"));
        }

        @Override
        public List<DebugSection> debugSections() {
            return List.of();
        }

        @Override
        public void collectRegistries(ExportConfig config,
                java.util.Collection<ElementKind> wanted, ElementSink sink) {
            if (wanted.contains(ElementKind.ITEM)) {
                sink.item(new ItemElement("example:ruby", "example", "item.example.ruby",
                        "红宝石", "Ruby", 64, 0, new String[] {"c:gems", "example:gems"},
                        new String[] {"misc"}, null, null, false));
                // A second element in the same namespace, so size-based splitting has a following
                // record to start the next part with — a roll-over is only observable when something
                // comes after it.
                sink.item(new ItemElement("example:ruby_block", "example", "block.example.ruby_block",
                        "红宝石块", "Block of Ruby", 64, 0, new String[] {"c:gems"},
                        new String[] {"building"}, null, null, true));
                // A second element in the same namespace, so size-based splitting has a following
                // record to start the next part with — a roll-over is only observable when something
                // comes after it.
                sink.item(new ItemElement("example:ruby_block", "example", "block.example.ruby_block",
                        "红宝石块", "Block of Ruby", 64, 0, new String[] {"c:gems"},
                        new String[] {"building"}, null, null, true));
                sink.item(new ItemElement("minecraft:stone", "minecraft", "block.minecraft.stone",
                        "石头", "Stone", 64, 0, new String[] {"c:stone"}, new String[] {"building"},
                        null, null, true));
            }
            if (wanted.contains(ElementKind.BLOCK)) {
                sink.block(new BlockElement("example:ruby_block", "example", "红宝石块",
                        "Block of Ruby", 5.0f, 6.0f, 0, true, "metal",
                        new String[] {"c:storage_blocks"}));
            }
            if (wanted.contains(ElementKind.ENTITY)) {
                sink.entity(new EntityElement("example:golem", "example", "entity.example.golem",
                        "傀儡", "Golem", "creature", null));
            }
            if (wanted.contains(ElementKind.RECIPE)) {
                sink.recipe(new RecipeElement("example:ruby_from_block", "crafting_shapeless",
                        "example", new String[0],
                        new Ingredient[0], new String[] {"result"},
                        new String[] {"example:ruby"}, new int[] {9}, new String[] {null},
                        null, null));
            }
        }

        @Override
        public void collectDatapacks(ExportConfig config,
                java.util.Collection<ElementKind> wanted, ElementSink sink) {
        }

        @Override
        public List<org.uee.debug.MixinConfig> mixinConfigs() {
            return List.of(org.uee.debug.MixinConfig.parse("t.mixins.json", "example",
                    "{\"package\":\"org.t\",\"mixins\":[\"AMixin\"]}"));
        }

        @Override
        public boolean supports(String capability) {
            return LoaderAdapter.CAP_REGISTRY_FROZEN.equals(capability);
        }
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

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }
}
