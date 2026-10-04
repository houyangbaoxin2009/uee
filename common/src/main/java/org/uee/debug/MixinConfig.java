package org.uee.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.uee.util.JsonReader;

/**
 * A parsed Sponge Mixin configuration.
 *
 * <p>Mixin configs are the machine-readable answer to "which classes does this mod patch", which is
 * the substance of most mod incompatibilities. Existing exporters report the mod list and stop;
 * parsing these configs is what lets UEE report an actual conflict — two mods patching the same
 * target class — rather than leaving the user to discover it from a crash.
 *
 * <p>The format is loader-agnostic, so this lives in the core with no Minecraft dependency and is
 * testable on hand-written fixtures.
 *
 * <p>Structure reference: a config declares a {@code package} prefix and lists mixin class names
 * under {@code mixins} (both sides), {@code client} and {@code server}. An entry may instead be an
 * object carrying its own {@code target} and a nested {@code mixins} array, which is how one config
 * describes mixins for several target classes.
 */
public record MixinConfig(
        String source,
        String modId,
        String packageName,
        String compatibilityLevel,
        String refmap,
        boolean required,
        String minVersion,
        List<Entry> entries) {

    /**
     * One mixin class declared by a config.
     *
     * @param mixinClass fully qualified mixin class name, i.e. package plus the declared name
     * @param environment {@code common}, {@code client} or {@code server}
     * @param explicitTarget explicit target class from a nested form, or {@code null} when the mixin
     *     targets itself
     */
    public record Entry(String mixinClass, String environment, String explicitTarget) {

        /**
         * The class this mixin patches.
         *
         * <p>A mixin whose name is unchanged from its target patches itself; the explicit form names
         * the target directly. The conventional {@code XxxMixin} naming is deliberately <em>not</em>
         * unwrapped: guessing a target from a suffix would produce confident wrong answers on the
         * many mixins that do not follow the convention.
         */
        public String targetClass() {
            return explicitTarget != null ? explicitTarget : mixinClass;
        }
    }

    public MixinConfig {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * Parses a mixin config.
     *
     * @param source a human-readable origin, used in diagnostics (usually the config resource path)
     * @param modId the owning mod, or {@code null} when unknown
     * @param json the config document
     * @throws IllegalArgumentException when the document is not a readable mixin config
     */
    public static MixinConfig parse(String source, String modId, String json) {
        Map<String, Object> root = JsonReader.parseObject(json);
        String pkg = JsonReader.str(root, "package");
        if (pkg == null || pkg.isEmpty()) {
            throw new IllegalArgumentException("mixin config has no 'package': " + source);
        }
        List<Entry> entries = new ArrayList<>(16);
        collect(root, "mixins", "common", pkg, entries);
        collect(root, "client", "client", pkg, entries);
        collect(root, "server", "server", pkg, entries);
        return new MixinConfig(source, modId, pkg,
                JsonReader.str(root, "compatibilityLevel"),
                JsonReader.str(root, "refmap"),
                JsonReader.bool(root, "required"),
                JsonReader.str(root, "minVersion"),
                entries);
    }

    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Object> root, String key, String environment, String pkg,
            List<Entry> out) {
        Object value = root.get(key);
        if (!(value instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item instanceof String name) {
                out.add(new Entry(pkg + "." + name, environment, null));
            } else if (item instanceof Map<?, ?> raw) {
                // The nested form: {"target": "...", "mixins": [...]} — one config section covering
                // several mixin classes for a single target.
                Map<String, Object> nested = (Map<String, Object>) raw;
                String target = JsonReader.str(nested, "target");
                for (String name : JsonReader.strArray(nested, "mixins")) {
                    out.add(new Entry(pkg + "." + name, environment, target));
                }
            }
        }
    }

    /**
     * Distinct target classes this config patches.
     *
     * <p>Only the whole class is reported. Narrowing to a method or field would require reading the
     * compiled mixin, which the config does not carry.
     */
    public String[] targetClasses() {
        return entries.stream().map(Entry::targetClass).distinct().toArray(String[]::new);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Directory prefix that mixin config resources conventionally live under inside a jar. */
    public static final String RESOURCE_SUFFIX = ".mixins.json";
}
