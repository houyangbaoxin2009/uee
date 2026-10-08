package org.uee.datapack;

import java.util.List;
import java.util.Locale;
import org.uee.analysis.Analysis;
import org.uee.analysis.Finding;

/**
 * A datapack-defined analysis strategy: a list of declarative rules.
 *
 * <h2>Why a fixed rule set rather than an expression language</h2>
 *
 * <p>A general predicate language would be more expressive and would also be a small programming
 * language shipped inside a data file: it would need evaluation limits, error semantics and a
 * versioning story, and a pack author could write a rule that runs for minutes. The rules below cover
 * what a pack author actually wants to assert about an instance — that a mod is present, absent,
 * co-present, that a namespace survived, that a category produced enough — and each one is a handful
 * of lines with no evaluation risk.
 *
 * <h2>Rules declare a stage</h2>
 *
 * <p>Each rule knows whether it needs the registry walk to have happened. This is the same
 * pre/post-collection seam the built-in checks use, and it falls out naturally: asking whether mod X
 * is loaded needs no collection, while asking whether namespace Y produced any elements needs all of
 * it. {@link org.uee.analysis.DeclarativeAnalysis} is therefore registered once per stage and runs
 * only its own half.
 *
 * @param id the strategy's identity, unique across all datapacks
 * @param namespace the declaring datapack's namespace
 * @param description optional human-readable note
 * @param rules the rules, run in order
 */
public record StrategyDefinition(String id, String namespace, String description, List<Rule> rules) {

    public StrategyDefinition {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("strategy id is required");
        }
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** Whether any rule in this strategy needs collected data. */
    public boolean needsCollection() {
        for (Rule r : rules) {
            if (r.stage() == Analysis.Stage.POST_COLLECTION) {
                return true;
            }
        }
        return false;
    }

    /** The rules that run at a given stage. */
    public List<Rule> rulesFor(Analysis.Stage stage) {
        return rules.stream().filter(r -> r.stage() == stage).toList();
    }

    /**
     * One declarative rule.
     *
     * <p>The parameter names are generic ({@code target}, {@code mods}, {@code value}) rather than one
     * distinct record per rule kind. A data format with fifteen differently-shaped entries would need
     * fifteen validators and would still leave every consumer switching on the kind; a small uniform
     * shape keeps parsing and validation in one place, and the rule kind is what carries the meaning.
     *
     * @param kind what to assert
     * @param severity how much it matters when the assertion fails
     * @param message the message to emit, or {@code null} for a generated one
     * @param mod a single mod id the rule is about
     * @param mods several mod ids, for the co-presence rules
     * @param target a class name, tag id or category token, depending on the kind
     * @param namespace a namespace the rule is about
     * @param value a numeric threshold, for the count rules
     */
    public record Rule(Kind kind, Finding.Severity severity, String message, String mod,
            List<String> mods, String target, String namespace, long value) {

        public Rule {
            if (kind == null) {
                throw new IllegalArgumentException("rule has no kind");
            }
            severity = severity == null ? Finding.Severity.WARN : severity;
            mods = mods == null ? List.of() : List.copyOf(mods);
        }

        /** The stage this rule runs at, derived from what it reads. */
        public Analysis.Stage stage() {
            return kind.needsCollection() ? Analysis.Stage.POST_COLLECTION
                    : Analysis.Stage.PRE_COLLECTION;
        }

        /** The message to emit, generating one when the rule did not supply it. */
        public String describe() {
            return message != null && !message.isEmpty() ? message : kind.defaultMessage(this);
        }
    }

    /** The rule vocabulary. */
    public enum Kind {
        /** A mod must be loaded. */
        REQUIRE_MOD(false, "mod"),
        /** A mod must not be loaded. */
        FORBID_MOD(false, "mod"),
        /** These mods must be loaded together. */
        REQUIRE_TOGETHER(false, "mods"),
        /** At most one of these mods may be loaded. */
        MUTUALLY_EXCLUSIVE(false, "mods"),
        /** No loaded mod may patch this class. */
        FORBID_MIXIN_TARGET(false, "target"),
        /** A namespace must appear in the registries. */
        REQUIRE_NAMESPACE(true, "namespace"),
        /** A category must produce at least this many elements. */
        MIN_COUNT(true, "value"),
        /** A category must produce at most this many elements. */
        MAX_COUNT(true, "value");

        private final boolean needsCollection;
        private final String parameter;

        Kind(boolean needsCollection, String parameter) {
            this.needsCollection = needsCollection;
            this.parameter = parameter;
        }

        /** Whether this kind reads registry observations, and so must run after collection. */
        public boolean needsCollection() {
            return needsCollection;
        }

        /** The parameter this kind requires, for error messages when it is missing. */
        public String requiredParameter() {
            return parameter;
        }

        /** Parses a token, or {@code null} when unrecognised. */
        public static Kind of(String token) {
            if (token == null) {
                return null;
            }
            String t = token.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            for (Kind k : values()) {
                if (k.name().toLowerCase(Locale.ROOT).equals(t)) {
                    return k;
                }
            }
            return null;
        }

        /** A one-line description, for the command surface and for documentation. */
        public String description() {
            return switch (this) {
                case REQUIRE_MOD -> "a mod must be loaded";
                case FORBID_MOD -> "a mod must not be loaded";
                case REQUIRE_TOGETHER -> "these mods must be loaded together";
                case MUTUALLY_EXCLUSIVE -> "at most one of these mods may be loaded";
                case FORBID_MIXIN_TARGET -> "no mod may patch this class";
                case REQUIRE_NAMESPACE -> "a namespace must appear in the registries";
                case MIN_COUNT -> "a category must produce at least N elements";
                case MAX_COUNT -> "a category must produce at most N elements";
            };
        }

        /** The finding kind string emitted when this rule fails. */
        public String findingKind() {
            return "rule:" + name().toLowerCase(Locale.ROOT);
        }

        String defaultMessage(Rule rule) {
            return switch (this) {
                case REQUIRE_MOD -> "required mod '" + rule.mod() + "' is not loaded";
                case FORBID_MOD -> "forbidden mod '" + rule.mod() + "' is loaded";
                case REQUIRE_TOGETHER -> "these mods must be loaded together: "
                        + String.join(", ", rule.mods());
                case MUTUALLY_EXCLUSIVE -> "more than one of these mods is loaded: "
                        + String.join(", ", rule.mods());
                case FORBID_MIXIN_TARGET -> "'" + rule.target() + "' must not be patched, but it is";
                case REQUIRE_NAMESPACE -> "namespace '" + rule.namespace()
                        + "' does not appear in the registries";
                case MIN_COUNT -> rule.target() + " produced fewer than " + rule.value() + " element(s)";
                case MAX_COUNT -> rule.target() + " produced more than " + rule.value() + " element(s)";
            };
        }
    }
}
