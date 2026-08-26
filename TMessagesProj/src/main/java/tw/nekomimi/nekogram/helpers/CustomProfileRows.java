package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * The order the look wants the profile's own rows in, and which of them it hides.
 *
 * <p>A look names rows by the reference's own ids — {@code phone}, {@code bio}, {@code username},
 * {@code id_dc} and so on — so the same order installs identically on both apps.
 *
 * <p><b>Reordering without renumbering.</b> The profile numbers its rows as it builds them and some
 * sixty fields hold those numbers; renumbering to move one row would mean touching every one of
 * them. So this permutes instead: it takes the positions the named rows already occupy, keeps that
 * set of positions exactly as it is, and hands them back out in the look's order. A profile with
 * phone at 4, username at 5 and bio at 6, told to put bio first, comes out as bio 4, phone 5,
 * username 6 — the rows move, the list does not change length, and no other row notices.
 *
 * <p>Rows the profile is not showing at all sit at {@code -1} and are left there: a look cannot
 * conjure a phone number onto a profile that has none.
 */
public final class CustomProfileRows {

    /**
     * The reference's ids for the rows both apps have, in its own order. The array is the contract
     * between {@code ProfileActivity}, which passes its row numbers in this order, and a look, which
     * names them.
     */
    public static final String[] IDS = {
            "phone", "bio", "username", "channel",
            "business_hours", "business_location", "location", "id_dc", "note",
    };

    private static String parsedOrderFrom = "";
    private static List<String> parsedOrder = Collections.emptyList();
    private static String parsedHiddenFrom = "";
    private static Set<String> parsedHidden = Collections.emptySet();

    private CustomProfileRows() {
    }

    // ---------------------------------------------------------------- editing

