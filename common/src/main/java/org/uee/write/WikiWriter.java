package org.uee.write;

import java.util.Base64;
import org.uee.config.ExportConfig;
import org.uee.config.WikiOptions;
import org.uee.model.ElementKind;
import org.uee.model.EntityElement;
import org.uee.model.Ingredient;
import org.uee.model.ItemElement;
import org.uee.model.RecipeElement;
import org.uee.util.Json;

/**
 * Wiki-mode writer: the projection that makes an export directly importable by the MC百科 pipelines.
 *
 * <p>This writer exists so that the wiki format is <em>one projection</em> of the neutral model
 * rather than the model itself. Everything the importers need is produced here, and nothing about
 * their field naming leaks into the collector or the other backends.
 *
 * <p>Two generations are supported side by side because their field sets genuinely differ — see
 * {@link WikiOptions} for the full table. Reproduced deliberately, not by accident:
 *
 * <ul>
 *   <li>{@code V1} writes stack size to the misspelled {@code maxStacksSize} as a number, and tags
 *       to {@code OredictList} as a bracketed <em>text</em> blob (not a JSON array);
 *   <li>{@code V2} writes {@code maxStackSize}/{@code maxDurability} as <em>strings</em> and tags
 *       to {@code TagList} as a real array;
 *   <li>{@code V2} never emits {@code "Block"} — every block item is reported as {@code "Item"}.
 * </ul>
 *
 * <p>Everything is NDJSON: one compact object per line, no enclosing array, which is what "N lines
 * processed" in an import log refers to. Icons are base64, matching both lineages.
 */
public final class WikiWriter extends Writer {

    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final String[] NO_TAGS = new String[0];

    private final WikiOptions wiki;
    private final boolean v1;
    private boolean recipeOpen;

    public WikiWriter(ExportConfig config) {
        super(config);
        this.wiki = config.wiki();
        this.v1 = wiki.format() == WikiOptions.WikiFormat.V1;
    }

    @Override
    public String token() {
        return "wiki";
    }

    @Override
    public String extension() {
        return "json";
    }

    /**
     * Places files where an importer looks for them, honouring the configured template so both
     * lineages' layouts are reproducible: the 2.x layout nests under {@code Json/<mod>/}, the 1.x
     * layout drops NDJSON next to the output root.
     */
    @Override
    public String outputPath(String namespace, ElementKind kind) {
        String template = switch (kind) {
            case ENTITY -> wiki.entityFileTemplate();
            case RECIPE -> wiki.recipeFileTemplate();
            default -> wiki.itemFileTemplate();
        };
        return wiki.fileName(template, namespace, kind.plural(), extension());
    }

    /**
     * The wiki projection carries items, entities and recipes. Blocks are only emitted when asked
     * for explicitly, because the 2.x lineage reports block items as plain items and a separate
     * block file would double-count every block in an import.
     */
    @Override
    public boolean supports(ElementKind kind) {
        return switch (kind) {
            case ITEM, ENTITY, RECIPE -> true;
            case BLOCK -> wiki.includeBlocksAsSeparateFile();
            default -> false;
        };
    }

    @Override
    public boolean needsIcons() {
        return wiki.icons();
    }

    @Override
    public void beginShard(String namespace, ElementKind kind) {
        recipeOpen = false;
    }

    @Override
    public void endShard() {
        if (recipeOpen) {
            out.ascii("],\"error\":[]}");
            out.nl();
            recipeOpen = false;
        }
    }

    // ---------------------------------------------------------------- items

    @Override
    public void item(ItemElement e) {
        if (v1) {
            itemV1(e);
        } else {
            itemV2(e);
        }
    }

    private void itemV2(ItemElement e) {
        out.ascii("{\"registerName\":");
        Json.quote(out, e.registryName());
        out.ascii(",\"type\":\"Item\"");
        out.ascii(",\"maxStackSize\":\"");
        out.dec(e.maxStackSize());
        out.ascii("\",\"maxDurability\":\"");
        out.dec(e.maxDurability());
        out.ascii("\",\"TagList\":");
        Json.quoteArray(out, e.tags());
        optionalName("name", e.nameZh());
        optionalName("englishName", e.nameEn());
        if (e.creativeTabs().length > 0) {
            out.ascii(",\"CreativeTabName\":");
            Json.quote(out, String.join(",", e.creativeTabs()));
        }
        iconBase64("largeIcon", e.iconLarge());
        iconBase64("smallIcon", e.iconSmall());
        out.ascii("}");
        out.nl();
    }

