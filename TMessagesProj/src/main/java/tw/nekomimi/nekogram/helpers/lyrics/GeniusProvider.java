package tw.nekomimi.nekogram.helpers.lyrics;

import android.net.Uri;
import android.text.Html;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import tw.nekomimi.nekogram.utils.HttpClient;

/**
 * Falls back to Genius when LRCLIB has nothing. Genius has a far larger catalogue but stores lyrics
 * as a web page, so what comes back is always plain text with no timestamps — it is only used when
 * the alternative is showing nothing.
 */
public final class GeniusProvider {

    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final double MIN_SCORE = 40.0;
    /** Fewer lines than this and the scrape almost certainly failed rather than the song being short. */
    private static final int SUSPICIOUS_LINE_COUNT = 8;
    private static final float SUSPICIOUS_PENALTY = 0.35f;

    private static final Pattern CONTAINER = Pattern.compile(
            "<div[^>]*data-lyrics-container=\"true\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern EMBEDDED = Pattern.compile("\"lyrics\":\\{\"body\":\\{\"plain\":\"(.*?)\"");
    private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private GeniusProvider() {
    }

    public static class Hit {
        String title;
        String artist;
        String url;
        double score;
        float titleSim;
        float artistSim;
    }

    /**
     * @return plain lyrics for the track, or {@code null} when nothing convincing was found.
     */
    @Nullable
    public static LyricsResult search(String title, @Nullable String artist) {
        String normTitle = LyricsMatcher.normalizeKey(title);
        String normArtist = LyricsMatcher.normalizeKey(artist);
        String query = TextUtils.isEmpty(artist) ? title : artist + " " + title;

        List<Hit> hits = new ArrayList<>();
        hits.addAll(searchSong(query, 1));
        if (hits.isEmpty()) {
            hits.addAll(searchSong(query, 2));
        }
        if (hits.isEmpty()) {
            hits.addAll(searchMulti(query));
        }

        Hit best = null;
        for (int a = 0; a < hits.size(); a++) {
            Hit hit = hits.get(a);
            score(hit, normTitle, normArtist);
            if (best == null || hit.score > best.score) {
                best = hit;
            }
        }
        if (best == null || best.score < MIN_SCORE || TextUtils.isEmpty(best.url)) {
            return null;
        }
        String lyrics = scrape(best.url);
        if (TextUtils.isEmpty(lyrics)) {
            return null;
        }
        LyricsResult result = new LyricsResult();
        result.lines.addAll(LrcParser.parsePlain(lyrics));
        result.synced = false;
        result.source = "Genius";
        result.confidence = LyricsMatcher.confidence(best.titleSim, best.artistSim, false);
        if (result.lines.size() < SUSPICIOUS_LINE_COUNT) {
            result.confidence -= SUSPICIOUS_PENALTY;
        }
        return result.isEmpty() ? null : result;
    }

    private static void score(Hit hit, String normTitle, String normArtist) {
        String candTitle = LyricsMatcher.normalizeKey(hit.title);
        String candArtist = LyricsMatcher.normalizeKey(hit.artist);
        hit.titleSim = LyricsMatcher.similarity(normTitle, candTitle);
        hit.artistSim = LyricsMatcher.similarity(normArtist, candArtist);

        double score = 40;
        boolean titleContains = !candTitle.isEmpty() && !normTitle.isEmpty()
                && (candTitle.contains(normTitle) || normTitle.contains(candTitle));
        if (candTitle.equals(normTitle) && !normTitle.isEmpty()) {
            score += 32;
        } else if (titleContains) {
            float coverage = Math.min(candTitle.length(), normTitle.length())
                    / (float) Math.max(candTitle.length(), normTitle.length());
            score += 10 + 22 * coverage;
        } else {
            score += hit.titleSim * 28;
        }
        boolean artistContains = !candArtist.isEmpty() && !normArtist.isEmpty()
                && (candArtist.contains(normArtist) || normArtist.contains(candArtist));
        if (candArtist.equals(normArtist) && !normArtist.isEmpty()) {
            score += 28;
        } else if (artistContains) {
            score += 18;
        } else {
            score += hit.artistSim * 24;
        }
        if (hit.titleSim < 0.2f && hit.artistSim < 0.2f) {
            score -= 20;
        }
        if (hit.titleSim < 0.25f) {
            score -= 150;
        }
        if (hit.artistSim < 0.25f && !artistContains) {
            score -= 150;
        }
        hit.score = score;
    }

