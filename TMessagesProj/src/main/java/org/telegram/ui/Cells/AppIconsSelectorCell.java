package org.telegram.ui.Cells;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.Spannable;
import android.text.SpannableString;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.drawable.IconCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.Easings;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.PremiumFeatureBottomSheet;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.LauncherIconController;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PremiumPreviewFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AppIconsSelectorCell extends RecyclerListView implements NotificationCenter.NotificationCenterDelegate {
    public final static float ICONS_ROUND_RADIUS = 18;
    public final static int CUSTOM_APP_ICON_REQUEST_CODE = 12581;
    private static final String CUSTOM_SHORTCUT_ID = "sovietgram_custom_app_icon";
    private static final String CUSTOM_ICON_FILE = "sovietgram_custom_app_icon.png";

    private List<LauncherIconController.LauncherIcon> availableIcons = new ArrayList<>();
    private LinearLayoutManager linearLayoutManager;
    private int currentAccount;
    private final BaseFragment fragment;

    public AppIconsSelectorCell(Context context, BaseFragment fragment, int currentAccount) {
        super(context);
        this.fragment = fragment;
        this.currentAccount = currentAccount;
        setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));

        setFocusable(false);
        setItemAnimator(null);
        setLayoutAnimation(null);

        setLayoutManager(linearLayoutManager = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        setAdapter(new Adapter() {

            @NonNull
            @Override
            public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RecyclerListView.Holder(new IconHolderView(parent.getContext()));
            }

            @Override
            public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
                IconHolderView holderView = (IconHolderView) holder.itemView;
                LauncherIconController.LauncherIcon icon = availableIcons.get(position);
                holderView.bind(icon);
                holderView.iconView.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(ICONS_ROUND_RADIUS), Color.TRANSPARENT, Theme.getColor(Theme.key_listSelector), Color.BLACK));
                holderView.iconView.setForeground(icon.foreground);
                holderView.iconView.setIsNekoXIcon(icon.isNekoX());
            }

            @Override
            public int getItemCount() {
                return availableIcons.size();
            }
        });
        addItemDecoration(new ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull State state) {
                int pos = parent.getChildViewHolder(view).getAdapterPosition();
                if (pos == 0) {
                    outRect.left = AndroidUtilities.dp(18);
                }
                if (pos == getAdapter().getItemCount() - 1) {
                    outRect.right = AndroidUtilities.dp(18);
                } else {
                    int itemCount = getAdapter().getItemCount();
                    if (itemCount == 4) {
                        outRect.right = (getWidth() - AndroidUtilities.dp(36) - AndroidUtilities.dp(58) * itemCount) / (itemCount - 1);
                    } else {
                        outRect.right = AndroidUtilities.dp(24);
                    }
                }
            }
        });
        setOnItemClickListener((view, position) -> {
            IconHolderView holderView = (IconHolderView) view;
            LauncherIconController.LauncherIcon icon = availableIcons.get(position);
            if (icon.customPicker) {
                pickCustomAppIcon();
                return;
            }
            if (icon.premium && !UserConfig.hasPremiumOnAccounts()) {
                fragment.showDialog(new PremiumFeatureBottomSheet(fragment, PremiumPreviewFragment.PREMIUM_FEATURE_APPLICATION_ICONS, true));
                return;
            }

            if (LauncherIconController.isEnabled(icon)) {
                return;
            }

            LinearSmoothScroller smoothScroller = new LinearSmoothScroller(context) {
                @Override
                public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd, int snapPreference) {
                    return boxStart - viewStart + AndroidUtilities.dp(16);
                }

                @Override
                protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                    return super.calculateSpeedPerPixel(displayMetrics) * 3f;
                }
            };
            smoothScroller.setTargetPosition(position);
            linearLayoutManager.startSmoothScroll(smoothScroller);

            LauncherIconController.setIcon(icon);
            holderView.setSelected(true, true);

            for (int i = 0; i < getChildCount(); i++) {
                IconHolderView otherView = (IconHolderView) getChildAt(i);
                if (otherView != holderView) {
                    otherView.setSelected(false, true);
                }
            }

            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_APP_ICON, icon);
        });
        updateIconsVisibility();
    }

    private void pickCustomAppIcon() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            fragment.startActivityForResult(intent, CUSTOM_APP_ICON_REQUEST_CODE);
        } catch (Throwable e) {
            FileLog.e("Unable to open custom app icon picker", e);
        }
    }

    public void handleCustomIconResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        try {
            getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignore) {
        }
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            Bitmap source = null;
            Bitmap icon = null;
            try {
                source = ImageLoader.loadBitmap(null, uri, 2048, 2048, true);
                if (source == null) {
                    throw new IllegalArgumentException("Selected image could not be decoded");
                }
                icon = createFramedCustomIcon(source);
                File output = getCustomIconFile(getContext());
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    icon.compress(Bitmap.CompressFormat.PNG, 100, stream);
                }
                final Bitmap shortcutBitmap = icon;
                AndroidUtilities.runOnUIThread(() -> installOrUpdateCustomIcon(shortcutBitmap));
                icon = null;
            } catch (Throwable e) {
                FileLog.e("Unable to create custom app icon", e);
            } finally {
                if (source != null) {
                    source.recycle();
                }
                if (icon != null) {
                    icon.recycle();
                }
            }
        });
    }

    private void installOrUpdateCustomIcon(Bitmap bitmap) {
        try {
            Context context = getContext();
            Intent launchIntent = new Intent(Intent.ACTION_MAIN)
                    .setClass(context, LaunchActivity.class)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(context, CUSTOM_SHORTCUT_ID)
                    .setShortLabel(LocaleController.getString(R.string.SovietGram))
                    .setLongLabel(LocaleController.getString(R.string.SovietGram))
                    .setIcon(IconCompat.createWithAdaptiveBitmap(bitmap))
                    .setIntent(launchIntent)
                    .build();

            boolean alreadyPinned = false;
            for (ShortcutInfoCompat existing : ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)) {
                if (CUSTOM_SHORTCUT_ID.equals(existing.getId())) {
                    alreadyPinned = true;
                    break;
                }
            }
            if (alreadyPinned) {
                ShortcutManagerCompat.updateShortcuts(context, Collections.singletonList(shortcut));
            } else if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                ShortcutManagerCompat.requestPinShortcut(context, shortcut, null);
            }
            int position = availableIcons.indexOf(LauncherIconController.LauncherIcon.SOVIET_CUSTOM);
            if (position >= 0) {
                getAdapter().notifyItemChanged(position);
            }
            NotificationCenter.getGlobalInstance().postNotificationName(
                    NotificationCenter.showBulletin, Bulletin.TYPE_APP_ICON, LauncherIconController.LauncherIcon.SOVIET_CUSTOM);
        } catch (Throwable e) {
            FileLog.e("Unable to install custom app icon", e);
        } finally {
            bitmap.recycle();
        }
    }

    private static Bitmap createFramedCustomIcon(Bitmap source) {
        final int size = 512;
        Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.rgb(189, 21, 21));

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        RectF frame = new RectF(18, 18, size - 18, size - 18);
        paint.setColor(Color.rgb(247, 238, 218));
        canvas.drawRoundRect(frame, 118, 118, paint);

        RectF photo = new RectF(34, 34, size - 34, size - 34);
        BitmapShader shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float scale = Math.max(photo.width() / source.getWidth(), photo.height() / source.getHeight());
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(
                photo.centerX() - source.getWidth() * scale / 2f,
                photo.centerY() - source.getHeight() * scale / 2f);
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);
        canvas.drawRoundRect(photo, 96, 96, paint);
        paint.setShader(null);
        return result;
    }

    private static File getCustomIconFile(Context context) {
        return new File(context.getFilesDir(), CUSTOM_ICON_FILE);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void updateIconsVisibility() {
        availableIcons.clear();
        availableIcons.addAll(Arrays.asList(LauncherIconController.LauncherIcon.values()));
        if (MessagesController.getInstance(currentAccount).premiumFeaturesBlocked()) {
            for (int i = 0; i < availableIcons.size(); i++) {
                if (availableIcons.get(i).premium) {
                    availableIcons.remove(i);
                    i--;
                }
            }
        }
        getAdapter().notifyDataSetChanged();
        invalidateItemDecorations();

        for (int i = 0; i < availableIcons.size(); i++) {
            LauncherIconController.LauncherIcon icon = availableIcons.get(i);
            if (LauncherIconController.isEnabled(icon)) {
                linearLayoutManager.scrollToPositionWithOffset(i, AndroidUtilities.dp(16));
                break;
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        invalidateItemDecorations();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthSpec), MeasureSpec.EXACTLY), heightSpec);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.premiumStatusChangedGlobal);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.premiumStatusChangedGlobal);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.premiumStatusChangedGlobal) {
            updateIconsVisibility();
        }
    }

    private final static class IconHolderView extends LinearLayout {
        private Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private AdaptiveIconImageView iconView;
        private TextView titleView;

        private float progress;

        private IconHolderView(@NonNull Context context) {
            super(context);

            setOrientation(VERTICAL);

            setWillNotDraw(false);
            iconView = new AdaptiveIconImageView(context);
            iconView.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
            addView(iconView, LayoutHelper.createLinear(58, 58, Gravity.CENTER_HORIZONTAL));

            titleView = new TextView(context);
            titleView.setSingleLine();
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));

            outlinePaint.setStyle(Paint.Style.STROKE);
            outlinePaint.setStrokeWidth(Math.max(2, AndroidUtilities.dp(0.5f)));

            fillPaint.setColor(Color.WHITE);
        }

        @Override
        public void draw(Canvas canvas) {
            float stroke = outlinePaint.getStrokeWidth();
            AndroidUtilities.rectTmp.set(iconView.getLeft() + stroke, iconView.getTop() + stroke, iconView.getRight() - stroke, iconView.getBottom() - stroke);
            canvas.drawRoundRect(AndroidUtilities.rectTmp, AndroidUtilities.dp(ICONS_ROUND_RADIUS), AndroidUtilities.dp(ICONS_ROUND_RADIUS), fillPaint);

            super.draw(canvas);

            canvas.drawRoundRect(AndroidUtilities.rectTmp, AndroidUtilities.dp(ICONS_ROUND_RADIUS), AndroidUtilities.dp(ICONS_ROUND_RADIUS), outlinePaint);
        }

        private void setProgress(float progress) {
            this.progress = progress;

            titleView.setTextColor(ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), Theme.getColor(Theme.key_windowBackgroundWhiteValueText), progress));
            outlinePaint.setColor(ColorUtils.blendARGB(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_switchTrack), 0x3F), Theme.getColor(Theme.key_windowBackgroundWhiteValueText), progress));
            outlinePaint.setStrokeWidth(Math.max(2, AndroidUtilities.dp(AndroidUtilities.lerp(0.5f, 2f, progress))));
            invalidate();
        }

        private void setSelected(boolean selected, boolean animate) {
            float to = selected ? 1 : 0;
            if (to == progress && animate) {
                return;
            }

            if (animate) {
                ValueAnimator animator = ValueAnimator.ofFloat(progress, to).setDuration(250);
                animator.setInterpolator(Easings.easeInOutQuad);
                animator.addUpdateListener(animation -> setProgress((Float) animation.getAnimatedValue()));
                animator.start();
            } else {
                setProgress(to);
            }
        }

        private void bind(LauncherIconController.LauncherIcon icon) {
            if (icon.customPicker) {
                File customFile = getCustomIconFile(getContext());
                Bitmap customBitmap = customFile.isFile() ? android.graphics.BitmapFactory.decodeFile(customFile.getAbsolutePath()) : null;
                if (customBitmap != null) {
                    iconView.setImageBitmap(customBitmap);
                } else {
                    iconView.setImageResource(icon.background);
                }
            } else {
                iconView.setImageResource(icon.background);
            }

            MarginLayoutParams params = (MarginLayoutParams) titleView.getLayoutParams();
            if (icon.premium && !UserConfig.hasPremiumOnAccounts()) {
                SpannableString str = new SpannableString("d " + LocaleController.getString(icon.title));
                ColoredImageSpan span = new ColoredImageSpan(R.drawable.msg_mini_premiumlock);
                span.setTopOffset(1);
                span.setSize(AndroidUtilities.dp(13));
                str.setSpan(span, 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                params.rightMargin = AndroidUtilities.dp(4);
                titleView.setText(str);
            } else {
                params.rightMargin = 0;
                titleView.setText(LocaleController.getString(icon.title));
            }
            setSelected(LauncherIconController.isEnabled(icon), false);
        }
    }

    public static class AdaptiveIconImageView extends ImageView {
        private boolean isNekoXIcon = false;
        private Drawable foreground;
        private Path path = new Path();
        private int outerPadding = AndroidUtilities.dp(5);
        private int backgroundOuterPadding = AndroidUtilities.dp(42);

        public AdaptiveIconImageView(Context context) {
            super(context);
        }

        public void setForeground(int res) {
            foreground = ContextCompat.getDrawable(getContext(), res);
            invalidate();
        }

        public void setIsNekoXIcon(boolean value) {
            this.isNekoXIcon = value;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            updatePath();
        }

        public void setPadding(int padding) {
            setPadding(padding, padding, padding, padding);
        }

        public void setOuterPadding(int outerPadding) {
            this.outerPadding = outerPadding;
        }

        public void setBackgroundOuterPadding(int backgroundOuterPadding) {
            this.backgroundOuterPadding = backgroundOuterPadding;
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.save();
            canvas.clipPath(path);
            if (!this.isNekoXIcon)
                canvas.scale(1f + backgroundOuterPadding / (float) getWidth(), 1f + backgroundOuterPadding / (float) getHeight(), getWidth() / 2f, getHeight() / 2f);
            super.draw(canvas);
            canvas.restore();

            if (foreground != null && !this.isNekoXIcon) {
                foreground.setBounds(-outerPadding, -outerPadding, getWidth() + outerPadding, getHeight() + outerPadding);
                foreground.draw(canvas);
            }
        }

        private void updatePath() {
            path.rewind();
            path.addCircle(getWidth() / 2f, getHeight() / 2f, Math.min(getWidth() - getPaddingLeft() - getPaddingRight(), getHeight() - getPaddingTop() - getPaddingBottom()) / 2f, Path.Direction.CW);
        }
    }
}