    /**
     * The order as it is stored, with every row this app knows about appended — a stored order is
     * allowed to name only some of them, and the editor has to show all of them.
     */
    public static List<String> editableOrder() {
        // The user's own order, not whichever look happens to be on screen: an editor that read the
        // drawing look would save a peer's order into these settings.
        final List<String> out = new ArrayList<>(
                parseList(NekoConfig.customProfileBlockOrder.String()));
        for (String id : IDS) {
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        out.retainAll(Arrays.asList(IDS));
        return out;
    }

    public static void setOrder(@Nullable List<String> ids) {
        final JSONArray array = new JSONArray();
        if (ids != null) {
            for (String id : ids) {
                array.put(id);
            }
        }
        NekoConfig.customProfileBlockOrder.setConfigString(
                ids == null || ids.isEmpty() ? "" : array.toString());
        CustomProfileHelper.onSettingsChanged();
    }

    /** Whether the look hides this row, read straight from the config rather than from the cache. */
    public static boolean hiddenStored(String id) {
        return new LinkedHashSet<>(parseList(NekoConfig.customProfileHiddenSections.String()))
                .contains(id);
    }

    public static void setHidden(String id, boolean hidden) {
        final LinkedHashSet<String> set =
                new LinkedHashSet<>(parseList(NekoConfig.customProfileHiddenSections.String()));
        if (hidden) {
            set.add(id);
        } else {
            set.remove(id);
        }
        final JSONArray array = new JSONArray();
        for (String value : set) {
            array.put(value);
        }
        NekoConfig.customProfileHiddenSections.setConfigString(
                set.isEmpty() ? "" : array.toString());
        CustomProfileHelper.onSettingsChanged();
    }

    /** Puts both lists back to "as Telegram builds them". */
    public static void reset() {
        NekoConfig.customProfileBlockOrder.setConfigString("");
        NekoConfig.customProfileHiddenSections.setConfigString("");
        CustomProfileHelper.onSettingsChanged();
    }

    /** Drops the parsed lists; the look under them has changed. */
    public static void invalidate() {
        parsedOrderFrom = "";
        parsedOrder = Collections.emptyList();
        parsedHiddenFrom = "";
        parsedHidden = Collections.emptySet();
    }

    /** Whether the look on screen has anything to say about the rows. */
    public static boolean has() {
        return CustomProfileHelper.isEnabled() && (!order().isEmpty() || !hidden().isEmpty());
    }

    private static List<String> order() {
        final String raw = CustomProfileHelper.cfgString(NekoConfig.customProfileBlockOrder);
        if (!raw.equals(parsedOrderFrom)) {
            parsedOrderFrom = raw;
            parsedOrder = parseList(raw);
        }
        return parsedOrder;
    }

    private static Set<String> hidden() {
        final String raw = CustomProfileHelper.cfgString(NekoConfig.customProfileHiddenSections);
        if (!raw.equals(parsedHiddenFrom)) {
            parsedHiddenFrom = raw;
            parsedHidden = new LinkedHashSet<>(parseList(raw));
        }
        return parsedHidden;
    }

    /**
     * The row numbers as the look wants them.
     *
     * @param rows the current numbers, in the order of {@link #IDS}; {@code -1} for a row this
     *             profile does not show. The array is not modified.
     * @return a new array in the same order as {@link #IDS}, or the one passed in when there is
     *         nothing to change.
     */
    public static int[] apply(int[] rows) {
        if (rows == null || rows.length != IDS.length || !has()) {
            return rows;
        }
        final List<String> wanted = order();
        if (wanted.isEmpty()) {
            return rows;
        }
        // The positions in play, kept exactly as they are and simply redistributed.
        final List<Integer> positions = new ArrayList<>();
        for (int row : rows) {
            if (row >= 0) {
                positions.add(row);
            }
        }
        if (positions.size() < 2) {
            return rows;
        }
        Collections.sort(positions);

        // The ids that have a position, in the look's order first and then whatever it did not
        // mention, left in the profile's own order so an incomplete list is not a reshuffle.
        final List<String> present = new ArrayList<>();
        for (String id : wanted) {
            final int index = indexOf(id);
            if (index >= 0 && rows[index] >= 0 && !present.contains(id)) {
                present.add(id);
            }
        }
        for (int i = 0; i < IDS.length; i++) {
            if (rows[i] >= 0 && !present.contains(IDS[i])) {
                present.add(IDS[i]);
            }
        }
        if (present.size() != positions.size()) {
            return rows;
        }

        final int[] out = Arrays.copyOf(rows, rows.length);
        for (int i = 0; i < present.size(); i++) {
            out[indexOf(present.get(i))] = positions.get(i);
        }
        return out;
    }

    /** Whether the look hides this row. The view is collapsed at bind rather than renumbered. */
    public static boolean isHidden(String id) {
        return CustomProfileHelper.isEnabled() && hidden().contains(id);
    }

    /**
     * Whether the look hides the row sitting at {@code row}, given the same numbers {@link #apply}
     * was handed. Answers the question the adapter actually has, which is about a position.
     */
    public static boolean isHiddenRow(int row, int[] rows) {
        if (row < 0 || rows == null || rows.length != IDS.length || hidden().isEmpty()) {
            return false;
        }
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] == row) {
                return isHidden(IDS[i]);
            }
        }
        return false;
    }

    private static int indexOf(String id) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A JSON array of ids, deduplicated, keeping the first mention of each. Their {@code custom:…}
     * entries are dropped here — those name the look's own rows, which are a separate feature.
     */
    static List<String> parseList(@Nullable String json) {
        if (TextUtils.isEmpty(json)) {
            return Collections.emptyList();
        }
        final LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            final JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                final String id = array.optString(i, "").trim().toLowerCase();
                if (!id.isEmpty() && !id.startsWith("custom:")) {
                    out.add(id);
                }
            }
        } catch (Throwable e) {
            FileLog.e("CustomProfileRows: unreadable list: " + e.getMessage());
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(out));
    }
}
