package tw.nekomimi.nekogram.helpers;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import static org.telegram.messenger.LocaleController.getString;

/**
 * The song on the profile ("♫ title" under the avatar), set from the Custom Profile settings.
 *
 * Telegram only lets a profile song be a document that already exists on its servers, so the file is
 * first sent to Saved Messages as an audio message and, when the server has it, saved to the profile
 * with {@code account.saveMusic}: the same two steps a person would do by hand, in one.
 */
public final class ProfileMusicHelper {

    /** Ceiling on a picked file; Telegram itself allows far more, a profile song does not need it. */
    private static final long MAX_BYTES = 100L * 1024 * 1024;

    /** How long a sent file is waited for before the attempt is dropped. */
    private static final long WAIT_MS = 5 * 60 * 1000L;

    private ProfileMusicHelper() {
    }

    /** Opens the system's audio picker; the result goes to the fragment's onActivityResultFragment. */
    public static void pick(BaseFragment fragment, int requestCode) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            fragment.startActivityForResult(Intent.createChooser(intent, getString(R.string.CustomProfileMusic)), requestCode);
        } catch (Throwable e) {
            FileLog.e(e);
            BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.UnknownError)).show();
        }
    }

    /** Copies the picked file aside, sends it to Saved Messages and saves it to the profile when it lands. */
    public static void upload(BaseFragment fragment, Uri uri) {
        if (fragment == null || uri == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.timer_3, getString(R.string.CustomProfileMusicUploading)).show();
        Utilities.globalQueue.postRunnable(() -> {
            final File copy = copyToCache(uri);
            AndroidUtilities.runOnUIThread(() -> {
                if (copy == null) {
                    BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.CustomProfileMusicFailed)).show();
                    return;
                }
                send(account, fragment, copy);
            });
        });
    }

    private static void send(int account, BaseFragment fragment, File file) {
        final long self = UserConfig.getInstance(account).getClientUserId();
        final Waiter waiter = new Waiter(account, self, fragment);
        waiter.start();
        final ArrayList<String> paths = new ArrayList<>();
        paths.add(file.getAbsolutePath());
        final ArrayList<String> originals = new ArrayList<>();
        originals.add(file.getName());
        try {
            SendMessagesHelper.prepareSendingDocuments(AccountInstance.getInstance(account), paths, originals, null,
                    "", "audio/mpeg", self, null, null, null, null, null, true, 0, null, null, 0, false, 0);
        } catch (Throwable e) {
            FileLog.e(e);
            waiter.stop();
            BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.CustomProfileMusicFailed)).show();
        }
    }

    /** Takes the sent audio off the server's reply and puts it on the profile. */
    private static final class Waiter implements NotificationCenter.NotificationCenterDelegate {
        private final int account;
        private final long self;
        private final BaseFragment fragment;
        private final int startedAt;
        private boolean done;
        private final Runnable timeout = this::stop;

        Waiter(int account, long self, BaseFragment fragment) {
            this.account = account;
            this.self = self;
            this.fragment = fragment;
            this.startedAt = ConnectionsManager.getInstance(account).getCurrentTime() - 5;
        }

        void start() {
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messageReceivedByServer);
            AndroidUtilities.runOnUIThread(timeout, WAIT_MS);
        }

        void stop() {
            if (done) {
                return;
            }
            done = true;
            AndroidUtilities.cancelRunOnUIThread(timeout);
            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messageReceivedByServer);
        }

        @Override
        public void didReceivedNotification(int id, int acc, Object... args) {
            if (done || id != NotificationCenter.messageReceivedByServer || acc != account || args.length < 4) {
                return;
            }
            if (!(args[2] instanceof TLRPC.Message) || !(args[3] instanceof Long) || (Long) args[3] != self) {
                return;
            }
            final TLRPC.Message message = (TLRPC.Message) args[2];
            if (message.date < startedAt || message.media == null || message.media.document == null) {
                return;
            }
            final TLRPC.Document document = message.media.document;
            boolean audio = false;
            for (int i = 0; i < document.attributes.size(); i++) {
                if (document.attributes.get(i) instanceof TLRPC.TL_documentAttributeAudio) {
                    audio = true;
                    break;
                }
            }
            if (!audio) {
                return;
            }
            stop();
            save(account, document, fragment);
        }
    }

    private static void save(int account, TLRPC.Document document, BaseFragment fragment) {
        final TLRPC.TL_account_saveMusic req = new TLRPC.TL_account_saveMusic();
        req.unsave = false;
        req.id = inputOf(document);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.CustomProfileMusicFailed)).show();
                return;
            }
            final long self = UserConfig.getInstance(account).getClientUserId();
            // The profile page listens for this and shows the new song at once.
            new MessagesController.SavedMusicList(account, self).add(document);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.saved_messages, getString(R.string.CustomProfileMusicAdded)).show();
        }));
    }

    /** Takes the song now on the profile off it. The next one the profile holds, if any, takes its place. */
    public static void removeCurrent(BaseFragment fragment) {
        final int account = fragment.getCurrentAccount();
        final TLRPC.UserFull full = MessagesController.getInstance(account)
                .getUserFull(UserConfig.getInstance(account).getClientUserId());
        if (full == null || full.saved_music == null) {
            BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.CustomProfileMusicNone)).show();
            return;
        }
        final TLRPC.Document document = full.saved_music;
        final TLRPC.TL_account_saveMusic req = new TLRPC.TL_account_saveMusic();
        req.unsave = true;
        req.id = inputOf(document);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.CustomProfileMusicFailed)).show();
                return;
            }
            full.flags2 &= ~TLObject.FLAG_21;
            full.saved_music = null;
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.profileMusicUpdated,
                    UserConfig.getInstance(account).getClientUserId());
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_delete, getString(R.string.CustomProfileMusicRemoved)).show();
        }));
    }

    private static TLRPC.TL_inputDocument inputOf(TLRPC.Document document) {
        final TLRPC.TL_inputDocument input = new TLRPC.TL_inputDocument();
        input.id = document.id;
        input.access_hash = document.access_hash;
        input.file_reference = document.file_reference == null ? new byte[0] : document.file_reference;
        return input;
    }

    private static File copyToCache(Uri uri) {
        final ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        String name = null;
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
            }
        } catch (Throwable ignore) {
        }
        if (TextUtils.isEmpty(name)) {
            name = "music_" + System.currentTimeMillis() + ".mp3";
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        final File dir = new File(ApplicationLoader.getFilesDirFixed(), "cache");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        final File out = new File(dir, name);
        try (InputStream in = resolver.openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) {
                return null;
            }
            final byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_BYTES) {
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    return null;
                }
                os.write(buffer, 0, read);
            }
            return total > 0 ? out : null;
        } catch (Throwable e) {
            FileLog.e(e);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return null;
        }
    }
}
