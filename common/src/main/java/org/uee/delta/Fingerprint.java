package org.uee.delta;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A content fingerprint: what a piece of output is, reduced to something comparable.
 *
 * <h2>Why a cryptographic hash and not a checksum</h2>
 *
 * <p>A delta compares fingerprints and concludes "unchanged" when they match. A collision is therefore not
 * a data-integrity nuisance but a <em>missing change</em>: two different artifacts would look the same and
 * the consumer would never be told about the second one. The failure is silent and it is on the side of
 * losing data, which is the wrong side. CRC-32 is built for detecting transmission errors, where a
 * one-in-four-billion miss is acceptable; here it is not, so this is SHA-256.
 *
 * <h2>Stable across runs, machines and time</h2>
 *
 * <p>The fingerprint is taken over the <b>bytes that go to the output</b>, not over the model object that
 * produced them. That distinction matters more than it looks: a model object's {@code hashCode} is a
 * property of the JVM's implementation, and several of the collections in this project are deliberately
 * unordered for speed. Fingerprinting the model would make the answer depend on something nobody promised
 * to keep stable, and a change in the writer would leave stale artifacts silently marked as current.
 * Fingerprinting the bytes means "unchanged" is exactly "the file the consumer would read is identical",
 * which is the only definition that cannot drift.
 *
 * <p>The hex form is fixed-width and lower-case so that a manifest sorts and diffs as text.
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /** The length in characters of a fingerprint's textual form. */
    public static final int HEX_LENGTH = 64;

    /**
     * Fingerprints bytes.
     *
     * @throws IllegalArgumentException when the algorithm is missing, which no JVM may be
     */
    public static String of(byte[] data) {
        return hex(digest().digest(data));
    }

    /**
     * Fingerprints a file without holding it in memory.
     *
     * <p>Streamed rather than read whole because this is called on artifacts that may be shards of
     * hundreds of megabytes, and the memory model for a run is a fixed ceiling rather than a fraction of
     * the input. Hashing is one pass and needs a sixteen-kilobyte window, which is the same order as the
     * buffers the writers already use.
     */
    public static String of(Path file) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[1 << 14];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    /**
     * Fingerprints a stream, leaving it for the caller to close.
     *
     * <p>Streamed for the same reason a file is: this is called on assets that may be megabytes, and the
     * memory model for a run is a fixed ceiling rather than a fraction of the input.
     */
    public static String of(InputStream in) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[1 << 14];
        int read;
        while ((read = in.read(buffer)) > 0) {
            digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    /**
     * Fingerprints a string.
     *
     * <p>Its bytes are UTF-8, fixed here rather than left to the platform's default charset, because a
     * fingerprint that depends on the machine's locale is exactly the kind of instability this class
     * exists to avoid.
     */
    public static String of(String text) {
        return of(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Whether a string looks like a fingerprint, so a manifest can tell a real entry from noise. */
    public static boolean isWellFormed(String candidate) {
        if (candidate == null || candidate.length() != HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("SHA-256 is required and missing", e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            out[i * 2] = DIGITS[value >>> 4];
            out[i * 2 + 1] = DIGITS[value & 0x0F];
        }
        return new String(out);
    }

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();
}
