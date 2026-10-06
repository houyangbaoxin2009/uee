package org.uee;

import java.util.List;

import org.uee.datapack.AdvancementFile;

/**
 * Checks the advancement parser against the shapes the shipped data actually has.
 *
 * <h2>The shape that matters most</h2>
 *
 * <p>Of the fourteen hundred advancements in the vanilla data, about a tenth carry a {@code display}
 * block. The rest are the invisible recipe-unlock nodes. That ratio is the whole design problem: a reader
 * that required a display would drop ninety per cent of them, and a reader that treated a missing display
 * as damaged would report nine hundred failures for a dataset in perfect health. Both are plausible
 * mistakes and both are checked here.
 *
 * <p>The other two things read off the data rather than assumed: a title is written as a translation key,
 * and an icon's item id is under {@code id} in this version — the older key was {@code item}, and a pack
 * may still write it.
 */
public final class AdvancementTest {

    private static int failures;

    public static void main(String[] args) {
        theTreeLink();
        mostHaveNothingToShow();
        theDisplayedOnes();
        textIsAKeyOrALiteral();
        criteria();
        paths();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- the tree

    private static void theTreeLink() {
        section("the parent link, which is the point of the record");

        AdvancementFile.Advancement child = AdvancementFile.parse("""
                { "parent": "minecraft:story/root",
                  "criteria": { "has_table": { "trigger": "minecraft:inventory_changed" } },
                  "requirements": [ [ "has_table" ] ] }""");
        check("a child records its parent", "minecraft:story/root".equals(child.parent()));
        check("and reaches the record",
                "minecraft:story/root".equals(joined(AdvancementFile.extraPairs(child), "parent")));

        // A root has no parent, and that is a fact about the tree rather than a missing value.
        AdvancementFile.Advancement root = AdvancementFile.parse("""
                { "criteria": { "c": { "trigger": "minecraft:tick" } }, "requirements": [ [ "c" ] ] }""");
        check("a root has no parent", root.parent() == null);
        check("and emits no parent key rather than an empty one",
                joined(AdvancementFile.extraPairs(root), "parent") == null);

        // A pack may write the parent without a namespace, meaning its own. Passed through as written:
        // resolving it needs the file's namespace, which the parser is not given and should not guess.
        AdvancementFile.Advancement unqualified =
                AdvancementFile.parse("{ \"parent\": \"root\", \"criteria\": {} }");
        check("an unqualified parent is passed through as written", "root".equals(unqualified.parent()));

        AdvancementFile.Advancement empty =
                AdvancementFile.parse("{ \"parent\": \"\", \"criteria\": {} }");
        check("an empty parent is treated as none", empty.parent() == null);

        boolean refused = false;
        try {
            AdvancementFile.parse("[1,2,3]");
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        check("a document that is not an object is refused", refused);
    }

    // ---------------------------------------------------------------- display

    private static void mostHaveNothingToShow() {
        section("most advancements have nothing to display");

        AdvancementFile.Advancement bare = AdvancementFile.parse("""
                { "parent": "minecraft:recipes/root",
                  "criteria": { "has_the_recipe": { "trigger": "minecraft:recipe_unlocked" } },
                  "requirements": [ [ "has_the_recipe" ] ],
                  "rewards": { "recipes": [ "minecraft:stone" ] } }""");
        check("an advancement with no display still parses", !bare.display());
        check("its parent survives", bare.parent() != null);
        check("its trigger survives",
                bare.triggers().equals(List.of("minecraft:recipe_unlocked")));
        check("its reward recipes are counted", bare.rewardRecipes() == 1);
        check("and it emits no display key",
                joined(AdvancementFile.extraPairs(bare), "display") == null);
        // The point: it is still a record with something to say, so it is not dropped.
        check("but the record still carries what it has",
                joined(AdvancementFile.extraPairs(bare), "criteria") != null);

        // An empty display object is not a display. Treating it as one would produce a record claiming a
        // title it does not have.
        AdvancementFile.Advancement emptyDisplay =
                AdvancementFile.parse("{ \"display\": {}, \"criteria\": {} }");
        check("an empty display block counts as none", !emptyDisplay.display());

        AdvancementFile.Advancement noCriteria = AdvancementFile.parse("{ }");
        check("a file with nothing at all parses", noCriteria.criteria() == 0);
        check("with no triggers", noCriteria.triggers().isEmpty());
        check("and no display", !noCriteria.display());
    }

    private static void theDisplayedOnes() {
        section("the displayed ones");

        AdvancementFile.Advancement shown = AdvancementFile.parse("""
                { "parent": "minecraft:story/root",
                  "display": {
                    "icon": { "count": 1, "id": "minecraft:grass_block" },
                    "title": { "translate": "advancements.story.root.title" },
                    "description": { "translate": "advancements.story.root.description" },
                    "frame": "challenge",
                    "show_toast": false,
                    "announce_to_chat": false,
                    "hidden": true },
                  "criteria": { "c": { "trigger": "minecraft:tick" } },
                  "requirements": [ [ "c" ] ] }""");
        check("a display is noticed", shown.display());
        check("the title key is read",
                "advancements.story.root.title".equals(shown.titleKey()));
        check("the description key is read",
                "advancements.story.root.description".equals(shown.descriptionKey()));
        check("the icon item is read from the id field",
                "minecraft:grass_block".equals(shown.iconItem()));
        check("the frame is read", "challenge".equals(shown.frame()));
        check("hidden is read", shown.hidden());
        check("everything reaches the record", shown.titleKey() != null
                && "true".equals(joined(AdvancementFile.extraPairs(shown), "display"))
                && "minecraft:grass_block".equals(joined(AdvancementFile.extraPairs(shown), "icon"))
                && "challenge".equals(joined(AdvancementFile.extraPairs(shown), "frame"))
                && "true".equals(joined(AdvancementFile.extraPairs(shown), "hidden")));

        // "task" is what the game assumes when a frame is not written, so writing it out would be noise
        // on the great majority of records.
        AdvancementFile.Advancement task = AdvancementFile.parse("""
                { "display": { "title": { "translate": "t" }, "frame": "task" }, "criteria": {} }""");
        check("an explicit task frame is not written out",
                joined(AdvancementFile.extraPairs(task), "frame") == null);
        // Nine vanilla advancements are hidden; not writing the key when false keeps the rest quiet.
        check("an unhidden advancement emits no hidden key",
                joined(AdvancementFile.extraPairs(task), "hidden") == null);

        // A malformed icon must not cost the parent and the triggers, which is what a strict reader would
        // lose along with the icon.
        AdvancementFile.Advancement badIcon = AdvancementFile.parse("""
                { "parent": "minecraft:story/root",
                  "display": { "icon": "not-an-object", "title": "not-an-object" },
                  "criteria": { "c": { "trigger": "minecraft:tick" } } }""");
        check("a malformed icon and title do not lose the tree link",
                "minecraft:story/root".equals(badIcon.parent())
                        && badIcon.triggers().equals(List.of("minecraft:tick")));
        check("and the icon is absent rather than wrong", badIcon.iconItem() == null);
    }

    private static void textIsAKeyOrALiteral() {
        section("text is a key, or occasionally a literal");

        // Every vanilla file writes a key, because that is what the language tables resolve.
        AdvancementFile.Advancement keyed = AdvancementFile.parse("""
                { "display": { "title": { "translate": "advancements.x.title" },
                               "description": { "translate": "advancements.x.desc" } },
                  "criteria": {} }""");
        check("a key is preferred as the title", "advancements.x.title".equals(keyed.titleOrKey()));
        check("and as the description",
                "advancements.x.desc".equals(keyed.descriptionOrKey()));

        // The format also allows a literal, and a pack that wrote one meant to be read.
        AdvancementFile.Advancement literal = AdvancementFile.parse("""
                { "display": { "title": { "text": "A literal title" },
                               "description": { "text": "And a literal description" } },
                  "criteria": {} }""");
        check("a literal is read", "A literal title".equals(literal.titleLiteral()));
        check("and is what the record shows", "A literal title".equals(literal.titleOrKey()));
        check("a literal description too",
                "And a literal description".equals(literal.descriptionOrKey()));

        // Both at once: the literal is what a reader should see, since it is already resolved text.
        AdvancementFile.Advancement both = AdvancementFile.parse("""
                { "display": { "title": { "translate": "k", "text": "literal wins" } }, "criteria": {} }""");
        check("when both are present the literal wins",
                "literal wins".equals(both.titleOrKey()));

        AdvancementFile.Advancement neither = AdvancementFile.parse("""
                { "display": { "frame": "goal" }, "criteria": {} }""");
        check("with neither, there is simply no title", neither.titleOrKey() == null);
        check("and no title key is emitted",
                joined(AdvancementFile.extraPairs(neither), "title") == null);
    }

    // ---------------------------------------------------------------- criteria

    private static void criteria() {
        section("criteria and rewards");

        // The names of the criteria are the file's own business; the triggers are what identify the kind
        // of action, and a reader wants the kinds.
        AdvancementFile.Advancement several = AdvancementFile.parse("""
                { "criteria": {
                    "a": { "trigger": "minecraft:inventory_changed" },
                    "b": { "trigger": "minecraft:player_killed_entity" },
                    "c": { "trigger": "minecraft:inventory_changed" } },
                  "requirements": [ [ "a", "b" ], [ "c" ] ] }""");
        check("the criteria are counted", several.criteria() == 3);
        check("the triggers are distinct and in order", several.triggers()
                .equals(List.of("minecraft:inventory_changed", "minecraft:player_killed_entity")));
        check("and reach the record",
                several.triggerArray().length == 2
                        && "minecraft:inventory_changed".equals(several.triggerArray()[0]));

        // The requirements list is what the game actually checks; it is not read here because it is a
        // statement about the criteria rather than about the advancement. Its presence must not break
        // anything.
        check("a requirements list is tolerated", several.criteria() == 3);

        AdvancementFile.Advancement xp = AdvancementFile.parse("""
                { "criteria": {}, "rewards": { "experience": 100, "recipes": [ "a", "b" ] } }""");
        check("the experience is read", xp.experience() == 100);
        check("and the reward recipes are counted", xp.rewardRecipes() == 2);
        check("both reach the record",
                "100".equals(joined(AdvancementFile.extraPairs(xp), "experience"))
                        && "2".equals(joined(AdvancementFile.extraPairs(xp), "rewardRecipes")));

        // A reward of zero is the common case -- only twenty-three of the vanilla advancements grant
        // experience -- so writing it out would be noise on nearly all of them.
        AdvancementFile.Advancement none = AdvancementFile.parse(
                "{ \"criteria\": {}, \"rewards\": { \"experience\": 0 } }");
        check("a zero experience is not written out",
                joined(AdvancementFile.extraPairs(none), "experience") == null);
        check("and neither is an empty reward list",
                joined(AdvancementFile.extraPairs(none), "rewardRecipes") == null);
    }

    private static void paths() {
        section("resource paths");

        check("the name is the whole path below the directory",
                "story/root".equals(AdvancementFile.pathOf("advancement/story/root.json")));
        check("a one-segment name works",
                "root".equals(AdvancementFile.pathOf("advancement/root.json")));
        check("a non-advancement path yields nothing",
                AdvancementFile.pathOf("loot_table/stone.json") == null);
        check("an empty name yields nothing", AdvancementFile.pathOf("advancement/.json") == null);
        check("and neither does null", AdvancementFile.pathOf(null) == null);
        check("the directory is named once",
                AdvancementFile.directory().equals("advancement"));
    }

    // ---------------------------------------------------------------- helpers

    private static String joined(String[] pairs, String key) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i].equals(key)) {
                return pairs[i + 1];
            }
        }
        return null;
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
