package tw.nekomimi.nekogram.helpers.lyrics;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.audioinfo.AudioInfo;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;
import tw.nekomimi.nekogram.utils.HttpClient;

/**
 * Finds the cover art for the playing track and turns it into a blurred backdrop. The file on disk
 * is tried first; only when the track carries no artwork at all do we ask iTunes for a picture.
 */
public final class LyricsCoverLoader {

    private static final int MAX_SIDE = 720;
    private static final int BLUR_RADIUS = 4;
    private static final int PALETTE_SIZE = 5;
    private static final int PALETTE_SAMPLE_SIDE = 48;

    public interface Callback {
        void onCover(@Nullable Bitmap bitmap);
    }

    private LyricsCoverLoader() {
    }

    public static void load(MessageObject messageObject, Callback callback) {
        Utilities.externalNetworkQueue.postRunnable(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = loadInternal(messageObject);
            } catch (Exception e) {
                FileLog.e(e);
            }
            final Bitmap delivered = bitmap;
            AndroidUtilities.runOnUIThread(() -> callback.onCover(delivered));
        });
    }

    @Nullable
    private static Bitmap loadInternal(MessageObject messageObject) {
        Bitmap source = fromAudioInfo();
        if (source == null) {
            source = fromArtworkUrl(messageObject);
        }
        if (source == null) {
            return null;
        }
        Bitmap scaled = downscale(source);
        if (scaled != source) {
            source.recycle();
        }
        Utilities.stackBlurBitmap(scaled, BLUR_RADIUS);
        return scaled;
    }

    @Nullable
    private static Bitmap fromAudioInfo() {
        AudioInfo info = MediaController.getInstance().getAudioInfo();
        if (info == null) {
            return null;
        }
        File file = info.getCoverFile();
        if (file != null && file.exists()) {
            Bitmap bitmap = decodeFile(file.getAbsolutePath());
            if (bitmap != null) {
                return bitmap;
            }
        }
        Bitmap cover = info.getCover();
        if (cover != null && !cover.isRecycled()) {
            // The player owns this bitmap, so hand back a copy the blur pass is free to chew on.
            try {
                return cover.copy(Bitmap.Config.ARGB_8888, true);
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    @Nullable
    private static Bitmap fromArtworkUrl(MessageObject messageObject) {
        String url = messageObject.getArtworkUrl(false);
        if (TextUtils.isEmpty(url)) {
            url = searchItunes(messageObject);
        }
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = HttpClient.INSTANCE.getInstance().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                try (InputStream stream = response.body().byteStream()) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    return BitmapFactory.decodeStream(stream, null, options);
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Last resort when the file carries no artwork: look the track up in the iTunes catalogue. */
    @Nullable
    private static String searchItunes(MessageObject messageObject) {
        String title = messageObject.getMusicTitle(false);
        String artist = messageObject.getMusicAuthor(false);
        if (TextUtils.isEmpty(title)) {
            return null;
        }
        String term = TextUtils.isEmpty(artist) ? title : artist + " " + title;
        String url = "https://itunes.apple.com/search?term=" + Uri.encode(term) + "&media=music&limit=1";
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = HttpClient.INSTANCE.getInstance().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                JsonObject root = JsonParser.parseString(response.body().string()).getAsJsonObject();
                JsonArray results = root.getAsJsonArray("results");
                if (results == null || results.size() == 0) {
                    return null;
                }
                JsonObject first = results.get(0).getAsJsonObject();
                if (!first.has("artworkUrl100")) {
                    return null;
                }
                return first.get("artworkUrl100").getAsString().replace("100x100bb", "400x400bb");
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static Bitmap decodeFile(String path) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(path, options);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap downscale(Bitmap source) {
        int max = Math.max(source.getWidth(), source.getHeight());
        if (max <= MAX_SIDE) {
            return source.isMutable() ? source : source.copy(Bitmap.Config.ARGB_8888, true);
        }
        float scale = MAX_SIDE / (float) max;
        Bitmap out = Bitmap.createScaledBitmap(source,
                Math.max(1, (int) (source.getWidth() * scale)),
                Math.max(1, (int) (source.getHeight() * scale)), true);
        return out.isMutable() ? out : out.copy(Bitmap.Config.ARGB_8888, true);
    }

    /**
     * @return the handful of colours the cover is mostly made of, brightest first, for the ambient
     * haze to borrow.
     */
    @Nullable
    public static int[] palette(@Nullable Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }
        try {
            Bitmap small = Bitmap.createScaledBitmap(bitmap, PALETTE_SAMPLE_SIDE, PALETTE_SAMPLE_SIDE, true);
            Map<Integer, Integer> counts = new HashMap<>();
            for (int y = 0; y < small.getHeight(); y++) {
                for (int x = 0; x < small.getWidth(); x++) {
                    int pixel = small.getPixel(x, y);
                    if (Color.alpha(pixel) < 128) {
                        continue;
                    }
                    // Quantise to 5 bits a channel so near-identical pixels land in one bucket.
                    int key = Color.rgb(Color.red(pixel) & 0xF8, Color.green(pixel) & 0xF8, Color.blue(pixel) & 0xF8);
                    Integer previous = counts.get(key);
                    counts.put(key, previous == null ? 1 : previous + 1);
                }
            }
            small.recycle();
            if (counts.isEmpty()) {
                return null;
            }
            List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(counts.entrySet());
            Collections.sort(entries, (a, b) -> b.getValue() - a.getValue());
            int size = Math.min(PALETTE_SIZE, entries.size());
            int[] out = new int[size];
            for (int a = 0; a < size; a++) {
                out[a] = entries.get(a).getKey();
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
