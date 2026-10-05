package org.uee.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.uee.model.ElementKind;

/**
 * The one resolved description of a run.
 *
 * <p>Every interface produces this and nothing else. The command surface parses into it, the config
 * file loads into it, the programmatic API takes it, and the pipeline consumes it. That funnel is the
 * point: with four loaders and three interfaces, anything that let a second shape exist would give
 * twelve paths to keep in agreement, and the one that drifted would do so silently.
 *
 * <p>Immutable; build one through {@link Builder}. A config is cheap to copy and is shared by the
 * whole pipeline, including the per-shard encoders.
 *
 * <h2>What is configurable</h2>
 * <ul>
 *   <li><b>which content</b> — {@link #kinds()}, plus namespace and mod filters
 *   <li><b>which format</b> — {@link #formats()}, several at once if wanted
 *   <li><b>where</b> — {@link #outputDir()}, and {@link #packagePerKind()} for the layout under it
 *   <li><b>whether to analyse</b> — {@link #analyze()}
 *   <li><b>whether analysis is bundled</b> — {@link #analysisSeparate()}
 * </ul>
 */
public final class ExportConfig {

    /** Output format tokens accepted by {@link #formats()}. */
    public static final String JSON = "json";
    /** Newline-delimited JSON: one compact object per line. The shape the wiki importers expect. */
    public static final String NDJSON = "ndjson";
    /** {@code tie:data} (td) — the tie ecosystem text format. */
    public static final String TD = "td";
    /** zd — the tie ecosystem binary serialization, columnar with content fingerprints. */
    public static final String ZD = "zd";
    /** YAML. */
    public static final String YAML = "yaml";
    /** TOML. */
    public static final String TOML = "toml";
    /** XML. */
    public static final String XML = "xml";
    /** The wiki projection, readable by the MC百科 import pipelines. */
    public static final String WIKI = "wiki";

    /** Every format this build can write, in the order the command surface lists them. */
    public static final String[] ALL_FORMATS =
            {JSON, WIKI, NDJSON, TD, ZD, YAML, TOML, XML};

    private final Path outputDir;
    private final String packageName;
    private final Set<String> formats;
    private final Set<ElementKind> kinds;
    private final Set<String> includeNamespaces;
    private final Set<String> excludeNamespaces;
    private final Set<String> excludeMods;
    private final boolean icons;
    private final boolean pretty;
    private final boolean incremental;
    private final boolean shardByNamespace;
    private final int shardSize;
    private final long memoryLimitBytes;
    private final int threads;
    private final boolean includePaths;
    private final boolean analyze;
    private final boolean analysisSeparate;
    private final boolean packagePerKind;
    private final boolean datapacks;
    private final String globalDatapacks;
    private final String globalDatapackDir;
    private final String flow;
    private final Set<String> targets;
    private final Set<String> strategies;
    private final WikiOptions wiki;

    private ExportConfig(Builder b) {
        this.outputDir = b.outputDir;
        this.packageName = b.packageName;
        this.formats = Collections.unmodifiableSet(new LinkedHashSet<>(b.formats));
        this.kinds = Collections.unmodifiableSet(
                b.kinds.isEmpty() ? EnumSet.allOf(ElementKind.class) : b.kinds);
        this.includeNamespaces = Collections.unmodifiableSet(new LinkedHashSet<>(b.includeNamespaces));
        this.excludeNamespaces = Collections.unmodifiableSet(new LinkedHashSet<>(b.excludeNamespaces));
        this.excludeMods = Collections.unmodifiableSet(new LinkedHashSet<>(b.excludeMods));
        this.icons = b.icons;
        this.pretty = b.pretty;
        this.incremental = b.incremental;
        this.shardByNamespace = b.shardByNamespace;
        this.shardSize = b.shardSize;
        this.memoryLimitBytes = b.memoryLimitBytes;
        this.threads = b.threads;
        this.includePaths = b.includePaths;
        this.analyze = b.analyze;
        this.analysisSeparate = b.analysisSeparate;
        this.packagePerKind = b.packagePerKind;
        this.datapacks = b.datapacks;
        this.globalDatapacks = b.globalDatapacks;
        this.globalDatapackDir = b.globalDatapackDir;
        this.flow = b.flow;
        this.targets = Collections.unmodifiableSet(new LinkedHashSet<>(b.targets));
        this.strategies = Collections.unmodifiableSet(new LinkedHashSet<>(b.strategies));
        this.wiki = b.wiki;
    }

