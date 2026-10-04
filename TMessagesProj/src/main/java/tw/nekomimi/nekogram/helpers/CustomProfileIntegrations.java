package tw.nekomimi.nekogram.helpers;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Integration metadata travels with the profile; credentials never belong in a block. */
public final class CustomProfileIntegrations {
    private static final String[] KEYS = {"lastfm", "github", "steam", "yamusic", "spotify", "soundcloud"};
    private static final String[] NAMES = {"Last.fm", "GitHub", "Steam", "Yandex Music", "Spotify", "SoundCloud"};
    private static final int[][] MODES = {
        {R.string.CustomProfileIntegrationNow, R.string.CustomProfileIntegrationScrobbles, R.string.CustomProfileIntegrationArtist, R.string.CustomProfileIntegrationAlbum},
        {R.string.CustomProfileIntegrationRepos, R.string.CustomProfileIntegrationStars, R.string.CustomProfileIntegrationFollowers, R.string.CustomProfileIntegrationFollowing, R.string.CustomProfileIntegrationSince},
        {R.string.CustomProfileIntegrationPlaying, R.string.CustomProfileIntegrationSince, R.string.CustomProfileIntegrationHoursRecent, R.string.CustomProfileExtraRowTitle, R.string.CustomProfileIntegrationLevel, R.string.CustomProfileIntegrationGames, R.string.CustomProfileIntegrationHours, R.string.CustomProfileIntegrationLastGame},
        {R.string.CustomProfileIntegrationNow, R.string.CustomProfileIntegrationLastLike, R.string.CustomProfileIntegrationLikedTracks, R.string.CustomProfileIntegrationPlaylists},
        {R.string.CustomProfileIntegrationNow, R.string.CustomProfileIntegrationLastTrack, R.string.CustomProfileIntegrationArtist, R.string.CustomProfileIntegrationFollowers},
        {R.string.CustomProfileIntegrationLastTrack, R.string.CustomProfileIntegrationFollowers, R.string.CustomProfileIntegrationTracks, R.string.CustomProfileIntegrationLikes}
    };
    private record Held(String value, long until) { }
    // UI-thread only. Bounded, scoped by both Telegram identity and provider configuration.
    private static final LinkedHashMap<String, Held> CACHE = new LinkedHashMap<>();
    private static final Map<String, List<Consumer<String>>> PENDING = new HashMap<>();
    private static int cacheGeneration;
    private CustomProfileIntegrations() { }

    // SoundCloud's own "share" links (on.soundcloud.com/XXXX) name no account: they redirect to the page that was
    // shared. Each one is followed once and the account found there is remembered; "" means it led nowhere.
    private static final Map<String, String> SHORT = new HashMap<>();
    private static final Map<String, List<Consumer<String>>> SHORT_WAITING = new HashMap<>();

