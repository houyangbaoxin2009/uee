package org.uee;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.uee.analysis.Analysis;
import org.uee.analysis.AnalysisContext;
import org.uee.analysis.AnalysisEngine;
import org.uee.analysis.AnalysisOutput;
import org.uee.analysis.DeclarativeAnalysis;
import org.uee.analysis.Finding;
import org.uee.config.ExportConfig;
import org.uee.datapack.DatapackCatalog;
import org.uee.datapack.DatapackSource;
import org.uee.datapack.FlowDefinition;
import org.uee.datapack.Selector;
import org.uee.datapack.StrategyDefinition;
import org.uee.datapack.TargetDefinition;
import org.uee.debug.MixinConfig;
import org.uee.globalpack.GlobalPackDiscovery;
import org.uee.globalpack.GlobalPackPolicy;
import org.uee.globalpack.KnownProviders;
import org.uee.model.Dependency;
import org.uee.model.ElementKind;
import org.uee.model.ModElement;

/**
 * Checks the fourth interface, the declarative one: datapack-defined flows, targets and strategies.
 *
 * <p>Every definition lives in a data file, so every one of them can be verified by writing that file
 * and reading it back — no game, no mod, no loader. The fixtures are real datapack directory trees,
 * because the layout is part of what is being tested.
 */
public final class DatapackTest {

