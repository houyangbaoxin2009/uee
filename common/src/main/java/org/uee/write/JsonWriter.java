package org.uee.write;

import java.util.Base64;
import org.uee.config.ExportConfig;
import org.uee.model.BlockElement;
import org.uee.model.DebugSection;
import org.uee.model.ElementKind;
import org.uee.model.EntityElement;
import org.uee.model.ItemElement;
import org.uee.model.ModElement;
import org.uee.model.RecipeElement;
import org.uee.util.Json;

/**
 * Neutral JSON writer, in two layouts.
 *
 * <p>{@link Layout#NDJSON} emits one compact object per line with no enclosing array. This is not an
 * arbitrary choice: the wiki importers consume newline-delimited records and report progress as
 * "N lines processed", so NDJSON is the shape an importable artifact must have.
 *
 * <p>{@link Layout#GROUPED} emits a single object keyed by category, for consumers that want one
 * document.
 *
 * <p>Field names here are UEE-internal and stable; the wiki-facing projection lives in
 * {@link WikiWriter}. Icons are base64-encoded at write time from the raw PNG bytes in the model.
 */
public class JsonWriter extends Writer {

    /** Output layout. */
    public enum Layout {
        /** One object per line, no enclosing array. */
        NDJSON,
        /** A single object with one array per element category. */
        GROUPED
    }

    private static final Base64.Encoder B64 = Base64.getEncoder();

    private final Layout layout;
    private long count;

    public JsonWriter(ExportConfig config) {
        this(config, Layout.NDJSON);
    }

    public JsonWriter(ExportConfig config, Layout layout) {
        super(config);
        this.layout = layout;
    }

    @Override
    public String token() {
        return layout == Layout.NDJSON ? ExportConfig.NDJSON : ExportConfig.JSON;
    }

    @Override
    public String extension() {
        // The two layouts must not share an extension: both are JSON, and letting them collide
        // silently overwrites one artifact with the other.
        return layout == Layout.NDJSON ? "ndjson" : "json";
    }

    @Override
    public boolean needsIcons() {
        return config.icons();
    }

    @Override
    public void beginShard(String namespace, ElementKind kind) {
        count = 0;
        if (layout == Layout.GROUPED) {
            out.ascii("{\"");
            out.ascii(kind.plural());
            out.ascii("\":[");
        }
    }

    @Override
    public void endShard() {
        if (layout == Layout.GROUPED) {
            out.ascii(count == 0 ? "]}" : "\n]}");
        }
    }

    // ---------------------------------------------------------------- record entry points

    @Override
    public void item(ItemElement e) {
        beginRecord(ElementKind.ITEM);
        out.ascii("\"kind\":\"item\",\"registryName\":");
        Json.quote(out, e.registryName());
        out.ascii(",\"namespace\":");
        Json.quote(out, e.namespace());
        if (e.translationKey() != null) {
            out.ascii(",\"translationKey\":");
            Json.quote(out, e.translationKey());
        }
        nameFields(e.nameZh(), e.nameEn());
        out.ascii(",\"maxStackSize\":").dec(e.maxStackSize());
        out.ascii(",\"maxDurability\":").dec(e.maxDurability());
        out.ascii(",\"tags\":");
        Json.quoteArray(out, e.tags());
        out.ascii(",\"creativeTabs\":");
        Json.quoteArray(out, e.creativeTabs());
        out.ascii(",\"blockItem\":").ascii(e.blockItem() ? "true" : "false");
        iconFields(e.iconLarge(), "largeIcon", e.iconSmall(), "smallIcon");
        endRecord();
    }

    @Override
    public void entity(EntityElement e) {
        beginRecord(ElementKind.ENTITY);
        out.ascii("\"kind\":\"entity\",\"registryName\":");
        Json.quote(out, e.registryName());
        out.ascii(",\"namespace\":");
        Json.quote(out, e.namespace());
        if (e.translationKey() != null) {
            out.ascii(",\"translationKey\":");
            Json.quote(out, e.translationKey());
        }
        nameFields(e.nameZh(), e.nameEn());
        if (e.category() != null) {
            out.ascii(",\"category\":");
            Json.quote(out, e.category());
        }
        iconFields(e.icon(), "icon", null, null);
        endRecord();
    }

    @Override
    public void block(BlockElement e) {
        beginRecord(ElementKind.BLOCK);
        out.ascii("\"kind\":\"block\",\"registryName\":");
        Json.quote(out, e.registryName());
        out.ascii(",\"namespace\":");
        Json.quote(out, e.namespace());
        nameFields(e.nameZh(), e.nameEn());
        out.ascii(",\"hardness\":").jsonNum(e.hardness());
        out.ascii(",\"blastResistance\":").jsonNum(e.blastResistance());
        out.ascii(",\"lightEmission\":").dec(e.lightEmission());
        out.ascii(",\"hasBlockItem\":").ascii(e.hasBlockItem() ? "true" : "false");
        if (e.material() != null) {
            out.ascii(",\"material\":");
            Json.quote(out, e.material());
        }
        out.ascii(",\"tags\":");
        Json.quoteArray(out, e.tags());
        endRecord();
    }

