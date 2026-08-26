package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import tw.nekomimi.nekogram.helpers.lyrics.GeniusProvider;
import tw.nekomimi.nekogram.helpers.lyrics.LrcParser;
import tw.nekomimi.nekogram.helpers.lyrics.LrclibProvider;
import tw.nekomimi.nekogram.helpers.lyrics.LyricsMatcher;
import tw.nekomimi.nekogram.helpers.lyrics.LyricsResult;

/**
 * Finds the words to the track that is playing. Both providers are queried at once and the better
 * answer wins: LRCLIB can return timed lines, so it is preferred unless Genius matches the track
 * noticeably better.
 */
public final class LyricsHelper {

    private static final float HIGH_CONFIDENCE_THRESHOLD = 0.82f;
    private static final long GENIUS_JOIN_TIMEOUT_MS = 15_000L;
    private static final long CACHE_TTL = 15L * 24 * 60 * 60 * 1000;

    private static final Map<String, Entry> cache = new HashMap<>();

    private LyricsHelper() {
    }

    private static class Entry {
        LyricsResult result;
        long time;
    }

    public interface Callback {
        void onResult(@Nullable LyricsResult result);
    }

    private static String cacheKey(String title, @Nullable String artist, double duration) {
        return LyricsMatcher.normalizeKey(artist) + "|" + LyricsMatcher.normalizeKey(title)
                + "|" + (int) Math.round(duration);
    }

    /**
     * Looks the lyrics up off the main thread and hands the answer back on it. {@code null} means
     * nothing was found — the caller decides what to show.
     */
    public static void load(MessageObject messageObject, Callback callback) {
        String title = messageObject.getMusicTitle(false);
        String artist = messageObject.getMusicAuthor(false);
        double duration = messageObject.getDuration();
        if (TextUtils.isEmpty(title)) {
            title = messageObject.getFileName();
        }
        if (TextUtils.isEmpty(title)) {
            AndroidUtilities.runOnUIThread(() -> callback.onResult(null));
            return;
        }
        final String finalTitle = title;
        final String finalArtist = artist;
        final String key = cacheKey(title, artist, duration);
        synchronized (cache) {
            Entry cached = cache.get(key);
            if (cached != null && System.currentTimeMillis() - cached.time < CACHE_TTL) {
                LyricsResult result = cached.result;
                AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
                return;
            }
        }
        Utilities.externalNetworkQueue.postRunnable(() -> {
            LyricsResult result = null;
            try {
                result = fetch(finalTitle, finalArtist, duration);
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (result != null) {
                Entry entry = new Entry();
                entry.result = result;
                entry.time = System.currentTimeMillis();
                synchronized (cache) {
                    cache.put(key, entry);
                }
            }
            final LyricsResult delivered = result;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(delivered));
        });
    }

    @Nullable
    private static LyricsResult fetch(String title, @Nullable String artist, double duration) {
        // Genius is started first because it is the slower of the two; if LRCLIB comes back with a
        // confident synced match we never wait for it.
        final AtomicReference<LyricsResult> genius = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        Utilities.externalNetworkQueue.postRunnable(() -> {
            try {
                genius.set(GeniusProvider.search(title, artist));
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                latch.countDown();
            }
        });

        LyricsResult lrclib = null;
        try {
            LrclibProvider.Candidate candidate = LrclibProvider.search(title, artist, duration);
            if (candidate != null) {
                lrclib = toResult(candidate, duration);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        if (lrclib != null && (lrclib.instrumental || lrclib.confidence >= HIGH_CONFIDENCE_THRESHOLD)) {
            return lrclib;
        }
        try {
            latch.await(GENIUS_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignore) {
        }
        LyricsResult fallback = genius.get();
        if (fallback == null) {
            return lrclib;
        }
        if (lrclib == null) {
            return fallback;
        }
        // Timed lines are worth a lot more than a better title match, so Genius has to clear a
        // wider margin to replace a synced result.
        float margin = lrclib.synced ? 0.15f : 0.05f;
        return fallback.confidence > lrclib.confidence + margin ? fallback : lrclib;
    }

    private static LyricsResult toResult(LrclibProvider.Candidate candidate, double duration) {
        LyricsResult result = new LyricsResult();
        result.source = "LRCLIB";
        result.instrumental = candidate.instrumental;
        if (!TextUtils.isEmpty(candidate.syncedLyrics)) {
            result.lines.addAll(LrcParser.parse(candidate.syncedLyrics, duration));
            result.synced = !result.lines.isEmpty();
        }
        if (result.lines.isEmpty() && !TextUtils.isEmpty(candidate.plainLyrics)) {
            result.lines.addAll(LrcParser.parsePlain(candidate.plainLyrics));
            result.synced = false;
        }
        result.confidence = LyricsMatcher.confidence(candidate.titleSim, candidate.artistSim, result.synced);
        if (result.isEmpty() && !result.instrumental) {
            result.confidence = 0f;
        }
        return result;
    }
}
