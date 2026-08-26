package tw.nekomimi.nekogram.helpers.lyrics;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;
import tw.nekomimi.nekogram.utils.HttpClient;

/**
 * Looks lyrics up on LRCLIB, the one source here that can return timed lines. Its exact-match
 * endpoint is tried first and the search endpoint only as a fallback, because search will happily
 * return a cover version or a different mix.
 */
public final class LrclibProvider {

    private static final String USER_AGENT = "SovietGram-Lyrics/1.0 (https://github.com/fxck123)";
    private static final double MIN_SCORE_THRESHOLD = 45.0;

    private static final Gson gson = new Gson();

    private LrclibProvider() {
    }

    /** One track as LRCLIB describes it. */
    public static class Candidate {
        public String trackName;
        public String artistName;
        public double duration;
        public String syncedLyrics;
        public String plainLyrics;
        public boolean instrumental;

        double score;
        public float titleSim;
        public float artistSim;
    }

    /**
     * @param artist may be {@code null} when the file carries no artist tag.
     * @param duration track length in seconds, or 0 when unknown.
     */
    @Nullable
    public static Candidate search(String title, @Nullable String artist, double duration) {
        String normTitle = LyricsMatcher.normalizeKey(title);
        String normArtist = LyricsMatcher.normalizeKey(artist);
        boolean artistOnlyQuery = TextUtils.isEmpty(normTitle) && !TextUtils.isEmpty(normArtist);

        List<Candidate> pool = new ArrayList<>();
        // Exact match with everything we know is by far the most reliable, so each rung down the
        // ladder is only tried when the one above produced nothing good enough.
        Candidate best = pick(get(title, artist, duration), normTitle, normArtist, duration, artistOnlyQuery, pool);
        if (best != null) {
            return best;
        }
        best = pick(get(title, artist, 0), normTitle, normArtist, duration, artistOnlyQuery, pool);
        if (best != null) {
            return best;
        }
        best = pick(searchBy(title, artist, null), normTitle, normArtist, duration, artistOnlyQuery, pool);
        if (best != null) {
            return best;
        }
        if (!TextUtils.isEmpty(artist)) {
            best = pick(searchBy(null, null, artist + " " + title), normTitle, normArtist, duration, artistOnlyQuery, pool);
            if (best != null) {
                return best;
            }
        }
        return pick(searchBy(null, null, title), normTitle, normArtist, duration, artistOnlyQuery, pool);
    }

    @Nullable
    private static Candidate pick(List<Candidate> candidates, String normTitle, String normArtist,
                                  double duration, boolean artistOnlyQuery, List<Candidate> pool) {
        Candidate best = null;
        for (int a = 0; a < candidates.size(); a++) {
            Candidate candidate = candidates.get(a);
            if (TextUtils.isEmpty(candidate.syncedLyrics) && TextUtils.isEmpty(candidate.plainLyrics)
                    && !candidate.instrumental) {
                continue;
            }
            score(candidate, normTitle, normArtist, duration, artistOnlyQuery);
            pool.add(candidate);
            if (best == null || candidate.score > best.score) {
                best = candidate;
            }
        }
        if (best == null || best.score < MIN_SCORE_THRESHOLD) {
            return null;
        }
        // Final veto: a title that barely resembles ours is only accepted when the artist and the
        // running time both line up and the title is not something as generic as "Intro".
        if (best.titleSim < 0.15f
                && !(best.artistSim >= 0.85f && duration > 0 && Math.abs(best.duration - duration) <= 2
                && !LyricsMatcher.isAmbiguous(normTitle))) {
            return null;
        }
        return best;
    }

