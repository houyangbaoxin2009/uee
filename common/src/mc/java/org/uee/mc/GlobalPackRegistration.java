package org.uee.mc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.uee.Uee;
import org.uee.analysis.Finding;
import org.uee.config.ExportConfig;
import org.uee.config.ConfigFile;
import org.uee.globalpack.GlobalPackDiscovery;
import org.uee.globalpack.GlobalPackPolicy;
import org.uee.spi.LoaderAdapter;

/**
 * The shared half of global datapack registration.
 *
 * <p>Registering packs is loader-specific: each loader has its own event or helper for adding a pack
 * finder. Deciding <em>whether</em> to register, and which packs, is not loader-specific at all — it is
 * the policy in {@link GlobalPackPolicy} plus a directory scan. So this class does the shared part and
 * each loader calls it from its own hook, which keeps four copies of the policy from drifting.
 *
 * <p><b>Compile-unverified along with the rest of the MC layer.</b> The loader hook that calls this is
 * a few lines per loader and is the only part that touches a loader's pack-finder API.
 */
public final class GlobalPackRegistration {

    private GlobalPackRegistration() {
    }

    /** What a loader should register, and why. */
    public record Decision(List<Path> packs, GlobalPackPolicy policy, List<Finding> findings) {

        public Decision {
            packs = List.copyOf(packs);
            findings = List.copyOf(findings);
        }

        /** Whether the loader should register anything. */
        public boolean shouldRegister() {
            return !packs.isEmpty();
        }

        /** Whether UEE declined because another mod provides the feature. */
        public boolean deferred() {
            return policy.deferred();
        }
    }

    /**
     * Decides what a loader should register.
     *
     * <p>A loader calls this once, from its pack-finder hook, and registers whatever comes back. An
     * empty list is the normal answer when another mod handles global datapacks — and the findings
     * explain why, which is the part that would otherwise be invisible.
     *
     * @param config the resolved configuration
     * @return the packs to register and the findings to report
     */
    public static Decision decide(ExportConfig config) {
        LoaderAdapter adapter = Uee.adapter();
        if (adapter == null) {
            // No loader has bound yet, so there is no game directory to resolve anything against.
            return new Decision(List.of(), GlobalPackPolicy.decide(GlobalPackPolicy.Mode.OFF,
                    List.of(), null, 0), List.of());
        }
        GlobalPackPolicy policy = Uee.globalPackPolicy(config);
        List<Finding> findings = new ArrayList<>(policy.findings());
        if (!policy.provide()) {
            return new Decision(List.of(), policy, findings);
        }
        List<Path> packs = packsToRegister(config, adapter);
        if (packs.isEmpty() && policy.ourPackCount() == 0) {
            // The empty-directory note is already in the policy's findings; nothing more to add.
            return new Decision(List.of(), policy, findings);
        }
        return new Decision(packs, policy, findings);
    }

    /** The packs UEE would register, in a deterministic order. */
    private static List<Path> packsToRegister(ExportConfig config, LoaderAdapter adapter) {
        String gameDir = adapter.info().gameDirectory();
        Path dir = Uee.globalDir(gameDir == null || gameDir.isEmpty() ? null : Path.of(gameDir),
                adapter);
        List<Path> packs = new ArrayList<>(4);
        for (GlobalPackDiscovery.Pack pack : GlobalPackDiscovery.packs(dir)) {
            packs.add(pack.path());
        }
        return packs;
    }

    /**
     * Convenience for a loader: the packs to register, or an empty list.
     *
     * <p>For a loader that has already resolved a configuration and only wants the paths.
     */
    public static List<Path> packsFor(ExportConfig config) {
        return decide(config).packs();
    }

    /**
     * Resolves the effective configuration, for a loader hook that runs before anything else has.
     *
     * <p>A loader's pack-finder event fires during startup, often before UEE's own command surface has
     * run, so the hook needs to resolve a configuration itself. Falling back to the defaults keeps a
     * broken config file from preventing packs from loading at all.
     */
    public static ExportConfig resolveForHook() {
        try {
            return Uee.resolveForRun(ConfigFile.empty()).config();
        } catch (Throwable t) {
            return ExportConfig.builder().build();
        }
    }
}
