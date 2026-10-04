package org.uee;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import org.uee.config.ExportConfig;
import org.uee.config.WikiOptions;
import org.uee.model.BlockElement;
import org.uee.model.DebugSection;
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
                .icons(true)
                .wiki(WikiOptions.builder().enabled(true).icons(true).build())
                .build();

        ExportReport report = new Exporter(new FakeAdapter(), config, root).run();
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
        for (ExportReport.Artifact a : report.artifacts()) {
            System.out.println("\n---- " + root.relativize(a.path()) + " ----");
            String text = Files.readString(a.path());
            System.out.println(text.length() > 900 ? text.substring(0, 900) + "\n...[truncated]" : text);
        }
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
                            new String[] {"fabricloader>=0.15.0"}, new String[] {"example_api"},
                            "example-1.0.0.jar"),
                    new ModElement("minecraft", "Minecraft", "1.21.1", "minecraft", "vanilla", "1.21.1",
                            new String[0], null, null, new String[0], new String[0], null));
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
                sink.generic(ElementKind.EFFECT, "example:shiny", "闪光", "Shiny",
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
