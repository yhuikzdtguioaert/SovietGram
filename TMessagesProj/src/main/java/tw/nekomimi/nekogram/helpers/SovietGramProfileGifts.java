package tw.nekomimi.nekogram.helpers;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.TimeZone;

/** Server-backed NFT rows projected into Telegram's native profile-gift model. */
public final class SovietGramProfileGifts {

    private static final long SERVER_ID_BASE = Long.MIN_VALUE;

    private SovietGramProfileGifts() {
    }

    public interface VisibilityCallback {
        void onResult(boolean success, @Nullable String error);
    }

    public static boolean isServerGift(@Nullable TL_stars.SavedStarGift gift) {
        return gift != null && (gift.flags & 2048) != 0 && gift.saved_id < (Long.MIN_VALUE / 2);
    }

    public static long serverId(TL_stars.SavedStarGift gift) {
        return gift.saved_id - SERVER_ID_BASE;
    }

    public static void load(int account, long dialogId, boolean includeDisplayed, boolean includeHidden,
                            Utilities.Callback<ArrayList<TL_stars.SavedStarGift>> callback) {
        final long targetId = dialogId == 0
                ? UserConfig.getInstance(account).getClientUserId()
                : dialogId;
        if (targetId <= 0 || !SovietGramApiClient.isReady(account)) {
            callback.run(new ArrayList<>());
            return;
        }
        SovietGramApiClient.get(account, "/v1/gifts/for/" + targetId + "?limit=100", (body, error) -> {
            final ArrayList<TL_stars.SavedStarGift> out = new ArrayList<>();
            if (error == null && body != null) {
                final JSONArray rows = body.optJSONArray("gifts");
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        final TL_stars.SavedStarGift gift = decode(rows.optJSONObject(i), targetId);
                        if (gift == null) continue;
                        if (gift.unsaved ? includeHidden : includeDisplayed) out.add(gift);
                    }
                }
            }
            callback.run(out);
        });
    }

    public static void setVisible(int account, TL_stars.SavedStarGift gift, boolean visible,
                                  VisibilityCallback callback) {
        if (!isServerGift(gift)) {
            callback.onResult(false, "not_server_gift");
            return;
        }
        try {
            final JSONObject body = new JSONObject();
            body.put("visible", visible);
            SovietGramApiClient.putSigned(account, "/v1/gifts/" + serverId(gift), body, (response, error) -> {
                if (error == null) gift.unsaved = !visible;
                callback.onResult(error == null, error);
            });
        } catch (Throwable e) {
            callback.onResult(false, e.getMessage());
        }
    }

    @Nullable
    private static TL_stars.SavedStarGift decode(@Nullable JSONObject row, long ownerId) {
        if (row == null || !"nft".equals(row.optString("type"))) return null;
        final long id = parsePositive(row.optString("id", ""));
        final long fromId = parsePositive(row.optString("from_id", ""));
        final JSONObject payload = row.optJSONObject("payload");
        final String blob = payload == null ? null : payload.optString("tl", null);
        if (id <= 0 || blob == null) return null;
        final TLRPC.MessageAction action = SovietGramGiftSync.decodeAction(blob);
        if (!(action instanceof TLRPC.TL_messageActionStarGiftUnique)) return null;
        final TLRPC.TL_messageActionStarGiftUnique uniqueAction =
                (TLRPC.TL_messageActionStarGiftUnique) action;
        if (!(uniqueAction.gift instanceof TL_stars.TL_starGiftUnique)) return null;

        final TL_stars.TL_starGiftUnique unique = (TL_stars.TL_starGiftUnique) uniqueAction.gift;
        final TLRPC.TL_peerUser owner = new TLRPC.TL_peerUser();
        owner.user_id = ownerId;
        unique.owner_id = owner;
        unique.flags |= 1;

        final TL_stars.TL_savedStarGift saved = new TL_stars.TL_savedStarGift();
        saved.flags = 2048;
        saved.saved_id = SERVER_ID_BASE + id;
        saved.gift = unique;
        saved.date = parseDate(row.optString("created_at", ""));
        saved.unsaved = !row.optBoolean("visible", true);
        if (fromId > 0) {
            final TLRPC.TL_peerUser from = new TLRPC.TL_peerUser();
            from.user_id = fromId;
            saved.from_id = from;
            saved.flags |= 2;
        }
        return saved;
    }

    private static long parsePositive(String value) {
        try {
            final long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : 0;
        } catch (Throwable ignore) {
            return 0;
        }
    }

    private static int parseDate(String value) {
        try {
            final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return (int) (format.parse(value).getTime() / 1000L);
        } catch (Throwable ignore) {
            return (int) (System.currentTimeMillis() / 1000L);
        }
    }
}
