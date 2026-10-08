package org.uee.spi;

import java.io.IOException;
import java.io.InputStream;

/**
 * A stream of bytes a caller wants written somewhere.
 *
 * <h2>Why a supplier rather than the bytes</h2>
 *
 * <p>An icon is small enough to hand over as an array; an asset is not. A sound file runs to megabytes and
 * the memory model for a run is a fixed ceiling, so the bytes are never all in hand — the pipeline streams
 * them. This is also what makes {@code open} more than a formality: the pipeline may need the content twice,
 * once to fingerprint it and once to write it, and asking for a second stream is cheaper than buffering the
 * first.
 *
 * <p>An implementation must therefore be openable more than once. The resource manager satisfies that — each
 * call returns a fresh stream over the same bytes — and it is stated here because an implementation that
 * only worked once would fail in a way that looks like data corruption rather than a broken contract.
 */
@FunctionalInterface
public interface BytesSource {

    /** Opens a stream over the bytes. The caller closes it. */
    InputStream open() throws IOException;
}
