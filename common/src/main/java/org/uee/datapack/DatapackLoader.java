package org.uee.datapack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.uee.analysis.Finding;
import org.uee.config.ConfigFile;
import org.uee.config.Tokens;
import org.uee.model.ElementKind;
import org.uee.util.JsonReader;
import org.tielang.td.Td;

/**
 * Reads UEE definitions out of datapacks.
 *
 * <h2>Layout</h2>
 *
 * <pre>
 * data/&lt;namespace&gt;/uee/flows/&lt;id&gt;.json        or .td
 * data/&lt;namespace&gt;/uee/targets/&lt;id&gt;.json      or .td
 * data/&lt;namespace&gt;/uee/analyses/&lt;id&gt;.json     or .td
 * </pre>
 *
 * <p>A datapack is already a collection of namespaced JSON under {@code data/}, so UEE's definitions
 * live where every other datapack file does. Nothing new has to be explained to a datapack author,
 * and a mod that ships definitions ships them the same way as a pack the user drops in.
 *
 * <h2>Two accepted syntaxes</h2>
 *
 * <p>{@code .json}, because that is what a datapack is and what its tooling expects;
 * {@code .td}, because that is what the rest of the tie ecosystem writes and UEE's own config uses.
 * Both parsers already exist for other reasons, so accepting both costs almost nothing, and it means
 * the choice is the author's rather than ours. When a name exists in both forms that is reported and
 * the JSON one is used, because silently picking one would make the loaded set depend on the order a
 * directory happened to be listed in.
 *
 * <p>Malformed definitions are reported as findings and skipped, never fatal: one bad file in a
 * datapack must not stop the other definitions from loading, for the same reason one bad element does
 * not stop an export.
 */
public final class DatapackLoader {

    /** Directory under a datapack's namespace that holds UEE definitions. */
    public static final String ROOT = "uee";
    /** Sub-directory of flow definitions. */
    public static final String FLOWS = "flows";
    /** Sub-directory of collection-target definitions. */
    public static final String TARGETS = "targets";
    /** Sub-directory of analysis-strategy definitions. */
    public static final String ANALYSES = "analyses";

    private DatapackLoader() {
    }

