package org.uee;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.tielang.zd.ZdFooter;
import org.tielang.zd.ZdRow;
import org.tielang.zd.ZdVolume;

/**
 * Round-trip check: bytes written by {@link org.uee.write.ZdWriter} must be readable by the zd
 * codec that consumes them.
 *
 * <p>This is the evidence that the tie-ecosystem backend is real rather than nominal. A writer that
 * merely emits plausible binary would pass a size check and fail here, so the test decodes the
 * document and asserts on the reconstructed record tree instead of on the byte count.
 */
public final class ZdRoundTripTest {

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "build/smoke/example/example-items.zd");
        byte[] data = Files.readAllBytes(file);
        System.out.println("file   : " + file);
        System.out.println("bytes  : " + data.length);

        List<ZdRow> rows = ZdVolume.readRows(data);
        System.out.println("rows   : " + rows.size());
        ZdFooter footer = ZdVolume.footer(data);
        System.out.println("footer : " + (footer == null ? "(none)" : footer.toString()));

        // Row 0 is the document root: a table whose child count is the number of elements.
        ZdRow root = rows.get(0);
        expect(root.kind() == 0, "root must be a table, got kind=" + root.kind());
        System.out.println("root   : kind=" + root.kind() + " children=" + root.childCount());

        // Walk the first element and print its fields, proving the tree structure survived.
        int i = 1;
        int elements = (int) root.childCount();
        expect(elements > 0, "expected at least one element");
        ZdRow first = rows.get(i);
        expect(first.kind() == 0, "element must be a table, got kind=" + first.kind());
        System.out.println("\nfirst element: " + first.childCount() + " fields");
        int j = i + 1;
        for (int f = 0; f < first.childCount(); f++) {
            ZdRow row = rows.get(j);
            String value = switch (row.kind()) {
                case 1 -> "\"" + row.valueStr() + "\"";
                case 2 -> String.valueOf(row.valueI64());
                case 3 -> String.valueOf(row.valueF64());
                default -> "{ " + row.childCount() + " children }";
            };
            System.out.printf("  %-16s = %s%n", row.key(), value);
            j++;
            if (row.kind() == 0) {
                j += (int) row.childCount();
            }
        }

        System.out.println("\nZD ROUND TRIP: OK");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
