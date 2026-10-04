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
 * XML writer, driven by {@link ElementExpander}.
 *
 * <p>Each field becomes a child element named after the key; a list field repeats its element once
 * per value. This is the shape that survives a round trip through schema-less XML tooling without
 * attribute-or-element judgement calls, and repeated child elements represent lists natively.
 */
public final class XmlWriter extends Writer implements FieldVisitor {

    private final StringBuilder run = new StringBuilder(96);
    private ElementKind shardKind;

    public XmlWriter(ExportConfig config) {
        super(config);
    }

    @Override
    public String token() {
        return ExportConfig.XML;
    }

    @Override
    public String extension() {
        return "xml";
    }

    @Override
    public void beginShard(String namespace, ElementKind kind) {
        shardKind = kind;
        out.ascii("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").nl();
        out.ascii("<uee kind=\"").ascii(kind.plural()).ascii("\" namespace=\"");
        xmlAttr(namespace);
        out.ascii("\">").nl();
    }

    @Override
    public void endShard() {
        out.ascii("</uee>").nl();
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
        out.ascii("  <").ascii(kind.singular()).ascii(">").nl();
        if (registryName != null) {
            field(registryName);
        }
    }

    @Override
    public void string(String key, String value) {
        if (value == null) {
            return;
        }
        open(key);
        xmlText(value);
        close(key);
    }

    @Override
    public void number(String key, long value) {
        open(key);
        out.dec(value);
        close(key);
    }

    @Override
    public void decimal(String key, double value) {
        open(key);
        out.jsonNum(value);
        close(key);
    }

    @Override
    public void bool(String key, boolean value) {
        open(key);
        out.ascii(value ? "true" : "false");
        close(key);
    }

    @Override
    public void array(String key, String[] values) {
        if (values.length == 0) {
            out.ascii("    <").ascii(key).ascii("/>").nl();
            return;
        }
        for (String v : values) {
            open(key);
            xmlText(v);
            close(key);
        }
    }

    @Override
    public void end() {
        out.ascii("  </").ascii(shardKind.singular()).ascii(">").nl();
    }

    // ---------------------------------------------------------------- emission

    private void field(String value) {
        open("registryName");
        xmlText(value);
        close("registryName");
    }

    private void open(String key) {
        out.ascii("    <").ascii(key).ascii(">");
    }

    private void close(String key) {
        out.ascii("</").ascii(key).ascii(">").nl();
    }

    /** Emits XML text content with the five predefined entity escapes. */
    private void xmlText(String s) {
        if (s == null) {
            return;
        }
        run.setLength(0);
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            String esc = switch (c) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&quot;";
                case '\'' -> "&apos;";
                default -> null;
            };
            if (esc != null) {
                if (run.length() > 0) {
                    out.utf8(run.toString());
                    run.setLength(0);
                }
                out.ascii(esc);
            } else if (c < 0x20 && c != '\n' && c != '\t') {
                // XML 1.0 forbids most control characters outright; drop them rather than emit
                // a document that no conforming parser will accept.
                if (run.length() > 0) {
                    out.utf8(run.toString());
                    run.setLength(0);
                }
            } else {
                run.append(c);
            }
        }
        if (run.length() > 0) {
            out.utf8(run.toString());
        }
    }

    /** Emits an attribute value, reusing the text escaper (its escape set is a superset of the needed one). */
    private void xmlAttr(String s) {
        xmlText(s);
    }
}
