package org.uee.datapack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.uee.analysis.Finding;

/**
 * Every definition UEE knows about, resolved.
 *
 * <p>The catalog is what makes an id unambiguous. A flow name typed on the command line, a target
 * named by a flow, a strategy named by a flow — all resolve here, and all resolve to exactly one
 * definition.
 *
 * <h2>Conflict resolution is deterministic and reported</h2>
 *
 * <p>Two datapacks can define the same id, and that is not necessarily a mistake — a user's global
 * directory overriding a mod's bundled flow is a legitimate way to customize. So the resolution is by
 * {@link DatapackSource.Origin#precedence()} (global beats world beats mod), and a shadowed
 * definition is reported as a finding rather than silently dropped. A user who overrode a flow should
 * be able to confirm that their override is the one in effect.
 *
 * <p>Within one origin the tie is broken by datapack id, and that is reported too: two same-origin
 * datapacks defining one id is genuinely ambiguous and deserves a mention even though a winner is
 * needed.
 */
public final class DatapackCatalog {

    private final Map<String, FlowDefinition> flows = new TreeMap<>();
    private final Map<String, TargetDefinition> targets = new TreeMap<>();
    private final Map<String, StrategyDefinition> strategies = new TreeMap<>();
    private final Map<String, DatapackSource> flowSource = new TreeMap<>();
    private final Map<String, DatapackSource> targetSource = new TreeMap<>();
    private final Map<String, DatapackSource> strategySource = new TreeMap<>();
    private final List<Finding> problems = new ArrayList<>(2);
    private final Map<String, DatapackSource> sources = new LinkedHashMap<>();

    private DatapackCatalog() {
    }

    /** An empty catalog: the built-in categories and checks only, no datapack definitions. */
    public static DatapackCatalog empty() {
        return new DatapackCatalog();
    }

    /**
     * Builds a catalog from loaded datapacks.
     *
     * @param sources the datapacks to read, in any order; precedence decides the winners
     */
    public static DatapackCatalog of(List<DatapackSource> sources) {
        DatapackCatalog catalog = new DatapackCatalog();
        for (DatapackSource source : sources) {
            catalog.sources.put(source.id(), source);
        }
        List<DatapackSource> ordered = new ArrayList<>(sources);
        ordered.sort(Comparator.comparingInt((DatapackSource s) -> s.origin().precedence())
                .thenComparing(DatapackSource::id));
        for (DatapackSource source : ordered) {
            DatapackLoader.Loaded loaded = DatapackLoader.load(source);
            catalog.problems.addAll(loaded.problems());
            for (FlowDefinition f : loaded.flows()) {
                catalog.addFlow(source, f);
            }
            for (TargetDefinition t : loaded.targets()) {
                catalog.addTarget(source, t);
            }
            for (StrategyDefinition s : loaded.strategies()) {
                catalog.addStrategy(source, s);
            }
        }
        return catalog;
    }

    /** Builds a catalog directly from already-parsed definitions, for tests and programmatic use. */
    public static DatapackCatalog ofDefinitions(List<FlowDefinition> flows,
            List<TargetDefinition> targets, List<StrategyDefinition> strategies) {
        DatapackCatalog catalog = new DatapackCatalog();
        for (FlowDefinition f : flows) {
            catalog.addFlow(null, f);
        }
        for (TargetDefinition t : targets) {
            catalog.addTarget(null, t);
        }
        for (StrategyDefinition s : strategies) {
            catalog.addStrategy(null, s);
        }
        return catalog;
    }

    // ---------------------------------------------------------------- registration

    private void addFlow(DatapackSource source, FlowDefinition flow) {
        if (shadowed(flow.id(), flows, flowSource, source, "flow")) {
            return;
        }
        flows.put(flow.id(), flow);
        if (source != null) {
            flowSource.put(flow.id(), source);
        }
    }

    private void addTarget(DatapackSource source, TargetDefinition target) {
        if (shadowed(target.id(), targets, targetSource, source, "target")) {
            return;
        }
        targets.put(target.id(), target);
        if (source != null) {
            targetSource.put(target.id(), source);
        }
    }

    private void addStrategy(DatapackSource source, StrategyDefinition strategy) {
        if (shadowed(strategy.id(), strategies, strategySource, source, "analysis strategy")) {
            return;
        }
        strategies.put(strategy.id(), strategy);
        if (source != null) {
            strategySource.put(strategy.id(), source);
        }
    }

