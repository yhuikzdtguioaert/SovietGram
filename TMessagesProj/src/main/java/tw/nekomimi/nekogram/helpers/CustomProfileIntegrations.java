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
    private static final String[] KEYS = {"lastfm", "github", "steam", "yamusic", "spotify", "soundcloud", "soundcloud-me"};
    private static final String[] NAMES = {"Last.fm", "GitHub", "Steam", "Yandex Music", "Spotify", "SoundCloud", "SoundCloud"};
    private static final int[][] MODES = {
        {R.string.CustomProfileIntegrationNow, R.string.CustomProfileIntegrationScrobbles, R.string.CustomProfileIntegrationArtist, R.string.CustomProfileIntegrationAlbum},
        {R.string.CustomProfileIntegrationRepos, R.string.CustomProfileIntegrationStars, R.string.CustomProfileIntegrationFollowers, R.string.CustomProfileIntegrationFollowing, R.string.CustomProfileIntegrationSince, R.string.CustomProfileIntegrationContributions},
        {R.string.CustomProfileIntegrationPlaying, R.string.CustomProfileIntegrationSince, R.string.CustomProfileIntegrationHoursRecent, R.string.CustomProfileExtraRowTitle, R.string.CustomProfileIntegrationLevel, R.string.CustomProfileIntegrationGames, R.string.CustomProfileIntegrationHours, R.string.CustomProfileIntegrationLastGame},
        {R.string.CustomProfileIntegrationNow, R.string.CustomProfileIntegrationLastLike, R.string.CustomProfileIntegrationLikedTracks, R.string.CustomProfileIntegrationPlaylists},
        {R.string.CustomProfileIntegrationNow, R.string.CustomProfileIntegrationLastTrack, R.string.CustomProfileIntegrationArtist, R.string.CustomProfileIntegrationFollowers},
        {R.string.CustomProfileIntegrationLatestTrack, R.string.CustomProfileIntegrationFollowers, R.string.CustomProfileIntegrationTracks, R.string.CustomProfileIntegrationLikes, R.string.CustomProfileIntegrationLastLike},
        // SoundCloud with the user's own session: what was played last and liked last come from their account.
        {R.string.CustomProfileIntegrationNow, R.string.CustomProfileIntegrationLastLike, R.string.CustomProfileIntegrationFollowers, R.string.CustomProfileIntegrationLikes}
    };
    /** A track, a game or anything else that is "on right now", with what a card needs to draw it. */
    public static final class Track {
        public String title = "", artist = "", album = "", cover = "", url = "";
        public long durationMs, progressMs;
        public boolean playing, stale;
        /** A track the account liked rather than made or played. */
        public boolean liked;
        /** {@link android.os.SystemClock#elapsedRealtime()} when {@link #progressMs} was true. */
        public long receivedAt;
        /**
         * How long after {@link #receivedAt} the bar may keep moving by itself. The next answer is due by
         * then; if it never comes (no network, a request stuck) the bar stops where it last knew the
         * track to be instead of running on for minutes after the music was paused.
         */
        public long trustMs = 30_000L;

        /** Where the track is now: the reported position plus the time since, while it plays. */
        public long positionNow() {
            long position = progressMs;
            if (playing) {
                position += Math.max(0, Math.min(trustMs, android.os.SystemClock.elapsedRealtime() - receivedAt));
            }
            return durationMs > 0 ? Math.min(position, durationMs) : position;
        }
    }

    /** GitHub's contribution calendar: a level 0-4 for each day from {@link #from}, oldest first. */
    public static final class Graph {
        public String from = "", levels = "";
        public int total;
    }

    /** Everything one fetch brought back for a block. */
    public static final class Rich {
        public final String text;
        public final Track track;
        public final Graph graph;
        public final int service;
        /** Nothing to show: every line the block asks for came back empty (nothing is playing). */
        public final boolean empty;

        Rich(String text, Track track, Graph graph, int service) {
            this(text, track, graph, service, false);
        }

        Rich(String text, Track track, Graph graph, int service, boolean empty) {
            this.text = text;
            this.track = track;
            this.graph = graph;
            this.service = service;
            this.empty = empty;
        }

        static Rich plain(String text, int service) {
            return new Rich(text, null, null, service);
        }

        /** Whether a picture card can be drawn for this data. */
        public boolean hasCard() {
            return track != null || graph != null;
        }
    }

    private record Held(Rich rich, long until) { }
    // UI-thread only. Bounded, scoped by both Telegram identity and provider configuration.
    private static final LinkedHashMap<String, Held> CACHE = new LinkedHashMap<>();
    private static final Map<String, List<Consumer<Rich>>> PENDING = new HashMap<>();
    private static final Map<String, Long> PENDING_SINCE = new HashMap<>();
    /** A request older than this is presumed lost and asked again, rather than waited for forever. */
    private static final long PENDING_LIMIT_MS = 25_000L;
    /** The newest data per block, so a tap on a card can open the track it shows. */
    private static final Map<String, Rich> LATEST = new HashMap<>();

    /** The address a card's tap opens: the track itself when there is one, the profile otherwise. */
    public static String openUrl(CustomProfileExtraRows.Block block) {
        Rich rich = LATEST.get(block.id);
        if (rich != null && rich.track != null && rich.track.url.startsWith("https://")) return rich.track.url;
        return profileUrl(block);
    }

    /**
     * How often a block asks for fresh data, in milliseconds: what its author chose, or the pace that
     * suits the service — what is playing changes within seconds, a profile's statistics within hours.
     */
    public static long refreshMs(CustomProfileExtraRows.Block block) {
        if (block.intRefresh > 0) return Math.max(5, block.intRefresh) * 1000L;
        return switch (block.service) {
            case 3, 4, 6 -> 10_000L;
            case 0 -> 15_000L;
            case 2 -> 30_000L;
            case 5 -> 120_000L;
            default -> 300_000L;
        };
    }
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
    public static boolean isConnected(int service) { return service == 3 || service == 4 || service == 6; }
    public static String key(int service) { return KEYS[Math.max(0, Math.min(6, service))]; }
    public static String serviceName(int service) { return NAMES[Math.max(0, Math.min(6, service))]; }
    public static int modeCount(int service) { return MODES[Math.max(0, Math.min(6, service))].length; }
    public static String modeName(int service, int mode) {
        int[] modes = MODES[Math.max(0, Math.min(6, service))];
        return LocaleController.getString(modes[Math.max(0, Math.min(modes.length - 1, mode))]);
    }
    /** Public profile identity only: never accept provider sign-in/callback URLs. */
    public static String publicSoundcloudAccount(String value) {
        String name = value == null ? "" : value.trim();
        if (name.contains("://")) {
            try {
                final java.net.URI uri = new java.net.URI(name);
                final String host = uri.getHost();
                if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                        || !(host.equalsIgnoreCase("soundcloud.com") || host.equalsIgnoreCase("www.soundcloud.com")
                        || host.equalsIgnoreCase("m.soundcloud.com"))
                        || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null) return "";
                final String query = uri.getRawQuery();
                if (query != null) {
                    for (String field : query.split("&")) {
                        final String key = field.split("=", 2)[0];
                        if (!(key.equals("si") || key.equals("utm_source") || key.equals("utm_medium") || key.equals("utm_campaign"))) return "";
                    }
                }
                final String path = uri.getPath();
                if (path == null || !path.matches("/[A-Za-z0-9._-]{1,64}/?")) return "";
                name = path.substring(1).replaceAll("/$", "");
            } catch (java.net.URISyntaxException ignored) { return ""; }
        }
        if (!name.matches("[A-Za-z0-9._-]{1,64}")) return "";
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "signin", "sign-in", "login", "logout", "discover", "you", "search", "stream", "upload", "settings", "oauth", "authorize", "connect" -> "";
            default -> name;
        };
    }

    public static String account(CustomProfileExtraRows.Block block) {
        String name = block.url.trim();
        if (name.isEmpty()) name = block.accounts.optString(key(block.service));
        if (block.service == 5 && !isShortSoundcloud(name)) return publicSoundcloudAccount(name);
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
            case 5, 6 -> "https://soundcloud.com/" + Uri.encode(name);
            default -> "";
        };
    }
    public static void load(int account, CustomProfileExtraRows.Block block, Consumer<String> sink) {
        loadRich(account, UserConfig.getInstance(account).getClientUserId(), block, rich -> sink.accept(rich.text));
    }
    public static void clearCache() {
        cacheGeneration++;
        CACHE.clear();
        PENDING.clear(); PENDING_SINCE.clear(); LATEST.clear();
    }
    public static void load(int account, long profileOwner, CustomProfileExtraRows.Block block, Consumer<String> sink) {
        loadRich(account, profileOwner, block, rich -> sink.accept(rich.text));
    }
    public static void loadRich(int account, long profileOwner, CustomProfileExtraRows.Block block, Consumer<Rich> sink) {
        String name = account(block);
        final int service = block.service;
        if (name.isEmpty() && block.service == 5 && isShortSoundcloud(block.url) && !SHORT.containsKey(block.url.trim())) {
            // A share link: find the account it leads to first, then load as usual.
            resolveShort(block.url, resolved -> loadRich(account, profileOwner, block, sink));
            return;
        }
        if (name.isEmpty() && profileOwner == UserConfig.getInstance(account).getClientUserId()) {
            // Typically a look installed from the Workshop: the row is there, the account is not yet.
            sink.accept(Rich.plain(LocaleController.getString(R.string.CustomProfileIntegrationNeedsBinding), service));
            return;
        }
        if (!name.matches("[a-zA-Z0-9._-]{1,64}") || !SovietGramApiClient.isReady(account)) {
            sink.accept(Rich.plain(LocaleController.getString(R.string.CustomProfileIntegrationUnavailable), service));
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
        final long keep = Math.max(3000L, refreshMs(block) - 1500L);
        Held held = CACHE.get(cacheKey);
        if (held != null && held.until > android.os.SystemClock.elapsedRealtime()) { sink.accept(held.rich); return; }
        if (held != null) sink.accept(held.rich);
        List<Consumer<Rich>> waiters = PENDING.get(cacheKey);
        final long asked = android.os.SystemClock.elapsedRealtime();
        final Long since = PENDING_SINCE.get(cacheKey);
        if (waiters != null && since != null && asked - since < PENDING_LIMIT_MS) { waiters.add(sink); return; }
        if (waiters == null && PENDING.size() >= 16) { sink.accept(Rich.plain(LocaleController.getString(R.string.CustomProfileIntegrationUnavailable), service)); return; }
        final List<Consumer<Rich>> mine = new ArrayList<>();
        if (waiters != null) mine.addAll(waiters);
        mine.add(sink);
        PENDING.put(cacheKey, mine);
        PENDING_SINCE.put(cacheKey, asked);
        int requestedGeneration = cacheGeneration;
        final String blockId = block.id;
        final long trust = refreshMs(block) + 8000L;
        SovietGramApiClient.get(account, path, (body, error) -> AndroidUtilities.runOnUIThread(() -> {
            // A reconnect/disconnect, or a retry after PENDING_LIMIT_MS, superseded this request.
            // It must not repaint cards, change their tap target, or consume the new waiters.
            if (requestedGeneration != cacheGeneration || PENDING.get(cacheKey) != mine) return;
            Rich value = describe(service, body, trust);
            if (CACHE.size() >= 256) CACHE.remove(CACHE.keySet().iterator().next());
            if (requestedGeneration == cacheGeneration) CACHE.put(cacheKey, new Held(value,
                    android.os.SystemClock.elapsedRealtime() + (error == null ? keep : 15000)));
            if (LATEST.size() >= 128) LATEST.remove(LATEST.keySet().iterator().next());
            LATEST.put(blockId, value);
            if (PENDING.get(cacheKey) == mine) { PENDING.remove(cacheKey); PENDING_SINCE.remove(cacheKey); }
            for (Consumer<Rich> listener : mine) listener.accept(value);
        }));
    }
    private static Rich describe(int service, JSONObject body, long trust) {
        JSONArray parts = body == null ? null : body.optJSONArray("parts");
        if (parts == null || body.optBoolean("unavailable")) return Rich.plain(LocaleController.getString(R.string.CustomProfileIntegrationUnavailable), service);
        StringBuilder value = new StringBuilder();
        Track track = null;
        Graph graph = null;
        boolean anything = false;
        for (int i = 0; i < parts.length() && i < 8; i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            if (value.length() > 0) value.append('\n');
            value.append(modeName(service, part.optInt("mode"))).append(": ");
            value.append(part.isNull("value") ? "—" : part.optString("value", "—"));
            anything |= !part.isNull("value") || part.optJSONObject("track") != null || part.optJSONObject("graph") != null;
            JSONObject t = part.optJSONObject("track");
            if (track == null && t != null && !t.optString("title").isEmpty()) {
                track = new Track();
                track.title = t.optString("title");
                track.artist = t.optString("artist");
                track.album = t.optString("album");
                track.cover = t.optString("cover");
                track.url = t.optString("url");
                track.durationMs = Math.max(0, t.optLong("durationMs"));
                track.progressMs = Math.max(0, t.optLong("progressMs"));
                track.playing = t.optBoolean("playing");
                track.stale = t.optBoolean("stale");
                track.liked = t.optBoolean("liked");
                track.receivedAt = android.os.SystemClock.elapsedRealtime();
                track.trustMs = trust;
            }
            JSONObject g = part.optJSONObject("graph");
            if (graph == null && g != null && g.optString("levels").length() >= 28) {
                graph = new Graph();
                graph.from = g.optString("from");
                graph.levels = g.optString("levels");
                graph.total = Math.max(0, g.optInt("total"));
            }
        }
        return new Rich(value.toString(), track, graph, service, !anything && parts.length() > 0);
    }
}
