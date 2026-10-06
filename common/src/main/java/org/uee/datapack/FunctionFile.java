package org.uee.datapack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * A parsed function: {@code data/<namespace>/function/<path>.mcfunction}.
 *
 * <h2>The one category that is not JSON</h2>
 *
 * <p>Everything else in the datapack half is a JSON document. A function is a text file of commands, one
 * per line, and that changes what "parsing" means: there is no structure to read, only lines to classify.
 * The classification is where the risk is, and it is not guessable — the rules below were read off the
 * game's own reader rather than inferred from the format's appearance, and every one of them is a thing a
 * reasonable implementation gets wrong.
 *
 * <h2>The rules, as the game applies them</h2>
 *
 * <ul>
 *   <li><b>A line ending in a backslash continues onto the next.</b> The backslash is removed and the next
 *       line's trimmed text is appended. So the number of lines is not the number of commands, and a
 *       reader that counted lines would report a figure the game does not agree with. A file whose last
 *       line ends with a backslash is one the game refuses outright.
 *   <li><b>A line whose first non-space character is {@code #} is a comment</b>, and an empty line is
 *       skipped. This matters more than it sounds: of a thousand and more functions in one real pack,
 *       several consist of nothing but a licence header, and a reader that counted non-empty lines would
 *       report them as functions with a dozen commands.
 *   <li><b>A line starting with {@code $} is a macro</b>, not a command. It is a different kind of line
 *       that is substituted before execution, and counting it as a command would overstate what the
 *       function does while understating that it takes parameters.
 *   <li><b>A line starting with {@code /} is an error, not a command.</b> The game refuses it with a
 *       message telling the author to drop the slash, which is a common mistake and worth reporting the
 *       same way rather than silently counting.
 * </ul>
 *
 * <h2>References are the point</h2>
 *
 * <p>What a wiki wants from a function is what it calls: the call graph. A function is named by the
 * {@code function} command, either at the start of a line or after {@code run} or {@code schedule}, and
 * the name may be a tag written with a leading {@code #}. Those are gathered. The commands themselves are
 * not copied — they are tens of thousands of lines and they are already in the file, which is the same
 * reason a loot table's entries are gathered rather than its whole document.
 *
 * <h2>Calls that cannot be resolved, which are counted rather than dropped</h2>
 *
 * <p>A macro line may compute the name it calls: {@code $function mod:path/$(mode)} names a different
 * function depending on what the caller passes. There is no id to record, and there is no honest way to
 * pretend otherwise. But dropping it would leave the call graph missing an edge with nothing to say one was
 * there, and a reader asking "what calls this" would find nothing and conclude the answer was nothing. So
 * they are kept as raw text under their own field: the difference between calling nothing and calling
 * something computed at run time is exactly the kind of distinction this project keeps running into.
 */
public final class FunctionFile {

    /**
     * Tokens that may precede the {@code function} keyword in a well-formed command.
     *
     * <p>Checked rather than accepting any occurrence of the word, so a {@code say} that happens to contain
     * it is not read as a call. These three are the positions the command grammar allows: the start of a
     * command, after {@code run} (from {@code execute ... run function}), and after {@code schedule}.
     */
    private static final java.util.Set<String> PRECEDING = java.util.Set.of("run", "schedule");

    /**
     * What a file contains.
     *
     * @param commands how many commands it runs, after continuations are joined
     * @param comments how many comment lines it has
     * @param macros how many macro lines it has
     * @param functions the functions it calls, in order, de-duplicated
     * @param tags the function tags it calls, in order, de-duplicated, without the leading hash
     * @param dynamic calls whose target is computed at run time rather than written down, as the raw text
     * @param lines how many lines the file has, for a reader that wants both figures
     * @param unterminatedContinuation whether the file ends with a line continuation, which makes it
     *     unloadable
     */
    public record Function(int commands, int comments, int macros, List<String> functions,
            List<String> tags, List<String> dynamic, int lines, boolean unterminatedContinuation) {

        public Function {
            functions = List.copyOf(functions);
            tags = List.copyOf(tags);
            dynamic = List.copyOf(dynamic);
        }

        /** The functions as an array, for a record's value list. */
        public String[] functionArray() {
            return functions.toArray(new String[0]);
        }

        /** Whether the file takes parameters. */
        public boolean usesMacros() {
            return macros > 0;
        }
    }

    private FunctionFile() {
    }

    /**
     * Parses a function.
     *
     * <p>Never throws. A function is a text file with no syntax a reader can be wrong about, so the worst
     * case is a line that is counted as a command when it is not — and the game's own reader is stricter
     * than this for reasons of its own. What this does refuse to do is lose a record: a file that is
     * nothing but comments still yields a record saying so, because "this function does nothing" is worth
     * knowing and a missing record is not the same answer.
     */
    public static Function parse(String text) {
        List<String> rawLines = splitLines(text);
        int lines = rawLines.size();

        int commands = 0;
        int comments = 0;
        int macros = 0;
        boolean unterminated = false;
        LinkedHashSet<String> functions = new LinkedHashSet<>();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        LinkedHashSet<String> dynamic = new LinkedHashSet<>();

        int i = 0;
        while (i < lines) {
            String line = rawLines.get(i).trim();
            i++;

            // A continuation joins this line with the next, repeatedly. The game refuses a file that ends
            // mid-continuation, which is recorded rather than thrown: the file is broken, and saying so is
            // more use than dropping the record.
            if (endsWithContinuation(line)) {
                StringBuilder joined = new StringBuilder(line.substring(0, line.length() - 1));
                // Whether the join ended on a complete line. It is not enough to look at the joined text
                // afterwards, because the backslash is what was removed: a join that ran out of lines and
                // one that found a complete line both leave text that does not end in one.
                boolean complete = false;
                while (i < lines) {
                    String next = rawLines.get(i).trim();
                    i++;
                    if (endsWithContinuation(next)) {
                        joined.append(next, 0, next.length() - 1);
                    } else {
                        joined.append(next);
                        complete = true;
                        break;
                    }
                }
                if (!complete) {
                    // The file ended mid-continuation. The game refuses such a file outright; the fact is
                    // recorded so a reader is told rather than left wondering why it never runs.
                    unterminated = true;
                }
                line = joined.toString().trim();
            }

            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == '#') {
                comments++;
                continue;
            }
            if (line.charAt(0) == '$') {
                // A macro. The rest of the line is still a command and its references still count, so it
                // is scanned the same way -- only the classification differs.
                macros++;
                scan(line.substring(1).trim(), functions, tags, dynamic);
                continue;
            }
            if (line.charAt(0) == '/') {
                // The game rejects this rather than treating it as a command, so it is not counted as one.
                // It is not a comment either; it is a mistake, and counting it as either would hide it.
                continue;
            }
            commands++;
            scan(line, functions, tags, dynamic);
        }

        return new Function(commands, comments, macros, new ArrayList<>(functions),
                new ArrayList<>(tags), new ArrayList<>(dynamic), lines, unterminated);
    }

    /**
     * Gathers the calls a command makes.
     *
     * <p>Token-based rather than a pattern over the whole line, because the position of the keyword is what
     * distinguishes a call from a word inside an argument. {@code function} counts when it is the first
     * token or follows {@code run} or {@code schedule} — the three places the grammar allows — and what
     * follows it is the name. A name beginning with {@code #} is a tag.
     */
    private static void scan(String command, LinkedHashSet<String> functions, LinkedHashSet<String> tags,
            LinkedHashSet<String> dynamic) {
        List<String> tokens = tokenise(command);
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (!tokens.get(i).equals("function")) {
                continue;
            }
            boolean allowed = i == 0 || PRECEDING.contains(tokens.get(i - 1));
            if (!allowed) {
                continue;
            }
            String name = tokens.get(i + 1);
            if (name.startsWith("#")) {
                name = name.substring(1);
                if (!name.isEmpty()) {
                    tags.add(name);
                }
            } else if (looksLikeId(name)) {
                functions.add(name);
            } else if (name.contains("$(")) {
                // A name computed by a macro. Kept as written, so the graph records that there is an edge
                // here even though where it leads cannot be known without running the function.
                dynamic.add(name);
            }
        }
    }

    /**
     * Splits a command into tokens, keeping quoted runs together.
     *
     * <p>Quotes are respected so that a {@code say} containing spaces does not have its words taken as
     * separate tokens, which could put {@code function} in a position it does not occupy.
     */
    private static List<String> tokenise(String command) {
        List<String> out = new ArrayList<>(8);
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (!quoted && (c == ' ' || c == '\t')) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /** Whether a token is shaped like a namespaced id, which is what a function name has to be. */
    private static boolean looksLikeId(String token) {
        int colon = token.indexOf(':');
        if (colon <= 0 || colon == token.length() - 1) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean fine = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.'
                    || c == '-' || c == ':' || c == '/';
            if (!fine) {
                return false;
            }
        }
        return true;
    }

    private static boolean endsWithContinuation(String line) {
        return !line.isEmpty() && line.charAt(line.length() - 1) == '\\';
    }

    /**
     * Splits into lines, accepting both line endings.
     *
     * <p>A pack authored on Windows has {@code \r\n}, and leaving the {@code \r} on would break the
     * continuation check — an author writing a backslash at the end of a line would find it did not
     * continue.
     */
    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.add(stripCarriageReturn(text.substring(start, i)));
                start = i + 1;
            }
        }
        // A trailing newline ends the last line rather than starting an empty one, and a file without one
        // still contributes its final line.
        if (start < text.length()) {
            out.add(stripCarriageReturn(text.substring(start)));
        }
        return out;
    }

    private static String stripCarriageReturn(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\r')) {
            end--;
        }
        return line.substring(0, end);
    }

    /**
     * The pairs a record carries beside its references.
     *
     * <p>Only what is true, as everywhere: a function with no comments and no macros carries neither key,
     * and a comment-only function is distinguishable because its command count is zero rather than absent.
     */
    public static String[] extraPairs(Function function) {
        java.util.LinkedHashMap<String, String> pairs = new java.util.LinkedHashMap<>();
        pairs.put("commands", Integer.toString(function.commands()));
        if (function.comments() > 0) {
            pairs.put("comments", Integer.toString(function.comments()));
        }
        if (function.usesMacros()) {
            pairs.put("macros", Integer.toString(function.macros()));
        }
        if (!function.tags().isEmpty()) {
            pairs.put("tagRefs", String.join(",", function.tags()));
        }
        if (!function.dynamic().isEmpty()) {
            pairs.put("dynamicRefs", String.join(",", function.dynamic()));
        }
        if (function.unterminatedContinuation()) {
            // The game will not load this file at all, which a reader should be told rather than left to
            // discover by wondering why a function never runs.
            pairs.put("broken", "true");
        }
        String[] out = new String[pairs.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> entry : pairs.entrySet()) {
            out[i++] = entry.getKey();
            out[i++] = entry.getValue();
        }
        return out;
    }

    /** The function's own path from a resource location. */
    public static String pathOf(String resourcePath) {
        if (resourcePath == null) {
            return null;
        }
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        if (!path.startsWith("function/")) {
            return null;
        }
        String rest = path.substring("function/".length());
        if (!rest.endsWith(".mcfunction")) {
            return null;
        }
        String name = rest.substring(0, rest.length() - ".mcfunction".length());
        return name.isEmpty() ? null : name;
    }

    /**
     * The pairs for a file that could not be read at all.
     *
     * <p>Present so a caller has something to record rather than nothing: a function whose bytes cannot be
     * decoded is still a function, and a record saying it is broken is more use than its absence.
     */
    public static String[] unreadablePairs() {
        return new String[] {"broken", "true"};
    }

    /** The resource directory functions live in. */
    public static String directory() {
        return "function";
    }

    /** The extension functions use, which is not {@code .json} like everything else. */
    public static String extension() {
        return ".mcfunction";
    }
}
