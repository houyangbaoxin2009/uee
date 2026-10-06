package org.uee.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.tielang.td.Td;
import org.tielang.td.TdTable;
import org.tielang.td.TdValue;
import org.uee.model.ElementKind;

/**
 * The configuration file, in {@code tie:data} (td) syntax.
 *
 * <p>td is the tie ecosystem's text data format, and the same syntax tiec's {@code config.parse_data}
 * accepts. Using it here rather than inventing a shape means a UEE config is readable by tie tooling
 * and looks like every other config in the family.
 *
 * <p>The file is a <b>partial</b> description: a key that is absent is not the same as a key set to
 * its default. That is what makes the layering work —
 *
 * <ol>
 *   <li>built-in defaults
 *   <li>the config file, overriding only the keys it names
 *   <li>the command line, overriding only the switches it was given
 *   <li>the programmatic call, overriding anything
 * </ol>
 *
 * — so a config file can set three things without silently resetting the other twelve.
 *
 * <p>An unrecognised key is reported rather than ignored. A typo in a config file that quietly does
 * nothing is the worst outcome: the user believes the setting took effect and the export behaves
 * differently from what they asked for, with nothing to point at.
 *
 * <h2>Example</h2>
 * <pre>
 * type tie&lt;data&gt;
 *
 * name = "uee"
 * output = "exports/uee"
 * formats = ["ndjson", "wiki"]
 * kinds = ["common"]
 * analyze = true
 * analysis_separate = false
 * </pre>
 */
public final class ConfigFile {

    /** Conventional file name, placed under the loader's config directory. */
    public static final String FILE_NAME = "uee.data.tie";

    /** Keys this loader understands, for validation and help. */
    public static final String[] KNOWN_KEYS = {
            "output", "package", "formats", "kinds",
            "analyze", "analysis_separate", "package_per_kind", "quiet",
            "fields", "exclude_fields", "dry_run", "include_tags", "exclude_tags",
            "max_file_mb",
            "icons", "pretty", "incremental", "include_paths",
            "shard_by_namespace", "shard_size", "memory_limit_mb", "threads",
            "include_namespaces", "exclude_namespaces", "exclude_mods",
            "wiki_format", "wiki_icons", "wiki_entities", "wiki_recipes", "wiki_blocks_separate",
            "datapacks", "global_datapacks", "global_datapack_dir", "flow", "targets", "strategies"};

    private final Path output;
    private final String packageName;
    private final Set<String> formats;
    private final Set<ElementKind> kinds;
    private final Boolean analyze;
    private final Boolean analysisSeparate;
    private final Boolean packagePerKind;
    private final Boolean quiet;
    private final FieldMask fields;
    private final Boolean dryRun;
    private final Set<String> includeTags;
    private final Set<String> excludeTags;
    private final Integer maxFileMb;
    private final Boolean icons;
    private final Boolean pretty;
    private final Boolean incremental;
    private final Boolean includePaths;
    private final Boolean shardByNamespace;
    private final Integer shardSize;
    private final Integer memoryLimitMb;
    private final Integer threads;
    private final Set<String> includeNamespaces;
    private final Set<String> excludeNamespaces;
    private final Set<String> excludeMods;
    private final Boolean datapacks;
    private final String globalDatapacks;
    private final String globalDatapackDir;
    private final String flow;
    private final Set<String> targets;
    private final Set<String> strategies;
    private final WikiOptions.WikiFormat wikiFormat;
    private final Boolean wikiIcons;
    private final Boolean wikiEntities;
    private final Boolean wikiRecipes;
    private final Boolean wikiBlocksSeparate;
    private final List<String> unknownKeys;

    private ConfigFile(Builder b) {
        this.output = b.output;
        this.packageName = b.packageName;
        this.formats = b.formats;
        this.kinds = b.kinds;
        this.analyze = b.analyze;
        this.analysisSeparate = b.analysisSeparate;
        this.packagePerKind = b.packagePerKind;
        this.quiet = b.quiet;
        // The two field sets are one request: include names what to keep, exclude names what to drop,
        // and either can be given without the other.
        this.fields = b.includeFields == null && b.excludeFields == null
                ? FieldMask.all()
                : FieldMask.of(b.includeFields, b.excludeFields);
        this.dryRun = b.dryRun;
        this.includeTags = b.includeTags;
        this.excludeTags = b.excludeTags;
        this.maxFileMb = b.maxFileMb;
        this.icons = b.icons;
        this.pretty = b.pretty;
        this.incremental = b.incremental;
        this.includePaths = b.includePaths;
        this.shardByNamespace = b.shardByNamespace;
        this.shardSize = b.shardSize;
        this.memoryLimitMb = b.memoryLimitMb;
        this.threads = b.threads;
        this.includeNamespaces = b.includeNamespaces;
        this.excludeNamespaces = b.excludeNamespaces;
        this.excludeMods = b.excludeMods;
        this.datapacks = b.datapacks;
        this.globalDatapacks = b.globalDatapacks;
        this.globalDatapackDir = b.globalDatapackDir;
        this.flow = b.flow;
        this.targets = b.targets;
        this.strategies = b.strategies;
        this.wikiFormat = b.wikiFormat;
        this.wikiIcons = b.wikiIcons;
        this.wikiEntities = b.wikiEntities;
        this.wikiRecipes = b.wikiRecipes;
        this.wikiBlocksSeparate = b.wikiBlocksSeparate;
        this.unknownKeys = List.copyOf(b.unknownKeys);
    }

    /** An empty description: applying it changes nothing. */
    public static ConfigFile empty() {
        return new Builder().build();
    }

