package org.uee.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.uee.datapack.StrategyDefinition;
import org.uee.model.ElementKind;

/**
 * Runs datapack-defined analysis strategies.
 *
 * <h2>Registered once per stage</h2>
 *
 * <p>The same class is registered twice, once for each {@link Analysis.Stage}, and each instance runs
 * only the rules belonging to its own stage. That is not a workaround — it is the stage seam doing its
 * job. A rule asking "is mod X loaded" is meaningful before collection; a rule asking "did namespace Y
 * produce anything" is meaningless until the registry has been walked. Letting the engine schedule
 * them separately is exactly what the seam exists for, and it means a data-free run still enforces the
 * rules that can be enforced.
 *
 * <h2>Findings, not exceptions</h2>
 *
 * <p>A rule that reserves an id, references a category token that no longer exists, or names a mod
 * that is absent is reported as a finding rather than thrown. A datapack author iterating on a rule
 * should see what their rule said, not a stack trace.
 */
public final class DeclarativeAnalysis implements Analysis {

    private final Stage stage;
    private final List<StrategyDefinition> strategies;

    /**
     * @param stage which half of the rules this instance runs
     * @param strategies the strategies to run; an empty list makes this a no-op, which is the case
     *     when no datapack defines any
     */
    public DeclarativeAnalysis(Stage stage, List<StrategyDefinition> strategies) {
        this.stage = stage == null ? Stage.PRE_COLLECTION : stage;
        this.strategies = strategies == null ? List.of() : List.copyOf(strategies);
    }

    /** A pre-collection instance over the given strategies. */
    public static DeclarativeAnalysis pre(List<StrategyDefinition> strategies) {
        return new DeclarativeAnalysis(Stage.PRE_COLLECTION, strategies);
    }

    /** A post-collection instance over the given strategies. */
    public static DeclarativeAnalysis post(List<StrategyDefinition> strategies) {
        return new DeclarativeAnalysis(Stage.POST_COLLECTION, strategies);
    }

    @Override
    public String id() {
        return stage == Stage.PRE_COLLECTION ? "datapack-rules" : "datapack-rules-post";
    }

    @Override
    public String description() {
        return "rules declared by datapacks over the facts available "
                + (stage == Stage.PRE_COLLECTION ? "before" : "after") + " collection";
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public void run(AnalysisContext ctx, AnalysisOutput out) {
        for (StrategyDefinition strategy : strategies) {
            for (StrategyDefinition.Rule rule : strategy.rulesFor(stage)) {
                try {
                    apply(strategy, rule, ctx, out);
                } catch (RuntimeException e) {
                    // One bad rule must not stop the others; the author sees which rule misbehaved.
                    out.finding(new Finding("rule_failed", Finding.Severity.WARN, strategy.id(),
                            "rule in '" + strategy.id() + "' failed: " + e,
                            new String[] {"strategy", strategy.id(), "rule", rule.kind().name()}));
                }
            }
        }
    }

    private void apply(StrategyDefinition strategy, StrategyDefinition.Rule rule,
            AnalysisContext ctx, AnalysisOutput out) {
        switch (rule.kind()) {
            case REQUIRE_MOD -> {
                if (!ctx.hasMod(rule.mod())) {
                    emit(strategy, rule, rule.mod(), out);
                }
            }
            case FORBID_MOD -> {
                if (ctx.hasMod(rule.mod())) {
                    emit(strategy, rule, rule.mod(), out);
                }
            }
            case REQUIRE_TOGETHER -> {
                // Only fires when the group is partially present: if none of them are loaded, the
                // pack does not use this set at all and a complaint would be noise.
                List<String> present = new ArrayList<>(rule.mods().size());
                for (String id : rule.mods()) {
                    if (ctx.hasMod(id)) {
                        present.add(id);
                    }
                }
                if (!present.isEmpty() && present.size() < rule.mods().size()) {
                    emit(strategy, rule, String.join(",", present), out);
                }
            }
            case MUTUALLY_EXCLUSIVE -> {
                List<String> present = new ArrayList<>(rule.mods().size());
                for (String id : rule.mods()) {
                    if (ctx.hasMod(id)) {
                        present.add(id);
                    }
                }
                if (present.size() > 1) {
                    emit(strategy, rule, String.join(",", present), out);
                }
            }
            case FORBID_MIXIN_TARGET -> {
                Set<String> owners = mixinOwners(ctx, rule.target());
                if (!owners.isEmpty()) {
                    emit(strategy, rule, String.join(",", owners), out);
                }
            }
            case REQUIRE_NAMESPACE -> {
                if (!ctx.observedNamespaces().contains(rule.namespace())) {
                    emit(strategy, rule, rule.namespace(), out);
                }
            }
            case MIN_COUNT -> {
                int have = ctx.counted(kindOf(rule.target()));
                if (have < rule.value()) {
                    emit(strategy, rule, rule.target() + "=" + have, out);
                }
            }
            case MAX_COUNT -> {
                int have = ctx.counted(kindOf(rule.target()));
                if (have > rule.value()) {
                    emit(strategy, rule, rule.target() + "=" + have, out);
                }
            }
        }
    }

    /** Which mods patch a class, by explicit target only — the same limit the built-in check has. */
    private static Set<String> mixinOwners(AnalysisContext ctx, String target) {
        Set<String> owners = new TreeSet<>();
        for (var cfg : ctx.mixins()) {
            for (var entry : cfg.entries()) {
                if (target.equals(entry.explicitTarget())) {
                    owners.add(cfg.modId() != null ? cfg.modId() : cfg.source());
                }
            }
        }
        return owners;
    }

    /** Resolves a category token used in a count rule. */
    private static ElementKind kindOf(String token) {
        Set<ElementKind> kinds = org.uee.config.Tokens.kinds(token);
        if (kinds == null || kinds.size() != 1) {
            throw new IllegalArgumentException("unknown category '" + token + "' in a count rule");
        }
        return kinds.iterator().next();
    }

    private static void emit(StrategyDefinition strategy, StrategyDefinition.Rule rule,
            String subject, AnalysisOutput out) {
        out.finding(new Finding(rule.kind().findingKind(), rule.severity(), subject,
                rule.describe(),
                new String[] {"strategy", strategy.id(),
                        "datapack", strategy.namespace() == null ? "" : strategy.namespace(),
                        "rule", rule.kind().name()}));
    }
}
