package org.uee.util;

import java.nio.charset.StandardCharsets;

/**
 * JSON string escaping with a single-scan fast path.
 *
 * <p>The hot path is "no escape needed" data — registry names, {@code namespace:path} keys,
 * language keys. For those a single scan finds nothing to escape and the string is copied verbatim,
 * which is the overwhelming majority of a Minecraft export. Escaping only costs when a value
 * genuinely contains a quote, backslash or control character.
 *
 * <p>Design note: {@code <}, {@code >}, {@code &} are <em>not</em> escaped. The HTML-escaping
 * behaviour of Gson in the tools we align with is a serialization accident, not part of the
 * contract, and keeping bytes identical to the source string matters more for diffable output.
 */
public final class Json {

    private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private Json() {
    }

    /**
     * Appends {@code s} as a quoted, escaped JSON string.
     *
     * @return the number of characters that required escaping (0 on the verbatim fast path)
     */
    public static int quote(ByteBuf out, String s) {
        if (s == null) {
            out.ascii("null");
            return 0;
        }
        int n = s.length();
        int firstEscape = -1;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20) {
                firstEscape = i;
                break;
            }
        }
        out.u8('"');
        if (firstEscape < 0) {
            out.utf8(s);
            out.u8('"');
            return 0;
        }
        out.utf8(s.substring(0, firstEscape));
        int escapes = 0;
        for (int i = firstEscape; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.ascii("\\\"");
                    escapes++;
                    break;
                case '\\':
                    out.ascii("\\\\");
                    escapes++;
                    break;
                case '\n':
                    out.ascii("\\n");
                    escapes++;
                    break;
                case '\r':
                    out.ascii("\\r");
                    escapes++;
                    break;
                case '\t':
                    out.ascii("\\t");
                    escapes++;
                    break;
                case '\b':
                    out.ascii("\\b");
                    escapes++;
                    break;
                case '\f':
                    out.ascii("\\f");
                    escapes++;
                    break;
                default:
                    if (c < 0x20) {
                        out.ascii("\\u00");
                        out.u8(HEX[(c >> 4) & 0xF]);
                        out.u8(HEX[c & 0xF]);
                        escapes++;
                    } else {
                        // A non-ASCII character the scan above let through: it may be a surrogate
                        // pair boundary, so hand the remainder to the UTF-8 encoder in one go and
                        // stop tracking escapes character by character.
                        int next = i;
                        while (next < n) {
                            char d = s.charAt(next);
                            if (d == '"' || d == '\\' || d < 0x20) {
                                break;
                            }
                            next++;
                        }
                        out.utf8(s.substring(i, next));
                        i = next - 1;
                    }
                    break;
            }
        }
        out.u8('"');
        return escapes;
    }

    /** Appends a quoted JSON string, or the literal {@code null} when the value is absent. */
    public static void quoteOpt(ByteBuf out, String s) {
        quote(out, s);
    }

    /** Appends a JSON array of strings with no separator ambiguity. */
    public static void quoteArray(ByteBuf out, String[] values) {
        out.u8('[');
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    out.u8(',');
                }
                quote(out, values[i]);
            }
        }
        out.u8(']');
    }

    /** True when {@code s} can be emitted without any escaping. Used by tests and diagnostics. */
    public static boolean isPlain(String s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20) {
                return false;
            }
        }
        return true;
    }
}