    public Path outputDir() {
        return outputDir;
    }

    /** Name of the export bundle, used as the directory under the output root. */
    public String packageName() {
        return packageName;
    }

    public Set<String> formats() {
        return formats;
    }

    public Set<ElementKind> kinds() {
        return kinds;
    }

    public Set<String> includeNamespaces() {
        return includeNamespaces;
    }

    public Set<String> excludeNamespaces() {
        return excludeNamespaces;
    }

    public Set<String> excludeMods() {
        return excludeMods;
    }

    /** Whether icon rendering is requested. Off by default: the data phase is render-free. */
    public boolean icons() {
        return icons;
    }

    public boolean pretty() {
        return pretty;
    }

    public boolean incremental() {
        return incremental;
    }

    public boolean shardByNamespace() {
        return shardByNamespace;
    }

    public int shardSize() {
        return shardSize;
    }

    public long memoryLimitBytes() {
        return memoryLimitBytes;
    }

    public int threads() {
        return threads;
    }

    /**
     * Whether full container paths are written to diagnostic output.
     *
     * <p>Off by default. The diagnostic artifacts are meant to be published or attached to a bug
     * report, and an absolute path discloses the account name and directory layout; the file name
     * identifies the mod just as well.
     */
    public boolean includePaths() {
        return includePaths;
    }

    /** Whether to run the analysis at all. Off means data only. */
    public boolean analyze() {
        return analyze;
    }

    /**
     * Whether the analysis output is kept apart from the data output.
     *
     * <p>Off (the default) writes the analysis under the same bundle, as one more package among the
     * others. On writes it to a sibling directory, which is what you want when the data half is going
     * to one consumer and the diagnostic half to another — a bug report, say, where the data does not
     * belong.
     */
    public boolean analysisSeparate() {
        return analysisSeparate;
    }

    /**
     * Whether each element category is written into its own sub-directory.
     *
     * <p>On by default. Off flattens everything into the bundle root, which is what a consumer with a
     * fixed expectation of the directory layout needs.
     */
    public boolean packagePerKind() {
        return packagePerKind;
    }

    /**
     * Whether datapack-defined flows, targets and strategies are read at all.
     *
     * <p>On by default. Off means the run uses only the built-in categories and checks, which is what
     * a caller wants when reproducing a result and they do not want a pack's definitions to perturb
     * it.
     */
    public boolean datapacks() {
        return datapacks;
    }

    /**
     * The global-datapack setting: {@code auto}, {@code on} or {@code off}.
     *
     * <p>Kept as a token rather than an enum so the value survives a round trip through the config
     * file unchanged, and so an unrecognised value degrades to {@code auto} instead of failing a run.
     * {@link org.uee.globalpack.GlobalPackPolicy.Mode#of} is where it is interpreted.
     */
    public String globalDatapacks() {
        return globalDatapacks;
    }

    /** The global datapack directory, or {@code null} for the default under the game directory. */
    public String globalDatapackDir() {
        return globalDatapackDir;
    }

    /** The flow this run follows, or {@code null} when it is an ad-hoc configuration. */
    public String flow() {
        return flow;
    }

    /** Datapack-defined targets this run writes, by id. */
    public Set<String> targets() {
        return targets;
    }

    /** Datapack-defined analysis strategies this run runs, by id. */
    public Set<String> strategies() {
        return strategies;
    }

    public WikiOptions wiki() {
        return wiki;
    }

    public boolean wants(ElementKind kind) {
        return kinds.contains(kind);
    }

    public boolean wantsFormat(String format) {
        return formats.contains(format);
    }

