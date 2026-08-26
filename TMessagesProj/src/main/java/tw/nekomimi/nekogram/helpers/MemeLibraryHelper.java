package tw.nekomimi.nekogram.helpers;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SendMessagesHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A private stash of pictures kept on the device, searchable by the tags the user gave them.
 * <p>
 * Nothing here goes near the network: the files sit next to the app's own data and the index is a
 * single JSON file beside them. Internal storage rather than the gallery on purpose — it needs no
 * permission, it survives an Android upgrade and the pictures do not turn up in the camera roll.
 */
public final class MemeLibraryHelper {

    private static final String DIR = "memes";
    private static final String DB = "memes_db.json";

    /** Same ceilings the reference plugin used; the chip row stops being usable much past this. */
    public static final int MAX_CATEGORIES = 30;
    public static final int MAX_CATEGORY_LENGTH = 10;

    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp4", "avi", "mov", "webm", "mkv", "3gp"));

    /** One saved picture. */
    public static final class Meme {
        public String filename = "";
        public String filepath = "";
        public String tags = "";
        public String category = "";
        public long timestamp;
        public boolean favorite;

        public boolean isVideo() {
            return VIDEO_EXTENSIONS.contains(extensionOf(filepath));
        }
    }

    private static List<Meme> memes;
    private static List<String> categories;

    private MemeLibraryHelper() {
    }

    private static File dir() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static File dbFile() {
        return new File(dir(), DB);
    }

