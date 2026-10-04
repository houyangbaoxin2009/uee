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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.jar.Attributes;
import org.uee.debug.MixinConfig;
import org.uee.debug.ModAnalyzer;
import org.uee.debug.ModContainerScanner;
import org.uee.model.DebugSection;
import org.uee.model.Dependency;
import org.uee.model.ElementKind;
import org.uee.model.ModElement;
import org.uee.spi.ElementSink;

/**
 * Exercises the diagnostic layer against real jar fixtures.
 *
 * <p>Everything under test — mixin config parsing, container scanning, and the dependency/conflict
 * analysis — is deliberately free of Minecraft classes, so it can be verified here with genuine jar
 * files rather than only inside a running game. The fixtures are built rather than checked in: a
 * generated jar with a known manifest and mixin config is a stronger statement than a binary blob
 * nobody can read.
 */
public final class DebugAnalysisTest {

    private static int failures;

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "build/debug-test");
        deleteRecursively(root);
        Files.createDirectories(root);

        // ---- fixtures -------------------------------------------------------
        Path exampleJar = buildJar(root.resolve("example-1.0.0.jar"), "example.mixins.json", Map.of(
                "example.mixins.json", """
                        {
                          "required": true,
                          "minVersion": "0.8",
                          "package": "org.example.mixin",
                          "compatibilityLevel": "JAVA_17",
                          "refmap": "example.refmap.json",
                          "mixins": ["CommonMixin", "SharedTargetMixin"],
                          "client": ["ClientOnlyMixin"],
                          "server": [
                            {"target": "net.minecraft.world.level.chunk.LevelChunk", "mixins": ["ChunkMixin"]}
                          ]
                        }
                        """,
                // Present in the jar but absent from the manifest: the scan path must find it.
                "example.extra.mixins.json", """
                        {
                          "package": "org.example.extra",
                          "mixins": ["ExtraMixin"]
                        }
                        """));
        // Rival patches the same vanilla class as example. Note that the conflict is only
        // expressible because both use the explicit nested form: two mixins that target themselves
        // are different classes and cannot be correlated from config alone.
        // Rival patches the same vanilla class as example. That conflict is only expressible
        // because both use the explicit nested form: two mixins targeting themselves are different
        // classes and cannot be correlated from config alone.
        // Rival patches the same vanilla class as example. That conflict is only expressible
        // because both use the explicit nested form: two mixins targeting themselves are different
        // classes and cannot be correlated from config alone.
        Path rivalJar = buildJar(root.resolve("rival-2.0.0.jar"), null, Map.of(
                "rival.mixins.json", """
                        {
                          "package": "org.rival.mixin",
                          "mixins": ["RivalMixin"],
                          "server": [
                            {"target": "net.minecraft.world.level.chunk.LevelChunk", "mixins": ["RivalChunkMixin"]}
                          ]
                        }
                        """));
        Path broken = root.resolve("broken-1.0.0.jar");
        Files.writeString(broken, "this is not a zip", StandardCharsets.UTF_8);

        // ---- scanning --------------------------------------------------------
        section("container scanning");
        ModContainerScanner.ContainerInfo exampleInfo = ModContainerScanner.inspect(exampleJar);
        ModContainerScanner.ContainerInfo rivalInfo = ModContainerScanner.inspect(rivalJar);
        ModContainerScanner.ContainerInfo brokenInfo = ModContainerScanner.inspect(broken);

        check("jar opened", exampleInfo != null && !exampleInfo.directory());
        check("manifest mixin config seen", exampleInfo.mixinConfigPaths().contains("example.mixins.json"));
        check("scanned mixin config seen", exampleInfo.mixinConfigPaths().contains("example.extra.mixins.json"));
        check("both configs found", exampleInfo.mixinConfigPaths().size() == 2);
        check("file name only, no directory", !exampleInfo.fileName().contains("/")
                && !exampleInfo.fileName().contains("\\"));
        check("size recorded", exampleInfo.sizeBytes() > 0);
        check("entries counted", exampleInfo.entryCount() > 0);
        check("unreadable jar degrades, not throws",
                brokenInfo != null && brokenInfo.manifestAttributes().containsKey("uee.readError"));

        // ---- mixin parsing ---------------------------------------------------
        section("mixin config parsing");
        List<MixinConfig> exampleMixins = ModContainerScanner.readMixinConfigs(exampleInfo, "example");
        check("both configs parsed", exampleMixins.size() == 2);
        MixinConfig main = exampleMixins.stream()
                .filter(c -> c.source().equals("example.mixins.json")).findFirst().orElseThrow();
        check("package read", "org.example.mixin".equals(main.packageName()));
        check("required read", main.required());
        check("compatibilityLevel read", "JAVA_17".equals(main.compatibilityLevel()));
        check("refmap read", "example.refmap.json".equals(main.refmap()));
        check("four entries across three environments", main.entries().size() == 4);
        check("client environment tagged", main.entries().stream()
                .anyMatch(e -> e.environment().equals("client")));
        check("common environment tagged", main.entries().stream()
                .anyMatch(e -> e.environment().equals("common")));
        check("nested target form read", main.entries().stream()
                .anyMatch(e -> "net.minecraft.world.level.chunk.LevelChunk".equals(e.explicitTarget())));
        check("target classes resolved", Set.of(
                "org.example.mixin.CommonMixin",
                "org.example.mixin.SharedTargetMixin",
                "org.example.mixin.ClientOnlyMixin",
                "net.minecraft.world.level.chunk.LevelChunk")
                .equals(Set.of(main.targetClasses())));

        List<MixinConfig> rivalMixins = ModContainerScanner.readMixinConfigs(rivalInfo, "rival");
        check("rival config parsed", rivalMixins.size() == 1);
        check("rival target includes the shared vanilla class", Set.of(
                "org.rival.mixin.RivalMixin", "net.minecraft.world.level.chunk.LevelChunk")
                .equals(Set.of(rivalMixins.get(0).targetClasses())));

        // ---- analysis --------------------------------------------------------
        section("analysis");
        List<ModElement> mods = new ArrayList<>();
        mods.add(new ModElement("example", "Example Mod", "1.0.0", "example", "fabric", "1.21.1",
                new String[] {"Someone"}, "TPL-2.3", "demo",
                new Dependency[] {
                        new Dependency("fabricloader", ">=0.15.0", Dependency.Kind.REQUIRED),
                        new Dependency("absentlib", null, Dependency.Kind.REQUIRED),
                        new Dependency("rival", null, Dependency.Kind.INCOMPATIBLE),
                        new Dependency("jei", null, Dependency.Kind.OPTIONAL)},
                new String[] {"example_api"}, "example-1.0.0.jar", exampleJar.toString()));
        mods.add(new ModElement("rival", "Rival Mod", "2.0.0", "example", "fabric", "1.21.1",
                new String[0], null, null,
                new Dependency[] {new Dependency("example", null, Dependency.Kind.REQUIRED)},
                new String[0], "rival-2.0.0.jar", rivalJar.toString()));
        mods.add(new ModElement("loner", "Loner", "1.0.0", "loner", "fabric", "1.21.1",
                new String[0], null, null, new Dependency[0], new String[0], null));

        Map<String, ModContainerScanner.ContainerInfo> containers = new LinkedHashMap<>();
        containers.put("example", exampleInfo);
        containers.put("rival", rivalInfo);
        Set<String> observed = new LinkedHashSet<>(List.of("example", "minecraft", "orphanpack"));

        Set<String> findings = new TreeSet<>();
        Set<String> namespaces = new TreeSet<>();
        Set<String> mixinRecords = new TreeSet<>();
        Set<String> dependencies = new TreeSet<>();
        Set<String> modRecords = new TreeSet<>();

        ModAnalyzer.analyze(new ModAnalyzer.Context(mods, containers,
                concat(exampleMixins, rivalMixins), observed), new CollectingSink(
                        findings, namespaces, mixinRecords, dependencies, modRecords));

        // The seven findings the fixture is built to provoke.
        expectFinding(findings, "missing_dependency", "absentlib");
        expectFinding(findings, "declared_incompatibility", "rival");
        expectFinding(findings, "contradictory_declaration", "rival");
        expectFinding(findings, "namespace_conflict", "example");
        expectFinding(findings, "orphan_namespace", "orphanpack");
        expectFinding(findings, "shared_mixin_target", "LevelChunk");
        check("no false unreadable_container", findings.stream()
                .noneMatch(f -> f.startsWith("unreadable_container")));

        check("namespace records emitted", startswith(namespaces, "ns:example"));
        check("conflicting namespace flagged", namespaces.stream()
                .anyMatch(n -> n.startsWith("ns:example") && n.contains("conflict=true")));
        check("dependency record emitted", startswith(dependencies, "dep:example"));
        check("reverse edge recorded", dependencies.stream()
                .anyMatch(d -> d.startsWith("dep:example") && d.contains("requiredBy=rival")));
        check("mixin records emitted", startswith(mixinRecords, "mixin:example.mixins.json"));
        check("mixin target list recorded", mixinRecords.stream()
                .anyMatch(m -> m.startsWith("mixin:example.mixins.json") && m.contains("targetClasses=4")));

        // ---- path privacy ----------------------------------------------------
        section("path privacy");
        check("file name emitted by default", startswith(modRecords, "mod:example")
                && modRecords.stream().anyMatch(m -> m.contains("container=example-1.0.0.jar")));
        check("absolute path absent by default",
                modRecords.stream().noneMatch(d -> d.contains(root.toString())));

        Set<String> withPaths = new TreeSet<>();
        ModAnalyzer.analyze(new ModAnalyzer.Context(mods, containers,
                concat(exampleMixins, rivalMixins), observed, true), new CollectingSink(
                        new TreeSet<>(), new TreeSet<>(), new TreeSet<>(), new TreeSet<>(), withPaths));
        check("path present only when opted in",
                withPaths.stream().anyMatch(d -> d.contains(root.toString())));

        section(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- sink

    /** Collects the fields this test asserts on into flat, greppable strings. */
    private record CollectingSink(Set<String> findings, Set<String> namespaces,
            Set<String> mixins, Set<String> dependencies, Set<String> mods) implements ElementSink {

        @Override
        public void mod(ModElement e) {
        }

        @Override
        public void item(org.uee.model.ItemElement e) {
        }

        @Override
        public void entity(org.uee.model.EntityElement e) {
        }

        @Override
        public void block(org.uee.model.BlockElement e) {
        }

        @Override
        public void recipe(org.uee.model.RecipeElement e) {
        }

        @Override
        public void generic(ElementKind kind, String namespace, String key, String nameZh,
                String nameEn, String[] listValues, String[] extra) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(key);
            for (int i = 0; i + 1 < extra.length; i += 2) {
                sb.append(' ').append(extra[i]).append('=').append(extra[i + 1]);
            }
            String line = sb.toString();
            switch (kind) {
                case CONFLICT -> findings.add(kindOf(extra) + "|" + key + "|" + line);
                case NAMESPACE -> namespaces.add(line);
                case MIXIN -> mixins.add(line);
                case DEPENDENCY -> dependencies.add(line);
                case MOD -> mods.add(line);
                default -> {
                }
            }
        }

        @Override
        public void debug(DebugSection section) {
        }

        @Override
        public void failure(ElementKind kind, String registryName, Throwable error) {
        }

    /**
     * Reads the finding's category from the record's own field.
     *
     * <p>The field is {@code findingKind}, not {@code kind}: the record already carries a structural
     * {@code kind} naming its category, and two fields with the same name would produce a duplicate
     * JSON key that a consumer would resolve arbitrarily.
     */
    private static String kindOf(String[] extra) {
        for (int i = 0; i + 1 < extra.length; i += 2) {
            if (extra[i].equals("findingKind")) {
                return extra[i + 1];
            }
        }
        return "?";
    }
    }

    // ---------------------------------------------------------------- helpers

    /** Builds a jar with the given manifest mixin config and a set of resources. */
    private static Path buildJar(Path target, String manifestMixinConfigs,
            Map<String, String> resources) throws IOException {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Implementation-Title", "uee-test-fixture");
        if (manifestMixinConfigs != null) {
            main.putValue(ModContainerScanner.MANIFEST_MIXIN_CONFIGS, manifestMixinConfigs);
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            for (Map.Entry<String, String> e : resources.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return target;
    }

    private static List<MixinConfig> concat(List<MixinConfig> a, List<MixinConfig> b) {
        List<MixinConfig> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    private static boolean startswith(Set<String> set, String prefix) {
        return set.stream().anyMatch(s -> s.startsWith(prefix));
    }



    private static void expectFinding(Set<String> findings, String kind, String needle) {
        boolean hit = findings.stream().anyMatch(f -> f.startsWith(kind + "|") && f.contains(needle));
        check("finding " + kind + " mentions " + needle, hit);
        if (!hit) {
            findings.stream().filter(f -> f.startsWith(kind + "|")).forEach(f -> System.out.println("      got: " + f));
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
