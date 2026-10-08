package org.uee.config;

/**
 * Wiki-writer options, covering the two exporter generations the MC百科 importers accept.
 *
 * <p>The two generations are <b>not</b> field-compatible, and the differences are exactly the kind
 * of thing that silently breaks an import, so both are modelled explicitly rather than one being
 * "the" format:
 *
 * <table>
 *   <caption>Field differences between the exporter generations</caption>
 *   <tr><th>Concept</th><th>{@link WikiFormat#V2}</th><th>{@link WikiFormat#V1}</th></tr>
 *   <tr><td>stack size</td><td>{@code maxStackSize}, string</td><td>{@code maxStacksSize}, int</td></tr>
 *   <tr><td>durability</td><td>{@code maxDurability}, string</td><td>{@code maxDurability}, int</td></tr>
 *   <tr><td>tags</td><td>{@code TagList}, JSON array</td><td>{@code OredictList}, bracketed text</td></tr>
 *   <tr><td>block items</td><td>{@code type} is always {@code "Item"}</td><td>{@code type} is {@code "Block"}</td></tr>
 *   <tr><td>creative tab</td><td>comma-joined, may be several</td><td>a single tab</td></tr>
 *   <tr><td>output path</td><td>{@code Json/<mod>/<mod>-items.json}</td><td>{@code <mod>.json}</td></tr>
 * </table>
 *
 * <p>Note the V1 tag field is <em>not</em> valid JSON when read as a string: it is a hand-built
 * {@code "[a, b]"} text blob. And V1's stack-size field is misspelled {@code StacksSize}; that typo
 * is load-bearing for importers that read it, so it is reproduced deliberately.
 */
public final class WikiOptions {

    /** Which exporter generation the output should be readable by. */
    public enum WikiFormat {
        /** The 2.x lineage: NDJSON item/entity files under {@code Json/<mod>/}, always {@code "Item"}. */
        V2,
        /** The 1.x lineage: NDJSON under the output root, {@code "Item"}/{@code "Block"}, text tags. */
        V1
    }

    private final boolean enabled;
    private final WikiFormat format;
    private final boolean icons;
    private final boolean includeEntities;
    private final boolean includeRecipes;
    private final boolean includeBlocksAsSeparateFile;
    private final String itemFileTemplate;
    private final String entityFileTemplate;
    private final String recipeFileTemplate;

    private WikiOptions(Builder b) {
        this.enabled = b.enabled;
        this.format = b.format;
        this.icons = b.icons;
        this.includeEntities = b.includeEntities;
        this.includeRecipes = b.includeRecipes;
        this.includeBlocksAsSeparateFile = b.includeBlocksAsSeparateFile;
        this.itemFileTemplate = b.itemFileTemplate;
        this.entityFileTemplate = b.entityFileTemplate;
        this.recipeFileTemplate = b.recipeFileTemplate;
    }

    public boolean enabled() {
        return enabled;
    }

    public WikiFormat format() {
        return format;
    }

    /** Whether base64 icon payloads are written. Without icons the output is a fraction of the size. */
    public boolean icons() {
        return icons;
    }

    public boolean includeEntities() {
        return includeEntities;
    }

    public boolean includeRecipes() {
        return includeRecipes;
    }

    public boolean includeBlocksAsSeparateFile() {
        return includeBlocksAsSeparateFile;
    }

    public String itemFileTemplate() {
        return itemFileTemplate;
    }

    public String entityFileTemplate() {
        return entityFileTemplate;
    }

    public String recipeFileTemplate() {
        return recipeFileTemplate;
    }

    /**
     * Resolves an output file name for a template.
     *
     * <p>Supported placeholders: {@code %mod%} (namespace), {@code %kind%} (the plural element
     * token, e.g. {@code items}), {@code %ext%} (extension without the dot).
     */
    public String fileName(String template, String mod, String kind, String ext) {
        return template.replace("%mod%", mod)
                .replace("%kind%", kind)
                .replace("%ext%", ext);
    }

    /** A builder pre-filled with these options, for producing a modified copy. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.enabled = enabled;
        b.format = format;
        b.icons = icons;
        b.includeEntities = includeEntities;
        b.includeRecipes = includeRecipes;
        b.includeBlocksAsSeparateFile = includeBlocksAsSeparateFile;
        b.itemFileTemplate = itemFileTemplate;
        b.entityFileTemplate = entityFileTemplate;
        b.recipeFileTemplate = recipeFileTemplate;
        return b;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Defaults match the current mainstream exporter: 2.x layout, NDJSON, no icons. */
    public static WikiOptions defaults() {
        return builder().build();
    }

    /** A v2 configuration that also embeds icons, i.e. a self-contained import package. */
    public static WikiOptions withIcons() {
        return builder().enabled(true).icons(true).build();
    }

    /** Mutable builder. */
    public static final class Builder {
        private boolean enabled = true;
        private WikiFormat format = WikiFormat.V2;
        private boolean icons = false;
        private boolean includeEntities = true;
        private boolean includeRecipes = true;
        private boolean includeBlocksAsSeparateFile = false;
        private String itemFileTemplate = "Json/%mod%/%mod%-%kind%.%ext%";
        private String entityFileTemplate = "Json/%mod%/%mod%-%kind%.%ext%";
        private String recipeFileTemplate = "Json/%mod%/%mod%-%kind%.%ext%";

        public Builder enabled(boolean on) {
            this.enabled = on;
            return this;
        }

        public Builder format(WikiFormat f) {
            this.format = f;
            if (f == WikiFormat.V1) {
                this.itemFileTemplate = "%mod%.%ext%";
                this.entityFileTemplate = "%mod%_entity.%ext%";
                this.recipeFileTemplate = "dump_recipes_%mod%.%ext%";
            }
            return this;
        }

        public Builder icons(boolean on) {
            this.icons = on;
            return this;
        }

        public Builder includeEntities(boolean on) {
            this.includeEntities = on;
            return this;
        }

        public Builder includeRecipes(boolean on) {
            this.includeRecipes = on;
            return this;
        }

        public Builder includeBlocksAsSeparateFile(boolean on) {
            this.includeBlocksAsSeparateFile = on;
            return this;
        }

        public Builder itemFileTemplate(String t) {
            this.itemFileTemplate = t;
            return this;
        }

        public Builder entityFileTemplate(String t) {
            this.entityFileTemplate = t;
            return this;
        }

        public Builder recipeFileTemplate(String t) {
            this.recipeFileTemplate = t;
            return this;
        }

        public WikiOptions build() {
            return new WikiOptions(this);
        }
    }
}