    private static String extensionOf(String path) {
        if (path == null) {
            return "";
        }
        final int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ store

    private static synchronized void load() {
        if (memes != null) {
            return;
        }
        memes = new ArrayList<>();
        categories = new ArrayList<>();
        final File file = dbFile();
        if (!file.exists()) {
            return;
        }
        try {
            final String raw = readText(file);
            if (TextUtils.isEmpty(raw)) {
                return;
            }
            final JSONObject root = new JSONObject(raw);
            final JSONArray list = root.optJSONArray("memes");
            if (list != null) {
                for (int a = 0; a < list.length(); a++) {
                    final JSONObject item = list.optJSONObject(a);
                    if (item == null) {
                        continue;
                    }
                    final Meme meme = new Meme();
                    meme.filename = item.optString("filename");
                    meme.filepath = item.optString("filepath");
                    meme.tags = item.optString("tags");
                    meme.category = item.optString("category");
                    meme.timestamp = item.optLong("timestamp");
                    meme.favorite = item.optBoolean("favorite");
                    // A file the user deleted from outside would otherwise show as a blank cell.
                    if (!TextUtils.isEmpty(meme.filepath) && new File(meme.filepath).exists()) {
                        memes.add(meme);
                    }
                }
            }
            final JSONArray cats = root.optJSONArray("categories");
            if (cats != null) {
                for (int a = 0; a < cats.length(); a++) {
                    final String name = cats.optString(a);
                    if (!TextUtils.isEmpty(name)) {
                        categories.add(name);
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static synchronized void save() {
        try {
            final JSONArray list = new JSONArray();
            for (Meme meme : memes) {
                final JSONObject item = new JSONObject();
                item.put("filename", meme.filename);
                item.put("filepath", meme.filepath);
                item.put("tags", meme.tags);
                item.put("category", meme.category);
                item.put("timestamp", meme.timestamp);
                item.put("favorite", meme.favorite);
                list.put(item);
            }
            final JSONObject root = new JSONObject();
            root.put("memes", list);
            root.put("categories", new JSONArray(categories));
            try (FileOutputStream stream = new FileOutputStream(dbFile())) {
                stream.write(root.toString().getBytes("UTF-8"));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Everything in the library, newest first. */
    public static synchronized List<Meme> getAll() {
        load();
        final List<Meme> copy = new ArrayList<>(memes);
        Collections.sort(copy, (a, b) -> Long.compare(b.timestamp, a.timestamp));
        return copy;
    }

    public static synchronized List<String> getCategories() {
        load();
        return new ArrayList<>(categories);
    }

    /**
     * The subset matching the current filters.
     *
     * @param category      null for every category.
     * @param favoritesOnly only the starred ones.
     * @param query         space-separated words; a meme matches when its tags contain all of them.
     */
    public static List<Meme> filter(@Nullable String category, boolean favoritesOnly, @Nullable String query) {
        final List<Meme> result = new ArrayList<>();
        final String[] words = TextUtils.isEmpty(query)
                ? new String[0] : query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        for (Meme meme : getAll()) {
            if (favoritesOnly && !meme.favorite) {
                continue;
            }
            if (category != null && !category.equals(meme.category)) {
                continue;
            }
            final String tags = meme.tags == null ? "" : meme.tags.toLowerCase(Locale.ROOT);
            boolean matches = true;
            for (String word : words) {
                if (!word.isEmpty() && !tags.contains(word)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                result.add(meme);
            }
        }
        return result;
    }

    /**
     * Copies a file into the library.
     *
     * @return the new entry, or null when the copy failed.
     */
    @Nullable
    public static synchronized Meme add(String sourcePath, String tags, String category) {
        load();
        final File source = new File(sourcePath);
        if (!source.exists() || source.length() == 0) {
            return null;
        }
        final String extension = extensionOf(sourcePath);
        final String name = "meme_" + System.currentTimeMillis() + (extension.isEmpty() ? ".jpg" : "." + extension);
        final File target = new File(dir(), name);
        try {
            copy(source, target);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
        return register(name, target, tags, category);
    }

    /** Same as {@link #add} for something the system picker handed us as a content URI. */
    @Nullable
    public static synchronized Meme addFromUri(Uri uri, String tags, String category) {
        load();
        if (uri == null) {
            return null;
        }
        String extension = extensionOf(uri.getLastPathSegment());
        if (extension.isEmpty()) {
            final String type = ApplicationLoader.applicationContext.getContentResolver().getType(uri);
            extension = type != null && type.startsWith("video/") ? "mp4" : "jpg";
        }
        final String name = "meme_" + System.currentTimeMillis() + "." + extension;
        final File target = new File(dir(), name);
        try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                return null;
            }
            final byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return null;
        }
        if (target.length() == 0) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return null;
        }
        return register(name, target, tags, category);
    }

    private static Meme register(String name, File target, String tags, String category) {
        final Meme meme = new Meme();
        meme.filename = name;
        meme.filepath = target.getAbsolutePath();
        meme.tags = tags == null ? "" : tags.trim();
        meme.category = normalizeCategory(category);
        meme.timestamp = System.currentTimeMillis();
        memes.add(meme);
        if (!TextUtils.isEmpty(meme.category) && !categories.contains(meme.category)
                && categories.size() < MAX_CATEGORIES) {
            categories.add(meme.category);
        }
        save();
        return meme;
    }

    private static String normalizeCategory(@Nullable String category) {
        if (category == null) {
            return "";
        }
        final String trimmed = category.trim();
        return trimmed.length() > MAX_CATEGORY_LENGTH ? trimmed.substring(0, MAX_CATEGORY_LENGTH) : trimmed;
    }

    private static String readText(File file) throws Exception {
        final byte[] bytes = new byte[(int) file.length()];
        try (InputStream in = new java.io.FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                final int read = in.read(bytes, offset, bytes.length - offset);
                if (read <= 0) {
                    break;
                }
                offset += read;
            }
        }
        return new String(bytes, "UTF-8");
    }

    private static void copy(File source, File target) throws Exception {
        try (InputStream in = new java.io.FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            final byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    public static synchronized void remove(Meme meme) {
        load();
        memes.remove(meme);
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(meme.filepath).delete();
        } catch (Throwable ignore) {
        }
        save();
    }

    public static synchronized void update(Meme meme, String tags, String category) {
        load();
        meme.tags = tags == null ? "" : tags.trim();
        meme.category = normalizeCategory(category);
        if (!TextUtils.isEmpty(meme.category) && !categories.contains(meme.category)
                && categories.size() < MAX_CATEGORIES) {
            categories.add(meme.category);
        }
        save();
    }

    public static synchronized void toggleFavorite(Meme meme) {
        load();
        meme.favorite = !meme.favorite;
        save();
    }

    // ------------------------------------------------------------- thumbnails

    /** A square-ish preview of a saved file, decoded no larger than it will be shown. */
    @Nullable
    public static Bitmap thumbnail(Meme meme, int size) {
        try {
            if (meme.isVideo()) {
                return android.media.ThumbnailUtils.createVideoThumbnail(meme.filepath,
                        android.provider.MediaStore.Images.Thumbnails.MINI_KIND);
            }
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(meme.filepath, bounds);
            final BitmapFactory.Options options = new BitmapFactory.Options();
            int sample = 1;
            while (size > 0 && Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) {
                sample *= 2;
            }
            options.inSampleSize = sample;
            return BitmapFactory.decodeFile(meme.filepath, options);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    // ------------------------------------------------------------------ send

    /** Sends the picked memes into the chat, in the order they were selected. */
    public static void send(AccountInstance account, long dialogId, List<Meme> selected) {
        if (selected == null || selected.isEmpty()) {
            return;
        }
        for (Meme meme : selected) {
            if (meme.isVideo()) {
                SendMessagesHelper.prepareSendingVideo(account, meme.filepath, null, null, null,
                        dialogId, null, null, null, null, null, 0, null, true, 0, 0, false, false,
                        null, null, 0, 0, 0);
            } else {
                SendMessagesHelper.prepareSendingPhoto(account, meme.filepath, null, dialogId,
                        null, null, null, null, null, null, null, 0, null, true, 0, 0, null, 0);
            }
        }
    }
}