    /**
     * The flow this description came from, or {@code null}.
     *
     * <p>Exposed because expanding a named flow happens one layer up, where the flow's own description
     * becomes the layer beneath the caller's arguments. Without this the caller would have to parse the
     * flow name twice.
     */
    public String flow() {
        return flow;
    }

    /**
     * Every key this build understands.
     *
     * <p>Exposed so a caller can check that what it produces is readable — the command surface uses it
     * to assert that every setting it can make is also one a config file can express, which is what
     * keeps the two interfaces the same interface.
     */
    public static java.util.Set<String> knownKeys() {
        return java.util.Set.of(KNOWN_KEYS);
    }

    /** Keys found in the file that this build does not understand. */
    public List<String> unknownKeys() {
        return unknownKeys;
    }

    /**
     * The keys this description actually sets, in canonical order.
     *
     * <p>Used to report what a layer will change before it is applied — which is the difference
     * between a user understanding a flow and a user trusting one. Derived from the fields rather
     * than stored, so it cannot fall out of step with what the description does.
     */
    public Set<String> setKeys() {
        Set<String> keys = new LinkedHashSet<>();
        if (output != null) {
            keys.add("output");
        }
        if (packageName != null) {
            keys.add("package");
        }
        if (formats != null) {
            keys.add("formats");
        }
        if (kinds != null) {
            keys.add("kinds");
        }
        if (analyze != null) {
            keys.add("analyze");
        }
        if (analysisSeparate != null) {
            keys.add("analysis_separate");
        }
        if (packagePerKind != null) {
            keys.add("package_per_kind");
        }
        if (quiet != null) {
            keys.add("quiet");
        }
        if (fields != null && !fields.isAll()) {
            // Asked of the mask rather than of the reference: the field is derived, so it is never
            // null and "is it set" has to mean "does it constrain anything".
            keys.add("fields");
        }
        if (dryRun != null) {
            keys.add("dry_run");
        }
        if (includeTags != null) {
            keys.add("include_tags");
        }
        if (excludeTags != null) {
            keys.add("exclude_tags");
        }
        if (maxFileMb != null) {
            keys.add("max_file_mb");
        }
        if (icons != null) {
            keys.add("icons");
        }
        if (pretty != null) {
            keys.add("pretty");
        }
        if (incremental != null) {
            keys.add("incremental");
        }
        if (includePaths != null) {
            keys.add("include_paths");
        }
        if (shardByNamespace != null) {
            keys.add("shard_by_namespace");
        }
        if (shardSize != null) {
            keys.add("shard_size");
        }
        if (memoryLimitMb != null) {
            keys.add("memory_limit_mb");
        }
        if (threads != null) {
            keys.add("threads");
        }
        if (includeNamespaces != null) {
            keys.add("include_namespaces");
        }
        if (excludeNamespaces != null) {
            keys.add("exclude_namespaces");
        }
        if (excludeMods != null) {
            keys.add("exclude_mods");
        }
        if (wikiFormat != null || wikiIcons != null || wikiEntities != null || wikiRecipes != null
                || wikiBlocksSeparate != null) {
            keys.add("wiki");
        }
        if (datapacks != null) {
            keys.add("datapacks");
        }
        if (globalDatapacks != null) {
            keys.add("global_datapacks");
        }
        if (globalDatapackDir != null) {
            keys.add("global_datapack_dir");
        }
        if (flow != null) {
            keys.add("flow");
        }
        if (targets != null) {
            keys.add("targets");
        }
        if (strategies != null) {
            keys.add("strategies");
        }
        return Collections.unmodifiableSet(keys);
    }

    public boolean isEmpty() {
        // Unknown keys count as content. A file whose every key is a typo sets no values, but it is
        // not "no description" — it is a description that failed to be understood, and treating it as
        // empty would drop it along with the warning that would have explained why nothing happened.
        if (!unknownKeys.isEmpty()) {
            return false;
        }
        return output == null && packageName == null && formats == null && kinds == null
                && analyze == null && analysisSeparate == null && packagePerKind == null
                && quiet == null && (fields == null || fields.isAll()) && dryRun == null
                && includeTags == null && excludeTags == null && maxFileMb == null
                && icons == null && pretty == null && incremental == null && includePaths == null
                && shardByNamespace == null && shardSize == null && memoryLimitMb == null
                && threads == null && includeNamespaces == null && excludeNamespaces == null
                && excludeMods == null && wikiFormat == null && wikiIcons == null
                && wikiEntities == null && wikiRecipes == null && wikiBlocksSeparate == null
                && datapacks == null && globalDatapacks == null && globalDatapackDir == null
                && flow == null && targets == null && strategies == null;
    }

    // ---------------------------------------------------------------- layering

