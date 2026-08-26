package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows which of the groups you share with a person they are currently typing in, right in the
 * header of the private chat with them. The list of common chats is fetched lazily per user and
 * kept for {@link #CACHE_TTL}; the typing state itself is read straight out of
 * {@link MessagesController#printingUsers}, which the server keeps up to date for every dialog
 * you are a member of, not just the open one.
 */
public final class CommonChatsTypingHelper {

    private static final long CACHE_TTL = 10 * 60 * 1000L;
    private static final long RETRY_DELAY = 30 * 1000L;
    private static final int FETCH_LIMIT = 100;
    private static final int MAX_CHATS = 500;

    private static final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    private CommonChatsTypingHelper() {
    }

    public static class Status {
        public final CharSequence text;
        public final int type;

        Status(CharSequence text, int type) {
            this.text = text;
            this.type = type;
        }
    }

    private static class Entry {
        final ArrayList<Long> chatIds = new ArrayList<>();
        long checkedAt;
        boolean loading;
        boolean loaded;
    }

    private static String key(int account, long userId) {
        return account + "_" + userId;
    }

    private static Entry entry(int account, long userId) {
        String key = key(account, userId);
        Entry entry = cache.get(key);
        if (entry == null) {
            entry = new Entry();
            cache.put(key, entry);
        }
        return entry;
    }

    /**
     * Makes sure the common-chat list for this user is known. Cheap to call repeatedly — the
     * result is cached and only refreshed once it goes stale.
     */
    public static void track(int account, long userId) {
        if (userId <= 0 || UserObject.isService(userId)) {
            return;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
        if (user == null || user.bot || UserObject.isUserSelf(user) || UserObject.isDeleted(user)) {
            return;
        }
        Entry entry = entry(account, userId);
        if (entry.loading) {
            return;
        }
        long elapsed = System.currentTimeMillis() - entry.checkedAt;
        if (entry.checkedAt != 0 && elapsed < (entry.loaded ? CACHE_TTL : RETRY_DELAY)) {
            return;
        }
        entry.loading = true;
        request(account, userId, entry, 0, new ArrayList<>());
    }

    private static void request(int account, long userId, Entry entry, long maxId, ArrayList<Long> collected) {
        TLRPC.InputUser inputUser = MessagesController.getInstance(account).getInputUser(userId);
        if (inputUser == null || inputUser instanceof TLRPC.TL_inputUserEmpty) {
            finish(account, entry, collected, false);
            return;
        }
        TLRPC.TL_messages_getCommonChats req = new TLRPC.TL_messages_getCommonChats();
        req.user_id = inputUser;
        req.max_id = maxId;
        req.limit = FETCH_LIMIT;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.messages_Chats)) {
                finish(account, entry, collected, false);
                return;
            }
            ArrayList<TLRPC.Chat> chats = ((TLRPC.messages_Chats) response).chats;
            MessagesController.getInstance(account).putChats(chats, false);
            long lastId = 0;
            for (int a = 0; a < chats.size(); a++) {
                TLRPC.Chat chat = chats.get(a);
                lastId = chat.id;
                if (ChatObject.isChannel(chat) && !chat.megagroup) {
                    continue;
                }
                if (!collected.contains(chat.id)) {
                    collected.add(chat.id);
                }
            }
            if (chats.size() >= FETCH_LIMIT && lastId != 0 && collected.size() < MAX_CHATS) {
                request(account, userId, entry, lastId, collected);
            } else {
                finish(account, entry, collected, true);
            }
        }));
    }

    private static void finish(int account, Entry entry, ArrayList<Long> collected, boolean success) {
        entry.loading = false;
        entry.loaded = success;
        entry.checkedAt = System.currentTimeMillis();
        if (success) {
            entry.chatIds.clear();
            entry.chatIds.addAll(collected);
            if (!collected.isEmpty()) {
                NotificationCenter.getInstance(account).postNotificationName(
                        NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_USER_PRINT);
            }
        }
    }

    /**
     * @return the subtitle to show plus the matching status-drawable index, or {@code null} when
     * the user is not typing in any shared group.
     */
    @Nullable
    public static Status getStatus(int account, long userId) {
        Entry entry = cache.get(key(account, userId));
        if (entry == null || entry.chatIds.isEmpty()) {
            return null;
        }
        MessagesController controller = MessagesController.getInstance(account);
        for (int a = 0; a < entry.chatIds.size(); a++) {
            long chatId = entry.chatIds.get(a);
            ConcurrentHashMap<Integer, ArrayList<MessagesController.PrintingUser>> threads = controller.printingUsers.get(-chatId);
            if (threads == null) {
                continue;
            }
            for (ArrayList<MessagesController.PrintingUser> users : threads.values()) {
                if (users == null) {
                    continue;
                }
                for (int b = 0; b < users.size(); b++) {
                    MessagesController.PrintingUser printing = users.get(b);
                    if (printing == null || printing.userId != userId) {
                        continue;
                    }
                    TLRPC.Chat chat = controller.getChat(chatId);
                    if (chat == null || TextUtils.isEmpty(chat.title)) {
                        continue;
                    }
                    String text = LocaleController.formatString(R.string.LookerActionInChat, action(printing.action), chat.title);
                    return new Status(text, type(printing.action));
                }
            }
        }
        return null;
    }

    private static String action(TLRPC.SendMessageAction action) {
        if (action instanceof TLRPC.TL_sendMessageRecordAudioAction) {
            return LocaleController.getString(R.string.RecordingAudio);
        } else if (action instanceof TLRPC.TL_sendMessageRecordRoundAction) {
            return LocaleController.getString(R.string.RecordingRound);
        } else if (action instanceof TLRPC.TL_sendMessageRecordVideoAction) {
            return LocaleController.getString(R.string.RecordingVideoStatus);
        } else if (action instanceof TLRPC.TL_sendMessageUploadRoundAction
                || action instanceof TLRPC.TL_sendMessageUploadVideoAction) {
            return LocaleController.getString(R.string.SendingVideoStatus);
        } else if (action instanceof TLRPC.TL_sendMessageUploadAudioAction) {
            return LocaleController.getString(R.string.SendingAudio);
        } else if (action instanceof TLRPC.TL_sendMessageUploadDocumentAction) {
            return LocaleController.getString(R.string.SendingFile);
        } else if (action instanceof TLRPC.TL_sendMessageUploadPhotoAction) {
            return LocaleController.getString(R.string.SendingPhoto);
        } else if (action instanceof TLRPC.TL_sendMessageGamePlayAction) {
            return LocaleController.getString(R.string.SendingGame);
        } else if (action instanceof TLRPC.TL_sendMessageGeoLocationAction) {
            return LocaleController.getString(R.string.SelectingLocation);
        } else if (action instanceof TLRPC.TL_sendMessageChooseContactAction) {
            return LocaleController.getString(R.string.SelectingContact);
        } else if (action instanceof TLRPC.TL_sendMessageChooseStickerAction) {
            return LocaleController.getString(R.string.ChoosingSticker);
        }
        return LocaleController.getString(R.string.Typing);
    }

    /**
     * Index into {@code ChatAvatarContainer.statusDrawables}. Type 5 is skipped on purpose: it
     * swaps a marker inside the text for the drawable, which our own wording does not carry.
     */
    private static int type(TLRPC.SendMessageAction action) {
        if (action instanceof TLRPC.TL_sendMessageRecordAudioAction) {
            return 1;
        } else if (action instanceof TLRPC.TL_sendMessageRecordRoundAction
                || action instanceof TLRPC.TL_sendMessageUploadRoundAction) {
            return 4;
        } else if (action instanceof TLRPC.TL_sendMessageUploadAudioAction
                || action instanceof TLRPC.TL_sendMessageUploadVideoAction
                || action instanceof TLRPC.TL_sendMessageRecordVideoAction
                || action instanceof TLRPC.TL_sendMessageUploadDocumentAction
                || action instanceof TLRPC.TL_sendMessageUploadPhotoAction) {
            return 2;
        } else if (action instanceof TLRPC.TL_sendMessageGamePlayAction) {
            return 3;
        }
        return 0;
    }
}