    /**
     * Namespace filter. An empty include set means "every namespace"; an exclude entry always wins.
     * The {@code minecraft} namespace is included by default because a wiki entry for a mod is
     * routinely described in terms of the vanilla things it interacts with.
     */
    public boolean acceptsNamespace(String ns) {
        if (ns == null) {
            return false;
        }
        if (excludeNamespaces.contains(ns)) {
            return false;
        }
        return includeNamespaces.isEmpty() || includeNamespaces.contains(ns);
    }

    /** A copy of this config with different content categories. */
    public ExportConfig withKinds(Set<ElementKind> newKinds) {
        Builder b = toBuilder().kinds(newKinds.toArray(new ElementKind[0]));
        return b.build();
    }

    /** A copy of this config with different formats. */
    public ExportConfig withFormats(Set<String> newFormats) {
        return toBuilder().formats(newFormats.toArray(new String[0])).build();
    }

    /** A copy of this config with analysis disabled. */
    public ExportConfig withoutAnalysis() {
        return toBuilder().analyze(false).build();
    }

    /** A builder pre-filled with this config, for producing a modified copy. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.outputDir = outputDir;
        b.packageName = packageName;
        b.formats = new LinkedHashSet<>(formats);
        b.kinds = kinds.isEmpty() ? EnumSet.noneOf(ElementKind.class) : EnumSet.copyOf(kinds);
        b.includeNamespaces = new LinkedHashSet<>(includeNamespaces);
        b.excludeNamespaces = new LinkedHashSet<>(excludeNamespaces);
        b.excludeMods = new LinkedHashSet<>(excludeMods);
        b.icons = icons;
        b.pretty = pretty;
        b.incremental = incremental;
        b.shardByNamespace = shardByNamespace;
        b.shardSize = shardSize;
        b.memoryLimitBytes = memoryLimitBytes;
        b.threads = threads;
        b.includePaths = includePaths;
        b.analyze = analyze;
        b.analysisSeparate = analysisSeparate;
        b.packagePerKind = packagePerKind;
        b.datapacks = datapacks;
        b.globalDatapacks = globalDatapacks;
        b.globalDatapackDir = globalDatapackDir;
        b.flow = flow;
        b.targets = new LinkedHashSet<>(targets);
        b.strategies = new LinkedHashSet<>(strategies);
        b.wiki = wiki;
        return b;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The one-key default, which is what {@code /uee} with no arguments runs.
     *
     * <p>Three choices, each deliberate:
     *
     * <ul>
     *   <li><b>json</b> — universally readable, and the format that needs no explanation to a consumer.
     *       {@code ndjson} is the streaming equivalent and is one token away.
     *   <li><b>every category in {@code common}</b> — the registries a wiki entry is built from plus
     *       the datapack content that describes how they are obtained. Property-style categories are
     *       omitted because they are large and rarely edited.
     *   <li><b>analysis kept as its own package</b> — so the data half can be handed to a consumer
     *       without the diagnostics riding along. This is the "+1" in "n data packages plus one
     *       analysis package": each selected data category becomes a package, and the analysis forms
     *       one more.
     * </ul>
     */
    public static ExportConfig defaults() {
        return builder().build();
    }

    /** The default output root under a game directory. */
    public static Path defaultOutputDir(Path gameDirectory) {
        return gameDirectory.resolve("exports").resolve(org.uee.Uee.MOD_ID);
    }

