package org.uee.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whether a present version satisfies a declared range.
 *
 * <h2>Three answers, not two</h2>
 *
 * <p>The answer is deliberately three-valued. A verdict of "not satisfied" is an accusation — it tells a
 * pack author that two mods do not go together — and a wrong accusation costs more than a missing one,
 * because it sends someone to fix a thing that is not broken. So anything that cannot be read with
 * confidence comes back as {@link Verdict#UNKNOWN} and is reported as such rather than guessed at.
 *
 * <p>That conservatism is not defensiveness about the code. Minecraft has changed its version scheme once
 * already, a year-based scheme now coexists with the older {@code 1.x} one, and mod versions are written by
 * hand in a dozen styles: {@code 1.2.3}, {@code v1.2}, {@code 1.2.3-beta.1}, {@code 2.0.0+build.5},
 * {@code 1.21.x}. Ordering those reliably is not a solved problem, and a comparator that pretends otherwise
 * would be wrong quietly.
 *
 * <h2>What is understood</h2>
 *
 * <p>The interval forms the loaders document — {@code [1.0,2.0)}, {@code [1.0,)}, {@code (,2.0]},
 * {@code [1.0]}, {@code *} — plus the bare version, which means that version exactly, and the trailing
 * {@code .x} or {@code .+} wildcard. Several intervals may be given, in which case any of them may match.
 */
public final class VersionRanges {

    /** What can be said about a version and a range. */
    public enum Verdict {
        /** The range is understood and the version is inside it. */
        SATISFIED,
        /** The range is understood and the version is outside it. */
        VIOLATED,
        /** The range could not be read, or the two could not be ordered. Says nothing either way. */
        UNKNOWN
    }

    private VersionRanges() {
    }

    /**
     * Whether {@code version} is inside {@code range}.
     *
     * @param version the version that is present
     * @param range the range that was declared; blank means nothing was declared
     */
    public static Verdict check(String version, String range) {
        String wanted = range == null ? "" : range.trim();
        if (wanted.isEmpty()) {
            // Nothing declared is not a satisfied range; it is an absent one.
            return Verdict.UNKNOWN;
        }
        if (version == null || version.isBlank()) {
            return Verdict.UNKNOWN;
        }
        if ("*".equals(wanted) || "any".equalsIgnoreCase(wanted)) {
            return Verdict.SATISFIED;
        }

        List<String> intervals = split(wanted);
        boolean sawUnderstood = false;
        for (String interval : intervals) {
            Verdict verdict = checkOne(version.trim(), interval);
            if (verdict == Verdict.SATISFIED) {
                return Verdict.SATISFIED;
            }
            if (verdict == Verdict.VIOLATED) {
                sawUnderstood = true;
            }
        }
        // All intervals were understood and none matched: violated. Anything else is unknown, because an
        // interval that could not be read might have been the one that matched.
        return sawUnderstood ? Verdict.VIOLATED : Verdict.UNKNOWN;
    }

    /**
     * Splits a possibly-multi-interval range at the boundaries between intervals.
     *
     * <p>A comma separates intervals only when brackets are in play at all: {@code [1,2),[3,4)}. A comma with
     * no brackets anywhere is not a form the loaders document, and is left whole so that it fails to parse
     * rather than being read as two intervals that nobody wrote.
     */
    private static List<String> split(String range) {
        if (range.indexOf('[') < 0 && range.indexOf('(') < 0) {
            return List.of(range);
        }
        List<String> out = new ArrayList<>(2);
        int depth = 0;
        int start = 0;
        for (int i = 0; i < range.length(); i++) {
            char c = range.charAt(i);
            if (c == '[' || c == '(') {
                depth++;
            } else if (c == ']' || c == ')') {
                depth--;
                // A comma outside any bracket separates intervals; one inside is the interval's own.
            } else if (c == ',' && depth <= 0) {
                out.add(range.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(range.substring(start).trim());
        return out;
    }

    private static Verdict checkOne(String version, String interval) {
        if (interval.isEmpty()) {
            return Verdict.UNKNOWN;
        }
        char first = interval.charAt(0);
        boolean bracketed = first == '[' || first == '(';
        boolean upperOpen = false;
        String body = interval;

        if (bracketed) {
            char last = interval.charAt(interval.length() - 1);
            if (last != ']' && last != ')') {
                return Verdict.UNKNOWN;
            }
            upperOpen = last == ')';
            body = interval.substring(1, interval.length() - 1);
        } else if (interval.indexOf(',') >= 0) {
            // A comma without brackets is not a form the loaders document.
            return Verdict.UNKNOWN;
        }

        int comma = body.indexOf(',');
        if (comma < 0) {
            // A single version: exact when bracketed, and also when bare, since that is what a bare version
            // means in every manifest seen here.
            String single = body.trim();
            if (single.isEmpty()) {
                return Verdict.UNKNOWN;
            }
            if (hasWildcard(single)) {
                return prefixMatch(version, single);
            }
            return compare(version, single) == 0 ? Verdict.SATISFIED : Verdict.VIOLATED;
        }

        String low = body.substring(0, comma).trim();
        String high = body.substring(comma + 1).trim();

        // The lower bound: inclusive when the bracket was square.
        if (!low.isEmpty()) {
            if (hasWildcard(low)) {
                return prefixMatch(version, low);
            }
            int c = compare(version, low);
            if (c == Integer.MIN_VALUE) {
                return Verdict.UNKNOWN;
            }
            if (c < 0) {
                return Verdict.VIOLATED;
            }
            if (c == 0 && first == '(') {
                return Verdict.VIOLATED;
            }
        }

        // The upper bound: exclusive when the closing bracket was round.
        if (!high.isEmpty()) {
            if (hasWildcard(high)) {
                return prefixMatch(version, high);
            }
            int c = compare(version, high);
            if (c == Integer.MIN_VALUE) {
                return Verdict.UNKNOWN;
            }
            if (c > 0) {
                return Verdict.VIOLATED;
            }
            if (c == 0 && upperOpen) {
                return Verdict.VIOLATED;
            }
        }

        return Verdict.SATISFIED;
    }

    private static boolean hasWildcard(String spec) {
        return spec.endsWith(".x") || spec.endsWith(".X") || spec.endsWith(".*")
                || spec.endsWith(".+");
    }

    /**
     * {@code 1.21.x} matches {@code 1.21.1} by prefix, which needs no ordering at all.
     *
     * <p>The wildcard is the last two characters — the dot and the letter — so both come off. Removing only
     * the letter left the dot behind and then appended another one, which made the prefix {@code "1.21.."}
     * and matched nothing; a wildcard that never matches is worse than no wildcard, because it reports every
     * mod that uses the form as out of range.
     */
    private static Verdict prefixMatch(String version, String spec) {
        String prefix = spec.substring(0, spec.length() - 2) + '.';
        return version.startsWith(prefix) ? Verdict.SATISFIED : Verdict.VIOLATED;
    }

    /**
     * Orders two versions, or returns {@link Integer#MIN_VALUE} when they cannot be ordered.
     *
     * <p>Numeric parts are compared as numbers so that {@code 1.10} comes after {@code 1.9}; parts that are
     * not numeric are compared as text when both sides are text, since that is all that can be done with a
     * qualifier. A numeric part against a text part has no defined order, which is the case that returns the
     * sentinel rather than inventing one.
     */
    static int compare(String a, String b) {
        String[] left = parts(a);
        String[] right = parts(b);
        int n = Math.max(left.length, right.length);
        for (int i = 0; i < n; i++) {
            String x = i < left.length ? left[i] : null;
            String y = i < right.length ? right[i] : null;
            if (x == null) {
                // A version that has run out of parts is not necessarily older -- 1.0 and 1.0.0 are the same
                // version spelled differently -- so a missing numeric part counts as zero, which orders 1.21
                // before 1.21.1 and leaves 1.0 equal to 1.0.0. A qualifier, though, has no order against
                // nothing at all, and that is the case that stays unresolved.
                if (!isNumeric(y)) {
                    return Integer.MIN_VALUE;
                }
                x = "0";
            }
            if (y == null) {
                if (!isNumeric(x)) {
                    return Integer.MIN_VALUE;
                }
                y = "0";
            }
            boolean xn = isNumeric(x);
            boolean yn = isNumeric(y);
            if (xn && yn) {
                int c = Long.compare(Long.parseLong(x), Long.parseLong(y));
                if (c != 0) {
                    return c;
                }
            } else if (!xn && !yn) {
                int c = x.compareTo(y);
                if (c != 0) {
                    return c;
                }
            } else {
                return Integer.MIN_VALUE;
            }
        }
        return 0;
    }

    private static String[] parts(String version) {
        String v = version.toLowerCase(Locale.ROOT);
        if (v.startsWith("v")) {
            v = v.substring(1);
        }
        // Build metadata never affects precedence; qualifiers do, and are kept as their own parts.
        int plus = v.indexOf('+');
        if (plus >= 0) {
            v = v.substring(0, plus);
        }
        return v.split("[.\\-]");
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty() || s.length() > 18) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
