package tw.nekomimi.nekogram.ui.frame;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

import tw.nekomimi.nekogram.helpers.CustomProfileFrame;
import tw.nekomimi.nekogram.helpers.frame.FrameContour;
import tw.nekomimi.nekogram.helpers.frame.FrameOutline;
import tw.nekomimi.nekogram.helpers.frame.FramePainter;
import tw.nekomimi.nekogram.helpers.frame.FrameShape;
import tw.nekomimi.nekogram.helpers.frame.FrameSpec;
import tw.nekomimi.nekogram.helpers.frame.FrameStage;

/**
 * The frame being edited, drawn around a stand-in avatar.
 *
 * <p>A stand-in rather than the user's own picture, and a shape the editor can change independently
 * of the look, because the point of the preview is to see how the frame behaves: a ribbon that lies
 * flat on a circle can pinch badly on a star, and the only way to notice is to try it.
 *
 * <p>Its own painter and its own contour: the profile page has one too, for a different shape, and
 * they would otherwise spend every frame rebuilding each other's cache.
 */
public class FrameStudioPreviewView extends View {

    private final FramePainter painter = new FramePainter();
    private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path avatarPath = new Path();

    private FrameSpec spec = FrameSpec.EMPTY;
    private int shape;
    private long contourKey = Long.MIN_VALUE;

    public FrameStudioPreviewView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    public void setSpec(@Nullable FrameSpec value) {
        spec = value == null ? FrameSpec.EMPTY : value;
        invalidate();
    }

    public FrameSpec getSpec() {
        return spec;
    }

    /** Which of the eight outlines the stand-in wears. Independent of the user's own avatar. */
    public void setShape(int value) {
        if (shape == value) {
            return;
        }
        shape = value;
        contourKey = Long.MIN_VALUE;
        invalidate();
    }

    public int getShape() {
        return shape;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float side = Math.min(getWidth(), getHeight());
        if (side <= 1) {
            return;
        }
        final float box = FrameStage.box(side, spec, FrameStage.avatarSide());
        final float left = (getWidth() - box) / 2f;
        final float top = (getHeight() - box) / 2f;
        prepare(box);

        avatarPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4));
        final int save = canvas.save();
        try {
            canvas.translate(left, top);
            canvas.scale(box / FrameShape.SPACE, box / FrameShape.SPACE);
            canvas.drawPath(avatarPath, avatarPaint);
        } finally {
            canvas.restoreToCount(save);
        }

        painter.draw(canvas, spec, left, top, box, box, FramePainter.toSpace(), 0f, 0xFF,
                CustomProfileFrame.sources());
        if (FramePainter.moving(spec)) {
            postInvalidateOnAnimation();
        }
    }

    /** Both the outline the frame follows and the shape the stand-in is drawn as, from one path. */
    private void prepare(float box) {
        final long key = shape * 31L + Math.round(box / 8f);
        if (key == contourKey && !avatarPath.isEmpty()) {
            return;
        }
        contourKey = key;
        avatarPath.rewind();
        avatarPath.addPath(FrameShape.of(shape, 12, 0, null, box));
        final FrameContour contour = FrameOutline.of(avatarPath, FrameOutline.SAMPLES);
        painter.contour(contour);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(220), MeasureSpec.EXACTLY));
    }
}
