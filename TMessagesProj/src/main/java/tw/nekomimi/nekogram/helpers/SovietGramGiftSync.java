package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The two-way sync for the fake gifts {@link LocalGiftHelper} fabricates. Locally a "gift" is just a
 * service message written straight into the chat database — the real peer never sees it. This class
 * carries that message across the SovietGram backend so the recipient's client can rebuild it:
 *
 * <ul>
 *   <li><b>Send</b> ({@link #pushGift}) — hooked into {@code LocalGiftHelper.deliver}. Right after the
 *       gift is written locally, the whole {@link TLRPC.MessageAction} is serialised to a TL blob and
 *       {@code POST /v1/gifts} (signed) ships it to the recipient. One code path covers all four kinds
 *       (NFT / Stars / Premium / TON), because the action serialises losslessly regardless of shape.</li>
 *   <li><b>Receive</b> ({@link #pollInbox}) — {@code GET /v1/gifts/inbox?since=<cursor>} pulls gifts
 *       addressed to an account, deserialises each action back and reconstructs it as an
 *       <em>incoming</em> service message from the sender, the mirror image of the outgoing one
 *       {@code deliver} writes. A persisted per-account high-water-mark id
 *       ({@link SovietGramTokenStore#giftCursor}) makes it exactly-once: a gift already materialised
 *       is never fetched again.</li>
 * </ul>
 *
 * <p>Both directions are per-account. The server identifies the peer purely by the token — it derives
 * {@code from_id} from the bearer token and filters the inbox's {@code to_id} to that same telegram id
 * — so a gift must be sent with the token of the account whose chat it was written into, and each
 * account drains its own inbox behind its own cursor. Everything here is best-effort and non-fatal: a
 * failed push or an undecodable blob is logged and skipped, never retried in a tight loop.
 */
public final class SovietGramGiftSync {

    /** Payload envelope version, so the wire format can change without misreading old blobs. */
    private static final int PAYLOAD_VERSION = 1;

    /**
     * Ceiling on the serialised action, kept comfortably under the server's 16 KiB payload limit
     * (the base64 of the blob is ~4/3 its size, plus the small JSON envelope, must fit). An NFT
     * action with its model, pattern and backdrop documents is a few KB; anything larger is almost
     * certainly wrong and is dropped rather than 400'd by the server.
     */
    private static final int MAX_ACTION_BYTES = 10 * 1024;

    /** Don't poll the inbox more than this often, however many surfaces ask for it. */
    private static final long POLL_MIN_INTERVAL_MS = 15_000L;

    /**
     * Page size the inbox drains in. Must match the {@code LIMIT} the server's {@code giftsInbox}
     * uses (see {@code api/routes/gifts.ts}) — a full page is the signal that more gifts may be
     * queued behind it, so we keep polling until a short page proves the inbox is caught up.
     */
    private static final int INBOX_PAGE_SIZE = 100;

    /** Accounts with a drain in flight, and when each last started one — both keyed by account slot. */
    private static final Set<Integer> polling = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Long> lastPollAt = new ConcurrentHashMap<>();

    private SovietGramGiftSync() {
    }

    // ===== send =====

    /**
     * Mirrors a just-delivered local gift to the backend. Called from {@code LocalGiftHelper.deliver}
     * once the outgoing message is written, with the account that wrote it — the gift must be sent
     * under that account's token, since the server takes the sender's identity from the token alone.
     * Only real single-user recipients are synced (including the own account, whose gifts show on its
     * public showcase): a {@code dialogId <= 0} is a group or channel with no single owner to notify,
     * and an action that is not one of the four gift kinds is ignored. Fire-and-forget; a failure is
     * logged and dropped.
     */
    public static void pushGift(int account, long dialogId, @Nullable TLRPC.MessageAction action) {
        if (dialogId <= 0 || action == null || !SovietGramApiClient.isReady(account)) {
            return;
        }
        final String type = serverType(action);
        if (type == null) {
            return;
        }
        final String blob = encodeAction(action);
        if (blob == null) {
            return;
        }
        try {
            final JSONObject payload = new JSONObject();
            payload.put("v", PAYLOAD_VERSION);
            payload.put("tl", blob);

            final JSONObject body = new JSONObject();
            // telegramIdSchema accepts the id as a string; send it as one to sidestep JSON's 53-bit
            // number range for large ids.
            body.put("to_id", Long.toString(dialogId));
            body.put("type", type);
            body.put("payload", payload);

            SovietGramApiClient.postSigned(account, "/v1/gifts", body, (response, error) -> {
                if (error != null) {
                    org.telegram.messenger.FileLog.e("SovietGramGiftSync: gift push failed: " + error);
                }
            });
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    /** Maps a gift {@link TLRPC.MessageAction} to the server's gift-type enum, or {@code null} if it is not a gift. */
    @Nullable
    private static String serverType(TLRPC.MessageAction action) {
        if (action instanceof TLRPC.TL_messageActionStarGiftUnique) {
            return "nft";
        }
        if (action instanceof TLRPC.TL_messageActionGiftStars) {
            return "stars";
        }
        if (action instanceof TLRPC.TL_messageActionGiftPremium) {
            return "premium";
        }
        if (action instanceof TLRPC.TL_messageActionGiftTon) {
            return "ton";
        }
        return null;
    }

    // ===== receive =====

    /**
     * Drains the inbox of every logged-in account that holds a token. Safe and cheap to call from many
     * surfaces (launch, resuming a chat): each account self-throttles independently.
     */
    public static void pollInbox() {
        for (int account : SovietGramTokenStore.accountsWithToken()) {
            pollInbox(account);
        }
    }

    /**
     * Pulls any gifts addressed to {@code account} since its stored cursor and materialises them as
     * incoming messages. No-ops when that account has no token or no server is selected, when it
     * polled within the last {@value #POLL_MIN_INTERVAL_MS} ms, or when a drain is already running for
     * it — the throttle and the in-flight flag are per-account so one account's poll never suppresses
     * another's.
     */
    public static void pollInbox(int account) {
        if (!SovietGramApiClient.isReady(account)) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = lastPollAt.get(account);
        if (last != null && Math.abs(now - last) < POLL_MIN_INTERVAL_MS) {
            return;
        }
        if (!polling.add(account)) {
            return;
        }
        lastPollAt.put(account, now);
        pollPage(account);
    }

    /**
     * Fetches and delivers one page of {@code account}'s inbox, then — if the server returned a whole
     * page and the cursor actually advanced — immediately fetches the next, bypassing the throttle.
     * This drains a large backlog in one burst instead of one page per external trigger, while the
     * {@link #polling} entry (cleared only when a short page or an error ends the drain) keeps
     * concurrent callers out. The {@code maxId > cursor} guard stops an all-unparseable page from
     * re-fetching forever.
     */
    private static void pollPage(int account) {
        final long cursor = SovietGramTokenStore.giftCursor(account);
        SovietGramApiClient.get(account, "/v1/gifts/inbox?since=" + cursor, (body, error) -> {
            if (error != null || body == null) {
                polling.remove(account);
                if (error != null) {
                    org.telegram.messenger.FileLog.e("SovietGramGiftSync: inbox poll failed: " + error);
                }
                return;
            }
            final JSONArray gifts = body.optJSONArray("gifts");
            final int count = gifts == null ? 0 : gifts.length();
            final long maxId = count > 0 ? deliverInbox(account, gifts) : 0;
            // A full page means the server hit its LIMIT and more gifts may be queued behind it; the
            // cursor has advanced past everything just delivered, so fetch the next page straight away.
            if (count >= INBOX_PAGE_SIZE && maxId > cursor) {
                pollPage(account);
            } else {
                polling.remove(account);
            }
        });
    }

    /**
     * Reconstructs each inbox gift into the chat with its sender, oldest first, advancing the cursor
     * as it goes so a mid-batch interruption never re-delivers what already landed. Runs on the UI
     * thread (the API callback is dispatched there), so it can touch the UI pipeline directly.
     *
     * @return the highest gift id the cursor was advanced to, or {@code 0} if nothing was usable —
     * the caller uses this to decide whether draining the next page can make progress.
     */
    private static long deliverInbox(int account, JSONArray gifts) {
        final long myId = UserConfig.getInstance(account).getClientUserId();

        // The API returns oldest-first; sort defensively so the cursor only ever moves forward even
        // if the order ever changes.
        final List<InboxGift> parsed = new ArrayList<>(gifts.length());
        for (int i = 0; i < gifts.length(); i++) {
            final JSONObject gift = gifts.optJSONObject(i);
            if (gift == null) {
                continue;
            }
            final long id = parseLong(gift.optString("id", null), 0);
            if (id <= 0) {
                continue;
            }
            final long fromId = parseLong(gift.optString("from_id", null), 0);
            final JSONObject payload = gift.optJSONObject("payload");
            final String blob = payload == null ? null : payload.optString("tl", null);
            parsed.add(new InboxGift(id, fromId, blob));
        }
        Collections.sort(parsed, (a, b) -> Long.compare(a.id, b.id));

        long maxId = 0;
        for (InboxGift gift : parsed) {
            // A gift I sent echoes back in my own inbox (self-gifts, to_id == from_id == me). I already
            // have the local outgoing copy, so only skip the render — the cursor must still advance past
            // it or every future poll re-fetches it.
            if (gift.fromId > 0 && gift.fromId != myId && !TextUtils.isEmpty(gift.blob)) {
                final TLRPC.MessageAction action = decodeAction(gift.blob);
                if (action != null) {
                    deliverIncoming(account, gift.fromId, action);
                }
            }
            SovietGramTokenStore.setGiftCursor(account, gift.id);
            maxId = gift.id; // parsed is ascending, so the last write is the high-water mark
        }
        return maxId;
    }

    /**
     * Writes an incoming gift service message into the chat with {@code fromId}. The mirror image of
     * {@code LocalGiftHelper.deliver}: same persistence and UI-refresh dance, but the message is
     * incoming ({@code out = false}) and both peer and sender are the gifter.
     */
    private static void deliverIncoming(int account, long fromId, TLRPC.MessageAction action) {
        final MessagesController controller = MessagesController.getInstance(account);
        final UserConfig userConfig = UserConfig.getInstance(account);

        final TLRPC.TL_messageService message = new TLRPC.TL_messageService();
        message.local_id = message.id = userConfig.getNewMessageId();
        message.dialog_id = fromId;
        message.peer_id = controller.getPeer(fromId);
        message.from_id = controller.getPeer(fromId);
        message.date = ConnectionsManager.getInstance(account).getCurrentTime();
        message.action = action;
        message.unread = true;
        // TL_messageService derives the out flag from this boolean on serialise; setting the bit by
        // hand would be overwritten.
        message.out = false;
        message.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        userConfig.saveConfig(false);

        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        messages.add(message);
        final ArrayList<MessageObject> objects = new ArrayList<>();
        objects.add(new MessageObject(account, message, true, true));

        MessagesStorage.getInstance(account).putMessages(messages, true, true, false, 0, 0, 0);
        controller.updateInterfaceWithMessages(fromId, objects, 0);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    // ===== TL blob (de)serialisation =====

    /**
     * Serialises a whole {@link TLRPC.MessageAction} (constructor + fields, including nested documents)
     * to a base64 string. Returns {@code null} if the action is implausibly large or serialisation
     * fails.
     */
    @Nullable
    private static String encodeAction(TLRPC.MessageAction action) {
        try {
            final SerializedData data = new SerializedData(action.getObjectSize());
            action.serializeToStream(data);
            final byte[] bytes = data.toByteArray();
            data.cleanup();
            if (bytes.length == 0 || bytes.length > MAX_ACTION_BYTES) {
                if (bytes.length > MAX_ACTION_BYTES) {
                    org.telegram.messenger.FileLog.e("SovietGramGiftSync: action blob too large (" + bytes.length + "B), not syncing");
                }
                return null;
            }
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
            return null;
        }
    }

    /**
     * Reverses {@link #encodeAction}: reads the leading constructor int and dispatches through
     * {@link TLRPC.MessageAction#TLdeserialize}. Returns {@code null} on any malformed blob.
     */
    @Nullable
    private static TLRPC.MessageAction decodeAction(String blob) {
        try {
            final byte[] bytes = Base64.decode(blob, Base64.NO_WRAP);
            if (bytes.length < 4) {
                return null;
            }
            final SerializedData data = new SerializedData(bytes);
            final int constructor = data.readInt32(false);
            final TLRPC.MessageAction action = TLRPC.MessageAction.TLdeserialize(data, constructor, false);
            data.cleanup();
            return action;
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
            return null;
        }
    }

    // ===== misc =====

    private static long parseLong(@Nullable String value, long fallback) {
        if (TextUtils.isEmpty(value)) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Minimal parsed inbox row: the fields the reconstructor actually needs. */
    private static final class InboxGift {
        final long id;
        final long fromId;
        @Nullable final String blob;

        InboxGift(long id, long fromId, @Nullable String blob) {
            this.id = id;
            this.fromId = fromId;
            this.blob = blob;
        }
    }
}
