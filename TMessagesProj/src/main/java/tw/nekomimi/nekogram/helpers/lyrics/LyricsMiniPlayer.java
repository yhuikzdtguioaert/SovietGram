package tw.nekomimi.nekogram.helpers.lyrics;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.Components.LayoutHelper;

/**
 * The transport bar pinned to the bottom of the lyrics view, so the track can be paused or skipped
 * without leaving the words.
 */
public class LyricsMiniPlayer extends FrameLayout {

    private final FrameLayout cover;
    private final TextView title;
    private final TextView artist;
    private final TextView playButton;
    private final TextView elapsed;

    private MessageObject messageObject;

    public LyricsMiniPlayer(Context context) {
        super(context);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(18, 18, 18));
        background.setCornerRadius(dp(18));
        setBackground(background);
        setPadding(dp(10), dp(6), dp(10), dp(6));

        cover = new FrameLayout(context);
        GradientDrawable coverBackground = new GradientDrawable();
        coverBackground.setColor(Color.argb(80, 255, 255, 255));
        coverBackground.setCornerRadius(dp(10));
        cover.setBackground(coverBackground);
        addView(cover, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);

        title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Color.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        artist = new TextView(context);
        artist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        artist.setTextColor(Color.argb(180, 255, 255, 255));
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(artist, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                0, 2, 0, 0));

        addView(titles, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 56 + 12, 0, 150, 0));

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        TextView previous = button(context, "⏮");
        previous.setOnClickListener(v -> MediaController.getInstance().playPreviousMessage());
        controls.addView(previous, LayoutHelper.createLinear(36, 36));

        playButton = button(context, "⏸");
        playButton.setOnClickListener(v -> togglePlayback());
        controls.addView(playButton, LayoutHelper.createLinear(36, 36));

        TextView next = button(context, "⏭");
        next.setOnClickListener(v -> MediaController.getInstance().playNextMessage());
        controls.addView(next, LayoutHelper.createLinear(36, 36));

        elapsed = new TextView(context);
        elapsed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        elapsed.setTextColor(Color.argb(180, 255, 255, 255));
        elapsed.setGravity(Gravity.CENTER);
        controls.addView(elapsed, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT, 4, 0, 0, 0));

        addView(controls, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL));
    }

    private static TextView button(Context context, String glyph) {
        TextView view = new TextView(context);
        view.setText(glyph);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    public void bind(@Nullable MessageObject messageObject) {
        this.messageObject = messageObject;
        if (messageObject == null) {
            return;
        }
        title.setText(messageObject.getMusicTitle());
        artist.setText(messageObject.getMusicAuthor());
        updateState();
        updateProgress();
    }

    public void updateState() {
        playButton.setText(MediaController.getInstance().isMessagePaused() ? "▶" : "⏸");
    }

    public void updateProgress() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) {
            return;
        }
        elapsed.setText(AndroidUtilities.formatShortDuration(playing.audioProgressSec));
    }

    private void togglePlayback() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) {
            return;
        }
        if (MediaController.getInstance().isMessagePaused()) {
            MediaController.getInstance().playMessage(playing);
        } else {
            MediaController.getInstance().pauseMessage(playing);
        }
        updateState();
    }
}
