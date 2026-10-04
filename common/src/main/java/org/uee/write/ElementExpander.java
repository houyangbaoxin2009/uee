package org.uee.write;

import org.uee.model.BlockElement;
import org.uee.model.DebugSection;
import org.uee.model.ElementKind;
import org.uee.model.Dependency;
import org.uee.model.EntityElement;
import org.uee.model.ItemElement;
import org.uee.model.ModElement;
import org.uee.model.RecipeElement;

/**
 * Expands a normalized element into format-independent fields.
 *
 * <p>This is the single definition of "which fields does an item have". Every text backend consumes
 * it, so adding a field is a one-line change that reaches td, YAML, TOML and XML together.
 *
 * <p>The field names here match {@code JsonWriter}'s hand-written output, which stays hand-written
 * because the JSON path is the hot one and benefits from emitting straight into the byte buffer
 * without an indirection per field. The two must be kept in step; a mismatch is the kind of defect
 * that produces no compile error and no test failure, only a missing key downstream.
 */
public final class ElementExpander {

    /** Columns of the dependency record list, in emission order. */
    public static final String[] DEPENDENCY_COLUMNS = {"id", "range", "kind"};

    private ElementExpander() {
    }

    /** Flattens a mod's dependencies into rows matching {@link #DEPENDENCY_COLUMNS}. */
    public static java.util.List<String[]> dependencyRows(org.uee.model.ModElement e) {
        java.util.List<String[]> rows = new java.util.ArrayList<>(e.dependencies().length);
        for (org.uee.model.Dependency d : e.dependencies()) {
            rows.add(new String[] {
                    d.id(),
                    d.hasVersionRange() ? d.versionRange() : "",
                    d.kind().name().toLowerCase(java.util.Locale.ROOT)});
        }
        return rows;
    }

    public static void item(ItemElement e, FieldVisitor v) {
        v.element(ElementKind.ITEM, e.registryName());
        v.string("namespace", e.namespace());
        v.string("translationKey", e.translationKey());
        v.string("name", e.nameZh());
        v.string("englishName", e.nameEn());
        v.number("maxStackSize", e.maxStackSize());
        v.number("maxDurability", e.maxDurability());
        v.array("tags", e.tags());
        v.array("creativeTabs", e.creativeTabs());
        v.bool("blockItem", e.blockItem());
        v.end();
    }

    public static void entity(EntityElement e, FieldVisitor v) {
        v.element(ElementKind.ENTITY, e.registryName());
        v.string("namespace", e.namespace());
        v.string("translationKey", e.translationKey());
        v.string("name", e.nameZh());
        v.string("englishName", e.nameEn());
        v.string("category", e.category());
        v.end();
    }

    public static void block(BlockElement e, FieldVisitor v) {
        v.element(ElementKind.BLOCK, e.registryName());
        v.string("namespace", e.namespace());
        v.string("name", e.nameZh());
        v.string("englishName", e.nameEn());
        v.decimal("hardness", e.hardness());
        v.decimal("blastResistance", e.blastResistance());
        v.number("lightEmission", e.lightEmission());
        v.bool("hasBlockItem", e.hasBlockItem());
        v.string("material", e.material());
        v.array("tags", e.tags());
        v.end();
    }

    public static void recipe(RecipeElement e, FieldVisitor v) {
        v.element(ElementKind.RECIPE, e.id());
        v.string("type", e.type());
        v.string("namespace", e.namespace());
        v.array("inputSlots", e.inputSlots());
        v.array("outputSlots", e.outputSlots());
        v.array("outputItems", e.outputItems());
        if (e.experience() != null) {
            v.decimal("experience", e.experience());
        }
        if (e.cookTime() != null) {
            v.number("cookTime", e.cookTime());
        }
        v.end();
    }

    public static void mod(ModElement e, FieldVisitor v) {
        v.element(ElementKind.MOD, e.id());
        v.string("name", e.name());
        v.string("version", e.version());
        v.string("namespace", e.namespace());
        v.string("loader", e.loader());
        v.string("minecraftVersion", e.minecraftVersion());
        v.array("authors", e.authors());
        v.string("license", e.license());
        v.string("description", e.description());
        if (e.dependencies().length > 0) {
            v.records("dependencies", DEPENDENCY_COLUMNS, dependencyRows(e));
        }
        v.array("providers", e.providers());
        v.string("sourceFile", e.sourceFile());
        v.end();
    }

    public static void generic(ElementKind kind, String namespace, String key, String nameZh,
            String nameEn, String[] listValues, String[] extra, FieldVisitor v) {
        // No identity value here: these records are not registry-backed, so labelling their identity
        // "registryName" would be wrong. Their identity goes in an explicit "key" field below.
        v.element(kind, null);
        v.string("key", key);
        v.string("namespace", namespace);
        v.string("name", nameZh);
        v.string("englishName", nameEn);
        v.array("values", listValues);
        if (extra != null) {
            for (int i = 0; i + 1 < extra.length; i += 2) {
                v.string(extra[i], extra[i + 1]);
            }
        }
        v.end();
    }

    public static void debug(DebugSection s, FieldVisitor v) {
        v.element(ElementKind.DEBUG, s.name());
        for (int i = 0; i < s.size(); i++) {
            v.string(s.keys()[i], s.values()[i]);
        }
        v.end();
    }
}