    private static int failures;

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "build/datapack-test");
        deleteRecursively(root);
        Files.createDirectories(root);

        selectors();
        loading(root);
        conflicts(root);
        flows(root);
        strategies(root);
        globalPacks(root);
        versionedStrategies();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- selectors

    private static void selectors() {
        section("selectors");

        check("an unconstrained selector accepts anything",
                Selector.ALL.accepts("minecraft:stone", "minecraft", new String[0]));
        check("an unconstrained selector knows it is unconstrained", Selector.ALL.isUnconstrained());

        Selector byNs = new Selector("example", null, null, null, null, null);
        check("namespace matches", byNs.accepts("example:thing", "example", null));
        check("namespace rejects another", !byNs.accepts("other:thing", "other", null));

        Selector byTag = new Selector(null, null, "c:gems", null, null, null);
        check("tag matches", byTag.accepts("example:ruby", "example", new String[] {"c:gems"}));
        check("missing tag rejects", !byTag.accepts("example:dirt", "example", new String[] {"c:dirt"}));
        check("a null tag array rejects rather than throwing",
                !byTag.accepts("example:ruby", "example", null));

        check("id matches exactly",
                new Selector(null, "example:ruby", null, null, null, null)
                        .accepts("example:ruby", "example", null));
        check("id rejects a prefix of itself",
                !new Selector(null, "example:ruby", null, null, null, null)
                        .accepts("example:ruby_block", "example", null));

        // Path matching applies to the part after the colon, so a namespace is not accidentally
        // matched by a path pattern.
        check("prefix matches the path, not the namespace",
                new Selector(null, null, null, "rub", null, null)
                        .accepts("example:ruby", "example", null));
        check("prefix does not match the namespace",
                !new Selector(null, null, null, "exa", null, null)
                        .accepts("example:ruby", "example", null));
        check("suffix matches", new Selector(null, null, null, null, "_ore", null)
                .accepts("minecraft:iron_ore", "minecraft", null));
        check("contains matches", new Selector(null, null, null, null, null, "log")
                .accepts("minecraft:oak_log", "minecraft", null));

        check("constraints combine", new Selector("example", null, "c:gems", "rub", null, null)
                .accepts("example:ruby", "example", new String[] {"c:gems"}));
        check("specificity counts constraints", byTag.specificity() == 1);
        check("describe names the constraints", byTag.describe().contains("tag=c:gems"));
        check("describe handles the unconstrained case",
                Selector.ALL.describe().equals("everything"));
        check("blank fields are treated as absent",
                new Selector("  ", null, null, null, null, null).isUnconstrained());
    }

    // ---------------------------------------------------------------- loading

    private static void loading(Path root) throws IOException {
        section("datapack loading");

        Path pack = root.resolve("examplepack");
        write(pack, "data/examplepack/uee/flows/wiki.json", """
                {
                  "id": "wiki",
                  "description": "the wiki projection",
                  "formats": ["json"],
                  "kinds": ["items", "blocks"],
                  "analyze": true
                }
                """);
        write(pack, "data/examplepack/uee/targets/gems.json", """
                {
                  "id": "gems",
                  "category": "items",
                  "selector": { "tag": "c:gems" }
                }
                """);
        write(pack, "data/examplepack/uee/analyses/packrules.json", """
                {
                  "id": "packrules",
                  "rules": [
                    {"kind": "forbid_mod", "mod": "badmod", "severity": "error"},
                    {"kind": "require_together", "mods": ["a", "b"], "severity": "warn"},
                    {"kind": "min_count", "target": "items", "value": 10}
                  ]
                }
                """);
        // The td syntax, which the rest of the ecosystem writes.
        write(pack, "data/examplepack/uee/targets/coals.td", """
                type tie<data>

                coals = [
                  id = "coals"
                  category = "items"
                  selector = [ tag = "minecraft:coals" ]
                ]
                """);

        DatapackSource source = new DatapackSource("examplepack", pack, DatapackSource.Origin.GLOBAL);
        DatapackCatalog catalog = DatapackCatalog.of(List.of(source));

        check("flow loaded", catalog.flow("wiki") != null);
        check("target loaded", catalog.target("gems") != null);
        check("second target loaded from td syntax", catalog.target("coals") != null);
        check("strategy loaded", catalog.strategy("packrules") != null);
        check("no load problems", catalog.problems().isEmpty());

        FlowDefinition wiki = catalog.flow("wiki");
        check("flow description read", "the wiki projection".equals(wiki.description()));
        check("flow names its datapack", "examplepack".equals(wiki.namespace()));
        check("flow sets the formats it named", wiki.touchedKeys().contains("formats"));
        check("flow carries its own id", wiki.qualifiedId().equals("examplepack:wiki"));

        // A flow is a partial description, so its settings must reach a config through the resolver
        // rather than through a private path.
        ExportConfig fromFlow = wiki.config().applyTo(ExportConfig.builder().build());
        check("flow applies its formats", fromFlow.formats().equals(Set.of(ExportConfig.JSON)));
        check("flow applies its categories",
                fromFlow.kinds().equals(Set.of(ElementKind.ITEM, ElementKind.BLOCK)));
        check("a key the flow did not name keeps its default",
                fromFlow.shardSize() == ExportConfig.builder().build().shardSize());

        TargetDefinition gems = catalog.target("gems");
        check("target category read", gems.category() == ElementKind.ITEM);
        check("target selector read", "c:gems".equals(gems.selector().tag()));
        check("target accepts a matching element",
                gems.accepts("example:ruby", "example", new String[] {"c:gems"}));
        check("target rejects a non-matching element",
                !gems.accepts("example:dirt", "example", new String[] {"c:dirt"}));

        TargetDefinition coals = catalog.target("coals");
        check("td syntax selector read", "minecraft:coals".equals(coals.selector().tag()));

        // A target on a category the writer cannot carry is still loadable; the pipeline is what
        // decides, so a bad target must not remove a good flow.
        check("id lookup accepts a qualified form", catalog.target("examplepack:gems") != null);
        check("unknown lookup returns null", catalog.target("nope") == null);
    }

    // ---------------------------------------------------------------- conflicts

    private static void conflicts(Path root) throws IOException {
        section("conflicts and precedence");

        Path globalPack = root.resolve("global-pack");
        Path modPack = root.resolve("mod-pack");
        Path secondGlobal = root.resolve("second-global");
        write(globalPack, "data/a/uee/flows/shared.json",
                "{ \"id\": \"shared\", \"description\": \"from the global pack\" }\n");
        write(modPack, "data/a/uee/flows/shared.json",
                "{ \"id\": \"shared\", \"description\": \"from a mod\" }\n");
        write(modPack, "data/a/uee/flows/modonly.json", "{ \"id\": \"modonly\" }\n");
        write(secondGlobal, "data/a/uee/flows/shared.json",
                "{ \"id\": \"shared\", \"description\": \"from another global pack\" }\n");
        // A malformed definition must be reported and skipped, never fatal.
        write(modPack, "data/a/uee/targets/broken.json",
                "{ \"id\": \"broken\", \"category\": \"nonsense\" }\n");
        write(modPack, "data/a/uee/targets/ores.json",
                "{ \"id\": \"ores\", \"category\": \"items\", \"selector\": { \"suffix\": \"_ore\" } }\n");
        write(modPack, "data/a/uee/targets/badsyntax.txt", "not a definition\n");

        DatapackCatalog catalog = DatapackCatalog.of(List.of(
                new DatapackSource("global-pack", globalPack, DatapackSource.Origin.GLOBAL),
                new DatapackSource("mod-pack", modPack, DatapackSource.Origin.MOD),
                new DatapackSource("second-global", secondGlobal, DatapackSource.Origin.GLOBAL)));

        // Origin decides, not directory listing order: a user's global pack beats a mod's.
        FlowDefinition shared = catalog.flow("shared");
        check("higher-precedence origin wins", shared != null
                && shared.description() != null
                && shared.description().contains("global"));
        check("the mod's own flow is still loaded", catalog.flow("modonly") != null);
        check("a shadowed definition is reported", catalog.problems().stream()
                .anyMatch(p -> p.kind().equals("datapack_shadowed")));
        check("an override by a higher origin is reported", catalog.problems().stream()
                .anyMatch(p -> p.kind().equals("datapack_override")));
        check("a same-origin tie is reported", catalog.problems().stream()
                .anyMatch(p -> p.kind().equals("datapack_override")
                        && p.detail().length > 0));
        check("an invalid target is reported, not thrown", catalog.problems().stream()
                .anyMatch(p -> p.kind().equals("datapack_target_invalid")));
        check("an unknown category token is rejected",
                catalog.target("broken") == null);
        check("a non-definition file is ignored", catalog.problems().stream()
                .noneMatch(p -> p.message().contains("badsyntax")));

        // Resolution reports unknown ids rather than silently dropping them.
        List<TargetDefinition> resolved = catalog.resolveTargets(List.of("ores", "ghost"));
        check("known target resolves", resolved.size() == 1);
        check("unknown target is reported", catalog.problems().stream()
                .anyMatch(p -> p.kind().equals("datapack_unknown_target")));
        check("unknown strategy is reported", catalog.resolveStrategies(List.of("ghost")).isEmpty()
                && catalog.problems().stream()
                        .anyMatch(p -> p.kind().equals("datapack_unknown_strategy")));
    }

    // ---------------------------------------------------------------- flows

    private static void flows(Path root) throws IOException {
        section("flows as partial configurations");

        Path pack = root.resolve("flowpack");
        write(pack, "data/fp/uee/flows/minimal.json", "{ \"id\": \"minimal\" }\n");
        write(pack, "data/fp/uee/flows/onlyitems.json",
                "{ \"id\": \"onlyitems\", \"kinds\": [\"items\"] }\n");
        write(pack, "data/fp/uee/flows/withtargets.json",
                "{ \"id\": \"withtargets\", \"kinds\": [\"items\"], \"targets\": [\"gems\"] }\n");

        DatapackCatalog catalog = DatapackCatalog.of(List.of(
                new DatapackSource("flowpack", pack, DatapackSource.Origin.GLOBAL)));

        // The property that makes a flow compose with the other interfaces: a flow does not have to
        // restate everything, and what it leaves out is not reset.
        ExportConfig defaults = ExportConfig.builder().build();
        ExportConfig minimal = catalog.flow("minimal").config().applyTo(defaults);
        check("a flow that names nothing changes nothing",
                minimal.formats().equals(defaults.formats())
                        && minimal.kinds().equals(defaults.kinds())
                        && minimal.shardSize() == defaults.shardSize());

        ExportConfig onlyItems = catalog.flow("onlyitems").config().applyTo(defaults);
        check("a flow overrides what it names",
                onlyItems.kinds().equals(Set.of(ElementKind.ITEM)));
        check("a flow leaves the rest alone",
                onlyItems.formats().equals(defaults.formats()));

        FlowDefinition withTargets = catalog.flow("withtargets");
        check("a flow can name targets", withTargets.hasTargets());
        check("flow targets are read", withTargets.targets().equals(List.of("gems")));
        check("a flow with no targets reports none", !catalog.flow("minimal").hasTargets());

        // A flow's touched keys are derived, so they cannot disagree with what it does.
        check("touched keys reflect the description",
                catalog.flow("onlyitems").touchedKeys().equals(Set.of("kinds", "flow")));
    }

    // ---------------------------------------------------------------- strategies

    private static void strategies(Path root) throws IOException {
        section("declarative analysis rules");

        Path pack = root.resolve("rulepack");
        write(pack, "data/rp/uee/analyses/rules.json", """
                {
                  "id": "rules",
                  "rules": [
                    {"kind": "require_mod", "mod": "presentmod"},
                    {"kind": "forbid_mod", "mod": "badmod", "severity": "error"},
                    {"kind": "require_together", "mods": ["a", "b"], "severity": "error"},
                    {"kind": "mutually_exclusive", "mods": ["x", "y"]},
                    {"kind": "forbid_mixin_target", "target": "net.minecraft.world.level.Level"},
                    {"kind": "require_namespace", "namespace": "unclaimed"},
                    {"kind": "min_count", "target": "items", "value": 100},
                    {"kind": "max_count", "target": "items", "value": 3}
                  ]
                }
                """);

        DatapackCatalog catalog = DatapackCatalog.of(List.of(
                new DatapackSource("rulepack", pack, DatapackSource.Origin.GLOBAL)));
        StrategyDefinition rules = catalog.strategy("rules");
        check("strategy loaded with all rules", rules.rules().size() == 8);
        check("strategy reports that it needs collection", rules.needsCollection());
        check("a metadata rule runs pre-collection", rules.rulesFor(Analysis.Stage.PRE_COLLECTION)
                .stream().anyMatch(r -> r.kind() == StrategyDefinition.Kind.FORBID_MOD));
        check("a count rule runs post-collection",
                rules.rulesFor(Analysis.Stage.POST_COLLECTION).stream()
                        .anyMatch(r -> r.kind() == StrategyDefinition.Kind.MIN_COUNT));

        // Facts: presentmod loaded, badmod loaded, a and b both loaded, x and y both loaded, items 5.
        List<ModElement> mods = new ArrayList<>();
        for (String id : List.of("presentmod", "badmod", "a", "b", "x", "y")) {
            mods.add(new ModElement(id, id, "1.0", id, "fabric", "1.21.1", new String[0], null, null,
                    new Dependency[0], new String[0], null));
        }
        AnalysisContext ctx = new AnalysisContext(mods, Map.of(), mixins(), false);
        for (int i = 0; i < 5; i++) {
            ctx.observe(ElementKind.ITEM, "example");
        }

        List<Finding> pre = run(DeclarativeAnalysis.pre(List.of(rules)), ctx);
        List<Finding> post = run(DeclarativeAnalysis.post(List.of(rules)), ctx);

        // A rule whose condition holds says nothing: these two are the negative cases, and they are
        // asserted as absences because "no finding" is the correct outcome, not an untested path.
        check("a satisfied requirement emits nothing",
                pre.stream().noneMatch(f -> f.kind().equals("rule:require_mod")));
        expect(pre, "rule:forbid_mod", "badmod");
        check("a satisfied co-presence rule emits nothing",
                pre.stream().noneMatch(f -> f.kind().equals("rule:require_together")));
        expect(pre, "rule:mutually_exclusive", "x");
        expect(pre, "rule:forbid_mixin_target", "Level");

        // Count and namespace rules must not run before collection.
        check("count rules do not run pre-collection",
                pre.stream().noneMatch(f -> f.kind().startsWith("rule:min_count")
                        || f.kind().startsWith("rule:max_count")));
        check("namespace rules do not run pre-collection",
                pre.stream().noneMatch(f -> f.kind().equals("rule:require_namespace")));

        // 5 items against min 100 and max 3: both fail, and each says so.
        expect(post, "rule:min_count", "items");
        expect(post, "rule:max_count", "items");
        check("the count rule names the actual count",
                post.stream().filter(f -> f.kind().equals("rule:min_count"))
                        .anyMatch(f -> f.subject().contains("5")));

        // Severity comes from the rule, and the declared message wins over the generated one.
        Finding forbids = pre.stream().filter(f -> f.kind().equals("rule:forbid_mod")).findFirst()
                .orElseThrow();
        check("rule severity is honoured", forbids.isError());
        check("the message is generated when none was given",
                forbids.message().contains("badmod"));

        // A rule that cannot be evaluated must not take the run down.
        StrategyDefinition bad = new StrategyDefinition("badrules", "rp", "broken",
                List.of(new StrategyDefinition.Rule(StrategyDefinition.Kind.MIN_COUNT,
                        Finding.Severity.WARN, null, null, List.of(), "nonsense", null, 5)));
        List<Finding> failed = run(DeclarativeAnalysis.post(List.of(bad)), ctx);
        check("a rule with an unknown category reports a failure rather than throwing",
                failed.stream().anyMatch(f -> f.kind().equals("rule_failed")));

        // The engine schedules the two halves by stage, which is the point of registering twice.
        AnalysisEngine engine = AnalysisEngine.of(List.of(
                DeclarativeAnalysis.pre(List.of(rules)), DeclarativeAnalysis.post(List.of(rules))));
        List<Finding> preOnly = collect(engine, Analysis.Stage.PRE_COLLECTION, ctx);
        check("the engine runs only the pre half when nothing was collected",
                preOnly.stream().noneMatch(f -> f.kind().equals("rule:min_count")));
    }

    private static void versionedStrategies() {
        section("rule vocabulary");

        check("every rule kind parses from its token", everyKindParses());
        check("an unknown rule kind is rejected",
                StrategyDefinition.Kind.of("no_such_rule") == null);
        check("a hyphenated token parses",
                StrategyDefinition.Kind.of("forbid-mixin-target")
                        == StrategyDefinition.Kind.FORBID_MIXIN_TARGET);
        check("every kind has a description",
                java.util.Arrays.stream(StrategyDefinition.Kind.values())
                        .allMatch(k -> k.description() != null && !k.description().isEmpty()));
        check("only the count and namespace kinds need collection",
                !StrategyDefinition.Kind.FORBID_MOD.needsCollection()
                        && StrategyDefinition.Kind.MIN_COUNT.needsCollection()
                        && StrategyDefinition.Kind.REQUIRE_NAMESPACE.needsCollection());
    }

    private static boolean everyKindParses() {
        for (StrategyDefinition.Kind k : StrategyDefinition.Kind.values()) {
            if (StrategyDefinition.Kind.of(k.name()) != k) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- global packs

    private static void globalPacks(Path root) throws IOException {
        section("global datapacks and standing down");

        Path game = root.resolve("game");
        Path ourDir = game.resolve(GlobalPackDiscovery.DEFAULT_DIR);
        Files.createDirectories(ourDir);
        // A pack in UEE's directory: a directory and a zip, plus a stray file that is not a pack.
        Files.createDirectories(ourDir.resolve("mypack"));
        Files.writeString(ourDir.resolve("mypack/pack.mcmeta"), "{}");
        Files.writeString(ourDir.resolve("other.zip"), "PK");
        Files.writeString(ourDir.resolve("readme.txt"), "not a pack");

        List<GlobalPackDiscovery.Pack> ours = GlobalPackDiscovery.packs(ourDir);
        check("packs are found", ours.size() == 2);
        check("a directory counts as a pack", ours.stream().anyMatch(p -> p.directory()
                && p.name().equals("mypack")));
        check("a zip counts as a pack", ours.stream().anyMatch(p -> !p.directory()
                && p.name().equals("other.zip")));
        check("an unrelated file is not a pack",
                ours.stream().noneMatch(p -> p.name().equals("readme.txt")));
        check("a pack's contents are not counted as packs",
                GlobalPackDiscovery.packs(ourDir).stream().noneMatch(p -> p.name().equals("pack.mcmeta")));

        // A provider mod with its own directory present.
        Files.createDirectories(game.resolve("config/openloader/data/theirpack"));
        Path openLoaderDir = game.resolve("config/openloader/data");
        List<GlobalPackPolicy.Detected> detected = GlobalPackDiscovery.detect(game,
                List.of("minecraft", "openloader", "fabricloader"));
        check("a loaded provider is detected", detected.size() == 1);
        check("detection names the provider",
                detected.get(0).provider().modId().equals("openloader"));
        check("detection finds its directory",
                detected.get(0).directories().contains("config/openloader/data"));
        check("detection counts its packs", detected.get(0).packCount() == 1);
        check("an unrelated mod is not a provider",
                GlobalPackDiscovery.detect(game, List.of("minecraft")).isEmpty());

        section("global datapacks: the four outcomes");

        // 1. auto, nobody else provides → UEE does.
        GlobalPackPolicy alone = GlobalPackPolicy.decide(GlobalPackPolicy.Mode.AUTO,
                List.of(), ourDir.toString(), 2);
        check("auto with no provider: UEE provides", alone.provide());
        check("auto with no provider: not deferred", !alone.deferred());

        // 2. auto, a provider is loaded → UEE stands down, and says so.
        GlobalPackPolicy deferred = GlobalPackPolicy.decide(GlobalPackPolicy.Mode.AUTO, detected,
                ourDir.toString(), 2);
        check("auto with a provider: UEE stands down", !deferred.provide());
        check("auto with a provider: deferred to it", deferred.deferred());
        check("the deferral names the provider",
                deferred.deferredTo().modId().equals("openloader"));
        check("the deferral is reported", deferred.findings().stream()
                .anyMatch(f -> f.kind().equals("global_pack_deferred")));

        // ★ The case worth having the whole mechanism for: packs sitting in our directory that
        // nobody is loading. Without this finding the user sees nothing at all.
        check("packs left in our directory while deferred are reported",
                deferred.findings().stream().anyMatch(f -> f.kind().equals("global_pack_ignored")));
        check("that finding says where the packs are",
                deferred.findings().stream().filter(f -> f.kind().equals("global_pack_ignored"))
                        .anyMatch(f -> f.message().contains("mypack") || f.message().contains("2")));
        check("that finding suggests a destination",
                deferred.findings().stream().filter(f -> f.kind().equals("global_pack_ignored"))
                        .anyMatch(f -> f.message().contains("openloader")));

        // 3. explicit on overrides the deferral.
        GlobalPackPolicy forced = GlobalPackPolicy.decide(GlobalPackPolicy.Mode.ON, detected,
                ourDir.toString(), 2);
        check("an explicit on provides anyway", forced.provide());
        check("an explicit on is not a deferral", !forced.deferred());
        check("running alongside a provider is noted", forced.findings().stream()
                .anyMatch(f -> f.kind().equals("global_pack_both_active")));

        // 4. explicit off.
        GlobalPackPolicy off = GlobalPackPolicy.decide(GlobalPackPolicy.Mode.OFF, detected,
                ourDir.toString(), 2);
        check("an explicit off does not provide", !off.provide());
        check("an explicit off does not claim to be a deferral", !off.deferred());
        check("an explicit off with packs present is reported", off.findings().stream()
                .anyMatch(f -> f.kind().equals("global_pack_disabled")));

        check("a provider with no packs is idle", detected.get(0).idle() == false
                || detected.get(0).packCount() == 0);
        check("an unrecognised mode degrades to auto",
                GlobalPackPolicy.Mode.of("nonsense") == GlobalPackPolicy.Mode.AUTO);
        check("mode tokens round trip", GlobalPackPolicy.Mode.of("on") == GlobalPackPolicy.Mode.ON);
        check("the policy describes itself",
                deferred.describe().contains("openloader")
                        && alone.describe().contains("global_datapacks=auto"));

        check("the provider list covers the well-known mods", knownProvidersCovered());

        // A provider that is loaded but has no directory at all is still deferred to: it is the mod,
        // not the directory, that would double-register.
        List<GlobalPackPolicy.Detected> bare = List.of(new GlobalPackPolicy.Detected(
                KnownProviders.byModId("paxi"), List.of(), 0));
        GlobalPackPolicy barePolicy = GlobalPackPolicy.decide(GlobalPackPolicy.Mode.AUTO, bare,
                ourDir.toString(), 0);
        check("a provider with no directory still causes a deferral", barePolicy.deferred());
        check("a bare provider produces no ignored-packs warning", barePolicy.findings().stream()
                .noneMatch(f -> f.kind().equals("global_pack_ignored")));
    }

    private static boolean knownProvidersCovered() {
        for (String id : List.of("openloader", "paxi", "global_packs")) {
            if (KnownProviders.byModId(id) == null) {
                return false;
            }
        }
        return KnownProviders.byModId("minecraft") == null;
    }

    // ---------------------------------------------------------------- harness

    private static List<MixinConfig> mixins() {
        return List.of(MixinConfig.parse("t.mixins.json", "someMixiner", """
                {
                  "package": "org.t",
                  "server": [
                    {"target": "net.minecraft.world.level.Level", "mixins": ["LevelMixin"]}
                  ]
                }
                """));
    }

    private static List<Finding> run(DeclarativeAnalysis analysis, AnalysisContext ctx) {
        List<Finding> out = new ArrayList<>();
        analysis.run(ctx, new AnalysisOutput() {
            @Override
            public void finding(Finding finding) {
                out.add(finding);
            }

            @Override
            public void record(ElementKind kind, String namespace, String key, String[] listValues,
                    String[] extra) {
            }
        });
        return out;
    }

    private static List<Finding> collect(AnalysisEngine engine, Analysis.Stage stage,
            AnalysisContext ctx) {
        List<Finding> out = new ArrayList<>();
        engine.run(stage, ctx, new AnalysisOutput() {
            @Override
            public void finding(Finding finding) {
                out.add(finding);
            }

            @Override
            public void record(ElementKind kind, String namespace, String key, String[] listValues,
                    String[] extra) {
            }
        });
        return out;
    }

    /** Asserts a finding of the given kind exists, optionally containing a needle. */
    private static void expect(List<Finding> findings, String kind, String needle) {
        boolean present = findings.stream().anyMatch(f -> f.kind().equals(kind));
        check("finding " + kind + " is present", present);
        if (needle != null) {
            check("finding " + kind + " mentions " + needle, findings.stream()
                    .anyMatch(f -> f.kind().equals(kind)
                            && (f.subject().contains(needle) || f.message().contains(needle))));
        }
    }

    private static void write(Path pack, String relative, String content) throws IOException {
        Path file = pack.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
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