    /** What a load produced, including every problem encountered. */
    public record Loaded(List<FlowDefinition> flows, List<TargetDefinition> targets,
            List<StrategyDefinition> strategies, List<Finding> problems) {

        public Loaded {
            flows = List.copyOf(flows);
            targets = List.copyOf(targets);
            strategies = List.copyOf(strategies);
            problems = List.copyOf(problems);
        }

        public static Loaded empty() {
            return new Loaded(List.of(), List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return flows.isEmpty() && targets.isEmpty() && strategies.isEmpty();
        }
    }

    /** Reads every definition from one datapack. */
    public static Loaded load(DatapackSource source) {
        if (source == null || !source.hasPath() || !Files.isDirectory(source.path())) {
            return Loaded.empty();
        }
        Path dataRoot = source.path().resolve("data");
        if (!Files.isDirectory(dataRoot)) {
            return Loaded.empty();
        }
        List<FlowDefinition> flows = new ArrayList<>(4);
        List<TargetDefinition> targets = new ArrayList<>(4);
        List<StrategyDefinition> strategies = new ArrayList<>(4);
        List<Finding> problems = new ArrayList<>(2);

        for (Path namespaceDir : sortedDirs(dataRoot)) {
            String namespace = namespaceDir.getFileName().toString();
            Path ueeRoot = namespaceDir.resolve(ROOT);
            if (!Files.isDirectory(ueeRoot)) {
                continue;
            }
            readAll(ueeRoot.resolve(FLOWS), source, problems, (name, text, isJson) ->
                    flows.add(parseFlow(namespace, source, name, text, problems)));
            readAll(ueeRoot.resolve(TARGETS), source, problems, (name, text, isJson) ->
                    targets.add(parseTarget(namespace, source, name, text, problems)));
            readAll(ueeRoot.resolve(ANALYSES), source, problems, (name, text, isJson) ->
                    strategies.add(parseStrategy(namespace, source, name, text, problems)));
        }
        // A parse failure returns null from the callbacks above; drop those rather than carrying
        // partial definitions into the catalog.
        flows.removeIf(java.util.Objects::isNull);
        targets.removeIf(java.util.Objects::isNull);
        strategies.removeIf(java.util.Objects::isNull);
        return new Loaded(flows, targets, strategies, problems);
    }

    /** Loads and merges several datapacks, lowest precedence first. */
    public static Loaded loadAll(List<DatapackSource> sources) {
        List<FlowDefinition> flows = new ArrayList<>(8);
        List<TargetDefinition> targets = new ArrayList<>(8);
        List<StrategyDefinition> strategies = new ArrayList<>(8);
        List<Finding> problems = new ArrayList<>(4);
        List<DatapackSource> ordered = new ArrayList<>(sources);
        // Lowest precedence first, so a later entry contributes the winning definition for an id.
        ordered.sort(java.util.Comparator.comparingInt(s -> s.origin().precedence()));
        for (DatapackSource source : ordered) {
            Loaded loaded = load(source);
            flows.addAll(loaded.flows());
            targets.addAll(loaded.targets());
            strategies.addAll(loaded.strategies());
            problems.addAll(loaded.problems());
        }
        return new Loaded(flows, targets, strategies, problems);
    }

    // ---------------------------------------------------------------- directory walking

    /** Callback for one definition file. Returning {@code null} means the parse failed. */
    @FunctionalInterface
    private interface FileHandler {
        Object handle(String name, String text, boolean isJson);
    }

    /**
     * Reads every definition file in a directory, resolving the {@code .json} / {@code .td} choice.
     *
     * <p>Groups by base name first so a name present in both forms is a reported conflict rather than
     * two definitions, one of which would otherwise shadow the other unpredictably.
     */
    private static void readAll(Path dir, DatapackSource source, List<Finding> problems,
            FileHandler handler) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        Map<String, Path> json = new java.util.TreeMap<>();
        Map<String, Path> td = new java.util.TreeMap<>();
        for (Path file : sortedFiles(dir)) {
            String name = file.getFileName().toString();
            if (name.endsWith(".json")) {
                json.put(baseName(name, ".json"), file);
            } else if (name.endsWith(".td")) {
                td.put(baseName(name, ".td"), file);
            }
        }
        Set<String> names = new LinkedHashSet<>(json.keySet());
        names.addAll(td.keySet());
        for (String name : names) {
            Path chosen = json.containsKey(name) ? json.get(name) : td.get(name);
            if (json.containsKey(name) && td.containsKey(name)) {
                problems.add(new Finding("datapack_duplicate_syntax", Finding.Severity.WARN,
                        source.id(),
                        "definitions '" + name + "' exist as both .json and .td in "
                                + source.id() + "; the .json one is used",
                        new String[] {"datapack", source.id(), "name", name}));
            }
            try {
                String text = Files.readString(chosen, StandardCharsets.UTF_8);
                handler.handle(name, text, chosen == json.get(name));
            } catch (IOException | RuntimeException e) {
                problems.add(new Finding("datapack_read_failed", Finding.Severity.WARN, source.id(),
                        "could not read '" + name + "' from " + source.id() + ": " + e,
                        new String[] {"datapack", source.id(), "file", chosen.getFileName().toString()}));
            }
        }
    }

    private static String baseName(String fileName, String suffix) {
        return fileName.substring(0, fileName.length() - suffix.length());
    }

