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
 * YAML writer, driven by {@link ElementExpander}.
 *
 * <p>Every scalar is emitted double-quoted. That is not cosmetic: YAML's plain scalars have a long
 * list of contextual traps (a value that looks like a number, a leading {@code *} or {@code &}, a
 * colon, a {@code #}), and a Minecraft export runs into them constantly through registry names and
 * localized text. Quoting unconditionally means the output is readable by any parser without a
 * per-value judgement call, and it stays diff-stable.
 */
public final class YamlWriter extends Writer implements FieldVisitor {

    private final StringBuilder run = new StringBuilder(96);

    public YamlWriter(ExportConfig config) {
        super(config);
    }

    @Override
    public String token() {
        return ExportConfig.YAML;
    }

    @Override
    public String extension() {
        return "yaml";
    }

    @Override
    public void beginShard(String namespace, ElementKind kind) {
        out.ascii("# UEE export / ").ascii(kind.plural()).ascii(" / ").utf8(namespace).nl();
        out.ascii(kind.plural()).ascii(":").nl();
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
    public void generic(ElementKind kind, String registryName, String nameZh, String nameEn,
            String[] tags, String[] extra) {
        ElementExpander.generic(kind, registryName, nameZh, nameEn, tags, this);
    }

    @Override
    public void debug(DebugSection s) {
        ElementExpander.debug(s, this);
    }

    // ---------------------------------------------------------------- visitor

    @Override
    public void element(ElementKind kind, String registryName) {
        out.ascii("  - registryName: ");
        yamlString(registryName);
        out.nl();
    }

    @Override
    public void string(String key, String value) {
        if (value == null) {
            return;
        }
        key(key);
        yamlString(value);
        out.nl();
    }

    @Override
    public void number(String key, long value) {
        key(key);
        out.dec(value).nl();
    }

    @Override
    public void decimal(String key, double value) {
        key(key);
        out.jsonNum(value).nl();
    }

    @Override
    public void bool(String key, boolean value) {
        key(key);
        out.ascii(value ? "true" : "false").nl();
    }

    @Override
    public void array(String key, String[] values) {
        key(key);
        if (values.length == 0) {
            out.ascii("[]").nl();
            return;
        }
        out.nl();
        for (String s : values) {
            out.ascii("      - ");
            yamlString(s);
            out.nl();
        }
    }

    @Override
    public void end() {
    }

    private void key(String key) {
        out.ascii("    ").ascii(key).ascii(": ");
    }

    /**
     * Emits a YAML double-quoted scalar.
     *
     * <p>Runs of ordinary characters are accumulated and handed to the UTF-8 encoder in one call:
     * localized names are mostly non-ASCII, and encoding them one character at a time would allocate
     * a throwaway string per character.
     */
    private void yamlString(String s) {
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
