package tw.nekomimi.nekogram.helpers.lyrics;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.helpers.LyricsHelper;

/**
 * Full-screen karaoke view for the track in the audio player: the words scroll past with the music,
 * the line being sung is lit up, and tapping a line twice jumps playback to it.
 */
public class LyricsViewer extends Dialog implements NotificationCenter.NotificationCenterDelegate {

    private static final float SYNC_OFFSET_SEC = 0.8f;
    private static final int ACTIVE_TEXT_SIZE = 35;
    private static final int INACTIVE_TEXT_SIZE = 27;
    private static final float INACTIVE_SCALE = INACTIVE_TEXT_SIZE / (float) ACTIVE_TEXT_SIZE;
    private static final int INACTIVE_ALPHA = 130;
    private static final float ACTIVE_SHADOW_RADIUS = 3.0f;
    private static final int ANIMATION_MS = 170;
    private static final int TRANSLATION_Y_DP = 14;
    private static final int DIM_ALPHA = 130;
    private static final long DOUBLE_TAP_MS = 340;
    private static final int SWIPE_DISTANCE_DP = 60;
    private static final int SWIPE_DRIFT_DP = 80;

    private final int currentAccount;
    private MessageObject messageObject;

    private FrameLayout root;
    private ImageView coverView;
    private AmbientBlobsView ambientView;
    private ScrollView scrollView;
    private LinearLayout linesLayout;
    private FrameLayout stateContainer;
    private TextView headerTitle;
    private TextView headerSubtitle;
    private TextView sourceView;
    private LyricsMiniPlayer miniPlayer;

    private final List<TextView> lineViews = new ArrayList<>();
    private List<LyricLine> lines = new ArrayList<>();
    private boolean synced;
    private int activeIndex = -1;
    private boolean firstCentring = true;
    private ObjectAnimator scrollAnimator;

    private float touchStartX;
    private float touchStartY;
    private long lastTapTime;
    private int lastTapIndex = -1;

