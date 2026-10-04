package org.uee.write;

import org.uee.config.ExportConfig;
import org.uee.util.StringPool;

/**
 * Creates the writer for an output format token.
 *
 * <p>A single switch is the whole registry — adding a backend means adding one class and one case,
 * which is the point of keeping the writer contract pure.
 */
public final class WriterFactory {

    private WriterFactory() {
    }

    /**
     * Token for the wiki projection.
     *
     * <p>Aliased to the definition in {@link ExportConfig} rather than repeating the literal: two
     * constants naming the same token is one edit away from them disagreeing.
     */
    public static final String WIKI = ExportConfig.WIKI;

    public static Writer create(String format, ExportConfig config, StringPool pool) {
        return switch (format) {
            case ExportConfig.NDJSON -> new JsonWriter(config, JsonWriter.Layout.NDJSON);
            case ExportConfig.JSON -> new JsonWriter(config, JsonWriter.Layout.GROUPED);
            case WIKI -> new WikiWriter(config);
            case ExportConfig.TD -> new TdWriter(config);
            case ExportConfig.ZD -> new ZdWriter(config, pool);
            case ExportConfig.YAML -> new YamlWriter(config);
            case ExportConfig.TOML -> new TomlWriter(config);
            case ExportConfig.XML -> new XmlWriter(config);
            default -> throw new IllegalArgumentException("unknown output format: " + format);
        };
    }

    /** True when the token names a format this build can write. */
    public static boolean isKnown(String format) {
        return switch (format) {
            case ExportConfig.NDJSON, ExportConfig.JSON, WIKI, ExportConfig.TD, ExportConfig.ZD,
                    ExportConfig.YAML, ExportConfig.TOML, ExportConfig.XML -> true;
            default -> false;
        };
    }

    /**
     * Whether a format can carry an element category, without instantiating a writer.
     *
     * <p>Consulted before a shard is created so that a format which has nothing to say about a
     * category never produces an empty file.
     */
    public static boolean supports(String format, org.uee.model.ElementKind kind,
            ExportConfig config) {
        return switch (format) {
            case WIKI -> switch (kind) {
                case ITEM, ENTITY, RECIPE -> true;
                case BLOCK -> config.wiki().includeBlocksAsSeparateFile();
                default -> false;
            };
            // The neutral backends describe any record faithfully; the debug and mod sections are
            // first-class there rather than a wiki-only special case.
            default -> true;
        };
    }
}