    private static List<Hit> searchSong(String query, int page) {
        String url = "https://genius.com/api/search/song?q=" + Uri.encode(query) + "&per_page=5&page=" + page;
        List<Hit> out = new ArrayList<>();
        String body = requestJson(url);
        if (body == null) {
            return out;
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray hits = root.getAsJsonObject("response").getAsJsonArray("hits");
            for (int a = 0; a < hits.size(); a++) {
                Hit hit = toHit(hits.get(a));
                if (hit != null) {
                    out.add(hit);
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    private static List<Hit> searchMulti(String query) {
        String url = "https://genius.com/api/search/multi?q=" + Uri.encode(query) + "&per_page=5&page=1";
        List<Hit> out = new ArrayList<>();
        String body = requestJson(url);
        if (body == null) {
            return out;
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray sections = root.getAsJsonObject("response").getAsJsonArray("sections");
            for (int a = 0; a < sections.size(); a++) {
                JsonObject section = sections.get(a).getAsJsonObject();
                if (!section.has("hits")) {
                    continue;
                }
                JsonArray hits = section.getAsJsonArray("hits");
                for (int b = 0; b < hits.size(); b++) {
                    JsonObject entry = hits.get(b).getAsJsonObject();
                    if (!entry.has("type") || !"song".equals(entry.get("type").getAsString())) {
                        continue;
                    }
                    Hit hit = toHit(entry);
                    if (hit != null) {
                        out.add(hit);
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    @Nullable
    private static Hit toHit(JsonElement element) {
        try {
            JsonObject result = element.getAsJsonObject().getAsJsonObject("result");
            Hit hit = new Hit();
            hit.title = result.get("title").getAsString();
            hit.url = result.get("url").getAsString();
            if (result.has("primary_artist") && !result.get("primary_artist").isJsonNull()) {
                hit.artist = result.getAsJsonObject("primary_artist").get("name").getAsString();
            }
            return hit;
        } catch (Exception e) {
            return null;
        }
    }

    /** Pulls the words out of a Genius song page. */
    @Nullable
    private static String scrape(String url) {
        String html = requestHtml(url);
        if (html == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        Matcher matcher = CONTAINER.matcher(html);
        while (matcher.find()) {
            String block = BR.matcher(matcher.group(1)).replaceAll("\n");
            block = TAG.matcher(block).replaceAll("");
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append(block);
        }
        if (builder.length() == 0) {
            Matcher embedded = EMBEDDED.matcher(html);
            if (embedded.find()) {
                builder.append(embedded.group(1).replace("\\n", "\n").replace("\\\"", "\""));
            }
        }
        if (builder.length() == 0) {
            return null;
        }
        return Html.fromHtml(builder.toString(), Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    @Nullable
    private static String requestJson(String url) {
        return request(url, true);
    }

    @Nullable
    private static String requestHtml(String url) {
        return request(url, false);
    }

    @Nullable
    private static String request(String url, boolean ajax) {
        try {
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_UA)
                    .get();
            if (ajax) {
                builder.header("X-Requested-With", "XMLHttpRequest")
                        .header("Accept", "application/json")
                        .header("Referer", "https://genius.com/search");
            }
            try (Response response = HttpClient.INSTANCE.getInstance().newCall(builder.build()).execute()) {
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
