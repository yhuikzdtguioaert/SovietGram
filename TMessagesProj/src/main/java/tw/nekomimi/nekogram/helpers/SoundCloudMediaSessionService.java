package tw.nekomimi.nekogram.helpers;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.List;

/** Notification access is only the Android authorization for active media sessions.
 * Never reads notifications, extras, messages, cookies or SoundCloud credentials. */
public final class SoundCloudMediaSessionService extends NotificationListenerService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager manager;
    private MediaController controller;
    private MediaController.Callback callback;
    private MediaSessionManager.OnActiveSessionsChangedListener sessions;
    private long generation = -1, sourceEpoch, listenerEpoch, sampledAt;
    private boolean listening, destroyed;

    @Override public void onListenerConnected() {
        // Android < N does not guarantee main-thread notification-listener callbacks.
        handler.post(() -> {
            if (destroyed) return;
            org.telegram.messenger.ApplicationLoader.postInitApplication();
            manager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            SoundCloudDeviceRpc.attach(this);
        });
    }
    @Override public void onListenerDisconnected() { handler.post(() -> SoundCloudDeviceRpc.detach(this)); }
    @Override public void onDestroy() {
        destroyed = true;
        SoundCloudDeviceRpc.detach(this);
        super.onDestroy();
    }
    // Deliberately empty: not even SoundCloud notification content is processed.
    @Override public void onNotificationPosted(StatusBarNotification notification) { }
    @Override public void onNotificationRemoved(StatusBarNotification notification) { }

    void startSource(long token) {
        if (listening && generation == token) return;
        stopSource();
        generation = token;
        final long epoch = ++listenerEpoch;
        if (manager == null) return;
        sessions = active -> {
            if (epoch == listenerEpoch && listening && SoundCloudDeviceRpc.isCurrent(token)) select(active, token);
        };
        try {
            listening = true;
            manager.addOnActiveSessionsChangedListener(sessions, new ComponentName(this, getClass()), handler);
            select(manager.getActiveSessions(new ComponentName(this, getClass())), token);
        } catch (SecurityException denied) { SoundCloudDeviceRpc.permissionLost(); }
    }
    void stopSource() {
        ++listenerEpoch;
        ++sourceEpoch;
        if (controller != null && callback != null) controller.unregisterCallback(callback);
        controller = null; callback = null;
        if (manager != null && sessions != null) manager.removeOnActiveSessionsChangedListener(sessions);
        sessions = null; listening = false; generation = -1;
    }
    private void select(List<MediaController> active, long token) {
        if (!SoundCloudDeviceRpc.isCurrent(token)) return;
        MediaController chosen = null;
        if (active != null) for (MediaController candidate : active) {
            // For every other package the only field inspected is its package name.
            if (!SoundCloudRpcPolicy.PACKAGE.equals(candidate.getPackageName())) continue;
            if (chosen == null) chosen = candidate;
            PlaybackState state = candidate.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) { chosen = candidate; break; }
        }
        if (chosen != null && controller != null && chosen.getSessionToken().equals(controller.getSessionToken())) {
            sample(SystemClock.elapsedRealtime()); return;
        }
        if (controller != null && callback != null) controller.unregisterCallback(callback);
        controller = chosen;
        final MediaController mine = chosen;
        final long epoch = ++sourceEpoch;
        callback = new MediaController.Callback() {
            private boolean current() { return epoch == sourceEpoch && controller == mine && SoundCloudDeviceRpc.isCurrent(token); }
            @Override public void onMetadataChanged(MediaMetadata metadata) {
                if (current()) {
                    sampledAt = SystemClock.elapsedRealtime();
                    SoundCloudDeviceRpc.offer(token, fromSession(metadata, mine.getPlaybackState(), sampledAt));
                }
            }
            @Override public void onPlaybackStateChanged(PlaybackState state) {
                if (current()) {
                    sampledAt = SystemClock.elapsedRealtime();
                    SoundCloudDeviceRpc.offer(token, fromSession(mine.getMetadata(), state, sampledAt));
                }
            }
            @Override public void onSessionDestroyed() {
                if (!current()) return;
                if (controller != null) controller.unregisterCallback(this);
                controller = null;
                SoundCloudDeviceRpc.offer(token, null);
            }
        };
        if (chosen != null) chosen.registerCallback(callback, handler);
        sample(SystemClock.elapsedRealtime());
    }
    void heartbeat(long now) {
        if (listening && now - sampledAt >= SoundCloudRpcPolicy.HEARTBEAT_MS) {
            // Re-enumerate: destroyed sessions or missed callbacks cannot retain an old track.
            try { select(manager.getActiveSessions(new ComponentName(this, getClass())), generation); }
            catch (SecurityException denied) { SoundCloudDeviceRpc.permissionLost(); }
        }
    }
    private void sample(long now) {
        if (!SoundCloudDeviceRpc.isCurrent(generation)) return;
        sampledAt = now;
        SoundCloudRpcPolicy.Snapshot snapshot = controller == null ? null
                : fromSession(controller.getMetadata(), controller.getPlaybackState(), now);
        SoundCloudDeviceRpc.offer(generation, snapshot);
    }
    static SoundCloudRpcPolicy.Snapshot fromSession(MediaMetadata metadata, PlaybackState state, long now) {
        if (metadata == null || state == null) return null;
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null || title.trim().isEmpty()) title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title == null || title.trim().isEmpty()) return null;
        String playback;
        switch (state.getState()) {
            case PlaybackState.STATE_PLAYING: playback = "playing"; break;
            case PlaybackState.STATE_PAUSED: playback = "paused"; break;
            case PlaybackState.STATE_STOPPED:
            case PlaybackState.STATE_NONE: playback = "stopped"; break;
            default: return null; // buffering/error/unknown is not evidence of playing.
        }
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position = state.getPosition();
        if (position < 0) {
            // Fixed RPC schema has no nullable timing. Zero duration suppresses the progress bar.
            duration = 0; position = 0;
        } else if (state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0
                && state.getPlaybackSpeed() > 0 && Float.isFinite(state.getPlaybackSpeed())) {
            position += (long) (Math.max(0, now - state.getLastPositionUpdateTime()) * (double) state.getPlaybackSpeed());
        }
        String cover = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
        if (cover == null || cover.isEmpty()) cover = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        return new SoundCloudRpcPolicy.Snapshot(title, metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM), duration, position, playback,
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI), cover);
    }
}
