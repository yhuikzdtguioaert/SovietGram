package tw.nekomimi.nekogram.helpers;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.Stars.StarsController;
import org.telegram.ui.web.HttpGetTask;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Puts the gifts Telegram removed from the catalogue back into it.
 * <p>
 * A "removed" gift is not deleted server side — it can still be bought and sent, the server simply
 * stops listing it in {@code payments.getStarGifts}, so nothing in the client ever offers it. This
 * class adds those entries back to the catalogue the send-gift sheet and the buy flow read from, so
 * they behave exactly like any other gift on sale.
 * <p>
 * Always on, by design: there is no setting for it. Everything happens in memory —
 * {@link StarsController#loadStarGifts()} calls {@link #inject} on the freshly filled list and the
 * SQLite cache keeps holding the untouched server catalogue, so the request hash stays valid and
 * nothing fabricated is ever written to disk.
 */
public class DeletedGiftsHelper {

    /**
     * Where the removed gifts go in the list. Position 11 is the end of the non-NFT block in the
     * real catalogue, so they land next to the gifts they were sold alongside instead of on top.
     */
    private static final int INSERT_POSITION = 11;
    /** The pack the previews come from; index 0 in it is a placeholder, real stickers start at 1. */
    private static final String STICKER_PACK = "DeletedGiftsStickers";
    /**
     * The list is kept upstream so a gift removed after this build still shows up. It is only ever
     * read — ids and prices in, nothing about the user out.
     */
    private static final String GIFTS_URL =
            "https://raw.githubusercontent.com/binbash-0/DeletedGifts-Plugin/refs/heads/main/gift_list.json";

    private static class DeletedGift {
        final long id;
        final long price;
        final int stickerNumber;
        final String name;

        DeletedGift(long id, long price, int stickerNumber, String name) {
            this.id = id;
            this.price = price;
            this.stickerNumber = stickerNumber;
            this.name = name;
        }
    }

    /**
     * What is known at build time, so the feature works on the first launch and with no network at
     * all. Replaced wholesale once {@link #GIFTS_URL} answers.
     */
    private static final DeletedGift[] BUILT_IN = {
            new DeletedGift(5956217000635139069L, 50, 1, "Новогодний мишка"),
            new DeletedGift(5922558454332916696L, 50, 2, "Ёлочка"),
            new DeletedGift(5800655655995968830L, 50, 3, "Мишка на 14 февраля"),
            new DeletedGift(5866352046986232958L, 50, 4, "Мишка на 8 марта"),
            new DeletedGift(5801108895304779062L, 50, 5, "Валентинка на 14 февраля"),
            new DeletedGift(5893356958802511476L, 50, 6, "Мишка лепрекон"),
            new DeletedGift(5935895822435615975L, 50, 7, "Мишка на 1 апреля"),
            new DeletedGift(5969796561943660080L, 50, 8, "Мишка на Пасху"),
            new DeletedGift(6026193266406327981L, 50, 9, "Мишка строитель"),
            new DeletedGift(5974210632977745012L, 50, 10, "Мишка на чемпионате"),
    };

    private static final ArrayList<DeletedGift> gifts = new ArrayList<>();
    /** Ids of everything this class put into the catalogue, so the cache can skip them. */
    private static final HashSet<Long> injectedIds = new HashSet<>();
    private static ArrayList<TLRPC.Document> stickers;
    private static String stickerPack = STICKER_PACK;
    private static boolean listRequested;
    private static boolean stickersRequested;

    static {
        for (DeletedGift gift : BUILT_IN) {
            gifts.add(gift);
        }
    }

    private DeletedGiftsHelper() {
    }

    /** True when the id belongs to a gift this class added rather than to the server catalogue. */
    public static boolean isInjected(long giftId) {
        return injectedIds.contains(giftId);
    }

    /** Drops everything this class added from a list about to be written to the gift cache. */
    public static ArrayList<TL_stars.StarGift> withoutInjected(ArrayList<TL_stars.StarGift> list) {
        if (list == null || injectedIds.isEmpty()) {
            return list;
        }
        ArrayList<TL_stars.StarGift> result = new ArrayList<>(list.size());
        for (TL_stars.StarGift gift : list) {
            if (gift != null && !injectedIds.contains(gift.id)) {
                result.add(gift);
            }
        }
        return result;
    }
    /**
     * Adds the removed gifts to a catalogue that has just been loaded, in place.
     * <p>
     * A donor is cloned rather than a gift being built from scratch: the flags, the conversion rate
     * and the resale fields the cells read are all copied from a real entry, so only the identity of
     * the gift has to be replaced. Called on the UI thread from
     * {@link StarsController#loadStarGifts()} for both the cached and the remote list.
     */
    public static void inject(int account, ArrayList<TL_stars.StarGift> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        requestList(account);
        requestStickers(account);

        final TL_stars.StarGift donor = findDonor(list);
        if (donor == null) {
            return;
        }
        final HashSet<Long> present = new HashSet<>();
        for (TL_stars.StarGift gift : list) {
            if (gift != null) {
                present.add(gift.id);
            }
        }

        int position = Math.min(INSERT_POSITION, list.size());
        for (DeletedGift gift : new ArrayList<>(gifts)) {
            if (present.contains(gift.id)) {
                continue;
            }
            list.add(Math.min(position, list.size()), clone(donor, gift));
            injectedIds.add(gift.id);
            position++;
        }
    }

    /**
     * The first plain gift in the list. NFT entries carry attributes and an owner, which would
     * follow the clone into the catalogue and make the cell draw as somebody else's collectible.
     */
    @Nullable
    private static TL_stars.StarGift findDonor(ArrayList<TL_stars.StarGift> list) {
        for (TL_stars.StarGift gift : list) {
            if (gift instanceof TL_stars.TL_starGift && gift.sticker != null) {
                return gift;
            }
        }
        return null;
    }

    private static TL_stars.TL_starGift clone(TL_stars.StarGift donor, DeletedGift gift) {
        TL_stars.TL_starGift result = new TL_stars.TL_starGift();
        result.flags = donor.flags;
        result.limited = false;
        result.sold_out = false;
        result.birthday = false;
        result.require_premium = false;
        result.resale_ton_only = false;
        result.limited_per_user = false;
        result.peer_color_available = false;
        result.can_upgrade = false;
        result.auction = false;
        result.theme_available = false;
        result.burned = false;
        result.crafted = false;
        result.id = gift.id;
        result.stars = gift.price;
        result.convert_stars = donor.convert_stars;
        result.title = gift.name;
        result.sticker = sticker(gift.stickerNumber, donor.sticker);
        result.attributes = new ArrayList<>();
        // A removed gift is not limited and has no market, so every count and price the cell could
        // print has to be cleared — the donor's numbers belong to a different gift.
        result.availability_remains = 0;
        result.availability_total = 0;
        result.availability_resale = 0;
        result.availability_issued = 0;
        result.upgrade_stars = 0;
        result.resell_min_stars = 0;
        result.resell_amount = null;
        result.per_user_total = 0;
        result.per_user_remains = 0;
        result.locked_until_date = 0;
        result.first_sale_date = 0;
        result.last_sale_date = 0;
        // Clear the flag bits that gate the fields above; the ones recomputed from the booleans on
        // serialise are left alone. 1: availability, 8: upgrade_stars, 16: resale, 32: title.
        result.flags &= ~(1 | 8 | 16);
        result.flags |= 32;
        return result;
    }

    /**
     * The preview for a removed gift. {@code stickerNumber} is the index into the pack's documents,
     * counting the placeholder at 0, which is how the list upstream numbers them.
     */
    private static TLRPC.Document sticker(int number, TLRPC.Document fallback) {
        final ArrayList<TLRPC.Document> documents = stickers;
        if (documents == null || documents.isEmpty()) {
            return fallback;
        }
        final int index = Math.min(Math.max(number, 0), documents.size() - 1);
        final TLRPC.Document document = documents.get(index);
        return document == null ? fallback : document;
    }

    // ===== remote list =====

    private static void requestList(int account) {
        if (listRequested) {
            return;
        }
        listRequested = true;
        new HttpGetTask(response -> {
            if (response == null || response.isEmpty()) {
                return;
            }
            try {
                parseList(response);
            } catch (Exception e) {
                FileLog.e(e);
                return;
            }
            // The catalogue was already built from whatever was known before the answer arrived, so
            // it has to be rebuilt now that the list changed.
            StarsController.getInstance(account).invalidateStarGifts();
        }).execute(GIFTS_URL);
    }

    private static void parseList(String json) throws Exception {
        final JSONObject root = new JSONObject(json);
        final JSONArray array = root.optJSONArray("gifts");
        if (array == null || array.length() == 0) {
            return;
        }
        final ArrayList<DeletedGift> parsed = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            final JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            final long id = item.optLong("id");
            if (id == 0) {
                continue;
            }
            parsed.add(new DeletedGift(id, item.optLong("price", 50),
                    item.optInt("sticker_number"), item.optString("debug_name")));
        }
        if (parsed.isEmpty()) {
            return;
        }
        stickerPack = root.optString("stickerpack", STICKER_PACK);
        gifts.clear();
        gifts.addAll(parsed);
    }

    // ===== preview stickers =====

    private static void requestStickers(int account) {
        if (stickersRequested || stickers != null) {
            return;
        }
        stickersRequested = true;
        final TLRPC.TL_inputStickerSetShortName input = new TLRPC.TL_inputStickerSetShortName();
        input.short_name = stickerPack;
        // The callback runs on a memory hit too, so the returned set would be the same answer twice.
        MediaDataController.getInstance(account)
                .getStickerSet(input, null, false, set -> onStickerSet(account, set));
    }

    private static void onStickerSet(int account, @Nullable TLRPC.TL_messages_stickerSet set) {
        if (set == null || set.documents == null || set.documents.isEmpty()) {
            // Let a later catalogue load try again rather than leaving the donor sticker for good.
            stickersRequested = false;
            return;
        }
        stickers = new ArrayList<>(set.documents);
        // Everything already injected is holding the donor's sticker, so rebuild with the real ones.
        final StarsController stars = StarsController.getInstance(account);
        for (TL_stars.StarGift gift : new ArrayList<>(stars.gifts)) {
            if (gift == null || !injectedIds.contains(gift.id)) {
                continue;
            }
            final int number = numberOf(gift.id);
            if (number >= 0) {
                gift.sticker = sticker(number, gift.sticker);
            }
        }
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.starGiftsLoaded);
    }

    private static int numberOf(long giftId) {
        for (DeletedGift gift : gifts) {
            if (gift.id == giftId) {
                return gift.stickerNumber;
            }
        }
        return -1;
    }
}
