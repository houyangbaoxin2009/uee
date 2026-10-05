package org.uee.globalpack;

import java.util.List;
import java.util.Locale;
import org.uee.analysis.Finding;

/**
 * Decides whether UEE should provide global datapacks, or stand down for a mod that already does.
 *
 * <h2>The problem</h2>
 *
 * <p>A datapack normally lives in one world's {@code datapacks/} directory, so using one in every world
 * means copying it into every world — and a world created later does not have it. Several mods solve
 * this by loading packs from a shared directory instead. UEE wants the same capability, and two mods
 * independently registering the same packs would double-load them.
 *
 * <h2>The rule</h2>
 *
 * <p>If a mod that provides this is loaded, UEE does not. That is the whole policy, and it is
 * deliberately not cleverer than that:
 *
 * <ul>
 *   <li>It is checked by <b>mod id</b>. Only a loaded mod can register packs, so a leftover
 *       directory with no mod behind it is not a reason to stand down.
 *   <li>The user can override in either direction, because a policy that cannot be overridden is a
 *       policy that is wrong for somebody.
 *   <li>Standing down is <b>reported</b>, naming the mod. "Nothing happened" is the worst possible
 *       feedback for a feature that silently declined to act.
 * </ul>
 *
 * <h2>What UEE keeps doing regardless</h2>
 *
 * <p>Standing down only concerns <em>loading</em> packs. UEE's datapack definitions — flows, targets,
 * strategies — are still read from wherever they are found, including from a global pack another mod
 * loaded. The feature being delegated is where packs come from, not what is inside them.
 *
 * @param mode the effective setting
 * @param provide whether UEE should register its own global packs
 * @param deferredTo the provider UEE stood down for, or {@code null}
 * @param detected directories of provider mods that were found, for reporting
 * @param ourDirectory the directory UEE would read packs from
 * @param ourPackCount packs found in that directory
 */
