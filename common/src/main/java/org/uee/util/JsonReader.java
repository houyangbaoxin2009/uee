package org.uee.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal recursive-descent JSON reader, for the small configuration documents the exporter has
 * to interpret: mixin configs, mod metadata manifests, loader descriptors.
 *
 * <p>Deliberately <b>not</b> a general-purpose parser and not on the export data path. Export
 * payloads are only ever written, never re-read, so this exists for the handful of small files whose
 * contents drive collection. That lets it stay compact and skip the machinery a general parser would
 * need.
 *
 * <p>Values map to: {@link Map} (object), {@link List} (array), {@link String}, {@link Double} or
 * {@link Long} (number), {@link Boolean}, and {@code null}.
 *
 * <p>Malformed input raises {@link IllegalArgumentException} rather than returning a partial result,
 * so a caller can distinguish "absent" from "corrupt" — a distinction that matters when the input is
 * a third-party mod's config file.
 */
public final class JsonReader {

    private final String src;
    private int pos;

    private JsonReader(String src) {
        this.src = src;
    }

    /** Parses a complete JSON document. Trailing whitespace is allowed, trailing content is not. */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("input is null");
        }
        JsonReader r = new JsonReader(text);
        Object value = r.readValue();
        r.skipWhitespace();
        if (r.pos != text.length()) {
            throw r.error("trailing content");
        }
        return value;
    }

    /** Parses a document and requires it to be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    // ---------------------------------------------------------------- accessors used by callers

    /** Reads a string field, or {@code null} when absent or of another type. */
    public static String str(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof String s ? s : null;
    }

    /** Reads a string field, falling back to {@code fallback} when absent. */
    public static String str(Map<String, Object> obj, String key, String fallback) {
        String v = str(obj, key);
        return v == null ? fallback : v;
    }

    /** Reads a boolean field, or {@code false} when absent or of another type. */
    public static boolean bool(Map<String, Object> obj, String key) {
        return obj.get(key) instanceof Boolean b && b;
    }

    /** Reads an array-of-strings field, or an empty array when absent. */
    @SuppressWarnings("unchecked")
    public static String[] strArray(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (!(v instanceof List<?> list)) {
            return new String[0];
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s) {
                out.add(s);
            }
        }
        return out.toArray(new String[0]);
    }

    // ---------------------------------------------------------------- parser

    private Object readValue() {
        skipWhitespace();
        if (pos >= src.length()) {
            throw error("unexpected end of input");
        }
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>(8);
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            map.put(key, readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw error("expected ',' or '}'");
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> list = new ArrayList<>(8);
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return list;
            }
            throw error("expected ',' or ']'");
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder(32);
        while (true) {
            if (pos >= src.length()) {
                throw error("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw error("unterminated escape");
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > src.length()) {
                        throw error("truncated \\u escape");
                    }
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw error("unknown escape \\" + e);
            }
        }
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-' || peek() == '+') {
            pos++;
        }
        boolean floating = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                floating = floating || c == '.' || c == 'e' || c == 'E';
                pos++;
            } else {
                break;
            }
        }
        String text = src.substring(start, pos);
        if (text.isEmpty()) {
            throw error("expected a value");
        }
        try {
            // Integral values stay Long so ids and counts do not lose precision through a double.
            return floating ? (Object) Double.valueOf(text) : (Object) Long.valueOf(text);
        } catch (NumberFormatException nfe) {
            throw error("invalid number '" + text + "'");
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!src.startsWith(literal, pos)) {
            throw error("expected '" + literal + "'");
        }
        pos += literal.length();
        return value;
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private char peek() {
        if (pos >= src.length()) {
            throw error("unexpected end of input");
        }
        return src.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private IllegalArgumentException error(String message) {
        int line = 1;
        int col = 1;
        for (int i = 0; i < pos && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new IllegalArgumentException("JSON error at line " + line + " col " + col + ": " + message);
    }
}