    public LyricsViewer(@NonNull Context context, int currentAccount, MessageObject messageObject) {
        super(context, R.style.TransparentDialog2);
        this.currentAccount = currentAccount;
        this.messageObject = messageObject;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getContext();

        root = new FrameLayout(context) {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    touchStartX = event.getX();
                    touchStartY = event.getY();
                }
                return false;
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    checkSwipeToDismiss(event);
                }
                return true;
            }
        };
        root.setBackgroundColor(Color.BLACK);

        coverView = new ImageView(context);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverView.setAlpha(0f);
        root.addView(coverView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ambientView = new AmbientBlobsView(context);
        root.addView(ambientView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        View dim = new View(context);
        dim.setBackgroundColor(Color.argb(DIM_ALPHA, 0, 0, 0));
        root.addView(dim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        buildScroll(context);
        buildStateContainer(context);
        buildMiniPlayer(context);
        buildHeader(context);

        Window window = getWindow();
        if (window != null) {
            window.setWindowAnimations(R.style.DialogNoAnimation);
            window.setContentView(root);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.dimAmount = 0;
            params.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            window.setAttributes(params);
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        }

        showLoading();
        loadLyrics();
        loadCover();
    }

    private void buildScroll(Context context) {
        scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(dp(18), dp(118), dp(18), dp(280));

        linesLayout = new LinearLayout(context);
        linesLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(linesLayout, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    private void buildStateContainer(Context context) {
        stateContainer = new FrameLayout(context);
        root.addView(stateContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    private void buildHeader(Context context) {
        FrameLayout header = new FrameLayout(context);
        header.setBackgroundColor(Color.argb(245, 10, 10, 10));
        header.setPadding(dp(4), 0, dp(4), 0);

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);

        headerTitle = new TextView(context);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        headerTitle.setTextColor(Color.WHITE);
        headerTitle.setSingleLine(true);
        headerTitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(headerTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        headerSubtitle = new TextView(context);
        headerSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        headerSubtitle.setTextColor(Color.argb(180, 255, 255, 255));
        headerSubtitle.setSingleLine(true);
        headerSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(headerSubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        header.addView(titles, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 8, 0, 48, 0));

        TextView close = new TextView(context);
        close.setText("×");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        close.setTextColor(Color.WHITE);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> dismiss());
        header.addView(close, LayoutHelper.createFrame(44, LayoutHelper.MATCH_PARENT, Gravity.RIGHT | Gravity.TOP));

        root.addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP | Gravity.LEFT,
                0, AndroidUtilities.statusBarHeight / AndroidUtilities.density, 0, 0));
        updateHeader();
    }

    private void buildMiniPlayer(Context context) {
        miniPlayer = new LyricsMiniPlayer(context);
        root.addView(miniPlayer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 52,
                Gravity.BOTTOM | Gravity.LEFT, 12, 0, 12, 16));
        miniPlayer.bind(messageObject);
    }

    private void updateHeader() {
        if (messageObject == null) {
            return;
        }
        headerTitle.setText(messageObject.getMusicTitle());
        headerSubtitle.setText(messageObject.getMusicAuthor());
    }
    private void showLoading() {
        stateContainer.removeAllViews();
        stateContainer.setVisibility(View.VISIBLE);
        scrollView.setVisibility(View.GONE);

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));

        ProgressBar progress = new ProgressBar(getContext());
        progress.setIndeterminate(true);
        box.addView(progress, LayoutHelper.createLinear(42, 42, Gravity.CENTER_HORIZONTAL));

        TextView status = new TextView(getContext());
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setTextColor(Color.argb(180, 255, 255, 255));
        status.setGravity(Gravity.CENTER);
        status.setText(LocaleController.getString(R.string.LyricsSearching));
        box.addView(status, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 0, 18, 0, 0));

        stateContainer.addView(box, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
    }

    private void showError(String title, String message) {
        stateContainer.removeAllViews();
        stateContainer.setVisibility(View.VISIBLE);
        scrollView.setVisibility(View.GONE);

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView titleView = new TextView(getContext());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Color.rgb(255, 120, 120));
        titleView.setGravity(Gravity.CENTER);
        titleView.setText(title);
        box.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL));

        TextView messageView = new TextView(getContext());
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        messageView.setTextColor(Color.argb(180, 255, 255, 255));
        messageView.setGravity(Gravity.CENTER);
        messageView.setText(message);
        box.addView(messageView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));

        TextView retry = new TextView(getContext());
        retry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        retry.setTypeface(AndroidUtilities.bold());
        retry.setTextColor(Color.WHITE);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(32, 32, 32));
        background.setCornerRadius(dp(18));
        retry.setBackground(background);
        retry.setText(LocaleController.getString(R.string.Retry));
        retry.setOnClickListener(v -> {
            showLoading();
            loadLyrics();
        });
        box.addView(retry, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 0, 22, 0, 0));

        stateContainer.addView(box, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
    }

    private void loadLyrics() {
        if (messageObject == null) {
            return;
        }
        final MessageObject requested = messageObject;
        LyricsHelper.load(requested, result -> {
            if (requested != messageObject) {
                return;
            }
            if (result == null || result.isEmpty()) {
                if (result != null && result.instrumental) {
                    showError(LocaleController.getString(R.string.LyricsInstrumental),
                            LocaleController.getString(R.string.LyricsInstrumentalInfo));
                } else {
                    showError(LocaleController.getString(R.string.LyricsNotFound),
                            LocaleController.getString(R.string.LyricsNotFoundInfo));
                }
                return;
            }
            lines = result.lines;
            synced = result.synced;
            buildLines(result.source);
        });
    }
    private void buildLines(@Nullable String source) {
        stateContainer.removeAllViews();
        stateContainer.setVisibility(View.GONE);
        scrollView.setVisibility(View.VISIBLE);
        linesLayout.removeAllViews();
        lineViews.clear();

        for (int a = 0; a < lines.size(); a++) {
            LyricLine line = lines.get(a);
            if (TextUtils.isEmpty(line.text)) {
                View spacer = new View(getContext());
                linesLayout.addView(spacer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 20));
                lineViews.add(null);
                continue;
            }
            final int index = a;
            TextView view = new TextView(getContext());
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, ACTIVE_TEXT_SIZE);
            view.setTypeface(AndroidUtilities.bold());
            view.setTextColor(Color.argb(INACTIVE_ALPHA, 185, 185, 185));
            view.setScaleX(INACTIVE_SCALE);
            view.setScaleY(INACTIVE_SCALE);
            view.setPivotX(0f);
            view.setTranslationY(-dp(TRANSLATION_Y_DP));
            view.setText(line.text);
            if (synced) {
                view.setOnClickListener(v -> onLineTapped(index, v));
            }
            linesLayout.addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT, 0, 6, 0, 6));
            lineViews.add(view);
        }

        if (!TextUtils.isEmpty(source)) {
            sourceView = new TextView(getContext());
            sourceView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sourceView.setTextColor(Color.argb(180, 255, 255, 255));
            sourceView.setText(LocaleController.formatString(R.string.LyricsSource, source));
            linesLayout.addView(sourceView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT, 0, 24, 0, 30));
        }

        if (synced) {
            activeIndex = -1;
            firstCentring = true;
            syncToPlayback();
        }
    }

    /**
     * Lights up the line that belongs to the current playback position and scrolls it to the middle.
     */
    private void syncToPlayback() {
        if (!synced || lines.isEmpty()) {
            return;
        }
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null || !playing.isMusic()) {
            return;
        }
        double position = playing.audioProgress * (MediaController.getInstance().getDuration() / 1000.0);
        int target = -1;
        for (int a = 0; a < lines.size(); a++) {
            if (lines.get(a).time <= position + SYNC_OFFSET_SEC) {
                target = a;
            } else {
                break;
            }
        }
        if (target == activeIndex) {
            return;
        }
        setActiveLine(target);
    }

    private void setActiveLine(int index) {
        int previous = activeIndex;
        activeIndex = index;
        if (previous >= 0 && previous < lineViews.size()) {
            animateLine(lineViews.get(previous), false);
        }
        if (index < 0 || index >= lineViews.size()) {
            return;
        }
        TextView view = lineViews.get(index);
        animateLine(view, true);
        if (view != null) {
            centerLine(view);
        }
    }

    private void animateLine(@Nullable TextView view, boolean active) {
        if (view == null) {
            return;
        }
        view.animate().cancel();
        view.setShadowLayer(active ? ACTIVE_SHADOW_RADIUS : 0f, 0, 0, Color.argb(220, 0, 0, 0));
        view.animate()
                .scaleX(active ? 1f : INACTIVE_SCALE)
                .scaleY(active ? 1f : INACTIVE_SCALE)
                .translationY(active ? 0f : -dp(TRANSLATION_Y_DP))
                .setDuration(active ? ANIMATION_MS + 17 : ANIMATION_MS + 51)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
        animateColor(view, active);
    }

    private void animateColor(TextView view, boolean active) {
        int from = view.getCurrentTextColor();
        int to = active ? Color.WHITE : Color.argb(INACTIVE_ALPHA, 185, 185, 185);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(active ? ANIMATION_MS + 17 : ANIMATION_MS + 51);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float value = animation.getAnimatedFraction();
            view.setTextColor(Color.argb(
                    (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * value),
                    (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * value),
                    (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * value),
                    (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * value)));
        });
        animator.start();
    }
    private void centerLine(View view) {
        if (scrollView == null) {
            return;
        }
        int target = Math.max(0, view.getTop() - scrollView.getHeight() / 2
                + view.getHeight() / 2 + scrollView.getPaddingTop());
        if (firstCentring) {
            // The scroll view has not settled on its final row positions yet, so re-centre twice
            // more after layout instead of animating to a position that is about to move.
            firstCentring = false;
            scrollView.scrollTo(0, target);
            AndroidUtilities.runOnUIThread(() -> centerLine(view), 50);
            AndroidUtilities.runOnUIThread(() -> centerLine(view), 200);
            return;
        }
        int distance = Math.abs(target - scrollView.getScrollY());
        if (distance < dp(2)) {
            return;
        }
        if (scrollAnimator != null) {
            scrollAnimator.cancel();
        }
        scrollAnimator = ObjectAnimator.ofInt(scrollView, "scrollY", target);
        scrollAnimator.setDuration(Math.max(260, Math.min(480, distance / 2)));
        scrollAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        scrollAnimator.start();
    }

    /** Two taps on the same line within a moment seek playback to it. */
    private void onLineTapped(int index, View view) {
        long now = System.currentTimeMillis();
        if (lastTapIndex == index && now - lastTapTime <= DOUBLE_TAP_MS) {
            lastTapIndex = -1;
            lastTapTime = 0;
            seekTo(lines.get(index).time);
            view.animate().cancel();
            view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(60)
                    .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(60).start())
                    .start();
            return;
        }
        lastTapIndex = index;
        lastTapTime = now;
    }

    private void seekTo(double seconds) {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) {
            return;
        }
        long duration = MediaController.getInstance().getDuration();
        if (duration <= 0) {
            return;
        }
        float progress = (float) Math.max(0, Math.min(1, seconds * 1000.0 / duration));
        MediaController.getInstance().seekToProgress(playing, progress);
        playing.audioProgress = progress;
        setActiveLine(indexAt(seconds));
    }

    private int indexAt(double seconds) {
        int target = -1;
        for (int a = 0; a < lines.size(); a++) {
            if (lines.get(a).time <= seconds + 0.01) {
                target = a;
            } else {
                break;
            }
        }
        return target;
    }

    private void checkSwipeToDismiss(MotionEvent event) {
        float dy = event.getY() - touchStartY;
        float dx = Math.abs(event.getX() - touchStartX);
        if (dy > dp(SWIPE_DISTANCE_DP) && dx < dp(SWIPE_DRIFT_DP)) {
            dismiss();
        }
    }

    private void loadCover() {
        if (messageObject == null) {
            return;
        }
        final MessageObject requested = messageObject;
        LyricsCoverLoader.load(requested, bitmap -> {
            if (bitmap == null || requested != messageObject) {
                return;
            }
            coverView.setImageBitmap(bitmap);
            coverView.animate().alpha(1f).setDuration(320).start();
            ambientView.setPalette(LyricsCoverLoader.palette(bitmap));
        });
    }

    @Override
    public void show() {
        super.show();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidReset);
        ambientView.start();
    }

    @Override
    public void dismiss() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidReset);
        ambientView.stop();
        if (scrollAnimator != null) {
            scrollAnimator.cancel();
            scrollAnimator = null;
        }
        super.dismiss();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            syncToPlayback();
            miniPlayer.updateProgress();
        } else if (id == NotificationCenter.messagePlayingPlayStateChanged) {
            miniPlayer.updateState();
        } else if (id == NotificationCenter.messagePlayingDidStart) {
            MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null && playing.isMusic() && playing != messageObject) {
                // The user skipped to another track, so start over with its words.
                messageObject = playing;
                lines = new ArrayList<>();
                synced = false;
                activeIndex = -1;
                updateHeader();
                miniPlayer.bind(playing);
                coverView.animate().alpha(0f).setDuration(200).start();
                showLoading();
                loadLyrics();
                loadCover();
            } else {
                miniPlayer.updateState();
            }
        } else if (id == NotificationCenter.messagePlayingDidReset) {
            miniPlayer.updateState();
        }
    }
}
