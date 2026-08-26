package tw.nekomimi.nekogram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.media.ExifInterface;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.io.FileOutputStream;

import tw.nekomimi.nekogram.helpers.ImageFXHelper;

/**
 * The ImageFX editor: a picture, a carousel of twenty-four looks, and a send button.
 * <p>
 * The preview runs at a bounded size so scrolling the carousel stays responsive, while the send path
 * re-applies the chosen filter to the full-resolution original. Touch and hold the picture to see
 * what it looked like before.
 */
public class ImageFXSheet extends Dialog {

    private static final int ACCENT = 0xFF3390EC;
    /** Big enough to judge a filter by, small enough to re-filter on every carousel tap. */
    private static final int PREVIEW_SIDE = 1024;
    private static final int THUMB_SIDE = 128;

    private final BaseFragment fragment;
    private final String sourcePath;
    private final long dialogId;

    private final ImageView preview;
    private final TextView originalBadge;
    private final LinearLayout carousel;

    private Bitmap previewOriginal;
    private Bitmap previewFiltered;
    private String selected = ImageFXHelper.ORIGINAL;
    private boolean comparing;
    private boolean sending;

    private final LruCache<String, Bitmap> thumbs = new LruCache<>(ImageFXHelper.FILTERS.size());
    private Bitmap thumbSource;

