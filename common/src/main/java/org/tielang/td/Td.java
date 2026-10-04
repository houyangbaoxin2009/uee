package org.tielang.td;

import java.util.ArrayList;
import java.util.List;

/**
 * td (tie data) parser and writer — the Java implementation of the subset used
 * by tie-main {@code config.parse_data} (header strip, optional table name,
 * bare tables). Supported values: strings (double-quoted with escapes),
 * integers, floats, booleans, nested tables and array elements. Separators are
 * commas; trailing commas are tolerated; {@code //} line comments are skipped.
 * <p>
 * Deterministic and pure; malformed input raises {@link IllegalArgumentException}.
 */
public final class Td {

    private Td() {
    }

    /**
     * Parses a td document into its root table. Accepts an optional
     * {@code type tie<data>} header line and an optional table name
     * ({@code name = [...]}), matching tiec's parse_data behaviour.
     */
    public static TdTable parse(String source) {
        Parser p = new Parser(stripOptionalName(headerStripped(source)));
        TdTable table = p.parseTable();
        p.skipWsAndComments();
        if (!p.atEnd()) {
            throw p.error("unexpected trailing content");
        }
        return table;
    }

    /** Serializes a table back to td text (2-space indent). */
    public static String write(TdTable table) {
        StringBuilder sb = new StringBuilder();
        writeTable(sb, table, 0);
        return sb.toString();
    }

    // ---------- writing ----------

    private static void writeTable(StringBuilder sb, TdTable table, int indent) {
        sb.append("[\n");
        String pad = "  ".repeat(indent + 1);
        for (String key : table.keys()) {
            indentLine(sb, pad);
            sb.append(key);
            sb.append(" = ");
            writeValue(sb, table.get(key), indent + 1);
            sb.append(",\n");
        }
        for (TdValue element : table.elements()) {
            indentLine(sb, pad);
            writeValue(sb, element, indent + 1);
            sb.append(",\n");
        }
        if (indent > 0) {
            indentLine(sb, "  ".repeat(indent));
        }
        sb.append(']');
    }

    private static void indentLine(StringBuilder sb, String pad) {
        sb.append(pad);
    }

    private static void writeValue(StringBuilder sb, TdValue value, int indent) {
        if (value instanceof TdTable nested) {
            writeTable(sb, nested, indent);
            return;
        }
        TdValue.Scalar s = value.scalar();
        switch (s.kind()) {
            case STRING -> writeString(sb, s.str());
            case INT -> sb.append(s.i());
            case FLOAT -> {
                double d = s.f();
                sb.append((d == Math.rint(d) && !Double.isInfinite(d)) ? String.format("%.1f", d) : Double.toString(d));
            }
            case BOOL -> sb.append(s.b() ? "true" : "false");
        }
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------- header strip ----------

    private static String headerStripped(String source) {
        if (source == null) {
            throw new IllegalArgumentException("td source must not be null");
        }
        String[] lines = source.split("\n", -1);
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }
            if (trimmed.startsWith("type ")) {
                continue; // type tie<data> header
            }
            kept.add(line);
        }
        return String.join("\n", kept);
    }

    /**
     * Strips an optional top-level table name ({@code name = [ ... ]}),
     * matching tiec's parse_data behaviour. A bare table ({@code [ ... ]})
     * is returned unchanged.
     */
    private static String stripOptionalName(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < s.length() && identChar(s.charAt(i), i == start)) {
            i++;
        }
        if (i == start) {
            return s; // bare table: starts with '['
        }
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (i >= s.length() || s.charAt(i) != '=') {
            return s;
        }
        i++;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (i >= s.length() || s.charAt(i) != '[') {
            return s;
        }
        return s.substring(i);
    }

    private static boolean identChar(char c, boolean first) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'
                || (!first && c >= '0' && c <= '9');
    }

    // ---------- parsing ----------

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("td parse error at offset " + pos + ": " + msg);
        }

        void skipWsAndComments() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                    while (pos < src.length() && src.charAt(pos) != '\n') {
                        pos++;
                    }
                } else {
                    break;
                }
            }
        }

        TdTable parseTable() {
            skipWsAndComments();
            if (pos >= src.length() || src.charAt(pos) != '[') {
                throw error("expected '['");
            }
            pos++;
            List<TdEntry> entries = new ArrayList<>();
            while (true) {
                skipWsAndComments();
                if (pos >= src.length()) {
                    throw error("unterminated table");
                }
                char c = src.charAt(pos);
                if (c == ']') {
                    pos++;
                    return new TdTable(entries);
                }
                if (c == ',') {
                    pos++;
                    continue; // tolerate stray separators
                }
                entries.add(parseEntry());
                skipWsAndComments();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                }
            }
        }

        private TdEntry parseEntry() {
            skipWsAndComments();
            // Array element: nested table.
            if (pos < src.length() && src.charAt(pos) == '[') {
                return new TdEntry(null, parseTable());
            }
            String key = readIdentifier();
            if (key == null) {
                // Value-led element: string / number / boolean.
                return new TdEntry(null, parseValue());
            }
            int save = pos;
            skipWsAndComments();
            if (pos < src.length() && src.charAt(pos) == '=') {
                pos++;
                TdValue value = parseValue();
                return new TdEntry(key, value);
            }
            // Bare identifier as an array element.
            pos = save;
            return new TdEntry(null, TdValue.str(key));
        }

        private TdValue parseValue() {
            skipWsAndComments();
            if (pos >= src.length()) {
                throw error("expected value");
            }
            char c = src.charAt(pos);
            if (c == '[') {
                return parseTable();
            }
            if (c == '"') {
                return TdValue.str(readString());
            }
            if (c == 't' && src.startsWith("true", pos)) {
                pos += 4;
                return TdValue.of(true);
            }
            if (c == 'f' && src.startsWith("false", pos)) {
                pos += 5;
                return TdValue.of(false);
            }
            return parseNumber();
        }

        private TdValue parseNumber() {
            int start = pos;
            if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) {
                pos++;
            }
            boolean dot = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' && !dot) {
                    dot = true;
                    pos++;
                } else {
                    break;
                }
            }
            if (pos == start || (pos == start + 1 && (src.charAt(start) == '-' || src.charAt(start) == '+'))) {
                throw error("invalid number");
            }
            String token = src.substring(start, pos);
            if (dot) {
                return TdValue.of(Double.parseDouble(token));
            }
            return TdValue.of(Long.parseLong(token));
        }

        private String readIdentifier() {
            skipWsAndComments();
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                boolean first = pos == start;
                boolean ident = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'
                        || (!first && c >= '0' && c <= '9');
                if (!ident) {
                    break;
                }
                pos++;
            }
            return pos == start ? null : src.substring(start, pos);
        }

        private String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) {
                    throw error("unterminated string");
                }
                char c = src.charAt(pos);
                if (c == '"') {
                    pos++;
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos + 1 >= src.length()) {
                        throw error("dangling escape");
                    }
                    char e = src.charAt(pos + 1);
                    pos += 2;
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        default -> throw error("unknown escape \\" + e);
                    }
                } else {
                    sb.append(c);
                    pos++;
                }
            }
        }
    }
}