package tw.nekomimi.nekogram.helpers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** UI-thread policy. One in-flight write; a latest snapshot, never a playback-history queue. */
public final class SoundCloudRpcPolicy {
    public static final String PACKAGE = "com.soundcloud.android";
    public static final long COALESCE_MS = 2000L, HEARTBEAT_MS = 25000L, TTL_MS = 90000L;
    private boolean enabled, published;
    private int account = -1;
    private long owner, generation, lastPost = -HEARTBEAT_MS, retryAt;
    private Snapshot latest, sent;
    private Request inFlight;
    // Only owners for which a POST was actually dispatched can enter this queue.
    private final LinkedHashMap<Long, Integer> clears = new LinkedHashMap<>();
    private final Map<Long, Long> clearSince = new LinkedHashMap<>();

    public static final class Snapshot {
        public final String title, artist, album, state, trackUrl, coverUrl;
        public final long durationMs, positionMs;
        public Snapshot(String title, String artist, String album, long durationMs, long positionMs, String state) {
            this(title, artist, album, durationMs, positionMs, state, "", "");
        }
        public Snapshot(String title, String artist, String album, long durationMs, long positionMs, String state, String trackUrl, String coverUrl) {
            this.title = bounded(title); this.artist = bounded(artist); this.album = bounded(album);
            this.trackUrl = httpsUrl(trackUrl, false); this.coverUrl = httpsUrl(coverUrl, true);
            boolean timingValid = durationMs <= 86_400_000L && positionMs <= 86_400_000L && positionMs >= 0;
            this.durationMs = timingValid ? Math.max(0, durationMs) : 0;
            this.positionMs = timingValid ? (this.durationMs > 0 ? Math.min(this.durationMs, positionMs) : positionMs) : 0;
            this.state = state;
        }
        private static String bounded(String value) {
            if (value == null) return "";
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < value.length() && text.length() < 256; i++) {
                char c = value.charAt(i);
                if (c >= 0x20 && c != 0x7f) text.append(c);
            }
            return text.toString().trim();
        }
        public static String httpsUrl(String value, boolean cover) {
            if (value == null || value.length() > 2048 || !value.startsWith("https://")) return "";
            try {
                java.net.URI uri = new java.net.URI(value);
                String host = uri.getHost();
                if (host == null || uri.getUserInfo() != null || uri.getPort() != -1) return "";
                host = host.toLowerCase(java.util.Locale.ROOT);
                boolean allowed = cover ? host.matches("(?:[a-z0-9-]+\\.)*sndcdn\\.com")
                        : host.equals("soundcloud.com") || host.equals("www.soundcloud.com") || host.equals("m.soundcloud.com") || host.equals("on.soundcloud.com");
                return allowed ? value : "";
            } catch (Exception ignored) { return ""; }
        }
        boolean valid() { return !title.isEmpty() && ("playing".equals(state) || "paused".equals(state) || "stopped".equals(state)); }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Snapshot)) return false;
            Snapshot s = (Snapshot) o;
            return title.equals(s.title) && artist.equals(s.artist) && album.equals(s.album) && state.equals(s.state)
                    && durationMs == s.durationMs && positionMs == s.positionMs && trackUrl.equals(s.trackUrl) && coverUrl.equals(s.coverUrl);
        }
        @Override public int hashCode() { return Objects.hash(title, artist, album, state, durationMs, positionMs, trackUrl, coverUrl); }
    }
    public static final class Request {
        public final int account;
        public final long owner, generation;
        public final boolean clear;
        public final Snapshot snapshot;
        Request(int account, long owner, long generation, Snapshot snapshot) {
            this.account = account; this.owner = owner; this.generation = generation;
            this.snapshot = snapshot; clear = snapshot == null;
        }
    }
    public boolean configure(int account, long owner, boolean optedIn, boolean permission) {
        boolean allow = account >= 0 && owner > 0 && optedIn && permission;
        if (this.account != account || this.owner != owner || enabled != allow) {
            if (published && this.owner > 0) clears.put(this.owner, this.account);
            generation++;
            latest = sent = null; published = false; lastPost = -HEARTBEAT_MS;
        }
        this.account = account; this.owner = owner; enabled = allow;
        return enabled;
    }
    public long generation() { return generation; }
    public boolean hasWork() { return enabled || inFlight != null || !clears.isEmpty(); }
    public boolean needsOwner(long owner) {
        return (enabled && this.owner == owner) || clears.containsKey(owner) || (inFlight != null && inFlight.owner == owner);
    }
    public boolean accepts(String packageName) { return enabled && PACKAGE.equals(packageName); }
    public void offer(long generation, String packageName, Snapshot snapshot) {
        if (this.generation != generation || !accepts(packageName)) return;
        latest = snapshot != null && snapshot.valid() ? snapshot : null;
        if (latest == null && published) {
            clears.put(owner, account); published = false; sent = null;
        }
    }
    public Request next(long now) {
        if (inFlight != null || now < retryAt) return null;
        clears.entrySet().removeIf(entry -> clearSince.containsKey(entry.getKey()) && now - clearSince.get(entry.getKey()) >= TTL_MS);
        clearSince.keySet().retainAll(clears.keySet());
        if (!clears.isEmpty()) {
            Map.Entry<Long, Integer> entry = clears.entrySet().iterator().next();
            if (!clearSince.containsKey(entry.getKey())) clearSince.put(entry.getKey(), now);
            inFlight = new Request(entry.getValue(), entry.getKey(), generation, null);
            return inFlight;
        }
        if (!enabled || latest == null || now - lastPost < COALESCE_MS) return null;
        if (latest.equals(sent) && now - lastPost < HEARTBEAT_MS) return null;
        inFlight = new Request(account, owner, generation, latest);
        published = true; sent = latest; lastPost = now;
        return inFlight;
    }
    public void complete(Request request, boolean ok, long now) {
        if (request == null || inFlight != request) return;
        inFlight = null;
        retryAt = ok ? 0 : now + COALESCE_MS;
        if (request.clear && ok) { clears.remove(request.owner); clearSince.remove(request.owner); }
        if (!request.clear && !ok && request.generation == generation) sent = null;
    }
}