    private static List<Path> sortedDirs(Path root) {
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<Path> sortedFiles(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------- parsing

    /** Reads a document as a flat map, from whichever syntax the file used. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> flatMap(String text, boolean isJson) {
        if (isJson) {
            return JsonReader.parseObject(text);
        }
        // td has no objects, so a definition is written as a table of name/value pairs and the
        // nested shapes (a selector, a rule) are nested tables. Converting to the same flat map the
        // JSON path produces keeps one consumer for both syntaxes.
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        org.tielang.td.TdTable root = Td.parse(text);
        for (String key : root.keys()) {
            out.put(key, fromTd(root.get(key)));
        }
        for (org.tielang.td.TdValue element : root.elements()) {
            // The td form wraps the definition in a table named after it; unwrap that one level.
            if (element instanceof org.tielang.td.TdTable nested) {
                for (String key : nested.keys()) {
                    out.putIfAbsent(key, fromTd(nested.get(key)));
                }
            }
        }
        return out;
    }

    private static Object fromTd(org.tielang.td.TdValue value) {
        if (value instanceof org.tielang.td.TdTable table) {
            if (!table.keys().isEmpty()) {
                Map<String, Object> map = new java.util.LinkedHashMap<>();
                for (String k : table.keys()) {
                    map.put(k, fromTd(table.get(k)));
                }
                return map;
            }
            List<Object> list = new ArrayList<>(table.elements().size());
            for (org.tielang.td.TdValue e : table.elements()) {
                list.add(fromTd(e));
            }
            return list;
        }
        org.tielang.td.TdValue.Scalar s = value.scalar();
        return switch (s.kind()) {
            case STRING -> s.str();
            case INT -> s.i();
            case FLOAT -> s.f();
            case BOOL -> s.b();
        };
    }

    private static FlowDefinition parseFlow(String namespace, DatapackSource source, String name,
            String text, List<Finding> problems) {
        try {
            Map<String, Object> map = flatMap(text, isJson(text));
            String id = string(map, "id", name);
            // A flow is a partial configuration, so its own fields are read by the config parser.
            // Reusing it means a flow can set anything a config file can, and the two cannot drift.
            java.util.Set<String> keys = new java.util.LinkedHashSet<>(map.keySet());
            keys.remove("id");
            keys.remove("description");
            keys.remove("targets");
            keys.remove("strategies");
            // The flow's own id is stamped into its description, so a run can report which flow it
            // followed even though the file itself does not name it.
            map.put("flow", namespace == null ? id : namespace + ":" + id);
            keys.add("flow");
            ConfigFile config = ConfigFile.parse(renderAsTd(map, keys));
            return new FlowDefinition(id, namespace, string(map, "description", null), config,
                    stringList(map, "targets"), stringList(map, "strategies"));
        } catch (RuntimeException e) {
            problems.add(new Finding("datapack_flow_invalid", Finding.Severity.WARN, namespace,
                    "flow '" + name + "' in " + source.id() + " is invalid: " + e.getMessage(),
                    new String[] {"datapack", source.id(), "file", name}));
            return null;
        }
    }

    private static TargetDefinition parseTarget(String namespace, DatapackSource source, String name,
            String text, List<Finding> problems) {
        try {
            Map<String, Object> map = flatMap(text, isJson(text));
            String id = string(map, "id", name);
            String categoryToken = string(map, "category", null);
            if (categoryToken == null) {
                throw new IllegalArgumentException("no 'category'");
            }
            java.util.Set<ElementKind> kinds = Tokens.kinds(categoryToken);
            if (kinds == null || kinds.size() != 1) {
                throw new IllegalArgumentException("unknown category '" + categoryToken + "'");
            }
            Map<String, Object> selectorMap = object(map, "selector");
            Selector selector = selectorMap == null ? Selector.ALL : selectorOf(selectorMap);
            return new TargetDefinition(id, namespace, kinds.iterator().next(), selector,
                    string(map, "description", null));
        } catch (RuntimeException e) {
            problems.add(new Finding("datapack_target_invalid", Finding.Severity.WARN, namespace,
                    "target '" + name + "' in " + source.id() + " is invalid: " + e.getMessage(),
                    new String[] {"datapack", source.id(), "file", name}));
            return null;
        }
    }

    private static StrategyDefinition parseStrategy(String namespace, DatapackSource source,
            String name, String text, List<Finding> problems) {
        try {
            Map<String, Object> map = flatMap(text, isJson(text));
            String id = string(map, "id", name);
            Object rawRules = map.get("rules");
            List<StrategyDefinition.Rule> rules = new ArrayList<>(4);
            if (rawRules instanceof List<?> list) {
                for (Object raw : list) {
                    if (raw instanceof Map<?, ?> ruleMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> rm = (Map<String, Object>) ruleMap;
                        rules.add(ruleOf(rm));
                    }
                }
            }
            return new StrategyDefinition(id, namespace, string(map, "description", null), rules);
        } catch (RuntimeException e) {
            problems.add(new Finding("datapack_strategy_invalid", Finding.Severity.WARN, namespace,
                    "analysis strategy '" + name + "' in " + source.id() + " is invalid: "
                            + e.getMessage(),
                    new String[] {"datapack", source.id(), "file", name}));
            return null;
        }
    }

    private static Selector selectorOf(Map<String, Object> map) {
        return new Selector(string(map, "namespace", null), string(map, "id", null),
                string(map, "tag", null), string(map, "prefix", null),
                string(map, "suffix", null), string(map, "contains", null));
    }

    private static StrategyDefinition.Rule ruleOf(Map<String, Object> map) {
        String kindToken = string(map, "kind", string(map, "rule", null));
        StrategyDefinition.Kind kind = StrategyDefinition.Kind.of(kindToken);
        if (kind == null) {
            throw new IllegalArgumentException("unknown rule kind '" + kindToken + "'");
        }
        Finding.Severity severity = severityOf(string(map, "severity", null));
        List<String> mods = stringList(map, "mods");
        String mod = string(map, "mod", null);
        if (mod != null && mods.isEmpty()) {
            mods = List.of(mod);
        }
        // Fail loudly on a rule that is missing the parameter its kind needs: a rule that silently
        // never matches is worse than one that refuses to load, because the pack author believes
        // their check is running.
        switch (kind) {
            case REQUIRE_MOD, FORBID_MOD -> {
                if (mod == null && mods.isEmpty()) {
                    throw new IllegalArgumentException(kind.name() + " needs 'mod'");
                }
            }
            case REQUIRE_TOGETHER, MUTUALLY_EXCLUSIVE -> {
                if (mods.size() < 2) {
                    throw new IllegalArgumentException(kind.name() + " needs at least two 'mods'");
                }
            }
            case FORBID_MIXIN_TARGET -> {
                if (string(map, "target", null) == null) {
                    throw new IllegalArgumentException("FORBID_MIXIN_TARGET needs 'target'");
                }
            }
            case REQUIRE_NAMESPACE -> {
                if (string(map, "namespace", null) == null) {
                    throw new IllegalArgumentException("REQUIRE_NAMESPACE needs 'namespace'");
                }
            }
            case MIN_COUNT, MAX_COUNT -> {
                if (string(map, "target", null) == null || object(map, "value") == null
                        && map.get("value") == null) {
                    throw new IllegalArgumentException(kind.name() + " needs 'target' and 'value'");
                }
            }
            default -> {
            }
        }
        return new StrategyDefinition.Rule(kind, severity, string(map, "message", null),
                mod, mods, string(map, "target", null), string(map, "namespace", null),
                number(map, "value"));
    }

    private static Finding.Severity severityOf(String token) {
        if (token == null) {
            return Finding.Severity.WARN;
        }
        return switch (token.trim().toLowerCase(Locale.ROOT)) {
            case "error" -> Finding.Severity.ERROR;
            case "info" -> Finding.Severity.INFO;
            default -> Finding.Severity.WARN;
        };
    }

    private static boolean isJson(String text) {
        String trimmed = text.stripLeading();
        return trimmed.startsWith("{");
    }

    // ---------------------------------------------------------------- small accessors

    private static String string(Map<String, Object> map, String key, String fallback) {
        Object v = map.get(key);
        return v instanceof String s && !s.isEmpty() ? s : fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static List<String> stringList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof String s && !s.isEmpty()) {
                    out.add(s);
                }
            }
            return out;
        }
        if (v instanceof String s && !s.isEmpty()) {
            return List.of(s);
        }
        return List.of();
    }

    private static long number(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /** Renders selected keys of a parsed document back as td, so the config parser can read them. */
    private static String renderAsTd(Map<String, Object> map, java.util.Set<String> keys) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("type tie<data>\n\nuee = [\n");
        for (String key : keys) {
            Object value = map.get(key);
            sb.append("  ").append(key).append(" = ").append(tdLiteral(value)).append(",\n");
        }
        return sb.append("]\n").toString();
    }

    private static String tdLiteral(Object value) {
        if (value == null) {
            return "[]";
        }
        if (value instanceof String s) {
            return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(tdLiteral(item));
                first = false;
            }
            return sb.append(']').toString();
        }
        // A nested map cannot be expressed as a config value, and none of the config keys take one.
        return "[]";
    }
}
