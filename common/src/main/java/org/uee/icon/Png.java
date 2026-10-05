package org.uee.icon;

import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Encodes raw pixels as a PNG.
 *
 * <h2>Why this is written rather than borrowed</h2>
 *
 * <p>The client can hand back a framebuffer's pixels but not an encoded image: the class that would do
 * the encoding keeps its pixel buffer private in this Minecraft version, so the pixels arrive as raw
 * RGBA and something has to turn them into a file. The JDK has no image writer for PNG that does not go
 * through the whole {@code ImageIO} registration machinery, which is heavier than the encoder and gives
 * a different byte sequence on different JDKs — and a byte sequence that varies is a problem here,
 * because the export is compared across runs.
 *
 * <p>So this is a minimal, deterministic encoder: one format, no options, the same bytes for the same
 * pixels on every platform. It lives in the core rather than beside the renderer for the usual reason —
 * it takes an array of ints and returns bytes, so it can be tested without a game, and the part that
 * cannot be tested is left as small as possible.
 *
 * <h2>What it does not do</h2>
 *
 * <p>No interlacing, no palette, no bit depths other than eight, and no filtering. Filtering is what
 * compresses a photograph; these are flat icons where the filter byte costs one byte per row and the
 * deflate that follows is doing the work anyway. Leaving it out also means the encoder cannot get the
 * filter selection wrong, which is a real failure mode: a filter that does not match the data it
 * describes produces a file that is valid and looks wrong.
 *
 * <p>Whole image in, whole image out. An icon is at most 128 by 128, so there is nothing to gain from
 * streaming and a simpler contract to be relied on.
 */
public final class Png {

    /** The eight bytes every PNG starts with. */
    private static final byte[] SIGNATURE = {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** Eight bits per channel, colour type 6 (truecolour with alpha), no interlace. */
    private static final int BIT_DEPTH = 8;
    private static final int COLOUR_TYPE_RGBA = 6;

    private Png() {
    }

    /**
     * Encodes {@code width * height} pixels.
     *
     * <p>Pixels are given as one int each, in the layout {@code NativeImage} hands back: alpha in the
     * high byte, then red, green, blue. That order is a detail of the platform's image type rather than
     * a choice made here, and getting it backwards is the classic way an exported icon comes out with
     * its channels swapped, so the encoder takes exactly what the caller has and does not offer another
     * arrangement.
     *
     * @param pixels {@code width * height} entries; extra entries are ignored and missing ones are an
     *     error, since a short array means the caller miscounted and encoding it would produce a
     *     corrupt image that nothing later would catch
     * @throws IllegalArgumentException when the size does not match or is not positive
     */
    public static byte[] encode(int[] pixels, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("an image needs a positive size, got " + width + "x" + height);
        }
        long expected = (long) width * height;
        if (pixels.length < expected) {
            throw new IllegalArgumentException("expected " + expected + " pixels for " + width + "x"
                    + height + " but got " + pixels.length);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(pixels.length + (pixels.length >> 3) + 64);
        out.write(SIGNATURE, 0, SIGNATURE.length);

        byte[] header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = BIT_DEPTH;
        header[9] = COLOUR_TYPE_RGBA;
        header[10] = 0;   // deflate, the only compression a PNG may use
        header[11] = 0;   // filter method zero, the adaptive one
        header[12] = 0;   // no interlace
        writeChunk(out, "IHDR", header);

        writeChunk(out, "IDAT", deflate(scanlines(pixels, width, height)));
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /**
     * The rows, each preceded by its filter byte.
     *
     * <p>Filter zero is "none": the bytes are the pixels as they are. Every other filter predicts a row
     * from the one above, which compresses a photograph well and a flat icon not at all, and which has to
     * be computed correctly to be worth anything.
     */
    private static byte[] scanlines(int[] pixels, int width, int height) {
        int stride = width * 4;
        byte[] raw = new byte[(stride + 1) * height];
        int at = 0;
        for (int y = 0; y < height; y++) {
            raw[at++] = 0;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int argb = pixels[row + x];
                // The platform's images are ARGB in an int, and a PNG stores R, G, B, A in that order,
                // so the alpha byte moves from the front to the back.
                raw[at++] = (byte) (argb >> 16);
                raw[at++] = (byte) (argb >> 8);
                raw[at++] = (byte) argb;
                raw[at++] = (byte) (argb >>> 24);
            }
        }
        return raw;
    }

    /**
     * Compresses the scanlines into the zlib stream a PNG's image data expects.
     *
     * <p>Nothing is added around the compressor's output, and that is worth stating because the obvious
     * thing to do is add it: a PNG's image data is a zlib stream, a deflate stream is not a zlib stream,
     * and a bare compressor would therefore need a two-byte header and a trailing Adler-32 put around
     * it. Java's compressor is not a bare one — it emits the zlib framing itself — so wrapping it again
     * produces a stream that decodes to a valid zlib stream which is not the image, and every decoder
     * rejects it. The wrapper was written first and the test that reads the output back with an
     * independent decoder is what showed it: the file had two identical headers in a row.
     */
    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length >> 1);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * Writes one chunk: length, type, data, and a checksum over the type and data.
     *
     * <p>The checksum covers the type as well as the data, which is worth stating because leaving the
     * type out also produces a plausible-looking file.
     */
    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] length = new byte[4];
        putInt(length, 0, data.length);
        out.write(length, 0, 4);

        byte[] typeBytes = {
            (byte) type.charAt(0), (byte) type.charAt(1), (byte) type.charAt(2), (byte) type.charAt(3)};
        out.write(typeBytes, 0, 4);
        out.write(data, 0, data.length);

        CRC32 crc = new CRC32();
        crc.update(typeBytes, 0, 4);
        crc.update(data, 0, data.length);
        byte[] checksum = new byte[4];
        putInt(checksum, 0, (int) crc.getValue());
        out.write(checksum, 0, 4);
    }

    private static void putInt(byte[] target, int at, int value) {
        target[at] = (byte) (value >>> 24);
        target[at + 1] = (byte) (value >>> 16);
        target[at + 2] = (byte) (value >>> 8);
        target[at + 3] = (byte) value;
    }
}
