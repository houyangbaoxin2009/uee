package org.uee.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.uee.model.ElementKind;

/**
 * Resolved export configuration.
 *
 * <p>Every option is here rather than hardcoded, with a default that is a starting point and not a
 * constraint — the project's standing rule is that options and parameters are configurable.
 *
 * <p>Immutable; build one through {@link Builder}. A config is cheap to copy and is shared by the
 * whole pipeline, including the per-shard encoders.
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

    private final Path outputDir;
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
    private final WikiOptions wiki;

    private ExportConfig(Builder b) {
        this.outputDir = b.outputDir;
        this.formats = Collections.unmodifiableSet(new LinkedHashSet<>(b.formats));
        this.kinds = Collections.unmodifiableSet(b.kinds.isEmpty() ? EnumSet.allOf(ElementKind.class) : b.kinds);
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
        this.wiki = b.wiki;
    }

    public Path outputDir() {
        return outputDir;
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

    public static Builder builder() {
        return new Builder();
    }

    /** Default configuration: NDJSON into {@code exports/}, no icons, sharded per namespace. */
    public static ExportConfig defaults() {
        return builder().build();
    }

    /** Mutable builder with the documented defaults. */
    public static final class Builder {
        private Path outputDir = Path.of("exports");
        private Set<String> formats = new LinkedHashSet<>(Set.of(NDJSON));
        private Set<ElementKind> kinds = EnumSet.noneOf(ElementKind.class);
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
        private WikiOptions wiki = WikiOptions.defaults();

        public Builder outputDir(Path dir) {
            this.outputDir = dir;
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

        public Builder kinds(ElementKind... ks) {
            this.kinds = ks.length == 0 ? EnumSet.noneOf(ElementKind.class) : EnumSet.copyOf(java.util.Arrays.asList(ks));
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

        public Builder wiki(WikiOptions w) {
            this.wiki = w;
            return this;
        }

        public ExportConfig build() {
            return new ExportConfig(this);
        }
    }
}
