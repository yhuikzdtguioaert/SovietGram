package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Plays a looping, muted video on top of the static chat wallpaper. The static wallpaper is left
 * in place on purpose: the chat blur is produced by drawing the view tree into a bitmap, and a
 * {@link TextureView} never shows up in that capture, so without it the blurred strips behind the
 * action bar and the input field would come out empty.
 */
public final class LiveWallpaperHelper {

    private static final String PREFS = "livewallpaper";
    private static final String KEY_PATH = "video_path";
    private static final String FILE_NAME = "live_wallpaper.mp4";

    private LiveWallpaperHelper() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * @return the video to play, or {@code null} when no live wallpaper is set. A path that no
     * longer resolves to a file is forgotten instead of being reported.
     */
    @Nullable
    public static String getVideoPath() {
        String path = prefs().getString(KEY_PATH, null);
        if (path == null) {
            return null;
        }
        File file = new File(path);
        if (!file.exists() || file.length() == 0) {
            prefs().edit().remove(KEY_PATH).apply();
            return null;
        }
        return path;
    }

    public static boolean isEnabled() {
        return getVideoPath() != null;
    }

    public static void clear() {
        String path = prefs().getString(KEY_PATH, null);
        prefs().edit().remove(KEY_PATH).apply();
        if (path != null) {
            try {
                new File(path).delete();
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * Copies the picked video into our own storage — the picker hands out a {@code content://} URI
     * whose permission does not survive a restart.
     *
     * @return the stored path, or {@code null} if the copy failed.
     */
    @Nullable
    public static String importVideo(Uri uri) {
        if (uri == null) {
            return null;
        }
        File target = new File(ApplicationLoader.getFilesDirFixed(), FILE_NAME);
        File temp = new File(target.getParentFile(), FILE_NAME + ".tmp");
        try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) {
                return null;
            }
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            FileLog.e(e);
            temp.delete();
            return null;
        }
        if (temp.length() == 0) {
            temp.delete();
            return null;
        }
        target.delete();
        if (!temp.renameTo(target)) {
            temp.delete();
            return null;
        }
        prefs().edit().putString(KEY_PATH, target.getAbsolutePath()).apply();
        return target.getAbsolutePath();
    }

    @Nullable
    private static LiveWallpaperView find(SizeNotifierFrameLayout contentView) {
        for (int a = 0; a < contentView.getChildCount(); a++) {
            View child = contentView.getChildAt(a);
            if (child instanceof LiveWallpaperView) {
                return (LiveWallpaperView) child;
            }
        }
        return null;
    }

    /**
     * Adds, removes or re-stacks the video layer to match the current setting. Safe to call
     * repeatedly — every chat lifecycle callback funnels through here.
     */
    public static void update(SizeNotifierFrameLayout contentView) {
        if (contentView == null) {
            return;
        }
        String path = getVideoPath();
        LiveWallpaperView view = find(contentView);
        if (path == null) {
            if (view != null) {
                view.release();
                contentView.removeView(view);
            }
            return;
        }
        int index = contentView.backgroundView != null
                ? contentView.indexOfChild(contentView.backgroundView) + 1 : 0;
        if (view == null) {
            view = new LiveWallpaperView(contentView.getContext());
            contentView.addView(view, index,
                    LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        } else if (contentView.indexOfChild(view) != index) {
            contentView.removeView(view);
            contentView.addView(view, index,
                    LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        view.setVideoPath(path);
    }

    public static void onResume(SizeNotifierFrameLayout contentView) {
        update(contentView);
        if (contentView != null) {
            LiveWallpaperView view = find(contentView);
            if (view != null) {
                view.onResume();
            }
        }
    }

    public static void onPause(SizeNotifierFrameLayout contentView) {
        if (contentView != null) {
            LiveWallpaperView view = find(contentView);
            if (view != null) {
                view.onPause();
            }
        }
    }

    public static void onDestroy(SizeNotifierFrameLayout contentView) {
        if (contentView != null) {
            LiveWallpaperView view = find(contentView);
            if (view != null) {
                view.release();
                contentView.removeView(view);
            }
        }
    }

    private static class LiveWallpaperView extends TextureView implements TextureView.SurfaceTextureListener {

        private MediaPlayer player;
        private Surface surface;
        private String videoPath;
        private int videoWidth;
        private int videoHeight;
        private boolean prepared;
        private boolean paused;

        LiveWallpaperView(Context context) {
            super(context);
            setOpaque(false);
            setSurfaceTextureListener(this);
        }

        void setVideoPath(String path) {
            if (path.equals(videoPath)) {
                return;
            }
            videoPath = path;
            releasePlayer();
            start();
        }

        void onResume() {
            paused = false;
            if (player != null && prepared) {
                try {
                    player.start();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            } else {
                start();
            }
        }

        void onPause() {
            paused = true;
            if (player != null && prepared) {
                try {
                    player.pause();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }

        void release() {
            setSurfaceTextureListener(null);
            releasePlayer();
            if (surface != null) {
                surface.release();
                surface = null;
            }
        }

        private void releasePlayer() {
            prepared = false;
            if (player != null) {
                try {
                    player.setOnPreparedListener(null);
                    player.setOnErrorListener(null);
                    player.setOnVideoSizeChangedListener(null);
                    player.reset();
                    player.release();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                player = null;
            }
        }

        private void start() {
            if (surface == null || videoPath == null || player != null) {
                return;
            }
            try {
                player = new MediaPlayer();
                player.setSurface(surface);
                player.setDataSource(videoPath);
                player.setLooping(true);
                player.setVolume(0f, 0f);
                player.setOnVideoSizeChangedListener((mp, width, height) -> {
                    videoWidth = width;
                    videoHeight = height;
                    applyTransform();
                });
                player.setOnErrorListener((mp, what, extra) -> {
                    releasePlayer();
                    return true;
                });
                player.setOnPreparedListener(mp -> {
                    prepared = true;
                    videoWidth = mp.getVideoWidth();
                    videoHeight = mp.getVideoHeight();
                    applyTransform();
                    if (!paused) {
                        try {
                            mp.start();
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                });
                player.prepareAsync();
            } catch (Exception e) {
                FileLog.e(e);
                releasePlayer();
            }
        }

        /**
         * A {@link TextureView} stretches its content to fill the view, so the transform has to
         * undo that stretch and then scale back up by the cover factor.
         */
        private void applyTransform() {
            int width = getWidth();
            int height = getHeight();
            if (width == 0 || height == 0 || videoWidth == 0 || videoHeight == 0) {
                return;
            }
            float scale = Math.max(width / (float) videoWidth, height / (float) videoHeight);
            Matrix matrix = new Matrix();
            matrix.setScale(videoWidth * scale / width, videoHeight * scale / height,
                    width / 2f, height / 2f);
            setTransform(matrix);
            invalidate();
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (changed) {
                applyTransform();
            }
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            surface = new Surface(surfaceTexture);
            start();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            applyTransform();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            releasePlayer();
            if (surface != null) {
                surface.release();
                surface = null;
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }
    }
}