    /**
     * Applies this description onto a base config: named keys win, absent keys are left alone.
     *
     * @param base the config to layer onto, typically the built-in defaults
     */
    public ExportConfig applyTo(ExportConfig base) {
        ExportConfig.Builder b = base.toBuilder();
        if (output != null) {
            b.outputDir(output);
        }
        if (packageName != null) {
            b.packageName(packageName);
        }
        if (formats != null) {
            b.formats(formats.toArray(new String[0]));
        }
        if (kinds != null) {
            b.kinds(kinds.toArray(new ElementKind[0]));
        }
        if (analyze != null) {
            b.analyze(analyze);
        }
        if (analysisSeparate != null) {
            b.analysisSeparate(analysisSeparate);
        }
        if (packagePerKind != null) {
            b.packagePerKind(packagePerKind);
        }
        if (quiet != null) {
            b.quiet(quiet);
        }
        if (fields != null) {
            b.fields(fields);
        }
        if (dryRun != null) {
            b.dryRun(dryRun);
        }
        if (includeTags != null) {
            for (String tag : includeTags) {
                b.includeTag(tag);
            }
        }
        if (excludeTags != null) {
            for (String tag : excludeTags) {
                b.excludeTag(tag);
            }
        }
        if (maxFileMb != null) {
            b.maxFileBytes(maxFileMb * 1024L * 1024L);
        }
        if (icons != null) {
            b.icons(icons);
        }
        if (pretty != null) {
            b.pretty(pretty);
        }
        if (incremental != null) {
            b.incremental(incremental);
        }
        if (includePaths != null) {
            b.includePaths(includePaths);
        }
        if (shardByNamespace != null) {
            b.shardByNamespace(shardByNamespace);
        }
        if (shardSize != null) {
            b.shardSize(shardSize);
        }
        if (memoryLimitMb != null) {
            b.memoryLimitBytes(memoryLimitMb * 1024L * 1024L);
        }
        if (threads != null && threads > 0) {
            // Zero means "leave it to the default", which is the auto-sizing rule. Treating it as a
            // literal request for zero workers would clamp to one and quietly serialise the export.
            b.threads(threads);
        }
        if (includeNamespaces != null) {
            for (String ns : includeNamespaces) {
                b.includeNamespace(ns);
            }
        }
        if (excludeNamespaces != null) {
            for (String ns : excludeNamespaces) {
                b.excludeNamespace(ns);
            }
        }
        if (excludeMods != null) {
            for (String id : excludeMods) {
                b.excludeMod(id);
            }
        }
        if (datapacks != null) {
            b.datapacks(datapacks);
        }
        if (globalDatapacks != null) {
            b.globalDatapacks(globalDatapacks);
        }
        if (globalDatapackDir != null) {
            b.globalDatapackDir(globalDatapackDir);
        }
        if (flow != null) {
            b.flow(flow);
        }
        if (targets != null) {
            b.targets(targets);
        }
        if (strategies != null) {
            b.strategies(strategies);
        }
        if (wikiFormat != null || wikiIcons != null || wikiEntities != null || wikiRecipes != null
                || wikiBlocksSeparate != null) {
            WikiOptions.Builder w = base.wiki().toBuilder().enabled(true);
            if (wikiFormat != null) {
                w.format(wikiFormat);
            }
            if (wikiIcons != null) {
                w.icons(wikiIcons);
            }
            if (wikiEntities != null) {
                w.includeEntities(wikiEntities);
            }
            if (wikiRecipes != null) {
                w.includeRecipes(wikiRecipes);
            }
            if (wikiBlocksSeparate != null) {
                w.includeBlocksAsSeparateFile(wikiBlocksSeparate);
            }
            b.wiki(w.build());
        }
        return b.build();
    }

    // ---------------------------------------------------------------- reading

    /** Reads a config file, or returns an empty description when the file does not exist. */
    public static ConfigFile read(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return empty();
        }
        // The file is a configuration, not data on the export path, so it is read whole. It is
        // bounded by its own size rather than by pack size, which is the distinction that matters.
        return parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    /** Reads the standard config file under a config directory. */
    public static ConfigFile readFrom(Path configDir) throws IOException {
        return configDir == null ? empty() : read(configDir.resolve(FILE_NAME));
    }

    /**
     * Parses a td document.
     *
     * <p>The syntax is tie's own, unextended. Comments are {@code //} because that is what td defines
     * and what every other tie file uses; adding a second spelling would make a UEE config a dialect
     * that only UEE reads, which defeats the point of choosing the ecosystem's format.
     *
     * @throws IllegalArgumentException when the document is not valid td, or names an unknown
     *     category or format
     */
    public static ConfigFile parse(String tdText) {
        if (tdText == null || tdText.isBlank()) {
            return empty();
        }
        TdTable root = Td.parse(tdText);
        Builder b = new Builder();

        for (String key : root.keys()) {
            TdValue value = root.get(key);
            switch (key.toLowerCase(java.util.Locale.ROOT)) {
                case "output" -> b.output = path(value.asString());
                case "package" -> b.packageName = value.asString();
                case "formats" -> {
                    Set<String> formats = Tokens.formats(strings(value));
                    if (formats == null) {
                        throw new IllegalArgumentException("unknown format in 'formats': " + value);
                    }
                    b.formats = formats;
                }
                case "kinds" -> {
                    Set<ElementKind> kinds = Tokens.kinds(strings(value));
                    if (kinds == null) {
                        throw new IllegalArgumentException("unknown category in 'kinds': " + value);
                    }
                    b.kinds = kinds;
                }
                case "analyze" -> b.analyze = value.asBool();
                case "analysis_separate" -> b.analysisSeparate = value.asBool();
                case "package_per_kind" -> b.packagePerKind = value.asBool();
                case "quiet" -> b.quiet = value.asBool();
                case "fields" -> b.includeFields = new LinkedHashSet<>(strings(value));
                case "exclude_fields" -> b.excludeFields = new LinkedHashSet<>(strings(value));
                case "dry_run" -> b.dryRun = value.asBool();
                case "include_tags" -> b.includeTags = new LinkedHashSet<>(strings(value));
                case "exclude_tags" -> b.excludeTags = new LinkedHashSet<>(strings(value));
                case "max_file_mb" -> b.maxFileMb = (int) value.asInt();
                case "icons" -> b.icons = value.asBool();
                case "pretty" -> b.pretty = value.asBool();
                case "incremental" -> b.incremental = value.asBool();
                case "include_paths" -> b.includePaths = value.asBool();
                case "shard_by_namespace" -> b.shardByNamespace = value.asBool();
                case "shard_size" -> b.shardSize = (int) value.asInt();
                case "memory_limit_mb" -> b.memoryLimitMb = (int) value.asInt();
                case "threads" -> b.threads = (int) value.asInt();
                case "include_namespaces" -> b.includeNamespaces = new LinkedHashSet<>(strings(value));
                case "exclude_namespaces" -> b.excludeNamespaces = new LinkedHashSet<>(strings(value));
                case "exclude_mods" -> b.excludeMods = new LinkedHashSet<>(strings(value));
                case "datapacks" -> b.datapacks = value.asBool();
                case "global_datapacks" -> b.globalDatapacks = value.asString();
                case "global_datapack_dir" -> b.globalDatapackDir = value.asString();
                case "flow" -> b.flow = value.asString();
                case "targets" -> b.targets = new LinkedHashSet<>(strings(value));
                case "strategies" -> b.strategies = new LinkedHashSet<>(strings(value));
                case "wiki_format" -> b.wikiFormat = wikiFormat(value.asString());
                case "wiki_icons" -> b.wikiIcons = value.asBool();
                case "wiki_entities" -> b.wikiEntities = value.asBool();
                case "wiki_recipes" -> b.wikiRecipes = value.asBool();
                case "wiki_blocks_separate" -> b.wikiBlocksSeparate = value.asBool();
                default -> b.unknownKeys.add(key);
            }
        }
        return b.build();
    }