public record GlobalPackPolicy(Mode mode, boolean provide, KnownProviders.Provider deferredTo,
        List<Detected> detected, String ourDirectory, int ourPackCount) {

    /** The setting a user can choose. */
    public enum Mode {
        /** Defer to a provider when one is loaded. The default. */
        AUTO("auto"),
        /** Always provide, even alongside an provider; the user's explicit choice. */
        ON("on"),
        /** Never provide, whatever else is loaded. */
        OFF("off");

        private final String token;

        Mode(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        /** Parses a token, defaulting to {@link #AUTO} for anything unrecognised. */
        public static Mode of(String token) {
            if (token == null) {
                return AUTO;
            }
            String t = token.trim().toLowerCase(Locale.ROOT);
            for (Mode m : values()) {
                if (m.token.equals(t)) {
                    return m;
                }
            }
            return AUTO;
        }
    }

    /**
     * A provider mod that is loaded.
     *
     * @param provider the known provider
     * @param directories its directories that actually exist, for the report
     * @param packCount packs found in those directories, when they could be counted
     */
    public record Detected(KnownProviders.Provider provider, List<String> directories, int packCount) {

        public Detected {
            directories = directories == null ? List.of() : List.copyOf(directories);
        }

        /** Whether the mod is loaded but has no packs where it conventionally looks. */
        public boolean idle() {
            return packCount == 0;
        }
    }

    public GlobalPackPolicy {
        mode = mode == null ? Mode.AUTO : mode;
        detected = detected == null ? List.of() : List.copyOf(detected);
    }

    /** Whether UEE stood down for someone else. */
    public boolean deferred() {
        return !provide && mode == Mode.AUTO && deferredTo != null;
    }

    /** Whether UEE has packs to load but is not loading them, for whatever reason. */
    public boolean wouldHaveProvided() {
        return !provide && ourPackCount > 0;
    }

    /**
     * Decides the policy.
     *
     * @param mode the user's setting
     * @param detected provider mods that are loaded
     * @param ourDirectory the directory UEE reads packs from, or {@code null}
     * @param ourPackCount packs found there
     */
    public static GlobalPackPolicy decide(Mode mode, List<Detected> detected, String ourDirectory,
            int ourPackCount) {
        List<Detected> found = detected == null ? List.of() : detected;
        return switch (mode) {
            case OFF -> new GlobalPackPolicy(mode, false, null, found, ourDirectory, ourPackCount);
            case ON -> new GlobalPackPolicy(mode, true, null, found, ourDirectory, ourPackCount);
            case AUTO -> {
                KnownProviders.Provider provider = found.isEmpty() ? null : found.get(0).provider();
                yield new GlobalPackPolicy(mode, provider == null, provider, found, ourDirectory,
                        ourPackCount);
            }
        };
    }

    /**
     * The findings this policy should produce.
     *
     * <p>Written here rather than by the caller because every one of them follows from the decision
     * and none is optional: a user who put packs in UEE's directory while another mod handles global
     * packs needs to hear about it, and that case is only knowable here.
     */
    public List<Finding> findings() {
        List<Finding> out = new java.util.ArrayList<>(2);

        if (deferred()) {
            StringBuilder who = new StringBuilder(deferredTo.name())
                    .append(" (").append(deferredTo.modId()).append(')');
            List<String> dirs = detected.isEmpty() ? List.of() : detected.get(0).directories();
            out.add(new Finding("global_pack_deferred", Finding.Severity.INFO, deferredTo.modId(),
                    "global datapacks are provided by " + who
                            + ", so UEE's own global-datapack feature is disabled",
                    new String[] {"provider", deferredTo.modId(),
                            "directories", String.join(", ", dirs),
                            "override", "set global_datapacks = \"on\" to use UEE's directory anyway"}));

            // The silently-ineffective case: packs in our directory that nobody is loading.
            if (ourPackCount > 0) {
                out.add(new Finding("global_pack_ignored", Finding.Severity.WARN, deferredTo.modId(),
                        ourPackCount + " pack(s) are in UEE's global directory ("
                                + ourDirectory + ") but UEE is not loading them, because "
                                + deferredTo.name() + " (" + deferredTo.modId()
                                + ") handles global datapacks",
                        new String[] {"directory", String.valueOf(ourDirectory),
                                "packs", Integer.toString(ourPackCount),
                                "suggestion", "move them to " + dirs}));
            }
        }

        if (mode == Mode.OFF && ourPackCount > 0) {
            out.add(new Finding("global_pack_disabled", Finding.Severity.INFO, "uee",
                    "global datapacks are switched off, so " + ourPackCount + " pack(s) in "
                            + ourDirectory + " are not loaded",
                    new String[] {"directory", String.valueOf(ourDirectory),
                            "packs", Integer.toString(ourPackCount)}));
        }

        if (mode == Mode.ON && !detected.isEmpty()) {
            // Both are loading. Whether that double-registers depends on the provider, and the user
            // chose this, so it is a note rather than a warning.
            out.add(new Finding("global_pack_both_active", Finding.Severity.INFO, "uee",
                    "UEE is providing global datapacks alongside "
                            + detected.get(0).provider().name()
                            + " (" + detected.get(0).provider().modId() + ")"
                            + "; if packs appear twice, set global_datapacks = \"auto\"",
                    new String[] {"provider", detected.get(0).provider().modId()}));
        }

        if (provide && ourPackCount == 0 && ourDirectory != null) {
            out.add(new Finding("global_pack_empty", Finding.Severity.INFO, "uee",
                    "UEE provides global datapacks but found none in " + ourDirectory,
                    new String[] {"directory", ourDirectory}));
        }
        return out;
    }

    /** A one-line summary, for the command surface. */
    public String describe() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("global_datapacks=").append(mode.token());
        if (deferred()) {
            sb.append(" → delegated to ").append(deferredTo.modId());
        } else if (provide) {
            sb.append(" → UEE provides (").append(ourPackCount).append(" pack(s))");
        } else {
            sb.append(" → disabled");
        }
        return sb.toString();
    }
}
