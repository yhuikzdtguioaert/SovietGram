package tw.nekomimi.nekogram.ui.cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import tw.nekomimi.nekogram.helpers.WorkshopHelper;

/**
 * One published look in the workshop grid: its preview shot with the title, the author and the
 * like count written over the bottom of it.
 * <p>
 * The preview is a portrait screenshot of a whole profile page, so the cell keeps that aspect and
 * lets {@link BackupImageView} fetch it — the URL goes through the same image cache as everything
 * else, which is what stops a scroll back up from re-downloading the row.
 */
public class WorkshopCell extends FrameLayout {

    private final BackupImageView imageView;
    private final TextView titleView;
    private final TextView subtitleView;
    private final TextView likesView;

    private WorkshopHelper.Work work;

    public WorkshopCell(Context context) {
        super(context);

        imageView = new BackupImageView(context);
        imageView.setRoundRadius(dp(12));
        addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Keeps the captions readable over a preview that happens to be bright down there.
        addView(new Scrim(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64,
                Gravity.BOTTOM | Gravity.LEFT));

        likesView = new TextView(context);
        likesView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        likesView.setTextColor(0xFFFFFFFF);
        likesView.setGravity(Gravity.CENTER_VERTICAL);
        likesView.setCompoundDrawablePadding(dp(3));
        likesView.setBackground(Theme.createRoundRectDrawable(dp(10), 0x60000000));
        likesView.setPadding(dp(6), dp(3), dp(6), dp(3));
        addView(likesView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT, 0, 8, 8, 0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.LEFT, 8, 0, 8, 22));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        subtitleView.setTextColor(0xB3FFFFFF);
        subtitleView.setMaxLines(1);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.LEFT, 8, 0, 8, 7));
    }

    public void setWork(WorkshopHelper.Work work) {
        this.work = work;
        titleView.setText(TextUtils.isEmpty(work.title)
                ? LocaleController.getString(R.string.WorkshopUntitled) : work.title);
        subtitleView.setText(TextUtils.isEmpty(work.authorName)
                ? LocaleController.getString(R.string.WorkshopAuthor) : work.authorName);
        updateLikes();
        imageView.setImage(ImageLocation.getForPath(WorkshopHelper.previewUrl(work)), "220_480",
                Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_listSelector)), null);
    }

    /** Refreshed on its own after a like, so the cell does not have to reload its picture. */
    public void updateLikes() {
        if (work == null) {
            return;
        }
        likesView.setText(String.valueOf(work.likes));
        likesView.setCompoundDrawablesWithIntrinsicBounds(
                work.liked ? R.drawable.msg_reactions_filled : R.drawable.msg_reactions, 0, 0, 0);
    }

    public WorkshopHelper.Work getWork() {
        return work;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Previews are portrait shots of a whole profile page; a squarer cell crops the name off.
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((int) (width * 1.6f), MeasureSpec.EXACTLY));
    }

    /** A bottom-up black fade, rebuilt only when the height it has to cover changes. */
    private static class Scrim extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int shaderHeight;

        Scrim(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int height = getMeasuredHeight();
            if (height <= 0) {
                return;
            }
            if (shaderHeight != height) {
                shaderHeight = height;
                paint.setShader(new LinearGradient(0, 0, 0, height,
                        0x00000000, 0xB0000000, Shader.TileMode.CLAMP));
            }
            canvas.drawRoundRect(0, -dp(12), getMeasuredWidth(), height, dp(12), dp(12), paint);
        }
    }
}