    public static boolean isShortSoundcloud(String link) {
        if (link == null) return false;
        try {
            Uri uri = Uri.parse(link.trim());
            return "on.soundcloud.com".equalsIgnoreCase(uri.getHost()) && uri.getPathSegments().size() == 1;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** UI thread. {@code done} gets the account the share link points to, or "" when it points to none. */
    public static void resolveShort(String link, Consumer<String> done) {
        final String key = link.trim();
        final String known = SHORT.get(key);
        if (known != null) {
            done.accept(known);
            return;
        }
        List<Consumer<String>> waiting = SHORT_WAITING.get(key);
        if (waiting != null) {
            waiting.add(done);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(done);
        SHORT_WAITING.put(key, waiting);
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            String account = "";
            try {
                String next = key;
                for (int hop = 0; hop < 6 && account.isEmpty(); hop++) {
                    java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(next).openConnection();
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(8000);
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
                    final int code = connection.getResponseCode();
                    final String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (code < 300 || code >= 400 || location == null) break;
                    next = new java.net.URL(new java.net.URL(next), location).toString();
                    Uri uri = Uri.parse(next);
                    String host = uri.getHost();
                    List<String> segments = uri.getPathSegments();
                    if (host != null && (host.equals("soundcloud.com") || host.equals("www.soundcloud.com") || host.equals("m.soundcloud.com"))
                            && !segments.isEmpty() && segments.get(0).matches("[a-zA-Z0-9._-]{1,64}")) {
                        account = segments.get(0);
                    }
                }
            } catch (Throwable ignore) {
            }
            final String result = account;
            AndroidUtilities.runOnUIThread(() -> {
                SHORT.put(key, result);
                List<Consumer<String>> listeners = SHORT_WAITING.remove(key);
                if (listeners != null) for (Consumer<String> listener : listeners) listener.accept(result);
            });
        });
    }
    /** Services whose statistics come from the user's own signed-in account rather than a public name. */
    public static boolean isConnected(int service) { return service == 3 || service == 4; }
    public static String key(int service) { return KEYS[Math.max(0, Math.min(5, service))]; }
    public static String serviceName(int service) { return NAMES[Math.max(0, Math.min(5, service))]; }
    public static int modeCount(int service) { return MODES[Math.max(0, Math.min(5, service))].length; }
    public static String modeName(int service, int mode) {
        int[] modes = MODES[Math.max(0, Math.min(5, service))];
        return LocaleController.getString(modes[Math.max(0, Math.min(modes.length - 1, mode))]);
    }
    public static String account(CustomProfileExtraRows.Block block) {
        String name = block.url.trim();
        if (name.isEmpty()) name = block.accounts.optString(key(block.service));
        if (name.startsWith("https://") || name.startsWith("http://")) {
            Uri uri = Uri.parse(name);
            String host = uri.getHost();
            List<String> segments = uri.getPathSegments();
            if (host == null || segments.isEmpty()) return "";
            if (block.service == 5 && isShortSoundcloud(name)) {
                final String resolved = SHORT.get(name);
                return resolved == null ? "" : resolved;
            }
            if (block.service == 0 && (host.equals("last.fm") || host.equals("www.last.fm"))
                    && segments.size() == 2 && segments.get(0).equals("user")) return segments.get(1);
            if (block.service == 1 && host.equals("github.com") && segments.size() == 1) return segments.get(0);
            if (block.service == 5 && (host.equals("soundcloud.com") || host.equals("www.soundcloud.com") || host.equals("m.soundcloud.com"))
                    && segments.size() >= 1) return segments.get(0);
            if (block.service == 2 && host.equals("steamcommunity.com") && segments.size() == 2
                    && (segments.get(0).equals("id") || segments.get(0).equals("profiles"))) return segments.get(1);
            return "";
        }
        return name;
    }
    public static String profileUrl(CustomProfileExtraRows.Block block) {
        String name = account(block);
        if (!name.matches("[a-zA-Z0-9._-]{1,64}")) return "";
        return switch (block.service) {
            case 0 -> "https://www.last.fm/user/" + Uri.encode(name);
            case 1 -> "https://github.com/" + Uri.encode(name);
            case 2 -> "https://steamcommunity.com/" + (name.matches("[0-9]{17}") ? "profiles/" : "id/") + Uri.encode(name);
            case 3 -> "https://music.yandex.ru/";
            case 4 -> "https://open.spotify.com/user/" + Uri.encode(name);
            case 5 -> "https://soundcloud.com/" + Uri.encode(name);
            default -> "";
        };
    }
    public static void load(int account, CustomProfileExtraRows.Block block, Consumer<String> sink) {
        load(account, UserConfig.getInstance(account).getClientUserId(), block, sink);
    }
    public static void clearCache() { cacheGeneration++; CACHE.clear(); }
    public static void load(int account, long profileOwner, CustomProfileExtraRows.Block block, Consumer<String> sink) {
        String name = account(block);
        if (name.isEmpty() && block.service == 5 && isShortSoundcloud(block.url) && !SHORT.containsKey(block.url.trim())) {
            // A share link: find the account it leads to first, then load as usual.
            resolveShort(block.url, resolved -> load(account, profileOwner, block, sink));
            return;
        }
        if (name.isEmpty() && profileOwner == UserConfig.getInstance(account).getClientUserId()) {
            // Typically a look installed from the Workshop: the row is there, the account is not yet.
            sink.accept(LocaleController.getString(R.string.CustomProfileIntegrationNeedsBinding));
            return;
        }
        if (!name.matches("[a-zA-Z0-9._-]{1,64}") || !SovietGramApiClient.isReady(account)) {
            sink.accept(LocaleController.getString(R.string.CustomProfileIntegrationUnavailable));
            return;
        }
        StringBuilder modes = new StringBuilder();
        for (int i = 0; i < block.parts.length(); i++) {
            int mode = block.parts.optInt(i, -1);
            if (mode < 0 || mode >= modeCount(block.service)) continue;
            if (modes.length() > 0) modes.append(',');
            modes.append(mode);
        }
        if (modes.length() == 0) modes.append(block.mode);
        String path = "/v1/integrations?service=" + key(block.service) + "&account=" + Uri.encode(name) + "&modes=" + modes;
        if (isConnected(block.service)) {
            path = profileOwner == UserConfig.getInstance(account).getClientUserId()
                    ? "/v1/integrations/self?service=" + key(block.service) + "&modes=" + modes
                    : "/v1/profile-integrations/" + profileOwner + "/" + Uri.encode(block.id);
        }
        String cacheKey = cacheGeneration + ":" + UserConfig.getInstance(account).getClientUserId() + ":" + path
                + ":" + block.service + ":" + name + ":" + modes + ":" + LocaleController.getInstance().getCurrentLocaleInfo().shortName;
        Held held = CACHE.get(cacheKey);
        if (held != null && held.until > android.os.SystemClock.elapsedRealtime()) { sink.accept(held.value); return; }
        if (held != null) sink.accept(held.value);
        List<Consumer<String>> waiters = PENDING.get(cacheKey);
        if (waiters != null) { waiters.add(sink); return; }
        if (PENDING.size() >= 16) { sink.accept(LocaleController.getString(R.string.CustomProfileIntegrationUnavailable)); return; }
        waiters = new ArrayList<>(); waiters.add(sink); PENDING.put(cacheKey, waiters);
        int service = block.service;
        int requestedGeneration = cacheGeneration;
        SovietGramApiClient.get(account, path, (body, error) -> AndroidUtilities.runOnUIThread(() -> {
            String value = describe(service, body);
            if (CACHE.size() >= 256) CACHE.remove(CACHE.keySet().iterator().next());
            if (requestedGeneration == cacheGeneration) CACHE.put(cacheKey, new Held(value,
                    android.os.SystemClock.elapsedRealtime() + (error == null ? (isConnected(service) ? 30000 : 60000) : 15000)));
            List<Consumer<String>> listeners = PENDING.remove(cacheKey);
            if (listeners != null) for (Consumer<String> listener : listeners) listener.accept(value);
        }));
    }
    private static String describe(int service, JSONObject body) {
        JSONArray parts = body == null ? null : body.optJSONArray("parts");
        if (parts == null || body.optBoolean("unavailable")) return LocaleController.getString(R.string.CustomProfileIntegrationUnavailable);
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < parts.length() && i < 8; i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            if (value.length() > 0) value.append('\n');
            value.append(modeName(service, part.optInt("mode"))).append(": ");
            value.append(part.isNull("value") ? "—" : part.optString("value", "—"));
        }
        return value.toString();
    }
}
