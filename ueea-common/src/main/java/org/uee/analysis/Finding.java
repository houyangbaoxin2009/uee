package org.uee.analysis;

import org.uee.model.ElementKind;

/**
 * One diagnostic finding.
 *
 * @param kind a stable machine-readable category, e.g. {@code missing_dependency}
 * @param severity how much it matters
 * @param subject what the finding is about: usually a mod id or a namespace
 * @param message a one-line human-readable description
 * @param detail additional key/value context, flattened as k0,v0,k1,v1,... or {@code null}
 */
public record Finding(String kind, Severity severity, String subject, String message, String[] detail) {

    /** How much a finding matters. */
    public enum Severity {
        /** The instance is broken or will misbehave. */
        ERROR,
        /** Something is suspicious and worth a look. */
        WARN,
        /** Context that is useful when reading the rest of the output. */
        INFO;

        public String token() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public Finding {
        if (kind == null || kind.isEmpty()) {
            throw new IllegalArgumentException("finding kind is required");
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("finding message is required");
        }
        severity = severity == null ? Severity.INFO : severity;
        detail = detail == null ? new String[0] : detail;
    }

    /** Convenience for findings with no extra context. */
    public static Finding of(String kind, Severity severity, String subject, String message) {
        return new Finding(kind, severity, subject, message, null);
    }

    /** A stable identity: two runs producing the same problem produce the same key. */
    public String key() {
        return kind + ":" + (subject == null ? "" : subject) + ":" + message;
    }

    /** Appends a field to the flattened detail array. */
    public static String[] detail(String... pairs) {
        return pairs;
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /** The kind a finding is filed under when it reaches an {@link org.uee.spi.ElementSink}. */
    public static final ElementKind ELEMENT_KIND = ElementKind.CONFLICT;
}