    public static void show(BaseFragment fragment, String path, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null || path == null) {
            return;
        }
        if (!new File(path).exists()) {
            BulletinFactory.global().createErrorBulletin(getString(R.string.ImageFXFailed)).show();
            return;
        }
        fragment.showDialog(new ImageFXSheet(fragment, path, dialogId));
    }

    private ImageFXSheet(BaseFragment fragment, String path, long dialogId) {
        super(fragment.getParentActivity(), R.style.TransparentDialog);
        this.fragment = fragment;
        this.sourcePath = path;
        this.dialogId = dialogId;

        final Context context = fragment.getParentActivity();
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);

        preview = new ImageView(context);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(preview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.TOP | Gravity.LEFT, 0, 56, 0, 130));
        preview.setOnTouchListener((v, event) -> {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                setComparing(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                setComparing(false);
            }
            return true;
        });

        originalBadge = new TextView(context);
        originalBadge.setText(getString(R.string.ImageFXOriginal));
        originalBadge.setTextColor(Color.WHITE);
        originalBadge.setTextSize(13);
        originalBadge.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(6), AndroidUtilities.dp(12), AndroidUtilities.dp(6));
        originalBadge.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), 0x80000000));
        originalBadge.setVisibility(View.GONE);
        root.addView(originalBadge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 72, 0, 0));

        root.addView(buildHeader(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56,
                Gravity.TOP | Gravity.LEFT, 0, 0, 0, 0));

        final HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        carousel = new LinearLayout(context);
        carousel.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(carousel, new FrameLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 120,
                Gravity.BOTTOM | Gravity.LEFT, 0, 0, 0, 10));

        setContentView(root, new ViewGroup.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        buildCarousel(context);
        loadPreview();
    }
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        final Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setStatusBarColor(Color.BLACK);
            window.setNavigationBarColor(Color.BLACK);
        }
    }

    private View buildHeader(Context context) {
        final FrameLayout header = new FrameLayout(context);

        final ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.msg_close);
        close.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        close.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP));
        close.setOnClickListener(v -> dismiss());
        header.addView(close, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

        final TextView title = new TextView(context);
        title.setText(getString(R.string.ImageFXTitle));
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(AndroidUtilities.bold());
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER, 0, 0, 0, 0));

        final TextView send = new TextView(context);
        send.setText(getString(R.string.ImageFXSend));
        send.setTextColor(Color.WHITE);
        send.setTextSize(14);
        send.setTypeface(AndroidUtilities.bold());
        send.setGravity(Gravity.CENTER);
        send.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        send.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), ACCENT,
                Theme.blendOver(ACCENT, 0x22FFFFFF)));
        send.setOnClickListener(v -> applyAndSend());
        header.addView(send, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 32,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        return header;
    }

    // ---------------------------------------------------------------- carousel

    private void buildCarousel(Context context) {
        for (int a = 0; a < ImageFXHelper.FILTERS.size(); a++) {
            final ImageFXHelper.Filter filter = ImageFXHelper.FILTERS.get(a);
            final FilterView view = new FilterView(context, filter);
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(76), LayoutHelper.MATCH_PARENT);
            params.leftMargin = params.rightMargin = AndroidUtilities.dp(4);
            view.setLayoutParams(params);
            view.setOnClickListener(v -> select(filter.key));
            carousel.addView(view);
        }
    }

    private void select(String key) {
        if (sending || key.equals(selected)) {
            return;
        }
        selected = key;
        for (int a = 0; a < carousel.getChildCount(); a++) {
            carousel.getChildAt(a).invalidate();
        }
        renderPreview();
    }

    /** One carousel entry: a rounded thumbnail with a frame that lights up when it is the chosen one. */
    private class FilterView extends View {

        private final ImageFXHelper.Filter filter;
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Matrix shaderMatrix = new Matrix();
        private Bitmap shaderSource;

        FilterView(Context context, ImageFXHelper.Filter filter) {
            super(context);
            this.filter = filter;
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(AndroidUtilities.dp(2));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final boolean active = filter.key.equals(selected);
            final int side = AndroidUtilities.dp(54);
            final int left = (getWidth() - side) / 2;

            rect.set(left, AndroidUtilities.dp(4), left + side, AndroidUtilities.dp(4) + side);
            framePaint.setColor(active ? ACCENT : 0x33FFFFFF);
            canvas.drawRoundRect(rect, AndroidUtilities.dp(8), AndroidUtilities.dp(8), framePaint);

            final Bitmap thumb = thumbOf(filter.key);
            if (thumb != null && !thumb.isRecycled()) {
                if (shaderSource != thumb) {
                    shaderSource = thumb;
                    thumbPaint.setShader(new BitmapShader(thumb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                }
                final int inner = side - AndroidUtilities.dp(6);
                final float scale = Math.max(inner / (float) thumb.getWidth(), inner / (float) thumb.getHeight());
                shaderMatrix.reset();
                shaderMatrix.setScale(scale, scale);
                shaderMatrix.postTranslate(
                        left + AndroidUtilities.dp(3) + (inner - thumb.getWidth() * scale) / 2f,
                        AndroidUtilities.dp(7) + (inner - thumb.getHeight() * scale) / 2f);
                thumbPaint.getShader().setLocalMatrix(shaderMatrix);
                rect.set(left + AndroidUtilities.dp(3), AndroidUtilities.dp(7),
                        left + AndroidUtilities.dp(3) + inner, AndroidUtilities.dp(7) + inner);
                canvas.drawRoundRect(rect, AndroidUtilities.dp(6), AndroidUtilities.dp(6), thumbPaint);
            }

            final String name = getString(filter.nameRes);
            final Paint.FontMetrics metrics = labelPaint.getFontMetrics();
            labelPaint.setColor(active ? ACCENT : Color.LTGRAY);
            canvas.drawText(name, getWidth() / 2f,
                    AndroidUtilities.dp(4) + side + AndroidUtilities.dp(14) - metrics.descent, labelPaint);
        }
    }

    private final Paint labelPaint = labelPaint();

    private static Paint labelPaint() {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(AndroidUtilities.dp(11));
        paint.setTextAlign(Paint.Align.CENTER);
        return paint;
    }
    // -------------------------------------------------------------- thumbnails

    /**
     * Thumbnails are filtered off the main thread and cached, so the carousel fills in as the work
     * finishes rather than blocking the first frame.
     */
    @Nullable
    private Bitmap thumbOf(String key) {
        final Bitmap cached = thumbs.get(key);
        if (cached != null || thumbSource == null) {
            return cached;
        }
        thumbs.put(key, thumbSource);
        final Bitmap source = thumbSource;
        Utilities.globalQueue.postRunnable(() -> {
            final Bitmap result = ImageFXHelper.ORIGINAL.equals(key)
                    ? source : ImageFXHelper.apply(source, key);
            AndroidUtilities.runOnUIThread(() -> {
                if (result != null) {
                    thumbs.put(key, result);
                }
                for (int a = 0; a < carousel.getChildCount(); a++) {
                    carousel.getChildAt(a).invalidate();
                }
            });
        });
        return cached;
    }

    // ----------------------------------------------------------------- preview

    private void loadPreview() {
        Utilities.globalQueue.postRunnable(() -> {
            final Bitmap large = decode(sourcePath, PREVIEW_SIDE);
            final Bitmap small = large == null ? null : scaledCopy(large, THUMB_SIDE);
            AndroidUtilities.runOnUIThread(() -> {
                if (large == null) {
                    BulletinFactory.global().createErrorBulletin(getString(R.string.ImageFXFailed)).show();
                    dismiss();
                    return;
                }
                previewOriginal = large;
                thumbSource = small;
                preview.setImageBitmap(large);
                for (int a = 0; a < carousel.getChildCount(); a++) {
                    carousel.getChildAt(a).invalidate();
                }
            });
        });
    }

    private void renderPreview() {
        if (previewOriginal == null) {
            return;
        }
        final Bitmap source = previewOriginal;
        final String key = selected;
        if (ImageFXHelper.ORIGINAL.equals(key)) {
            showFiltered(null);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            final Bitmap result = ImageFXHelper.apply(source, key);
            AndroidUtilities.runOnUIThread(() -> {
                if (!key.equals(selected)) {
                    if (result != null) {
                        result.recycle();
                    }
                    return;
                }
                showFiltered(result);
            });
        });
    }

    private void showFiltered(@Nullable Bitmap filtered) {
        final Bitmap previous = previewFiltered;
        previewFiltered = filtered;
        if (!comparing) {
            preview.setImageBitmap(filtered != null ? filtered : previewOriginal);
        }
        if (previous != null && previous != previewOriginal) {
            previous.recycle();
        }
    }

    private void setComparing(boolean value) {
        if (comparing == value || previewOriginal == null) {
            return;
        }
        comparing = value;
        originalBadge.setVisibility(value ? View.VISIBLE : View.GONE);
        preview.setImageBitmap(value || previewFiltered == null ? previewOriginal : previewFiltered);
    }
    // -------------------------------------------------------------------- send

    /**
     * The preview was a downscaled copy, so the filter is run again over the full-resolution original
     * before sending. PNG keeps the result of a lossy-unfriendly filter (contour, posterize, 8-bit)
     * intact; Telegram will do its own compression afterwards either way.
     */
    private void applyAndSend() {
        if (sending) {
            return;
        }
        sending = true;
        if (ImageFXHelper.ORIGINAL.equals(selected)) {
            send(sourcePath);
            return;
        }
        BulletinFactory.global().createSimpleBulletin(R.raw.ic_download,
                getString(R.string.ImageFXApplying)).show();
        final String key = selected;
        Utilities.globalQueue.postRunnable(() -> {
            String path = null;
            final Bitmap full = decode(sourcePath, 0);
            if (full != null) {
                final Bitmap filtered = ImageFXHelper.apply(full, key);
                full.recycle();
                if (filtered != null) {
                    path = write(filtered);
                    filtered.recycle();
                }
            }
            final String result = path;
            AndroidUtilities.runOnUIThread(() -> {
                if (result == null) {
                    sending = false;
                    BulletinFactory.global().createErrorBulletin(getString(R.string.ImageFXFailed)).show();
                    return;
                }
                send(result);
            });
        });
    }

    private void send(String path) {
        SendMessagesHelper.prepareSendingPhoto(AccountInstance.getInstance(fragment.getCurrentAccount()),
                path, null, dialogId, null, null, null, null, null, null, null, 0, null,
                true, 0, 0, null, 0);
        dismiss();
    }

    @Nullable
    private String write(Bitmap bitmap) {
        try {
            final File file = new File(ApplicationLoader.getFilesDirFixed(),
                    "imagefx_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream stream = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            return file.getAbsolutePath();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    // ------------------------------------------------------------------ decode

    /**
     * Decodes the picture, optionally bounded to {@code maxSide}, and puts it the right way up. The
     * EXIF rotation has to be applied by hand because {@link BitmapFactory} ignores it.
     */
    @Nullable
    private static Bitmap decode(String path, int maxSide) {
        try {
            final BitmapFactory.Options options = new BitmapFactory.Options();
            if (maxSide > 0) {
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, options);
                final int larger = Math.max(options.outWidth, options.outHeight);
                options.inSampleSize = 1;
                while (larger / options.inSampleSize > maxSide * 2) {
                    options.inSampleSize *= 2;
                }
                options.inJustDecodeBounds = false;
            }
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeFile(path, options);
            if (bitmap == null) {
                return null;
            }
            if (maxSide > 0) {
                bitmap = scaledCopy(bitmap, maxSide);
            }
            return rotate(bitmap, path);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static Bitmap scaledCopy(Bitmap source, int maxSide) {
        final int larger = Math.max(source.getWidth(), source.getHeight());
        if (larger <= maxSide) {
            return source;
        }
        final float scale = maxSide / (float) larger;
        final Bitmap scaled = Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(source.getWidth() * scale)),
                Math.max(1, Math.round(source.getHeight() * scale)), true);
        if (scaled != source) {
            source.recycle();
        }
        return scaled;
    }

    private static Bitmap rotate(Bitmap bitmap, String path) {
        int degrees = 0;
        try {
            switch (new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    degrees = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    degrees = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    degrees = 270;
                    break;
            }
        } catch (Throwable ignore) {
        }
        if (degrees == 0) {
            return bitmap;
        }
        final Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        final Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
    }

    @Override
    public void dismiss() {
        super.dismiss();
        preview.setImageDrawable(null);
        if (previewFiltered != null && previewFiltered != previewOriginal) {
            previewFiltered.recycle();
        }
        previewFiltered = null;
        for (Bitmap bitmap : thumbs.snapshot().values()) {
            if (bitmap != thumbSource && bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        thumbs.evictAll();
        if (thumbSource != null && thumbSource != previewOriginal) {
            thumbSource.recycle();
        }
        thumbSource = null;
        if (previewOriginal != null) {
            previewOriginal.recycle();
        }
        previewOriginal = null;
    }
}
