package tw.nekomimi.nekogram.helpers;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.LongSparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Who is a developer and who is a supporter.
 *
 * <p>A short public list served by our own API and held in memory: a few dozen ids against a status
 * and, optionally, a line of text. Every name the app draws asks this, including while a chat list
 * is scrolling, so the answer has to be a lookup and nothing else — no request, no disk, no parse.
 *
 * <p>The list is written by hand into the database and read by everyone; there is no route to change
 * it from a client. It is fetched once per launch and then once every few hours, with the file it
 * was last read from standing in until the answer arrives, so a badge is on screen immediately after
 * a cold start rather than a second into it.
 *
 * <p>Unlike the plugin this idea comes from, the badge is not a custom emoji: it is the app's own
 * notification mark — the same one that sits in the phone's status bar — so it reads as "this person
 * is part of SovietGram" rather than as a sticker somebody happened to pick.
 */
public final class SovietGramBadges {

    public static final int STATUS_NONE = 0;
    public static final int STATUS_DEVELOPER = 1;
    public static final int STATUS_SUPPORTER = 2;

    private static final String PATH = "/v1/badges";
    private static final String CACHE = "sovietgram_badges.json";
    private static final int MAX_BYTES = 128 * 1024;
    /**
     * How long a fetched list is trusted before it is worth asking again.
     *
     * <p>Short, because the cost of asking is a hundred bytes and the cost of not asking is a badge
     * that was granted or taken away this morning and still shows the old answer tonight. The app
     * does not have to be restarted for that to be wrong — this is a static field, so it survives
     * every screen and every swipe away that leaves the process alive.
     */
    private static final long REFRESH_MS = 15 * 60 * 1000L;
    /** After a failure, how long before trying again — a server that is down stays down a while. */
    private static final long RETRY_MS = 10 * 60 * 1000L;

    /** One person's badge. */
    public static final class Badge {
        public final long id;
        public final int status;
        /** What the badge says when tapped; null falls back to the plain wording for the status. */
        @Nullable
        public final String label;

        Badge(long id, int status, @Nullable String label) {
            this.id = id;
            this.status = status;
            this.label = label;
        }
    }

    private static final LongSparseArray<Badge> badges = new LongSparseArray<>();

    private static boolean loaded;
    private static boolean fetching;
    private static long checkedAt;
    /** Set once the server has answered, so the disk copy can no longer overwrite a fresh list. */
    private static boolean fetched;

    private SovietGramBadges() {
    }

    // ---------------------------------------------------------------- asking

    /** This person's badge, or null. A lookup: safe to call from a draw or a bind. */
    @Nullable
    public static Badge badgeOf(long id) {
        ensureLoaded();
        return id == 0 ? null : badges.get(id);
    }

    public static boolean has(long id) {
        return badgeOf(id) != null;
    }

    public static boolean isDeveloper(long id) {
        final Badge badge = badgeOf(id);
        return badge != null && badge.status == STATUS_DEVELOPER;
    }

    public static boolean isSupporter(long id) {
        final Badge badge = badgeOf(id);
        return badge != null && badge.status == STATUS_SUPPORTER;
    }

    /**
     * The mark itself, sized to sit beside a name.
     *
     * <p>A fresh drawable each time because callers tint it to whatever colour the name beside it is
     * drawn in, and a shared one would leave the last caller's colour behind.
     */
    @Nullable
    public static Drawable drawable() {
        return drawable(BESIDE_NAME_DP);
    }