    private static void score(Candidate candidate, String normTitle, String normArtist,
                              double duration, boolean artistOnlyQuery) {
        String candTitle = LyricsMatcher.normalizeKey(candidate.trackName);
        String candArtist = LyricsMatcher.normalizeKey(candidate.artistName);
        candidate.titleSim = LyricsMatcher.similarity(normTitle, candTitle);
        candidate.artistSim = LyricsMatcher.similarity(normArtist, candArtist);

        double score = 0;
        if (!TextUtils.isEmpty(candidate.syncedLyrics)) {
            score += 40;
        } else if (!TextUtils.isEmpty(candidate.plainLyrics)) {
            score += 12;
        } else if (candidate.instrumental) {
            score += 3;
        }

        boolean titleContains = !candTitle.isEmpty() && !normTitle.isEmpty()
                && (candTitle.contains(normTitle) || normTitle.contains(candTitle));
        if (candTitle.equals(normTitle) && !normTitle.isEmpty()) {
            score += 32;
        } else if (titleContains) {
            float coverage = Math.min(candTitle.length(), normTitle.length())
                    / (float) Math.max(candTitle.length(), normTitle.length());
            score += 10 + 22 * coverage;
        } else {
            score += candidate.titleSim * 28;
        }

        boolean artistContains = !candArtist.isEmpty() && !normArtist.isEmpty()
                && (candArtist.contains(normArtist) || normArtist.contains(candArtist));
        if (candArtist.equals(normArtist) && !normArtist.isEmpty()) {
            score += 28;
        } else if (artistContains) {
            score += 18;
        } else {
            score += candidate.artistSim * 24;
        }

        if (duration > 0 && candidate.duration > 0) {
            double diff = Math.abs(candidate.duration - duration);
            if (diff <= 2) {
                score += 22;
            } else if (diff <= 5) {
                score += 14;
            } else if (diff <= 12) {
                score += 5;
            } else {
                score -= Math.min(20, diff / 3);
            }
        }

        if (candidate.titleSim < 0.2f && candidate.artistSim < 0.2f) {
            score -= 20;
        }
        boolean artistExact = candArtist.equals(normArtist) && !normArtist.isEmpty();
        boolean durationClose = duration > 0 && candidate.duration > 0
                && Math.abs(candidate.duration - duration) <= 10;
        if (candidate.titleSim < 0.25f
                && !(artistExact && durationClose && !LyricsMatcher.isAmbiguous(normTitle))) {
            score -= 150;
        }
        if (candidate.artistSim < 0.25f && !artistContains) {
            score -= 150;
        }
        if (artistOnlyQuery && candidate.titleSim < 0.25f) {
            score -= 35;
        }
        candidate.score = score;
    }

    private static List<Candidate> get(String title, @Nullable String artist, double duration) {
        Uri.Builder builder = Uri.parse("https://lrclib.net/api/get").buildUpon();
        builder.appendQueryParameter("track_name", title);
        if (!TextUtils.isEmpty(artist)) {
            builder.appendQueryParameter("artist_name", artist);
        }
        if (duration > 0) {
            builder.appendQueryParameter("duration", String.valueOf((int) Math.round(duration)));
        }
        List<Candidate> out = new ArrayList<>();
        String body = request(builder.build().toString());
        if (body == null) {
            return out;
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject()) {
                Candidate candidate = toCandidate(parsed.getAsJsonObject());
                if (candidate != null) {
                    out.add(candidate);
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    private static List<Candidate> searchBy(@Nullable String title, @Nullable String artist, @Nullable String query) {
        Uri.Builder builder = Uri.parse("https://lrclib.net/api/search").buildUpon();
        if (!TextUtils.isEmpty(query)) {
            builder.appendQueryParameter("q", query);
        } else {
            builder.appendQueryParameter("track_name", title);
            if (!TextUtils.isEmpty(artist)) {
                builder.appendQueryParameter("artist_name", artist);
            }
        }
        List<Candidate> out = new ArrayList<>();
        String body = request(builder.build().toString());
        if (body == null) {
            return out;
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonArray()) {
                JsonArray array = parsed.getAsJsonArray();
                for (int a = 0; a < array.size(); a++) {
                    if (!array.get(a).isJsonObject()) {
                        continue;
                    }
                    Candidate candidate = toCandidate(array.get(a).getAsJsonObject());
                    if (candidate != null) {
                        out.add(candidate);
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    @Nullable
    private static Candidate toCandidate(JsonObject object) {
        try {
            Candidate candidate = new Candidate();
            candidate.trackName = string(object, "trackName");
            candidate.artistName = string(object, "artistName");
            if (object.has("duration") && !object.get("duration").isJsonNull()) {
                candidate.duration = object.get("duration").getAsDouble();
            }
            candidate.syncedLyrics = string(object, "syncedLyrics");
            candidate.plainLyrics = string(object, "plainLyrics");
            candidate.instrumental = object.has("instrumental") && !object.get("instrumental").isJsonNull()
                    && object.get("instrumental").getAsBoolean();
            return candidate;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    /** @return the response body, or {@code null} on any failure including a 404 "no match". */
    @Nullable
    private static String request(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build();
            try (Response response = HttpClient.INSTANCE.getInstance().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                return response.body().string();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