    /** Reads a string list, tolerating a single scalar where a one-element list was meant. */
    private static List<String> strings(TdValue value) {
        List<String> out = new ArrayList<>(4);
        if (value instanceof TdTable table) {
            for (TdValue element : table.elements()) {
                out.add(element.asString());
            }
            // A table with named entries is not a list; treat its keys as the tokens, which is what a
            // user writing {ndjson, wiki} rather than ["ndjson","wiki"] most likely meant.
            if (out.isEmpty()) {
                out.addAll(table.keys());
            }
        } else {
            String single = value.asString();
            if (!single.isEmpty()) {
                out.add(single);
            }
        }
        return out;
    }

    private static Path path(String s) {
        return s == null || s.isEmpty() ? null : Path.of(s);
    }

    private static WikiOptions.WikiFormat wikiFormat(String s) {
        return "v1".equalsIgnoreCase(s) ? WikiOptions.WikiFormat.V1 : WikiOptions.WikiFormat.V2;
    }

    // ---------------------------------------------------------------- writing

    /**
     * Renders the current config as a td document.
     *
     * <p>Written through td-java's own writer rather than assembled by hand, so the file this produces
     * is by construction one that {@link #parse} accepts back.
     */
    public static String render(ExportConfig config) {
        TdTable.Builder b = TdTable.builder();
        b.put("output", portablePath(config.outputDir()));
        b.put("package", config.packageName());
        b.put("formats", stringList(config.formats()));
        b.put("kinds", kindList(config.kinds()));
        b.put("analyze", TdValue.of(config.analyze()));
        b.put("analysis_separate", TdValue.of(config.analysisSeparate()));
        b.put("package_per_kind", TdValue.of(config.packagePerKind()));
        b.put("icons", TdValue.of(config.icons()));
        b.put("pretty", TdValue.of(config.pretty()));
        b.put("incremental", TdValue.of(config.incremental()));
        b.put("include_paths", TdValue.of(config.includePaths()));
        b.put("shard_by_namespace", TdValue.of(config.shardByNamespace()));
        b.put("shard_size", TdValue.of((long) config.shardSize()));
        b.put("memory_limit_mb", TdValue.of(config.memoryLimitBytes() / (1024 * 1024)));
        b.put("threads", TdValue.of((long) config.threads()));
        b.put("include_namespaces", stringList(config.includeNamespaces()));
        b.put("exclude_namespaces", stringList(config.excludeNamespaces()));
        b.put("exclude_mods", stringList(config.excludeMods()));
        b.put("wiki_format",
                config.wiki().format() == WikiOptions.WikiFormat.V1 ? "v1" : "v2");
        b.put("wiki_icons", TdValue.of(config.wiki().icons()));
        b.put("wiki_entities", TdValue.of(config.wiki().includeEntities()));
        b.put("wiki_recipes", TdValue.of(config.wiki().includeRecipes()));
        b.put("wiki_blocks_separate", TdValue.of(config.wiki().includeBlocksAsSeparateFile()));
        return Td.write(b.build());
    }