    /**
     * The mark at a chosen size in dp, for the places that are not a line of text — the bulletin,
     * mainly, whose image slot draws a drawable at whatever size it says it is.
     */
    @Nullable
    public static Drawable drawable(int sizeDp) {
        try {
            final Drawable source = ApplicationLoader.applicationContext.getResources()
                    .getDrawable(R.drawable.sovietgram_notification, null);
            return source == null ? null : new Mark(source.mutate(), AndroidUtilities.dp(sizeDp));
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * How tall the mark is when it stands next to a name.
     *
     * <p>The source is a status-bar icon: 24dp of box with the shape running the full height of it,
     * where a premium star is 14dp of box with the star filling about seven eighths. Handed over as
     * it comes, the badge is drawn nearly twice the size of every other mark a name can carry, which
     * is what makes it look wrong rather than merely different. Fifteen puts its height within a
     * pixel of the star's.
     */
    private static final int BESIDE_NAME_DP = 15;

    /**
     * The notification icon at a size of our choosing.
     *
     * <p>Only exists because the size a drawable is drawn at, in a name row or a bulletin, is the
     * size the drawable itself reports; there is no scale to set from outside. So this reports the
     * one we want and paints the source across whatever bounds it is given.
     */
    private static final class Mark extends Drawable {

        private final Drawable source;
        private final int size;

        Mark(Drawable source, int size) {
            this.source = source;
            this.size = size;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect bounds = getBounds();
            if (bounds.isEmpty()) {
                return;
            }
            source.setBounds(bounds);
            source.draw(canvas);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int alpha) {
            source.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            source.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** What a badge says: its own text, or the plain wording for the status. */
    public static String label(@Nullable Badge badge) {
        if (badge == null) {
            return "";
        }
        if (badge.label != null && !badge.label.isEmpty()) {
            return badge.label;
        }
        return org.telegram.messenger.LocaleController.getString(
                badge.status == STATUS_DEVELOPER
                        ? R.string.SovietGramBadgeDeveloper : R.string.SovietGramBadgeSupporter);
    }

    /** The line under the title in the popup: what having this badge actually means. */
    private static String about(Badge badge) {
        return org.telegram.messenger.LocaleController.getString(
                badge.status == STATUS_DEVELOPER
                        ? R.string.SovietGramBadgeDeveloperInfo : R.string.SovietGramBadgeSupporterInfo);
    }

    /**
     * What tapping a badge does: says who this is, in the strip that slides up from the bottom.
     *
     * <p>Nothing happens for somebody without one, so callers can wire the tap unconditionally.
     */
    public static void show(@Nullable BaseFragment fragment, long id) {
        final Badge badge = badgeOf(id);
        if (fragment == null || badge == null || fragment.getContext() == null) {
            return;
        }
        final Drawable icon = drawable(28);
        if (icon != null) {
            icon.setColorFilter(new PorterDuffColorFilter(
                    Theme.getColor(Theme.key_undo_infoColor, fragment.getResourceProvider()),
                    PorterDuff.Mode.SRC_IN));
        }
        BulletinFactory.of(fragment)
                .createSimpleBulletin(icon, title(fragment, badge), about(badge))
                .show();
    }

    /**
     * The bold line of the popup: whose badge this is.
     *
     * <p>The name rather than the status, because the line under it already says the status, and a
     * popup that says "SovietGram developer" twice says nothing the second time. Falls back to the
     * status when the account is not one we have cached.
     */
    private static String title(BaseFragment fragment, Badge badge) {
        try {
            final org.telegram.tgnet.TLRPC.User user = org.telegram.messenger.MessagesController
                    .getInstance(fragment.getCurrentAccount()).getUser(badge.id);
            final String name = user == null ? null : org.telegram.messenger.UserObject.getUserName(user);
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
        } catch (Throwable ignore) {
        }
        return label(badge);
    }

    // ---------------------------------------------------------------- keeping it current

    /**
     * Reads the last fetched list off disk, once, and asks the server if it is time to.
     *
     * <p>Called from every lookup, so the common path is one boolean.
     */
    private static void ensureLoaded() {
        if (!loaded) {
            loaded = true;
            readCache();
        }
        sync(false);
    }

    /** Asks the server, unless it was asked recently. Safe to call from anywhere. */
    public static void sync(boolean force) {
        final long now = System.currentTimeMillis();
        if (fetching || (!force && now - checkedAt < REFRESH_MS)) {
            return;
        }
        fetching = true;
        checkedAt = now;
        SovietGramApiClient.getPublic(PATH, (body, error) -> {
            fetching = false;
            if (body == null) {
                // Try again sooner than the usual interval, but not on the next name drawn.
                checkedAt = System.currentTimeMillis() - REFRESH_MS + RETRY_MS;
                return;
            }
            fetched = true;
            apply(body, true);
        });
    }

    private static void apply(JSONObject body, boolean store) {
        final JSONArray array = body.optJSONArray("badges");
        if (array == null) {
            return;
        }
        final LongSparseArray<Badge> next = new LongSparseArray<>();
        for (int i = 0; i < array.length(); i++) {
            final JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            final long id = parseId(item.optString("id", ""));
            final int status = status(item.optString("status", ""));
            if (id == 0 || status == STATUS_NONE) {
                continue;
            }
            // optString hands back the four letters "null" for a JSON null rather than the fallback,
            // and a row without a line of its own is exactly that, so ask before reading.
            final String label = item.isNull("label") ? "" : item.optString("label", "").trim();
            next.put(id, new Badge(id, status, label.isEmpty() ? null : label));
        }
        final boolean changed = differs(next);
        badges.clear();
        for (int i = 0; i < next.size(); i++) {
            badges.put(next.keyAt(i), next.valueAt(i));
        }
        if (store) {
            writeCache(body.toString());
        }
        if (changed) {
            // Names already on screen were drawn without these; the badge appears without a restart.
            // Two signals because they reach different places: reloadInterface rebuilds the lists,
            // and the emoji-status mask is what a chat header and an open profile listen to for the
            // mark beside a name — which is exactly the thing that just changed.
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
                for (int account = 0; account < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; account++) {
                    if (org.telegram.messenger.UserConfig.getInstance(account).isClientActivated()) {
                        NotificationCenter.getInstance(account).postNotificationName(
                                NotificationCenter.updateInterfaces,
                                org.telegram.messenger.MessagesController.UPDATE_MASK_EMOJI_STATUS);
                    }
                }
            });
        }
    }

    private static boolean differs(LongSparseArray<Badge> next) {
        if (next.size() != badges.size()) {
            return true;
        }
        for (int i = 0; i < next.size(); i++) {
            final Badge was = badges.get(next.keyAt(i));
            if (was == null || was.status != next.valueAt(i).status) {
                return true;
            }
        }
        return false;
    }

    /** The id arrives as text: it is a Telegram id and does not survive a JSON number everywhere. */
    private static long parseId(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Throwable ignore) {
            return 0;
        }
    }

    private static int status(String value) {
        return switch (value) {
            case "developer" -> STATUS_DEVELOPER;
            case "supporter" -> STATUS_SUPPORTER;
            default -> STATUS_NONE;
        };
    }

    private static File cacheFile() {
        return new File(ApplicationLoader.getFilesDirFixed(), CACHE);
    }

    private static void readCache() {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final File file = cacheFile();
                if (!file.isFile() || file.length() == 0 || file.length() > MAX_BYTES) {
                    return;
                }
                final JSONObject body = new JSONObject(
                        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                AndroidUtilities.runOnUIThread(() -> {
                    // The read was posted at the same moment the request went out, and this is the
                    // slower of the two often enough to matter: without the guard, a launch where
                    // the network wins first shows the new list and then replaces it with the old.
                    if (!fetched) {
                        apply(body, false);
                    }
                });
            } catch (Throwable e) {
                FileLog.e("SovietGramBadges: cache unreadable: " + e.getMessage());
            }
        });
    }

    private static void writeCache(String body) {
        if (body.length() > MAX_BYTES) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                Files.write(cacheFile().toPath(), body.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable e) {
                FileLog.e("SovietGramBadges: cache not written: " + e.getMessage());
            }
        });
    }
}
