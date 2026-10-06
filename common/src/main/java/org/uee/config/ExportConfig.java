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
    /**
     * Where the portable state lives, or null for the default.
     *
     * <p>Null rather than a resolved path, because "not set" and "set to the default" are different facts:
     * only the first should fall through to the environment variable and then to the home directory.
     */
    private final Path userDir;
    private final String packageName;
    private final Set<String> formats;
    private final Set<ElementKind> kinds;
    private final Set<String> includeNamespaces;
    private final Set<String> excludeNamespaces;
    private final Set<String> excludeMods;
    private final boolean icons;
    private final boolean pretty;
    private final boolean shardByNamespace;
    private final int shardSize;
    private final long memoryLimitBytes;
    private final int threads;
    private final boolean includePaths;
    private final boolean analyze;
    private final boolean analysisSeparate;
    private final boolean packagePerKind;
    private final boolean quiet;
    private final FieldMask fields;
    private final boolean dryRun;

    /**
     * Whether to write only what changed since the previous run.
     *
     * <p>Off by default, and deliberately: a delta changes what lands on disk — an unchanged artifact is
     * left exactly as it was rather than rewritten — so a run that did not ask for one must behave exactly
     * as it did before the option existed. It also writes a temporary file per artifact while it works,
     * which is a cost nobody should pay without asking for the feature that needs it.
     */
    private final boolean delta;

    /**
     * Whether to copy the pack's assets — textures, models, sounds — into the output.
     *
     * <p>Off by default, and that is the documented intent rather than a limitation: the one-key command is
     * meant to produce the data side without crossing into assets, which are thousands of files and
     * megabytes of bytes nobody asked for. A run that wants a complete import package turns this on.
     *
     * <p>The bytes are copied verbatim, never decoded. That is not only about speed: decoding changes the
     * hash, and a fingerprint is taken over the bytes that land on disk, so a re-encoded texture would be a
     * different artifact on every run and the delta would report every asset as changed forever.
     */
    private final boolean assets;
    /**
     * Whether to export as soon as a game has started, without being asked.
     *
     * <p>Off by default, and deliberately. It writes files and spends time on every launch, and a tool that
     * does that uninvited is a tool people remove. The point of the option is the case it serves: someone
     * who analyses pack after pack and wants the export to be simply there, having declared once that they
     * want it.
     *
     * <p>Persisted like any other preference, since the whitelist is what decides that and this is exactly
     * the kind of decision worth keeping.
     */
    private final boolean autoRun;
    private final Set<String> includeTags;
    /**
     * Which settings are kept between runs and between instances.
     *
     * <p>A whitelist rather than everything, because a session accumulates experiments. Someone who tries
     * {@code ndjson} once to see what it looks like should not still be getting it three packs later, and
     * which settings stick is therefore something to declare rather than to infer.
     *
     * <p>The keys that control persistence are excluded by construction and not merely by default; see
     * {@code Uee.persistableKeys}. A setting that could make itself permanent, and could not then be
     * changed from the surface that made it, has no way back.
     */
    private final Set<String> persist;
    private final Set<String> excludeTags;
    private final long maxFileBytes;
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
        this.shardByNamespace = b.shardByNamespace;
        this.shardSize = b.shardSize;
        this.memoryLimitBytes = b.memoryLimitBytes;
        this.threads = b.threads;
        this.includePaths = b.includePaths;
        this.analyze = b.analyze;
        this.analysisSeparate = b.analysisSeparate;
        this.packagePerKind = b.packagePerKind;
        this.quiet = b.quiet;
        this.fields = b.fields;
        this.dryRun = b.dryRun;
        this.delta = b.delta;
        this.assets = b.assets;
        this.userDir = b.userDir;
        this.persist = b.persist == null ? Set.of() : Set.copyOf(b.persist);
        this.autoRun = b.autoRun;
        this.includeTags = Collections.unmodifiableSet(new LinkedHashSet<>(b.includeTags));
        this.excludeTags = Collections.unmodifiableSet(new LinkedHashSet<>(b.excludeTags));
        this.maxFileBytes = b.maxFileBytes;
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

    public boolean shardByNamespace() {
        return shardByNamespace;
    }

    public int shardSize() {
        return shardSize;
    }

    /**
     * The size of one shard's output buffer before it is flushed, derived from the configured
     * ceiling divided by sixteen.
     *
     * <p><b>Not a ceiling on the run.</b> It is a per-shard valve: memory is bounded by the number of
     * live shards times this, which {@code ScaleTest} measures as a few hundred kilobytes in practice
     * because buffers are flushed far more often than the valve would require. Setting it low
     * therefore does not make a run use less memory, and setting it high does not make one use more —
     * what actually bounds a run is the flush interval, and what the {@code ScaleTest} evidence shows
     * is that the whole export stays under a small constant however large the pack is.
     *
     * <p>The name is kept because it is a published config key; the wording around it is what had to
     * be corrected.
     */
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
     * Whether to suppress per-artifact progress output, keeping only the closing summary.
     *
     * <p>Off by default, because a person watching an interactive run wants to see it working. On is
     * what a scripted run wants: an MC function logs every command's output, so a verbose export from
     * a tick loop fills the server log with lines nobody reads and buries the one line that matters.
     */
    public boolean quiet() {
        return quiet;
    }

    /** Which fields records should carry. */
    public FieldMask fields() {
        return fields;
    }

    /**
     * Whether to plan without writing.
     *
     * <p>A scripted caller can then find out what a configuration would produce — how many files, how
     * many records, where — without touching the filesystem. Useful enough to be worth its own switch
     * rather than something achieved by exporting to a temporary directory and deleting it.
     */
    public boolean dryRun() {
        return dryRun;
    }

    /** Whether to write only what changed since the previous run. */
    public boolean delta() {
        return delta;
    }

    /** Whether to copy the pack's assets into the output. */
    public boolean assets() {
        return assets;
    }

    /** Where the portable state lives, or null for the default. */
    public Path userDir() {
        return userDir;
    }

    /** Which settings are kept between runs and between instances. */
    public Set<String> persist() {
        return persist;
    }

    /** Whether to export as soon as a game has started, without being asked. */
    public boolean autoRun() {
        return autoRun;
    }

    /** Only elements carrying one of these tags. Empty means no tag restriction. */
    public Set<String> includeTags() {
        return includeTags;
    }

    /** Elements carrying any of these tags are skipped. */
    public Set<String> excludeTags() {
        return excludeTags;
    }

    /**
     * Split a shard once it exceeds this many bytes. Zero means split by record count instead.
     *
     * <p>By bytes rather than by count because a consumer cares about file size, and records vary
     * enormously: a thousand blocks and a thousand recipes are not comparable amounts of data.
     */
    public long maxFileBytes() {
        return maxFileBytes;
    }

    /** Whether a tag filter is in effect. */
    public boolean hasTagFilter() {
        return !includeTags.isEmpty() || !excludeTags.isEmpty();
    }

    /**
     * Whether an element carrying these tags passes the filter.
     *
     * <p>An element with no tags is excluded when an include filter is set — it cannot be shown to
     * carry one — but passes an exclude-only filter, since there is nothing to exclude it for.
     */
    public boolean acceptsTags(String[] tags) {
        if (!hasTagFilter()) {
            return true;
        }
        if (tags != null) {
            for (String tag : tags) {
                if (excludeTags.contains(tag)) {
                    return false;
                }
            }
        }
        if (includeTags.isEmpty()) {
            return true;
        }
        if (tags == null) {
            return false;
        }
        for (String tag : tags) {
            if (includeTags.contains(tag)) {
                return true;
            }
        }
        return false;
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
        b.shardByNamespace = shardByNamespace;
        b.shardSize = shardSize;
        b.memoryLimitBytes = memoryLimitBytes;
        b.threads = threads;
        b.includePaths = includePaths;
        b.analyze = analyze;
        b.analysisSeparate = analysisSeparate;
        b.packagePerKind = packagePerKind;
        b.quiet = quiet;
        b.fields = fields;
        b.dryRun = dryRun;
        // Easy to forget and invisible when forgotten: a toBuilder round trip that dropped this would
        // silently turn a delta run into a full one, which looks like the feature not working rather
        // than like a copied field being missing.
        b.delta = delta;
        b.assets = assets;
        b.userDir = userDir;
        b.persist = persist;
        b.autoRun = autoRun;
        b.includeTags = new LinkedHashSet<>(includeTags);
        b.excludeTags = new LinkedHashSet<>(excludeTags);
        b.maxFileBytes = maxFileBytes;
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
        private boolean shardByNamespace = true;
        private int shardSize = 20_000;
        /**
         * Per-shard buffer ceiling, in bytes. See {@link #memoryLimitBytes()} for what this does and
         * does not bound.
         */
        private long memoryLimitBytes = 256L * 1024 * 1024;
        private int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        private boolean includePaths = false;
        private boolean analyze = true;
        private boolean analysisSeparate = true;
        private boolean packagePerKind = true;
        private boolean quiet = false;
        private FieldMask fields = FieldMask.all();
        private boolean dryRun = false;
        private boolean delta = false;
        private boolean assets = false;
        private Path userDir;
        private Set<String> persist = Set.of();
        private boolean autoRun = false;
        private Set<String> includeTags = new LinkedHashSet<>();
        private Set<String> excludeTags = new LinkedHashSet<>();
        private long maxFileBytes = 0;
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

        public Builder quiet(boolean on) {
            this.quiet = on;
            return this;
        }

        public Builder fields(FieldMask mask) {
            this.fields = mask == null ? FieldMask.all() : mask;
            return this;
        }

        /** Sets where the portable state lives. */
        public Builder userDir(Path dir) {
            this.userDir = dir;
            return this;
        }

        /** Declares which settings are kept between runs. */
        public Builder persist(Set<String> keys) {
            this.persist = keys == null ? Set.of() : keys;
            return this;
        }

        /** Asks for an export as soon as a game has started. */
        public Builder autoRun(boolean on) {
            this.autoRun = on;
            return this;
        }

        /** Asks for the pack's assets to be copied into the output. */
        public Builder assets(boolean on) {
            this.assets = on;
            return this;
        }

        /** Asks for a delta against the snapshot in the output directory, if there is one. */
        public Builder delta(boolean on) {
            this.delta = on;
            return this;
        }

        public Builder dryRun(boolean on) {
            this.dryRun = on;
            return this;
        }

        public Builder includeTag(String tag) {
            this.includeTags.add(tag);
            return this;
        }

        public Builder excludeTag(String tag) {
            this.excludeTags.add(tag);
            return this;
        }

        public Builder maxFileBytes(long bytes) {
            this.maxFileBytes = Math.max(0, bytes);
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
