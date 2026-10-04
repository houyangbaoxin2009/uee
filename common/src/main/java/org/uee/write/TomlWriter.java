package org.uee.write;

import org.uee.config.ExportConfig;
import org.uee.model.BlockElement;
import org.uee.model.DebugSection;
import org.uee.model.ElementKind;
import org.uee.model.EntityElement;
import org.uee.model.ItemElement;
import org.uee.model.ModElement;
import org.uee.model.RecipeElement;

/**
 * TOML writer, driven by {@link ElementExpander}.
 *
 * <p>Each element becomes an array-of-tables entry ({@code [[items]]}), which is TOML's natural
 * shape for a repeated record and keeps the output re-parseable into the same structure the model
 * had. Field order is preserved, so a textual diff of two exports lines up.
 */
public final class TomlWriter extends Writer implements FieldVisitor {

    public TomlWriter(ExportConfig config) {
        super(config);
    }

    @Override
    public String token() {
        return ExportConfig.TOML;
    }

    @Override
    public String extension() {
        return "toml";
    }

    @Override
    public void beginShard(String namespace, ElementKind kind) {
        out.ascii("# UEE export / ").ascii(kind.plural()).ascii(" / ").utf8(namespace).nl().nl();
    }

    @Override
    public void item(ItemElement e) {
        ElementExpander.item(e, this);
    }

    @Override
    public void entity(EntityElement e) {
        ElementExpander.entity(e, this);
    }

    @Override
    public void block(BlockElement e) {
        ElementExpander.block(e, this);
    }

    @Override
    public void recipe(RecipeElement e) {
        ElementExpander.recipe(e, this);
    }

    @Override
    public void mod(ModElement e) {
        ElementExpander.mod(e, this);
    }

    @Override
    public void generic(ElementKind kind, String namespace, String key, String nameZh,
            String nameEn, String[] listValues, String[] extra) {
        ElementExpander.generic(kind, namespace, key, nameZh, nameEn, listValues, extra, this);
    }

    @Override
    public void debug(DebugSection s) {
        ElementExpander.debug(s, this);
    }

    // ---------------------------------------------------------------- visitor

    @Override
    public void element(ElementKind kind, String registryName) {
        if (!out.isEmpty()) {
            out.nl();
        }
        out.ascii("[[").ascii(kind.plural()).ascii("]]").nl();
        if (registryName != null) {
            out.ascii("registryName = ");
            tomlString(registryName);
            out.nl();
        }
    }

    @Override
    public void string(String key, String value) {
        if (value == null) {
            return;
        }
        out.ascii(key).ascii(" = ");
        tomlString(value);
        out.nl();
    }

    @Override
    public void number(String key, long value) {
        out.ascii(key).ascii(" = ").dec(value).nl();
    }

    @Override
    public void decimal(String key, double value) {
        out.ascii(key).ascii(" = ").jsonNum(value).nl();
    }

    @Override
    public void bool(String key, boolean value) {
        out.ascii(key).ascii(" = ").ascii(value ? "true" : "false").nl();
    }

    @Override
    public void array(String key, String[] values) {
        out.ascii(key).ascii(" = [");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.ascii(", ");
            }
            tomlString(values[i]);
        }
        out.ascii("]").nl();
    }

    @Override
    public void end() {
    }

    private final StringBuilder run = new StringBuilder(96);

    @Override
    public void records(String key, String[] columns, java.util.List<String[]> rows) {
        out.ascii(key).ascii(" = [");
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) {
                out.ascii(", ");
            }
            String[] row = rows.get(r);
            out.ascii("{ ");
            for (int c = 0; c < columns.length; c++) {
                if (c > 0) {
                    out.ascii(", ");
                }
                out.ascii(columns[c]).ascii(" = ");
                tomlString(c < row.length ? row[c] : "");
            }
            out.ascii(" }");
        }
        out.ascii("]").nl();
    }

    /** Emits a TOML basic (double-quoted) string. */
    private void tomlString(String s) {
        out.u8('"');
        if (s != null) {
            run.setLength(0);
            for (int i = 0, n = s.length(); i < n; i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t' || c < 0x20) {
                    if (run.length() > 0) {
                        out.utf8(run.toString());
                        run.setLength(0);
                    }
                    switch (c) {
                        case '"' -> out.ascii("\\\"");
                        case '\\' -> out.ascii("\\\\");
                        case '\n' -> out.ascii("\\n");
                        case '\r' -> out.ascii("\\r");
                        case '\t' -> out.ascii("\\t");
                        default -> {
                            out.ascii("\\u00");
                            out.u8(HEX[(c >> 4) & 0xF]);
                            out.u8(HEX[c & 0xF]);
                        }
                    }
                } else {
                    run.append(c);
                }
            }
            if (run.length() > 0) {
                out.utf8(run.toString());
            }
        }
        out.u8('"');
    }

    private static final byte[] HEX = "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
}
