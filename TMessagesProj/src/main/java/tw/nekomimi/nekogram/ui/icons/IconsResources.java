package tw.nekomimi.nekogram.ui.icons;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import androidx.annotation.Nullable;

import org.telegram.messenger.FileLog;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import sovietgram.com.NaConfig;

@SuppressLint("UseCompatLoadingForDrawables")
public class IconsResources extends Resources {

    public static final int ICON_REPLACE_SOLAR = 1;
    private int _iconsType = -1;

    public IconsResources(Resources resources) {
        super(resources.getAssets(), resources.getDisplayMetrics(), resources.getConfiguration());
    }

    /** Keyed on the wrapped Resources, which are per context, so wrappers are not rebuilt per call. */
    private static final Map<Resources, IconsResources> wrappers =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The icon replacing view of some Resources. Every Context that can inflate a drawable
     * has to hand these out from getResources(), or the icons it loads are the built-in ones
     * — which is what a replacement set that only applies in half the app looks like.
     *
     * A wrapper carries its own copy of the assets and metrics, so it is rebuilt when either
     * is replaced under it rather than serving drawables at a density that is no longer live.
     */
    public static Resources wrap(Resources base) {
        if (base == null || base instanceof IconsResources) {
            return base;
        }
        try {
            IconsResources wrapper = wrappers.get(base);
            DisplayMetrics metrics = base.getDisplayMetrics();
            if (wrapper == null
                    || wrapper.getAssets() != base.getAssets()
                    || wrapper.getDisplayMetrics().densityDpi != metrics.densityDpi) {
                wrapper = new IconsResources(base);
                wrappers.put(base, wrapper);
            }
            return wrapper;
        } catch (Throwable e) {
            FileLog.e(e);
            return base;
        }
    }

    @Override
    public Drawable getDrawable(int id) throws NotFoundException {
        return super.getDrawable(getConversion(id), null);
    }

    @Override
    public Drawable getDrawable(int id, @Nullable Theme theme) throws NotFoundException {
        return super.getDrawable(getConversion(id), theme);
    }

    @Nullable
    @Override
    public Drawable getDrawableForDensity(int id, int density, @Nullable Theme theme) {
        return super.getDrawableForDensity(getConversion(id), density, theme);
    }

    @Nullable
    @Override
    public Drawable getDrawableForDensity(int id, int density) throws NotFoundException {
        return super.getDrawableForDensity(getConversion(id), density, null);
    }

    private int getConversion(int icon) {
        return getConversion(icon, -1);
    }

    private int getConversion(int icon, int forcedIconsType) {
        if (_iconsType == -1) {
            _iconsType = NaConfig.INSTANCE.getIconReplacements().Int();
        }

        int consideredIconsType = forcedIconsType == -1 ? _iconsType : forcedIconsType;

        if (consideredIconsType == ICON_REPLACE_SOLAR) {
            return SolarIcons.Companion.getConversion(icon);
        }

        return icon;
    }
}
