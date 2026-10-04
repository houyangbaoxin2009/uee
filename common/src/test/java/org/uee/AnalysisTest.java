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
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.uee.analysis.Analysis;
import org.uee.analysis.AnalysisContext;
import org.uee.analysis.AnalysisEngine;
import org.uee.analysis.Finding;
import org.uee.debug.MixinConfig;
import org.uee.debug.ModContainerScanner;
import org.uee.model.Dependency;
import org.uee.model.ElementKind;
import org.uee.model.ModElement;

/**
 * Exercises the analysis module against real jar fixtures.
 *
 * <p>The analyses are deliberately free of Minecraft classes, so they can be verified here with
 * genuine jar files rather than only inside a running game. The fixtures are built rather than
 * checked in: a generated jar with a known manifest and mixin config is a stronger statement than a
 * binary blob nobody can read.
 *
 * <p>The stage split gets its own section. It is not decoration — a check that reads registry
 * observations and runs before collection reports "nothing found", which reads as a clean bill of
 * health rather than as "not asked". Asserting the split is what keeps that from regressing.
 */
public final class AnalysisTest {

    private static int failures;

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "build/analysis-test");
        deleteRecursively(root);
        Files.createDirectories(root);

        Fixtures fx = buildFixtures(root);

        stages();
        mixinParsing(fx);
        dependencyFindings(fx);
        containerFindings(fx);
        namespaceFindings(fx);
        coverage(fx);

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- fixtures

    /** The jars and records every section works from. */
    private record Fixtures(Path exampleJar, Path rivalJar, Path broken,
            ModContainerScanner.ContainerInfo exampleInfo,
            ModContainerScanner.ContainerInfo rivalInfo,
            ModContainerScanner.ContainerInfo brokenInfo,
            List<MixinConfig> mixins,
            List<ModElement> mods) {

        /** A context over these fixtures, with optional registry observations. */
        AnalysisContext context(Set<String> observed, boolean includePaths) {
            Map<String, ModContainerScanner.ContainerInfo> containers = new LinkedHashMap<>();
            containers.put("example", exampleInfo);
            containers.put("rival", rivalInfo);
            return new AnalysisContext(mods, containers, mixins, includePaths);
        }

        /** A context that has "collected" the given namespaces for the item category. */
        AnalysisContext collected(Set<String> namespaces) {
            AnalysisContext ctx = context(namespaces, false);
            for (String ns : namespaces) {
                for (int i = 0; i < 3; i++) {
                    ctx.observe(ElementKind.ITEM, ns);
                }
            }
            return ctx;
        }
    }

    private static Fixtures buildFixtures(Path root) throws IOException {
        Path exampleJar = buildJar(root.resolve("example-1.0.0.jar"), "example.mixins.json", Map.of(
                "example.mixins.json", """
                        {
                          "required": true,
                          "minVersion": "0.8",
                          "package": "org.example.mixin",
                          "compatibilityLevel": "JAVA_17",
                          "refmap": "example.refmap.json",
                          "mixins": ["CommonMixin", "SelfTargetMixin"],
                          "client": ["ClientOnlyMixin"],
                          "server": [
                            {"target": "net.minecraft.world.level.chunk.LevelChunk", "mixins": ["ChunkMixin"]}
                          ]
                        }
                        """,
                // In the jar but absent from the manifest: the scan path must find it.
                "example.extra.mixins.json", """
                        {
                          "package": "org.example.extra",
                          "mixins": ["ExtraMixin"]
                        }
                        """));

        // Rival patches the same vanilla class as example. That conflict is only expressible because
        // both use the explicit nested form: two mixins targeting themselves are different classes and
        // cannot be correlated from config alone.
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

        ModContainerScanner.ContainerInfo exampleInfo = ModContainerScanner.inspect(exampleJar);
        ModContainerScanner.ContainerInfo rivalInfo = ModContainerScanner.inspect(rivalJar);
        ModContainerScanner.ContainerInfo brokenInfo = ModContainerScanner.inspect(broken);

        List<MixinConfig> mixins = new ArrayList<>();
        mixins.addAll(ModContainerScanner.readMixinConfigs(exampleInfo, "example"));
        mixins.addAll(ModContainerScanner.readMixinConfigs(rivalInfo, "rival"));

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

        return new Fixtures(exampleJar, rivalJar, broken, exampleInfo, rivalInfo, brokenInfo,
                mixins, mods);
    }

    // ---------------------------------------------------------------- stages

    private static void stages() {
        section("stage split");

        AnalysisEngine engine = AnalysisEngine.standard();
        check("engine registers checks", !engine.analyses().isEmpty());

        // Every standard check must declare a stage; a check that reads registry observations without
        // saying so is the failure mode the split exists to prevent.
        boolean allStaged = engine.analyses().stream().allMatch(a -> a.stage() != null);
        check("every check declares a stage", allStaged);

        int pre = engine.analysesUpTo(Analysis.Stage.PRE_COLLECTION).size();
        int post = engine.analysesUpTo(Analysis.Stage.POST_COLLECTION).size();
        check("some checks run before collection", pre > 0);
        check("some checks need collection", post > pre);
        check("the post-collection set includes everything earlier", post == engine.analyses().size());

        // The split has to be real: a context that never collected must yield no post-collection
        // findings, because "not asked" and "nothing found" look identical in a report.
        AnalysisContext empty = new AnalysisContext(List.of(), Map.of(), List.of(), false);
        List<Finding> preOnly = collect(engine, Analysis.Stage.PRE_COLLECTION, empty);
        List<Finding> everything = collect(engine, Analysis.Stage.POST_COLLECTION, empty);
        check("a data-free run produces no coverage report",
                everything.stream().noneMatch(f -> f.kind().equals("collection_summary")));
        check("a data-free run still runs the metadata checks",
                preOnly.size() == everything.size());
    }

    // ---------------------------------------------------------------- mixins

    private static void mixinParsing(Fixtures fx) {
        section("container scanning and mixin parsing");

        check("jar opened", fx.exampleInfo() != null && !fx.exampleInfo().directory());
        check("manifest mixin config seen",
                fx.exampleInfo().mixinConfigPaths().contains("example.mixins.json"));
        check("scanned mixin config seen",
                fx.exampleInfo().mixinConfigPaths().contains("example.extra.mixins.json"));
        check("file name carries no directory", !fx.exampleInfo().fileName().contains("/")
                && !fx.exampleInfo().fileName().contains("\\"));
        check("unreadable jar degrades rather than throwing",
                fx.brokenInfo() != null
                        && fx.brokenInfo().manifestAttributes().containsKey("uee.readError"));

        List<MixinConfig> exampleMixins = fx.mixins().stream()
                .filter(m -> "example".equals(m.modId())).toList();
        check("both example configs parsed", exampleMixins.size() == 2);

        MixinConfig main = exampleMixins.stream()
                .filter(c -> c.source().equals("example.mixins.json")).findFirst().orElseThrow();
        check("package read", "org.example.mixin".equals(main.packageName()));
        check("required read", main.required());
        check("compatibilityLevel read", "JAVA_17".equals(main.compatibilityLevel()));
        check("refmap read", "example.refmap.json".equals(main.refmap()));
        check("entries across three environments", main.entries().size() == 4);
        check("client environment tagged",
                main.entries().stream().anyMatch(e -> e.environment().equals("client")));
        check("nested target form read", main.entries().stream()
                .anyMatch(e -> "net.minecraft.world.level.chunk.LevelChunk".equals(e.explicitTarget())));
        check("target classes resolved", Set.of(
                "org.example.mixin.CommonMixin",
                "org.example.mixin.SelfTargetMixin",
                "org.example.mixin.ClientOnlyMixin",
                "net.minecraft.world.level.chunk.LevelChunk")
                .equals(Set.of(main.targetClasses())));
    }

    // ---------------------------------------------------------------- dependency findings

    private static void dependencyFindings(Fixtures fx) {
        section("dependency analysis");

        List<Finding> findings = collect(AnalysisEngine.standard(), Analysis.Stage.PRE_COLLECTION,
                fx.context(Set.of(), false));

        expectFinding(findings, "missing_dependency", "absentlib");
        expectFinding(findings, "declared_incompatibility", "rival");
        expectFinding(findings, "contradictory_declaration", "rival");
        check("no false duplicate id", findings.stream()
                .noneMatch(f -> f.kind().equals("duplicate_mod_id")));

        // A required dependency that is absent is an error; verify the severity is not merely set.
        Finding missing = findings.stream()
                .filter(f -> f.kind().equals("missing_dependency")).findFirst().orElseThrow();
        check("missing dependency is an error", missing.isError());

        // The graph records what each mod needs and who needs it.
        List<String[]> records = recordsof(AnalysisEngine.standard(), fx.context(Set.of(), false),
                ElementKind.DEPENDENCY);
        check("a dependency record per mod", records.size() == fx.mods().size());
        check("reverse edge recorded", records.stream().anyMatch(r ->
                r[0].startsWith("dep:example") && r[1].contains("requiredBy=rival")));
        check("optional and incompatible are distinguished", records.stream().anyMatch(r ->
                r[0].startsWith("dep:example") && r[1].contains("optionalOn=jei")
                        && r[1].contains("declaresIncompatible=rival")));
        check("missing count recorded", records.stream().anyMatch(r ->
                r[0].startsWith("dep:example") && r[1].contains("missingRequired=2")));
    }

    // ---------------------------------------------------------------- containers

    private static void containerFindings(Fixtures fx) {
        section("container analysis");

        Map<String, ModContainerScanner.ContainerInfo> containers = new LinkedHashMap<>();
        containers.put("example", fx.exampleInfo());
        containers.put("brokenmod", fx.brokenInfo());
        AnalysisContext ctx = new AnalysisContext(fx.mods(), containers, List.of(), false);
        List<Finding> findings = collect(AnalysisEngine.standard(),
                Analysis.Stage.PRE_COLLECTION, ctx);

        expectFinding(findings, "unreadable_container", "brokenmod");
        // A mod in the loader's list with no located container is reported as information, because
        // its mixins and manifest are simply absent from this run.
        expectFinding(findings, "no_container", "loner");

        // The path must not leak by default.
        List<String[]> records = recordsof(AnalysisEngine.standard(),
                fx.context(Set.of(), false), ElementKind.DEPENDENCY);
        check("file name recorded", records.stream().anyMatch(r ->
                r[0].startsWith("dep:example") && r[1].contains("container=example-1.0.0.jar")));
        check("absolute path withheld by default", records.stream()
                .noneMatch(r -> r[1].contains(fx.exampleJar().toString())));

        List<String[]> withPaths = recordsof(AnalysisEngine.standard(),
                fx.context(Set.of(), true), ElementKind.DEPENDENCY);
        check("absolute path present when opted in", withPaths.stream()
                .anyMatch(r -> r[1].contains(fx.exampleJar().toString())));
    }

    // ---------------------------------------------------------------- namespaces

    private static void namespaceFindings(Fixtures fx) {
        section("namespace analysis");

        // Claims collide: both mods claim "example". That is knowable before collection.
        List<Finding> pre = collect(AnalysisEngine.standard(), Analysis.Stage.PRE_COLLECTION,
                fx.context(Set.of("orphanpack"), false));
        expectFinding(pre, "namespace_conflict", "example");
        check("orphan detection does not run pre-collection",
                pre.stream().noneMatch(f -> f.kind().equals("orphan_namespace")));

        // Orphans need the registry walk, so they only appear once something was collected.
        List<Finding> post = collect(AnalysisEngine.standard(), Analysis.Stage.POST_COLLECTION,
                fx.collected(Set.of("example", "orphanpack")));
        expectFinding(post, "orphan_namespace", "orphanpack");
        check("a claimed namespace is not called an orphan",
                post.stream().filter(f -> f.kind().equals("orphan_namespace"))
                        .noneMatch(f -> f.subject().equals("example")));

        List<String[]> records = recordsof(AnalysisEngine.standard(),
                fx.context(Set.of(), false), ElementKind.NAMESPACE);
        check("a namespace record per claimed namespace", records.size() >= 3);
        check("conflict flagged in the record", records.stream().anyMatch(r ->
                r[0].startsWith("ns:example") && r[1].contains("conflict=true")));
        check("non-conflicting namespace not flagged", records.stream().anyMatch(r ->
                r[0].startsWith("ns:loner") && r[1].contains("conflict=false")));
    }

    // ---------------------------------------------------------------- coverage

    private static void coverage(Fixtures fx) {
        section("coverage analysis");

        AnalysisContext ctx = fx.context(Set.of(), false);
        for (int i = 0; i < 5; i++) {
            ctx.observe(ElementKind.ITEM, "example");
        }
        for (int i = 0; i < 2; i++) {
            ctx.observe(ElementKind.ITEM, "other");
        }
        ctx.filter(ElementKind.ITEM, "filteredout");

        List<Finding> findings = collect(AnalysisEngine.standard(),
                Analysis.Stage.POST_COLLECTION, ctx);
        check("collection facts observed", ctx.collected());
        check("counted total", ctx.totalCounted() == 7);
        check("filtered total", ctx.totalFiltered() == 1);
        check("namespace set", ctx.observedNamespaces().equals(Set.of("example", "other")));
        check("per-category count", ctx.counted(ElementKind.ITEM) == 7);
        check("per-namespace count",
                ctx.countByNamespace(ElementKind.ITEM).get("example") == 5);

        expectFinding(findings, "collection_summary", "7 record");
        expectFinding(findings, "filter_discarded_elements", "1 element");

        List<String[]> records = recordsof(AnalysisEngine.standard(), ctx, ElementKind.ITEM);
        check("coverage record emitted",
                records.stream().anyMatch(r -> r[0].equals("coverage:item")));
        check("coverage record carries the counts", records.stream().anyMatch(r ->
                r[0].equals("coverage:item") && r[1].contains("counted=7")
                        && r[1].contains("filtered=1") && r[1].contains("source=registry")));
        check("coverage record names the namespaces", records.stream().anyMatch(r ->
                r[0].equals("coverage:item") && r[1].contains("example=5")));
    }

    // ---------------------------------------------------------------- harness

    /** Collects findings from a run at the given stage. */
    private static List<Finding> collect(AnalysisEngine engine, Analysis.Stage stage,
            AnalysisContext ctx) {
        List<Finding> out = new ArrayList<>();
        engine.run(stage, ctx, new org.uee.analysis.AnalysisOutput() {
            @Override
            public void finding(Finding finding) {
                out.add(finding);
            }

            @Override
            public void record(ElementKind kind, String namespace, String key,
                    String[] listValues, String[] extra) {
            }
        });
        return out;
    }

    /**
     * Collects one category's records as two-element arrays: the key, then the flattened extra fields.
     *
     * <p>Flattening to text rather than keeping the arrays keeps the assertions readable and greppable,
     * which matters more here than preserving structure the test does not otherwise use.
     */
    private static List<String[]> recordsof(AnalysisEngine engine, AnalysisContext ctx,
            ElementKind kind) {
        List<String[]> out = new ArrayList<>();
        engine.run(Analysis.Stage.POST_COLLECTION, ctx, new org.uee.analysis.AnalysisOutput() {
            @Override
            public void finding(Finding finding) {
            }

            @Override
            public void record(ElementKind recordKind, String namespace, String key,
                    String[] listValues, String[] extra) {
                if (recordKind != kind) {
                    return;
                }
                StringBuilder sb = new StringBuilder(128);
                for (int i = 0; i + 1 < extra.length; i += 2) {
                    sb.append(extra[i]).append('=').append(extra[i + 1]).append(' ');
                }
                out.add(new String[] {key, sb.toString()});
            }
        });
        return out;
    }

    private static void expectFinding(List<Finding> findings, String kind, String needle) {
        boolean hit = findings.stream()
                .anyMatch(f -> f.kind().equals(kind) && f.message().contains(needle));
        check("finding " + kind + " mentions " + needle, hit);
        if (!hit) {
            findings.stream().filter(f -> f.kind().equals(kind))
                    .forEach(f -> System.out.println("      got: " + f.message()));
        }
    }

    /** Builds a jar with the given manifest mixin configs and resources. */
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
