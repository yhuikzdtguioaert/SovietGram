package tw.nekomimi.nekogram.helpers.remote;

import android.text.TextUtils;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sovietgram.com.NaConfig;

public class UpdateHelper extends BaseRemoteHelper {

    public static final int UPDATE_OFF = 0;
    public static final int UPDATE_CHANNEL_RELEASE = 1;
    public static final int UPDATE_CHANNEL_BETA = 2;
    private static final String DEFAULT_CHANGELOG = "SovietGram update";
    private static final Pattern VERSION_CODE_PATTERN = Pattern.compile("\\((\\d+)\\)");
    private static final Pattern VERSION_NAME_PATTERN = Pattern.compile("-v([^()]+)\\(");

    public static UpdateHelper getInstance() {
        return InstanceHolder.instance;
    }

    public static int getAutoUpdateChannel() {
        int channel = NaConfig.INSTANCE.getAutoUpdateChannel().Int();
        return channel == UPDATE_CHANNEL_BETA ? UPDATE_CHANNEL_RELEASE : channel;
    }

    public static void cleanAppUpdate() {
        if (SharedConfig.pendingAppUpdate != null && SharedConfig.pendingAppUpdate.document != null) {
            File path = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(SharedConfig.pendingAppUpdate.document, true);
            if (path != null && path.exists()) {
                Utilities.globalQueue.postRunnable(() -> {
                    try {
                        if (!path.delete()) path.deleteOnExit();
                    } catch (Exception ignored) {
                    }
                });
            }
        }
        SharedConfig.pendingAppUpdate = null;
        SharedConfig.saveConfig();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    @Override
    protected void onError(String text, Delegate delegate) {
        delegate.onTLResponse(null, text);
    }

    @Override
    protected String getTag() {
        return "updateRelease";
    }

    public void checkNewVersionAvailable(Delegate delegate) {
        checkNewVersionAvailable(delegate, false);
    }

    public void checkNewVersionAvailable(Delegate delegate, boolean updateAlways) {
        loadMessages("", 50, (response, error) -> {
            if (error != null) {
                delegate.onTLResponse(null, error);
                return;
            }
            if (response == null) {
                delegate.onTLResponse(null, null);
                return;
            }
            TLRPC.Message apkMessage = null;
            TLRPC.Document sticker = pickRandomStickerDocument(response.messages);
            for (var message : response.messages) {
                if (getApkDocument(message) != null) {
                    apkMessage = message;
                    break;
                }
            }
            TLRPC.TL_help_appUpdate update = buildUpdateFromMessage(apkMessage, sticker, updateAlways);
            delegate.onTLResponse(update, null);
        });
    }

    private TLRPC.Document getApkDocument(TLRPC.Message message) {
        if (message == null || message.media == null || message.media.document == null) {
            return null;
        }
        TLRPC.Document document = message.media.document;
        String fileName = FileLoader.getDocumentFileName(document);
        if (!isApkDocument(document, fileName)) {
            return null;
        }
        return document;
    }

    private TLRPC.TL_help_appUpdate buildUpdateFromMessage(TLRPC.Message message, TLRPC.Document sticker, boolean updateAlways) {
        TLRPC.Document document = getApkDocument(message);
        if (document == null) {
            return null;
        }
        String fileName = FileLoader.getDocumentFileName(document);
        int remoteVersionCode = parseVersionCode(fileName);
        boolean shouldUpdate = remoteVersionCode > BuildConfig.VERSION_CODE;
        if (!shouldUpdate && !updateAlways) {
            return null;
        }

        var update = new TLRPC.TL_help_appUpdate();
        update.version = parseVersionName(fileName);
        update.can_not_skip = false;
        update.text = extractCommitMessage(message.message);
        update.document = document;
        update.url = "https://t.me/" + CHANNEL_METADATA_NAME;
        update.flags |= 2;
        update.flags |= 4;
        if (sticker != null) {
            update.sticker = sticker;
            update.flags |= 8;
        }

        if (getAutoUpdateChannel() == UPDATE_OFF && !update.can_not_skip && !updateAlways) {
            return null;
        }
        return update;
    }

    private TLRPC.Document pickRandomStickerDocument(ArrayList<TLRPC.Message> messages) {
        ArrayList<TLRPC.Document> stickers = new ArrayList<>();
        for (var message : messages) {
            if (message == null || message.media == null || message.media.document == null) {
                continue;
            }
            TLRPC.Document document = message.media.document;
            String fileName = FileLoader.getDocumentFileName(document);
            if (!isApkDocument(document, fileName) && isStickerDocument(document)) {
                stickers.add(document);
            }
        }
        if (stickers.isEmpty()) {
            return null;
        }
        return stickers.get(Utilities.random.nextInt(stickers.size()));
    }

    private boolean isStickerDocument(TLRPC.Document document) {
        if (document == null || document.attributes == null) {
            return false;
        }
        for (var attribute : document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeSticker) {
                return true;
            }
            if (attribute instanceof TLRPC.TL_documentAttributeCustomEmoji) {
                return true;
            }
        }
        return false;
    }

    private boolean isApkDocument(TLRPC.Document document, String fileName) {
        if (!TextUtils.isEmpty(fileName) && fileName.toLowerCase(Locale.US).endsWith(".apk")) {
            return true;
        }
        return "application/vnd.android.package-archive".equals(document.mime_type);
    }

    private int parseVersionCode(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return 0;
        }
        Matcher matcher = VERSION_CODE_PATTERN.matcher(fileName);
        int versionCode = 0;
        while (matcher.find()) {
            try {
                versionCode = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return versionCode;
    }

    private String parseVersionName(String fileName) {
        if (!TextUtils.isEmpty(fileName)) {
            Matcher matcher = VERSION_NAME_PATTERN.matcher(fileName);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return BuildConfig.BUILD_VERSION_STRING;
    }

    private String extractCommitMessage(String text) {
        if (TextUtils.isEmpty(text)) {
            return DEFAULT_CHANGELOG;
        }
        String changelog = text.trim();
        if (TextUtils.isEmpty(changelog)) {
            return DEFAULT_CHANGELOG;
        }
        return changelog;
    }

    private static final class InstanceHolder {
        private static final UpdateHelper instance = new UpdateHelper();
    }
}