    /**
     * Writes the current config to a file, creating parent directories.
     *
     * <p>Writes the annotated form. A generated config with no explanation of its keys is barely
     * better than none, and comments are the reason a comment-capable format was chosen over JSON in
     * the first place — so the form that keeps them is the form that gets written.
     */
    public static void write(ExportConfig config, Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, renderAnnotated(config).getBytes(StandardCharsets.UTF_8));
    }

    private static TdValue stringList(Set<String> values) {
        TdTable.Builder b = TdTable.builder();
        for (String v : values) {
            b.element(TdValue.str(v));
        }
        return b.build();
    }

    /** Categories render as their plural token, which is what {@link Tokens} accepts back. */
    private static TdValue kindList(Set<ElementKind> kinds) {
        TdTable.Builder b = TdTable.builder();
        if (kinds.size() == ElementKind.values().length) {
            b.element(TdValue.str(Tokens.ALL));
        } else {
            for (ElementKind k : kinds) {
                b.element(TdValue.str(k.plural()));
            }
        }
        return b.build();
    }

    /** A commented template with every key at its default, for first-run setup. */
    public static String template() {
        return TEMPLATE;
    }

    /**
     * The commented template.
     *
     * <p>Comments sit at the end of the line they document, not above it. td is a flat list of
     * assignments, so an own-line comment between two keys is genuinely ambiguous about which one it
     * describes — and a reader who guesses wrong is misled by the documentation rather than informed
     * by it. Trailing comments cannot be misread.
     *
     * <p>Everything here is valid to {@link #parse}: {@code //} is td's comment, and the
     * {@code type tie<data>} header is stripped by the td reader.
     */
    private static final String TEMPLATE = """
            // UEE 配置 / UEE configuration
            //
            // 用 tie:data（td）语法，与 tie 生态其它文件一致；注释用 //。
            // Written in tie:data (td) syntax like every other tie file; comments use //.
            //
            // 只写想改的键即可：写了的覆盖，没写的保持默认。
            // Partial by design: the keys you name override, the rest keep their defaults.
            //
            // 改完用 /uee reload 生效，不必重启 / reload with /uee reload, no restart needed.
            //
            // 顶层必须是一个表：td 的文档本身就是一个表，所以键写在 uee = [ ... ] 里。
            // The top level must be a table: a td document is one table, so the keys live inside
            // uee = [ ... ].

            type tie<data>

            uee = [
                // ─── 输出在哪 / where ──────────────────────────────────────────────
                output = "exports/uee"          // 输出根目录，相对游戏目录 / output root, relative to the game dir
                package = "uee-export"          // 本次导出的包名 / name of this export bundle

                // ─── 输出什么 / what ───────────────────────────────────────────────
                // 格式可多选 / any number of formats: json ndjson wiki td zd yaml toml xml
                //   json    通用分组文档 / grouped document, readable anywhere
                //   ndjson  逐行一条，流式友好；百科导入端吃这个形状 / one record per line, streaming
                //   wiki    百科投影，导入端直接可用 / the projection an importer consumes directly
                formats = ["json"]

                // 内容类别 / content categories
                //   组词 groups: all 全部 · data 全部数据 · analysis 仅分析 · common 常用
                //   单项 tokens: mods items blocks entities recipes effects fluids enchantments
                //                damage_types biomes dimensions structures sounds particles
                //                attributes creative_tabs namespaces dependencies conflicts mixins
                kinds = ["common"]

                // ─── 分析与布局 / analysis and layout ──────────────────────────────
                // 默认布局 = n 个数据包 + 1 个分析包：每个选中的类目各自成包，分析另成一包，
                // 这样数据那半可以单独交给下游，不必带着诊断一起走。
                // Default = n data packages plus one analysis package: each selected category becomes
                // a package, the analysis one more, so the data half can be handed on by itself.
                analyze = true                  // 是否分析 / run the analysis
                analysis_separate = true        // 分析独立成包，与数据分开 / keep the analysis in its own bundle
                package_per_kind = true         // 每个类目一个子目录 / one sub-directory per category
                quiet = false                   // 静默：只留汇总行。供函数/脚本调用时用 / quiet: summary only, for functions and scripts

                // ─── 记录内容与筛选 / what goes in a record ───────────────────────────
                // 只保留这些字段 / keep only these fields (identity fields are always kept):
                //   translationKey maxDurability tags creativeTabs blockItem
                //   hardness blastResistance lightEmission hasBlockItem material category
                fields = []
                // 反向写法 / or name what to drop:
                exclude_fields = []
                // 字段投影只作用于 json ndjson td yaml toml xml；
                // 百科投影(zk)与 zd 不受影响——它们的字段名是对外契约 / a schema.
                // Projection applies to json ndjson td yaml toml xml only.
                // 留空 = 全字段。省略掉标识字段（如 registryName）的请求会被忽略。
                // Empty means every field; a request to drop an identity field is ignored.

                // 只导出带这些标签的元素 / export only elements carrying one of these tags
                include_tags = []
                // 这些标签的元素跳过 / elements carrying these are skipped
                exclude_tags = []

                // 试运行：不写任何文件，但仍然给出真实的文件清单与记录数
                // Dry run: write nothing, but report the real file set and record counts
                dry_run = false

                // 关于 tags 类目 / about the tags category:
                //   记录 data/<命名空间>/tags/<类型>/<路径>.json 中「声明」的成员，含以井号开头的
                //   嵌套标签；嵌套标签只记录不展开（展开要解析注册表并检测环，且会把「包写了什么」
                //   换成「算出来什么」）。
                //   多包同名标签的合并遵循原版 TagLoader：按包优先级叠加，某包声明 replace 时清空其下。
                //   Members are recorded as declared, nested tags included but not expanded, and merged
                //   the way vanilla's TagLoader does: stacked by pack priority, where a pack's replace
                //   flag discards everything below it.
                //
                // 关于 loot_tables 类目 / about the loot_tables category:
                //   记录 data/<命名空间>/loot_table/<路径>.json 产出的物品，以及它引用的标签与其它表
                //   （引用只记录不展开）。战利品表是「单份胜出」：同一 id 后加载的包整份替换前面的，
                //   与标签的合并规则不同——这是读原版加载路径得到的结论。
                //   Records the item ids a table produces, plus the tags and other tables it references
                //   (referenced, not expanded). Unlike a tag, a table is single-winner: a pack shipping
                //   the same id replaces the one below rather than adding to it.
                //
                // 关于 advancements 类目 / about the advancements category:
                //   记录 data/<命名空间>/advancement/<路径>.json 的父节点（前置）、触发条件与显示信息。
                //   ★ 原版 1400 个进度里只有约一成带 display，其余是技术性节点 ⇒ 没有 display 是常态，
                //   既不丢弃该记录、也不当失败。
                //   Records the parent (the prerequisite), the triggers and the display fields. Only about
                //   a tenth of the shipped advancements carry a display; the rest are invisible by design,
                //   so a missing display is normal rather than damaged and such a record is kept.
                //
                // 关于 worldgen 类目 / about the worldgen category:
                //   记录 data/<命名空间>/worldgen/<种类>/<名字>.json —— 十四种文件共用一个类目，记录里带
                //   种类、类型与它引用的 id。按「包写了什么」采集，不是「游戏加载了什么」 ⇒ 专用服务器上
                //   同样有效。不在默认集：体量大、对百科价值低，且生物群系与结构另有注册表类目给出后者。
                //   Records data/<namespace>/worldgen/<kind>/<name>.json -- fourteen kinds of file behind
                //   one category, each record carrying its kind, its type and the ids it references. Read
                //   as what the pack wrote rather than what the game loaded, so it works on a dedicated
                //   server too. Not in the default set: it is large, of little use to a wiki, and biomes
                //   and structures have registry categories that give the loaded answer.
                //
                // 关于 langs 类目 / about the langs category:
                //   记录 assets/<命名空间>/lang/<语言>.json 的键值表（zh_cn 与 en_us 各一条记录）。
                //   ★ 语言文件在 assets/ 下，专用服务器的资源管理器看不到它 ⇒ 该类别只在客户端有效，
                //   服务器上会明确报告而不是静默为空。
                //   Records the key/value tables under assets/<namespace>/lang/ (one record per locale,
                //   zh_cn and en_us). Language files live under assets/, which a dedicated server's
                //   resource manager cannot see, so this works on a client and reports rather than going
                //   quiet on a server.

                // ─── 采集 / collection ─────────────────────────────────────────────
                icons = false                   // 图标渲染，较慢 / render icons, slow
                pretty = false                  // 美化缩进，仅 json / pretty-print, json only
                include_paths = false           // 记录容器全路径；产物要发布，默认关 / full container paths; off
                shard_by_namespace = true       // 按命名空间分片 / shard per namespace
                shard_size = 20000              // 每片元素上限 / elements per shard
                // 按字节切分（优先于 shard_size）。0 = 不按字节切；整条记录不会跨文件。
                // Split by size, taking precedence over shard_size. 0 disables it; a record never
                // spans two files, so a part may overshoot by one record.
                max_file_mb = 0
                // 单个分片缓冲区的上限（不是整次运行的内存上限！）。
                // 实际占用由「活跃分片数 × 该值」决定，而缓冲区远早于此就被刷写，
                // 所以实测保留集只有几百 KiB，与包大小无关（见 ScaleTest）。
                // A per-shard buffer ceiling, NOT a ceiling on the run. Memory is
                // live-shards x this, and buffers flush far sooner, so this is a valve
                // rather than an operating point.
                memory_limit_mb = 256
                threads = 0                     // 并发度，0 = 自动 / worker threads, 0 = auto

                // ─── 过滤 / filters ────────────────────────────────────────────────
                // include 为空 = 全部；exclude 优先 / empty include means all; exclude wins
                include_namespaces = []
                exclude_namespaces = []
                exclude_mods = []

                // ─── 数据驱动 / data-driven ───────────────────────────────────────
                // 数据包可定义流程、采集对象、分析策略，放在 data/<命名空间>/uee/ 下。
                // A datapack can define flows, collection targets and analysis strategies under
                // data/<namespace>/uee/.
                datapacks = true                // 是否读取数据包定义 / read datapack definitions
                flow = ""                       // 跟随某条流程；空 = 不指定 / follow a flow; empty = none
                targets = []                    // 启用哪些采集对象 / which collection targets to write
                strategies = []                 // 运行哪些分析策略 / which analysis strategies to run

                // 全局数据包 / global datapacks
                //   auto = 别人提供了就让位（默认） / stand down if another mod provides them
                //   on   = 总是提供，即使别人也有 / always provide, even alongside another mod
                //   off  = 从不提供 / never provide
                // 让位是有意的：两个模组各自加载同一批数据包会重复注册。
                // Standing down is deliberate: two mods registering the same packs would double-load.
                global_datapacks = "auto"
                global_datapack_dir = ""        // 空 = 用默认目录 config/uee/datapacks / empty = default

                // ─── 百科投影 / wiki projection ────────────────────────────────────
                // v2 = 当前主流导出器 · v1 = 旧一代，字段集不同
                // v2 = the current exporter lineage · v1 = the previous one, different field set
                wiki_format = "v2"
                wiki_icons = false
                wiki_entities = true
                wiki_recipes = true
                wiki_blocks_separate = false
            ]
            """;

    /**
     * Rewrites nothing.
     *
     * <p>Kept as a named no-op so the intent is on the record rather than implied by absence: the td
     * syntax is used exactly as tie defines it, deliberately without the conveniences other config
     * formats offer (a second comment character, section headers, unquoted strings). A config that
     * only UEE can read would be a worse outcome than a config that needs one extra character.
     */
    static String asTieSyntax(String text) {
        return text;
    }

    /**
     * Renders the effective configuration as td <b>with the explanatory comments kept</b>.
     *
     * <p>This is what {@code /uee config save} writes and what {@code /uee config template} shows.
     * Plain {@link #render} output would be structurally identical but carry no guidance, and a
     * generated config nobody can read is barely better than no config file — which is the whole
     * reason a comment-capable format was picked in the first place.
     *
     * <p>Implemented by patching values into the commented template rather than by serialising the
     * model, so the comments and their ordering are preserved exactly and cannot fall out of step
     * with the template.
     */
    public static String renderAnnotated(ExportConfig config) {
        Map<String, String> values = effectiveValues(config);
        StringBuilder out = new StringBuilder(TEMPLATE.length() + 256);
        for (String line : TEMPLATE.split("\n", -1)) {
            String stripped = line.stripLeading();
            if (stripped.isEmpty() || stripped.startsWith("//") || stripped.startsWith("type ")) {
                out.append(line).append('\n');
                continue;
            }
            int eq = stripped.indexOf('=');
            if (eq < 0) {
                // Table delimiters ('[' / ']') and anything else without an assignment.
                out.append(line).append('\n');
                continue;
            }
            String key = stripped.substring(0, eq).trim();
            String replacement = values.get(key);
            if (replacement == null) {
                out.append(line).append('\n');
                continue;
            }
            // The comment after the value is the documentation for this key, so it has to come
            // through intact. Patching only the value and dropping the tail would produce a
            // correctly-configured file that no longer explains itself.
            String comment = trailingComment(stripped.substring(eq + 1));
            String indent = line.substring(0, line.length() - stripped.length());
            String head = indent + key + " = " + replacement;
            out.append(head);
            if (!comment.isEmpty()) {
                // Align comments to a fixed column so the file stays scannable whatever the value
                // lengths turn out to be.
                int pad = Math.max(2, COMMENT_COLUMN - head.length());
                out.append(" ".repeat(pad)).append(comment);
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Column the trailing comments are aligned to, so the file stays scannable. */
    private static final int COMMENT_COLUMN = 44;

    /**
     * Returns the {@code //} comment at the end of a value, or an empty string.
     *
     * <p>Scans with quote awareness so a {@code //} inside a quoted value is not mistaken for the
     * start of a comment.
     */
    private static String trailingComment(String afterEquals) {
        boolean inString = false;
        for (int i = 0; i < afterEquals.length() - 1; i++) {
            char c = afterEquals.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '/' && afterEquals.charAt(i + 1) == '/') {
                return afterEquals.substring(i);
            }
        }
        return "";
    }

    /** The td text for each key, as it should appear in a written file. */
    private static Map<String, String> effectiveValues(ExportConfig config) {
        Map<String, String> v = new java.util.LinkedHashMap<>();
        v.put("output", quote(portablePath(config.outputDir())));
        v.put("package", quote(config.packageName()));
        v.put("formats", list(config.formats()));
        v.put("kinds", kindListText(config.kinds()));
        v.put("analyze", Boolean.toString(config.analyze()));
        v.put("analysis_separate", Boolean.toString(config.analysisSeparate()));
        v.put("package_per_kind", Boolean.toString(config.packagePerKind()));
        v.put("quiet", Boolean.toString(config.quiet()));
        v.put("fields", config.fields().include().isEmpty()
                ? "[]" : list(config.fields().include()));
        v.put("exclude_fields", list(config.fields().exclude()));
        v.put("dry_run", Boolean.toString(config.dryRun()));
        v.put("include_tags", list(config.includeTags()));
        v.put("exclude_tags", list(config.excludeTags()));
        v.put("max_file_mb", Long.toString(config.maxFileBytes() / (1024 * 1024)));
        v.put("icons", Boolean.toString(config.icons()));
        v.put("pretty", Boolean.toString(config.pretty()));
        v.put("include_paths", Boolean.toString(config.includePaths()));
        v.put("shard_by_namespace", Boolean.toString(config.shardByNamespace()));
        v.put("shard_size", Integer.toString(config.shardSize()));
        v.put("memory_limit_mb", Long.toString(config.memoryLimitBytes() / (1024 * 1024)));
        v.put("threads", Integer.toString(config.threads()));
        v.put("include_namespaces", list(config.includeNamespaces()));
        v.put("exclude_namespaces", list(config.excludeNamespaces()));
        v.put("exclude_mods", list(config.excludeMods()));
        v.put("datapacks", Boolean.toString(config.datapacks()));
        v.put("global_datapacks", quote(config.globalDatapacks()));
        v.put("global_datapack_dir", config.globalDatapackDir() == null
                ? "\"\"" : quote(config.globalDatapackDir()));
        v.put("flow", config.flow() == null ? "\"\"" : quote(config.flow()));
        v.put("targets", list(config.targets()));
        v.put("strategies", list(config.strategies()));
        v.put("wiki_format", quote(config.wiki().format() == WikiOptions.WikiFormat.V1 ? "v1" : "v2"));
        v.put("wiki_icons", Boolean.toString(config.wiki().icons()));
        v.put("wiki_entities", Boolean.toString(config.wiki().includeEntities()));
        v.put("wiki_recipes", Boolean.toString(config.wiki().includeRecipes()));
        v.put("wiki_blocks_separate", Boolean.toString(config.wiki().includeBlocksAsSeparateFile()));
        return v;
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * Renders a path with forward slashes.
     *
     * <p>A config file gets shared and copied between machines, so it must not carry the separator of
     * whichever platform wrote it. {@code Path} normalises separators on ingest, so a forward-slash
     * path is read correctly everywhere, whereas a backslash path written on Windows would be an
     * escape sequence on any other platform.
     */
    private static String portablePath(Path p) {
        return p.toString().replace('\\', '/');
    }

    private static String list(Set<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(quote(v));
            first = false;
        }
        return sb.append(']').toString();
    }

    /** Categories render as plural tokens, except the full set which renders as the group token. */
    private static String kindListText(Set<ElementKind> kinds) {
        if (kinds.size() == ElementKind.values().length) {
            return "[\"" + Tokens.ALL + "\"]";
        }
        if (kinds.equals(Tokens.commonKinds())) {
            return "[\"" + Tokens.COMMON + "\"]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ElementKind k : kinds) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(quote(k.plural()));
            first = false;
        }
        return sb.append(']').toString();
    }

    /** A builder for a partial description, as the command surface produces. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder, used by {@link #parse} and by callers assembling a description programmatically. */
    public static final class Builder {
        private Path output;
        private String packageName;
        private Set<String> formats;
        private Set<ElementKind> kinds;
        private Boolean analyze;
        private Boolean analysisSeparate;
        private Boolean packagePerKind;
        private Boolean quiet;
        private Set<String> includeFields;
        private Set<String> excludeFields;
        private Boolean dryRun;
        private Set<String> includeTags;
        private Set<String> excludeTags;
        private Integer maxFileMb;
        private Boolean icons;
        private Boolean pretty;
        private Boolean incremental;
        private Boolean includePaths;
        private Boolean shardByNamespace;
        private Integer shardSize;
        private Integer memoryLimitMb;
        private Integer threads;
        private Set<String> includeNamespaces;
        private Set<String> excludeNamespaces;
        private Set<String> excludeMods;
        private Boolean datapacks;
        private String globalDatapacks;
        private String globalDatapackDir;
        private String flow;
        private Set<String> targets;
        private Set<String> strategies;
        private WikiOptions.WikiFormat wikiFormat;
        private Boolean wikiIcons;
        private Boolean wikiEntities;
        private Boolean wikiRecipes;
        private Boolean wikiBlocksSeparate;
        private final List<String> unknownKeys = new ArrayList<>(2);

        public Builder output(Path p) {
            this.output = p;
            return this;
        }

        public Builder packageName(String n) {
            this.packageName = n;
            return this;
        }

        public Builder formats(Set<String> f) {
            this.formats = f;
            return this;
        }

        public Builder kinds(Set<ElementKind> k) {
            this.kinds = k;
            return this;
        }

        public Builder analyze(boolean on) {
            this.analyze = on;
            return this;
        }

        public Builder icons(boolean on) {
            this.icons = on;
            return this;
        }

        public Builder includePaths(boolean on) {
            this.includePaths = on;
            return this;
        }

        public Builder excludeMod(String id) {
            if (excludeMods == null) {
                excludeMods = new LinkedHashSet<>();
            }
            excludeMods.add(id);
            return this;
        }

        public Builder includeNamespace(String ns) {
            if (includeNamespaces == null) {
                includeNamespaces = new LinkedHashSet<>();
            }
            includeNamespaces.add(ns);
            return this;
        }

        public Builder excludeNamespace(String ns) {
            if (excludeNamespaces == null) {
                excludeNamespaces = new LinkedHashSet<>();
            }
            excludeNamespaces.add(ns);
            return this;
        }

        public Builder analysisSeparate(boolean on) {
            this.analysisSeparate = on;
            return this;
        }

        public Builder quiet(boolean on) {
            this.quiet = on;
            return this;
        }

        public Builder fields(FieldMask mask) {
            if (mask != null) {
                this.includeFields = new LinkedHashSet<>(mask.include());
                this.excludeFields = new LinkedHashSet<>(mask.exclude());
            }
            return this;
        }

        public Builder includeFields(Set<String> names) {
            this.includeFields = names;
            return this;
        }

        public Builder excludeFields(Set<String> names) {
            this.excludeFields = names;
            return this;
        }

        public Builder dryRun(boolean on) {
            this.dryRun = on;
            return this;
        }

        public Builder includeTags(Set<String> tags) {
            this.includeTags = tags;
            return this;
        }

        public Builder excludeTags(Set<String> tags) {
            this.excludeTags = tags;
            return this;
        }

        public Builder maxFileMb(int mb) {
            this.maxFileMb = mb;
            return this;
        }

        /**
         * The remaining keys, which the parser could set but no caller could.
         *
         * <p>The parse path assigns these fields directly, being inside the same class — so they
         * worked from a file and were unreachable from code. That was invisible until the command
         * surface tried to build a layer for {@code /uee set shards} and found there was no setter
         * for it, which is a gap worth closing rather than working around.
         */
        public Builder shardSize(int records) {
            this.shardSize = records;
            return this;
        }

        public Builder shardByNamespace(boolean on) {
            this.shardByNamespace = on;
            return this;
        }

        public Builder memoryLimitMb(int mb) {
            this.memoryLimitMb = mb;
            return this;
        }

        public Builder threads(int n) {
            this.threads = n;
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

        public Builder packagePerKind(boolean on) {
            this.packagePerKind = on;
            return this;
        }

        public Builder datapacks(boolean on) {
            this.datapacks = on;
            return this;
        }

        public Builder globalDatapacks(String mode) {
            this.globalDatapacks = mode;
            return this;
        }

        public Builder globalDatapackDir(String dir) {
            this.globalDatapackDir = dir;
            return this;
        }

        public Builder flow(String id) {
            this.flow = id;
            return this;
        }

        public Builder targets(Set<String> ids) {
            this.targets = new LinkedHashSet<>(ids);
            return this;
        }

        public Builder strategies(Set<String> ids) {
            this.strategies = new LinkedHashSet<>(ids);
            return this;
        }

        public ConfigFile build() {
            return new ConfigFile(this);
        }
    }

    /** Category set used for rendering; kept accessible for tests. */
    static Set<ElementKind> allKinds() {
        return EnumSet.allOf(ElementKind.class);
    }
}
