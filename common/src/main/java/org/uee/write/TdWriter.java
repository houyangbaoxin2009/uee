package org.uee.write;

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
 * td ({@code tie:data}) writer — the tie ecosystem's text data format.
 *
 * <p>Emitted syntax matches what tiec's {@code config.parse_data} accepts, so a UEE artifact can be
 * read back by tie tooling without a converter: a table is a {@code [...]} list of
 * {@code key = value} entries, and a value list is a {@code [...]} whose first entry is a bare
 * value. Arrays of tables are therefore nested brackets, which is exactly how the element
 * collections are laid out here.
 *
 * <p>Field names are the neutral UEE names, not the wiki projection — the wiki format is a
 * different writer's job.
 */
public final class TdWriter extends Writer {

    private final StringBuilder buf = new StringBuilder(256);

    public TdWriter(ExportConfig config) {
        super(config);
    }

    @Override
    public String token() {
        return ExportConfig.TD;
    }

    @Override
    public String extension() {
        return "td";
    }

    @Override
    public void beginShard(String namespace, ElementKind kind) {
        out.ascii("type tie<data>").nl().nl();
        out.ascii("name = ");
        tdString(namespace);
        out.nl().nl();
        out.ascii(kind.plural());
        out.ascii(" = [").nl();
    }

    @Override
    public void endShard() {
        out.ascii("]").nl();
    }

    @Override
    public void item(ItemElement e) {
        openRow(ElementKind.ITEM);
        kv("registryName", e.registryName(), true);
        kv("namespace", e.namespace(), false);
        kvOpt("translationKey", e.translationKey());
        kvOpt("name", e.nameZh());
        kvOpt("englishName", e.nameEn());
        kvNum("maxStackSize", e.maxStackSize(), false);
        kvNum("maxDurability", e.maxDurability(), false);
        kvArr("tags", e.tags(), false);
        kvArr("creativeTabs", e.creativeTabs(), false);
        kvBool("blockItem", e.blockItem());
        closeRow();
    }

    @Override
    public void entity(EntityElement e) {
        openRow(ElementKind.ENTITY);
        kv("registryName", e.registryName(), true);
        kv("namespace", e.namespace(), false);
        kvOpt("translationKey", e.translationKey());
        kvOpt("name", e.nameZh());
        kvOpt("englishName", e.nameEn());
        kvOpt("category", e.category());
        closeRow();
    }

    @Override
    public void block(BlockElement e) {
        openRow(ElementKind.BLOCK);
        kv("registryName", e.registryName(), true);
        kv("namespace", e.namespace(), false);
        kvOpt("name", e.nameZh());
        kvOpt("englishName", e.nameEn());
        kvNumF("hardness", e.hardness(), false);
        kvNumF("blastResistance", e.blastResistance(), false);
        kvNum("lightEmission", e.lightEmission(), false);
        kvBool("hasBlockItem", e.hasBlockItem());
        kvOpt("material", e.material());
        kvArr("tags", e.tags(), false);
        closeRow();
    }

    @Override
    public void recipe(RecipeElement e) {
        openRow(ElementKind.RECIPE);
        kv("type", e.type(), true);
        kvOpt("id", e.id());
        kv("namespace", e.namespace(), false);
        kv("inputSlots", String.join(",", e.inputSlots()), false);
        kv("outputSlots", String.join(",", e.outputSlots()), false);
        kvArr("outputItems", e.outputItems(), false);
        closeRow();
    }

    @Override
    public void mod(ModElement e) {
        openRow(ElementKind.MOD);
        kv("id", e.id(), true);
        kvOpt("name", e.name());
        kvOpt("version", e.version());
        kv("namespace", e.namespace(), false);
        kvOpt("loader", e.loader());
        kvOpt("minecraftVersion", e.minecraftVersion());
        kvArr("authors", e.authors(), false);
        kvOpt("license", e.license());
        records("dependencies", ElementExpander.DEPENDENCY_COLUMNS, ElementExpander.dependencyRows(e));
        kvArr("providers", e.providers(), false);
        closeRow();
    }

    @Override
    public void generic(ElementKind kind, String namespace, String key, String nameZh,
            String nameEn, String[] listValues, String[] extra) {
        openRow(kind);
        kv("key", key, true);
        kvOpt("name", nameZh);
        kvOpt("englishName", nameEn);
        kvArr("values", listValues, false);
        if (extra != null) {
            for (int i = 0; i + 1 < extra.length; i += 2) {
                kvOpt(extra[i], extra[i + 1]);
            }
        }
        closeRow();
    }

    @Override
    public void debug(DebugSection s) {
        openRow(ElementKind.DEBUG);
        kv("name", s.name(), true);
        for (int i = 0; i < s.size(); i++) {
            kvOpt(s.keys()[i], s.values()[i]);
        }
        closeRow();
    }

    // ---------------------------------------------------------------- emission

    private void openRow(ElementKind kind) {
        out.ascii("  [").nl();
    }

    private void closeRow() {
        out.nl();
        out.ascii("  ],").nl();
    }

    private void sep(boolean first) {
        if (!first) {
            out.ascii(",\n");
        }
    }

    private void kv(String key, String value, boolean first) {
        sep(first);
        out.ascii("    ").ascii(key).ascii(" = ");
        tdString(value);
    }

    private void kvOpt(String key, String value) {
        if (value == null) {
            return;
        }
        kv(key, value, false);
    }

    private void kvNum(String key, long value, boolean first) {
        sep(first);
        out.ascii("    ").ascii(key).ascii(" = ").dec(value);
    }

    private void kvNumF(String key, double value, boolean first) {
        sep(first);
        out.ascii("    ").ascii(key).ascii(" = ").jsonNum(value);
    }

    private void kvBool(String key, boolean value) {
        sep(false);
        out.ascii("    ").ascii(key).ascii(" = ").ascii(value ? "true" : "false");
    }

    private void kvArr(String key, String[] values, boolean first) {
        sep(first);
        out.ascii("    ").ascii(key).ascii(" = [");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.ascii(", ");
            }
            tdString(values[i]);
        }
        out.u8(']');
    }

    /** Appends a record list: an array of nested tables, one per row. */
    public void records(String key, String[] columns, java.util.List<String[]> rows) {
        sep(false);
        out.ascii("    ").ascii(key).ascii(" = [").nl();
        for (String[] row : rows) {
            out.ascii("      [");
            for (int c = 0; c < columns.length; c++) {
                if (c > 0) {
                    out.ascii(", ");
                }
                out.ascii(columns[c]).ascii(" = ");
                tdString(c < row.length ? row[c] : "");
            }
            out.ascii("],").nl();
        }
        out.ascii("    ]");
    }

    /** Appends a td string literal with td's escape set. */
    private void tdString(String s) {
        out.u8('"');
        if (s != null) {
            int n = s.length();
            int i = 0;
            buf.setLength(0);
            while (i < n) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t') {
                    if (buf.length() > 0) {
                        out.utf8(buf.toString());
                        buf.setLength(0);
                    }
                    switch (c) {
                        case '"' -> out.ascii("\\\"");
                        case '\\' -> out.ascii("\\\\");
                        case '\n' -> out.ascii("\\n");
                        case '\r' -> out.ascii("\\r");
                        default -> out.ascii("\\t");
                    }
                } else {
                    buf.append(c);
                }
                i++;
            }
            if (buf.length() > 0) {
                out.utf8(buf.toString());
            }
        }
        out.u8('"');
    }

    /** Exposed for tests: the JSON escape table is a superset of td's for the characters td allows. */
    static boolean plain(String s) {
        return Json.isPlain(s);
    }
}
