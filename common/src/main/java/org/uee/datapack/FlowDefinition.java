package org.uee.datapack;

import java.util.List;
import java.util.Set;
import org.uee.config.ConfigFile;

/**
 * A datapack-defined flow: a named, reusable description of a whole run.
 *
 * <h2>A flow is a named partial configuration</h2>
 *
 * <p>This is the design decision that keeps the fourth interface from breaking the first three. A
 * flow does not get its own resolution path — it produces a {@link ConfigFile}, the same partial
 * description the command line and the config file produce, and it is layered through the same
 * resolver. So {@code /uee flow wiki} and a config file that spells out the same keys cannot mean
 * different things, and "no second resolution path" continues to hold with four interfaces instead of
 * three.
 *
 * <p>The consequence worth stating: a flow can set anything the config file can, and it participates
 * in the same precedence order. A flow is a starting point that the caller can still override, not a
 * separate mode.
 *
 * <h2>What a flow adds beyond a preset</h2>
 *
 * <p>A flow may also name {@link TargetDefinition}s and {@link StrategyDefinition}s. Naming a target
 * pulls its selection into this run's output; naming a strategy adds its rules to this run's
 * analysis. So a flow is where the three kinds of datapack definition meet, and the only place they
 * are combined into something runnable.
 *
 * @param id the flow's identity, unique across all datapacks
 * @param namespace the declaring datapack's namespace
 * @param description optional human-readable note
 * @param config the partial configuration this flow applies
 * @param targets ids of targets this flow turns on, or empty for none
 * @param strategies ids of strategies this flow runs, or empty for the default set
 */
public record FlowDefinition(String id, String namespace, String description, ConfigFile config,
        List<String> targets, List<String> strategies) {

    public FlowDefinition {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("flow id is required");
        }
        config = config == null ? ConfigFile.empty() : config;
        targets = targets == null ? List.of() : List.copyOf(targets);
        strategies = strategies == null ? List.of() : List.copyOf(strategies);
    }

    /** The flow's fully qualified id, as it is written on the command line. */
    public String qualifiedId() {
        return namespace == null ? id : namespace + ":" + id;
    }

    /** Whether this flow enables any datapack-defined target. */
    public boolean hasTargets() {
        return !targets.isEmpty();
    }

    /**
     * The keys this flow sets, for reporting what it will change.
     *
     * <p>Derived from the partial description rather than stored, so it cannot fall out of step with
     * what the flow actually does.
     */
    public Set<String> touchedKeys() {
        return config.setKeys();
    }
}
