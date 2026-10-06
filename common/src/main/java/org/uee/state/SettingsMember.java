package org.uee.state;

import java.util.ArrayList;
import java.util.List;

import org.tielang.zd.ZdDocWriter;
import org.tielang.zd.ZdRow;
import org.tielang.zd.ZdVolume;

/**
 * The settings member: configuration text, carried as a zd payload.
 *
 * <h2>Why the payload holds text rather than fields</h2>
 *
 * <p>A settings layer is a partial configuration description, and that description already has a syntax with
 * a parser and a writer. Storing it field by field would mean a second encoder for the same content, and the
 * two would drift — the failure being a state file that this program can write and cannot read. So the
 * member holds the text, the writer is {@code ConfigFile}'s, and the reader is {@code ConfigFile}'s parser.
 *
 * <h2>Why one row and not one row per setting</h2>
 *
 * <p>Because the values written are the <em>effective</em> ones, taken from the resolved configuration rather
 * than from the sequence of commands that produced it. Every key therefore appears once, and replaying them
 * as a single layer is the same thing as replaying the individual commands — while a row per command would
 * store a history whose repetition the reader would have to resolve, which is the layering logic again.
 *
 * <p>A consequence worth knowing: the file records the state, not how it was reached. That is what makes it
 * small and what makes it safe to edit by hand.
 */
public final class SettingsMember {

    /** zd row kind for a string, per {@code ZdRow}. */
    private static final int K_STRING = 1;

    /** The key of the row holding the text, so a reader does not depend on row order. */
    private static final String TEXT_KEY = "text";

    private SettingsMember() {
    }

    /** Encodes configuration text as the settings member's payload. */
    public static byte[] encode(String tdText) {
        List<ZdRow> rows = new ArrayList<>(1);
        rows.add(new ZdRow(K_STRING, TEXT_KEY, 0L, 0.0, tdText == null ? "" : tdText, 0));
        return ZdDocWriter.writeIndexed(0, rows);
    }

    /**
     * The configuration text in a member's payload.
     *
     * <p>Empty rather than throwing when the payload cannot be read: a settings member that has been damaged
     * costs one layer, and the rest of the state file — the accumulated tables — is still worth reading.
     */
    public static String decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "";
        }
        try {
            for (ZdRow row : ZdVolume.readRows(payload)) {
                if (row.kind() == K_STRING && TEXT_KEY.equals(row.key())) {
                    return row.valueStr() == null ? "" : row.valueStr();
                }
            }
        } catch (RuntimeException e) {
            return "";
        }
        return "";
    }

    /** The settings text in a store, or empty when the store has no settings member. */
    public static String textOf(UserStore store) {
        if (store == null) {
            return "";
        }
        return decode(store.member(UserStore.SETTINGS));
    }
}
