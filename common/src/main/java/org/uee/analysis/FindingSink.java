package org.uee.analysis;

/** Receives findings as analyses produce them. */
@FunctionalInterface
public interface FindingSink {

    void finding(Finding finding);

    /** A sink that discards everything, for runs that only want the data output. */
    static FindingSink discard() {
        return finding -> {
        };
    }
}
