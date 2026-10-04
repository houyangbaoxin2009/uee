package org.uee.analysis;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.uee.debug.MixinConfig;
import org.uee.model.ElementKind;
import org.uee.model.ModElement;

/**
 * Checks mixin configs, and works out which classes more than one mod patches.
 *
 * <p>Reads parsed configs only, so it needs no registry access.
 *
 * <p>The shared-target check is the most useful thing this module produces, and it comes with a firm
 * limitation that is worth stating rather than papering over: it can only correlate mixins that name
 * their target explicitly. A config entry that is a bare class name means "this mixin patches itself",
 * and there is no way to learn from the config which class that mixin is really applied to — that is a
 * property of the compiled mixin, not of the config. So a config listing {@code FooMixin} without a
 * target contributes nothing to the correlation.
 *
 * <p>The alternative would be to guess from naming: strip a {@code Mixin} suffix and assume the rest
 * is the target. That convention is common but far from universal, and a wrong guess here produces a
 * confident false conflict report — worse than a missing one, because it sends someone chasing a
 * problem that does not exist. So it is not done.
 */
public final class MixinAnalysis implements Analysis {

    @Override
    public String id() {
        return "mixins";
    }

    @Override
    public String description() {
        return "which classes each mod patches, and target classes patched by more than one mod";
    }

    @Override
    public Stage stage() {
        return Stage.PRE_COLLECTION;
    }

    @Override
    public void run(AnalysisContext ctx, AnalysisOutput out) {
        Map<String, TreeSet<String>> ownersByTarget = new TreeMap<>();

        for (MixinConfig cfg : ctx.mixins()) {
            ModElement owner = ctx.mod(cfg.modId());
            String namespace = owner != null ? owner.namespace() : GLOBAL;
            String[] targets = cfg.targetClasses();

            String[] extra = new String[] {
                    "modId", nullToEmpty(cfg.modId()),
                    "config", nullToEmpty(cfg.source()),
                    "package", nullToEmpty(cfg.packageName()),
                    "compatibilityLevel", nullToEmpty(cfg.compatibilityLevel()),
                    "refmap", nullToEmpty(cfg.refmap()),
                    "required", Boolean.toString(cfg.required()),
                    "mixinClasses", Integer.toString(cfg.entries().size()),
                    "explicitTargets", Integer.toString(targets.length),
                    "targets", String.join(", ", targets)};
            out.record(ElementKind.MIXIN, namespace, "mixin:" + cfg.source(), targets, extra);

            // Only explicit targets can be correlated; see the class comment.
            for (MixinConfig.Entry entry : cfg.entries()) {
                if (entry.explicitTarget() == null) {
                    continue;
                }
                String who = cfg.modId() != null ? cfg.modId() : cfg.source();
                ownersByTarget.computeIfAbsent(entry.explicitTarget(), k -> new TreeSet<>()).add(who);
            }
        }

        int implicit = 0;
        for (MixinConfig cfg : ctx.mixins()) {
            for (MixinConfig.Entry entry : cfg.entries()) {
                if (entry.explicitTarget() == null) {
                    implicit++;
                }
            }
        }

        for (Map.Entry<String, TreeSet<String>> e : ownersByTarget.entrySet()) {
            if (e.getValue().size() > 1) {
                out.finding(new Finding("shared_mixin_target", Finding.Severity.WARN, e.getKey(),
                        e.getKey() + " is patched by " + e.getValue().size() + " mods",
                        new String[] {"owners", String.join(", ", e.getValue()),
                                "count", Integer.toString(e.getValue().size())}));
            }
        }

        if (implicit > 0) {
            // Stated once rather than per mixin: it is a property of the format, not a per-mod problem,
            // and reporting it per mixin would bury the findings that are actionable.
            out.finding(new Finding("mixin_targets_uncorrelated", Finding.Severity.INFO, GLOBAL,
                    implicit + " mixin entries name no explicit target, so they cannot take part in"
                            + " shared-target correlation",
                    new String[] {"entries", Integer.toString(implicit),
                            "totalConfigs", Integer.toString(ctx.mixins().size())}));
        }
    }

    /** Namespace used for findings that are about the collection rather than about one mod. */
    static final String GLOBAL = Global.NAMESPACE;

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