    /**
     * Decides whether a definition loses to the one already registered.
     *
     * @return true when the incoming definition is shadowed and should be dropped
     */
    private <T> boolean shadowed(String id, Map<String, T> existing,
            Map<String, DatapackSource> owners, DatapackSource incoming, String what) {
        if (!existing.containsKey(id)) {
            return false;
        }
        DatapackSource current = owners.get(id);
        int currentRank = current == null ? -1 : current.origin().precedence();
        int incomingRank = incoming == null ? -1 : incoming.origin().precedence();
        if (incomingRank > currentRank) {
            // The incoming definition wins; report that it replaced one, so an override is visible
            // rather than merely effective.
            problems.add(new Finding("datapack_override", Finding.Severity.INFO, id,
                    what + " '" + id + "' is defined by both "
                            + name(current) + " and " + name(incoming)
                            + "; the one from " + name(incoming) + " is used",
                    new String[] {"id", id, "kind", what,
                            "used", name(incoming), "shadowed", name(current)}));
            return false;
        }
        problems.add(new Finding("datapack_shadowed", Finding.Severity.WARN, id,
                what + " '" + id + "' from " + name(incoming) + " is shadowed by "
                        + name(current),
                new String[] {"id", id, "kind", what,
                        "used", name(current), "shadowed", name(incoming)}));
        return true;
    }

    private static String name(DatapackSource source) {
        return source == null ? "built-in" : source.id() + " (" + source.origin().token() + ")";
    }

    // ---------------------------------------------------------------- lookup

    /** A flow by id, or {@code null}. Accepts either the bare id or {@code namespace:id}. */
    public FlowDefinition flow(String id) {
        if (id == null) {
            return null;
        }
        FlowDefinition direct = flows.get(id);
        if (direct != null) {
            return direct;
        }
        int colon = id.indexOf(':');
        return colon < 0 ? null : flows.get(id.substring(colon + 1));
    }

    /** A target by id, or {@code namespace:id}. */
    public TargetDefinition target(String id) {
        if (id == null) {
            return null;
        }
        TargetDefinition direct = targets.get(id);
        if (direct != null) {
            return direct;
        }
        int colon = id.indexOf(':');
        return colon < 0 ? null : targets.get(id.substring(colon + 1));
    }

    /** An analysis strategy by id, or {@code namespace:id}. */
    public StrategyDefinition strategy(String id) {
        if (id == null) {
            return null;
        }
        StrategyDefinition direct = strategies.get(id);
        if (direct != null) {
            return direct;
        }
        int colon = id.indexOf(':');
        return colon < 0 ? null : strategies.get(id.substring(colon + 1));
    }

    /** Every flow, by id, sorted. */
    public Map<String, FlowDefinition> flows() {
        return Map.copyOf(flows);
    }

    /** Every target, by id, sorted. */
    public Map<String, TargetDefinition> targets() {
        return Map.copyOf(targets);
    }

    /** Every strategy, by id, sorted. */
    public Map<String, StrategyDefinition> strategies() {
        return Map.copyOf(strategies);
    }

    /** Datapacks this catalog was built from. */
    public Map<String, DatapackSource> sources() {
        return Map.copyOf(sources);
    }

    /** Problems found while loading and merging, for reporting. */
    public List<Finding> problems() {
        return List.copyOf(problems);
    }

    public boolean isEmpty() {
        return flows.isEmpty() && targets.isEmpty() && strategies.isEmpty();
    }

    /** Whether anything was defined at all, including a datapack that only had problems. */
    public boolean hasSources() {
        return !sources.isEmpty();
    }

    /**
     * Resolves a list of target ids, reporting the ones that do not exist.
     *
     * <p>An unknown id is reported rather than skipped: a flow that names a target nobody defined
     * would otherwise run without it, and the user would see a missing output rather than a reason.
     */
    public List<TargetDefinition> resolveTargets(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<TargetDefinition> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            TargetDefinition t = target(id);
            if (t == null) {
                problems.add(new Finding("datapack_unknown_target", Finding.Severity.WARN, id,
                        "target '" + id + "' is named but no datapack defines it",
                        new String[] {"target", id}));
                continue;
            }
            out.add(t);
        }
        return out;
    }

    /** Resolves a list of strategy ids, reporting the ones that do not exist. */
    public List<StrategyDefinition> resolveStrategies(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<StrategyDefinition> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            StrategyDefinition s = strategy(id);
            if (s == null) {
                problems.add(new Finding("datapack_unknown_strategy", Finding.Severity.WARN, id,
                        "analysis strategy '" + id + "' is named but no datapack defines it",
                        new String[] {"strategy", id}));
                continue;
            }
            out.add(s);
        }
        return out;
    }
}