    @Override
    public void recipe(RecipeElement e) {
        beginRecord(ElementKind.RECIPE);
        out.ascii("\"kind\":\"recipe\",\"type\":");
        Json.quote(out, e.type());
        if (e.id() != null) {
            out.ascii(",\"name\":");
            Json.quote(out, e.id());
        }
        out.ascii(",\"namespace\":");
        Json.quote(out, e.namespace());
        out.ascii(",\"input\":{");
        for (int i = 0; i < e.inputSlots().length; i++) {
            if (i > 0) {
                out.u8(',');
            }
            Json.quote(out, e.inputSlots()[i]);
            out.u8(':');
            writeIngredient(e.inputs()[i]);
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
        endRecord();
    }

    @Override
    public void mod(ModElement e) {
        beginRecord(ElementKind.MOD);
        out.ascii("\"kind\":\"mod\",\"id\":");
        Json.quote(out, e.id());
        out.ascii(",\"name\":");
        Json.quote(out, e.name());
        out.ascii(",\"version\":");
        Json.quote(out, e.version());
        out.ascii(",\"namespace\":");
        Json.quote(out, e.namespace());
        out.ascii(",\"loader\":");
        Json.quote(out, e.loader());
        out.ascii(",\"minecraftVersion\":");
        Json.quote(out, e.minecraftVersion());
        out.ascii(",\"authors\":");
        Json.quoteArray(out, e.authors());
        if (e.license() != null) {
            out.ascii(",\"license\":");
            Json.quote(out, e.license());
        }
        if (e.description() != null) {
            out.ascii(",\"description\":");
            Json.quote(out, e.description());
        }
        out.ascii(",\"dependencies\":[");
        for (int i = 0; i < e.dependencies().length; i++) {
            if (i > 0) {
                out.u8(',');
            }
            org.uee.model.Dependency d = e.dependencies()[i];
            out.ascii("{\"id\":");
            Json.quote(out, d.id());
            if (d.hasVersionRange()) {
                out.ascii(",\"range\":");
                Json.quote(out, d.versionRange());
            }
            out.ascii(",\"kind\":");
            Json.quote(out, d.kind().name().toLowerCase(java.util.Locale.ROOT));
            out.u8('}');
        }
        out.u8(']');
        out.ascii(",\"providers\":");
        Json.quoteArray(out, e.providers());
        if (e.sourceFile() != null) {
            out.ascii(",\"sourceFile\":");
            Json.quote(out, e.sourceFile());
        }
        endRecord();
    }

    @Override
    public void generic(ElementKind kind, String namespace, String key, String nameZh,
            String nameEn, String[] listValues, String[] extra) {
        beginRecord(kind);
        out.ascii("\"kind\":");
        Json.quote(out, kind.singular());
        out.ascii(",\"key\":");
        Json.quote(out, key);
        out.ascii(",\"namespace\":");
        Json.quote(out, namespace);
        nameFields(nameZh, nameEn);
        out.ascii(",\"values\":");
        Json.quoteArray(out, listValues);
        if (extra != null) {
            for (int i = 0; i + 1 < extra.length; i += 2) {
                out.u8(',');
                Json.quote(out, extra[i]);
                out.u8(':');
                Json.quote(out, extra[i + 1]);
            }
        }
        endRecord();
    }

    @Override
    public void debug(DebugSection s) {
        beginRecord(ElementKind.DEBUG);
        out.ascii("\"kind\":\"debug\",\"name\":");
        Json.quote(out, s.name());
        out.ascii(",\"entries\":{");
        for (int i = 0; i < s.size(); i++) {
            if (i > 0) {
                out.u8(',');
            }
            Json.quote(out, s.keys()[i]);
            out.u8(':');
            Json.quote(out, s.values()[i]);
        }
        out.ascii("}");
        endRecord();
    }

    // ---------------------------------------------------------------- helpers

    private void nameFields(String zh, String en) {
        if (zh != null) {
            out.ascii(",\"name\":");
            Json.quote(out, zh);
        }
        if (en != null) {
            out.ascii(",\"englishName\":");
            Json.quote(out, en);
        }
    }

    /** Appends icon fields as base64, skipping absent or empty payloads. */
    protected void iconFields(byte[] large, String largeKey, byte[] small, String smallKey) {
        if (large != null && large.length > 0 && largeKey != null) {
            out.u8(',');
            Json.quote(out, largeKey);
            out.u8(':');
            out.u8('"');
            out.raw(B64.encode(large));
            out.u8('"');
        }
        if (small != null && small.length > 0 && smallKey != null) {
            out.u8(',');
            Json.quote(out, smallKey);
            out.u8(':');
            out.u8('"');
            out.raw(B64.encode(small));
            out.u8('"');
        }
    }

    /** Writes a vanilla-shaped ingredient object. */
    protected void writeIngredient(org.uee.model.Ingredient ing) {
        if (ing == null || ing.isEmpty()) {
            out.ascii("{}");
            return;
        }
        switch (ing.kind()) {
            case org.uee.model.Ingredient.TAG -> {
                out.ascii("{\"tag\":");
                Json.quote(out, ing.values()[0]);
                out.u8('}');
            }
            case org.uee.model.Ingredient.ITEMS -> {
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

    /**
     * Opens a record object.
     *
     * <p>The enclosing brace is emitted here and closed by {@link #endRecord()} rather than written
     * at each call site. Every writer emitting its own brace means every writer can forget one, and
     * a forgotten brace yields a file whose size looks right and whose every line fails to parse —
     * which is exactly the defect this pairing removes.
     */
    private void beginRecord(ElementKind kind) {
        if (layout == Layout.GROUPED && count > 0) {
            out.u8(',');
        }
        out.u8('{');
    }

    /** Closes the record object opened by {@link #beginRecord(ElementKind)}. */
    private void endRecord() {
        out.u8('}');
        if (layout == Layout.NDJSON) {
            out.nl();
        } else {
            count++;
        }
    }
}