    /** Mutable builder with the documented defaults. */
    public static final class Builder {
        private Path outputDir = Path.of("exports");
        private String packageName = "uee-export";
        private Set<String> formats = new LinkedHashSet<>(Set.of(JSON));
        // The common set, not "everything": biomes, dimensions and structures are large and rarely
        // edited, so a default that includes them makes the first run needlessly expensive. An
        // explicitly empty selection still means all, which is how a caller asks for everything.
        private Set<ElementKind> kinds = Tokens.commonKinds();
        private Set<String> includeNamespaces = new LinkedHashSet<>();
        private Set<String> excludeNamespaces = new LinkedHashSet<>();
        private Set<String> excludeMods = new LinkedHashSet<>();
        private boolean icons = false;
        private boolean pretty = false;
        private boolean incremental = false;
        private boolean shardByNamespace = true;
        private int shardSize = 20_000;
        private long memoryLimitBytes = 256L * 1024 * 1024;
        private int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        private boolean includePaths = false;
        private boolean analyze = true;
        private boolean analysisSeparate = true;
        private boolean packagePerKind = true;
        private boolean datapacks = true;
        private String globalDatapacks = "auto";
        private String globalDatapackDir = null;
        private String flow = null;
        private Set<String> targets = new LinkedHashSet<>();
        private Set<String> strategies = new LinkedHashSet<>();
        private WikiOptions wiki = WikiOptions.defaults();

        public Builder outputDir(Path dir) {
            this.outputDir = dir;
            return this;
        }

        public Builder packageName(String name) {
            this.packageName = name == null || name.isEmpty() ? "uee-export" : name;
            return this;
        }

        public Builder formats(String... tokens) {
            this.formats = new LinkedHashSet<>(java.util.Arrays.asList(tokens));
            return this;
        }

        public Builder addFormat(String token) {
            this.formats.add(token);
            return this;
        }

        /** Replaces the content categories. An empty array means "every category". */
        public Builder kinds(ElementKind... ks) {
            this.kinds = ks.length == 0
                    ? EnumSet.noneOf(ElementKind.class)
                    : EnumSet.copyOf(java.util.Arrays.asList(ks));
            return this;
        }

        public Builder includeNamespace(String ns) {
            this.includeNamespaces.add(ns);
            return this;
        }

        public Builder excludeNamespace(String ns) {
            this.excludeNamespaces.add(ns);
            return this;
        }

        public Builder excludeMod(String id) {
            this.excludeMods.add(id);
            return this;
        }

        public Builder icons(boolean on) {
            this.icons = on;
            return this;
        }

        public Builder pretty(boolean on) {
            this.pretty = on;
            return this;
        }

        public Builder incremental(boolean on) {
            this.incremental = on;
            return this;
        }

        public Builder shardByNamespace(boolean on) {
            this.shardByNamespace = on;
            return this;
        }

        public Builder shardSize(int n) {
            this.shardSize = Math.max(1, n);
            return this;
        }

        public Builder memoryLimitBytes(long n) {
            this.memoryLimitBytes = Math.max(1 << 20, n);
            return this;
        }

        public Builder threads(int n) {
            this.threads = Math.max(1, n);
            return this;
        }

        public Builder includePaths(boolean on) {
            this.includePaths = on;
            return this;
        }

        public Builder analyze(boolean on) {
            this.analyze = on;
            return this;
        }

        public Builder analysisSeparate(boolean on) {
            this.analysisSeparate = on;
            return this;
        }

        public Builder packagePerKind(boolean on) {
            this.packagePerKind = on;
            return this;
        }

        public Builder datapacks(boolean on) {
            this.datapacks = on;
            return this;
        }

        public Builder globalDatapacks(String mode) {
            this.globalDatapacks = mode == null ? "auto" : mode;
            return this;
        }

        public Builder globalDatapackDir(String dir) {
            this.globalDatapackDir = dir;
            return this;
        }

        /** Records which flow this configuration came from, for reporting. */
        public Builder flow(String id) {
            this.flow = id;
            return this;
        }

        public Builder targets(Set<String> ids) {
            this.targets = new LinkedHashSet<>(ids);
            return this;
        }

        public Builder addTarget(String id) {
            this.targets.add(id);
            return this;
        }

        public Builder strategies(Set<String> ids) {
            this.strategies = new LinkedHashSet<>(ids);
            return this;
        }

        public Builder addStrategy(String id) {
            this.strategies.add(id);
            return this;
        }

        public Builder wiki(WikiOptions w) {
            this.wiki = w;
            return this;
        }

        public ExportConfig build() {
            return new ExportConfig(this);
        }
    }
}