    private void itemV1(ItemElement e) {
        out.ascii("{\"name\":");
        Json.quote(out, e.nameZh() != null ? e.nameZh() : e.nameEn());
        out.ascii(",\"englishName\":");
        Json.quote(out, e.nameEn() != null ? e.nameEn() : e.nameZh());
        out.ascii(",\"registerName\":");
        Json.quote(out, e.registryName());
        out.ascii(",\"CreativeTabName\":");
        Json.quote(out, e.creativeTabs().length > 0 ? e.creativeTabs()[0] : "");
        out.ascii(",\"type\":\"");
        out.ascii(e.blockItem() ? "Block" : "Item");
        out.ascii("\",\"OredictList\":");
        // V1 packs tags into a bracketed text blob, not a JSON array. Reproduced verbatim.
        out.u8('"');
        out.u8('[');
        String[] tags = e.tags() == null ? NO_TAGS : e.tags();
        for (int i = 0; i < tags.length; i++) {
            if (i > 0) {
                out.ascii(", ");
            }
            out.ascii(tags[i]);
        }
        out.u8(']');
        out.u8('"');
        out.ascii(",\"maxStacksSize\":").dec(e.maxStackSize());
        out.ascii(",\"maxDurability\":").dec(e.maxDurability());
        iconBase64("smallIcon", e.iconSmall());
        iconBase64("largeIcon", e.iconLarge());
        out.ascii("}");
        out.nl();
    }

    // ---------------------------------------------------------------- entities

    @Override
    public void entity(EntityElement e) {
        if (v1) {
            out.ascii("{\"name\":");
            Json.quote(out, e.nameZh() != null ? e.nameZh() : e.nameEn());
            out.ascii(",\"englishName\":");
            Json.quote(out, e.nameEn() != null ? e.nameEn() : e.nameZh());
            out.ascii(",\"registerName\":");
            Json.quote(out, e.registryName());
            out.ascii(",\"mod\":");
            Json.quote(out, e.namespace());
            out.ascii(",\"type\":\"Entity\"");
        } else {
            out.ascii("{\"registerName\":");
            Json.quote(out, e.registryName());
            optionalName("name", e.nameZh());
            optionalName("englishName", e.nameEn());
        }
        iconBase64("Icon", e.icon());
        out.ascii("}");
        out.nl();
    }

    // ---------------------------------------------------------------- recipes

    @Override
    public void recipe(RecipeElement e) {
        if (!recipeOpen) {
            // The recipe container is a single object whose members are an array plus an error list,
            // so it is opened lazily on the first recipe and closed at shard end.
            out.ascii("{\"recipes\":[");
            recipeOpen = true;
        } else {
            out.u8(',');
        }
        out.ascii("{\"type\":");
        Json.quote(out, e.type());
        if (e.id() != null) {
            out.ascii(",\"name\":");
            Json.quote(out, e.id());
        }
        out.ascii(",\"input\":{");
        for (int i = 0; i < e.inputSlots().length; i++) {
            Ingredient ing = e.inputs()[i];
            if (ing == null || ing.isEmpty()) {
                continue;
            }
            if (i > 0) {
                out.u8(',');
            }
            Json.quote(out, e.inputSlots()[i]);
            out.u8(':');
            writeIngredient(ing);
        }
        out.ascii("},\"output\":{");
        for (int i = 0; i < e.outputSlots().length; i++) {
            if (i > 0) {
                out.u8(',');
            }
            Json.quote(out, e.outputSlots()[i]);
            out.ascii(":{\"item\":");
            Json.quote(out, e.outputItems()[i]);
            out.ascii(",\"count\":").dec(e.outputCounts()[i]);
            if (e.hasNbt(i)) {
                out.ascii(",\"nbt\":");
                Json.quote(out, e.outputNbt()[i]);
            }
            out.u8('}');
        }
        out.u8('}');
        if (e.experience() != null) {
            out.ascii(",\"experience\":").jsonNum(e.experience());
        }
        if (e.cookTime() != null) {
            out.ascii(",\"cookTime\":").dec(e.cookTime());
        }
        out.u8('}');
    }

    private void writeIngredient(Ingredient ing) {
        switch (ing.kind()) {
            case Ingredient.TAG -> {
                out.ascii("{\"tag\":");
                Json.quote(out, ing.values()[0]);
                out.u8('}');
            }
            case Ingredient.ITEMS -> {
                out.ascii("{\"items\":");
                Json.quoteArray(out, ing.values());
                out.u8('}');
            }
            default -> {
                out.ascii("{\"item\":");
                Json.quote(out, ing.values()[0]);
                out.u8('}');
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private void optionalName(String key, String value) {
        if (value == null) {
            return;
        }
        out.u8(',');
        Json.quote(out, key);
        out.u8(':');
        Json.quote(out, value);
    }

    private void iconBase64(String key, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        out.u8(',');
        Json.quote(out, key);
        out.ascii(":\"");
        out.raw(B64.encode(data));
        out.u8('"');
    }
}
