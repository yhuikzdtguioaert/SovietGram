package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

public class LauncherIconController {
    private static final int SOVIETGRAM_ICON_MIGRATION_VERSION = 3;
    private static final String[] LEGACY_ICON_KEYS = {
            "DefaultIcon",
            "NagramIcon",
            "NagramXIcon",
            "NekoXIcon",
            "SovietGramGoogleIcon",
            "SovietGramColorfulIcon",
            "SovietGramDarkGreenIcon",
            "SovietGramNeonIcon",
            "SovietGramNielloIcon",
            "SovietGramBlueIcon",
            "SovietGramDarkBlueIcon",
            "SovietGramBlurBlueIcon",
            "SovietGramTelegramIcon",
            "SovietGramVintageIcon",
            "SovietGramAquaIcon",
            "SovietGramPremiumIcon",
            "SovietGramTurboIcon",
            "SovietGramNoxIcon"
    };

    public static void tryFixLauncherIconIfNeeded() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        if (preferences.getInt("sovietgramIconMigrationVersion", 0) < SOVIETGRAM_ICON_MIGRATION_VERSION) {
            setIcon(LauncherIcon.DEFAULT);
            preferences.edit().putInt("sovietgramIconMigrationVersion", SOVIETGRAM_ICON_MIGRATION_VERSION).apply();
            return;
        }

        for (LauncherIcon icon : LauncherIcon.values()) {
            if (isEnabled(icon)) {
                return;
            }
        }

        setIcon(LauncherIcon.DEFAULT);
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        int i = ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx));
        return i == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || i == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == LauncherIcon.DEFAULT;
    }

    public static void setIcon(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        for (LauncherIcon i : LauncherIcon.values()) {
            pm.setComponentEnabledSetting(i.getComponentName(ctx), i == icon ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
        for (String legacyIconKey : LEGACY_ICON_KEYS) {
            try {
                pm.setComponentEnabledSetting(new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + legacyIconKey),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            } catch (Exception ignored) {
            }
        }
        ctx.getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
                .edit()
                .putInt("sovietgramIconMigrationVersion", SOVIETGRAM_ICON_MIGRATION_VERSION)
                .apply();
    }

    public enum LauncherIcon {
        DEFAULT("SovietGramIcon", R.mipmap.ic_launcher_sovietuniongram, R.mipmap.icon_background_sovietuniongram, R.string.SovietGram),
        TELEGRAM("TelegramIcon", R.drawable.icon_background_sa, R.mipmap.icon_foreground_sa, R.string.AppIconTelegramOriginal),
        VINTAGE("VintageIcon", R.drawable.icon_6_background_sa, R.mipmap.icon_6_foreground_sa, R.string.AppIconVintage),
        AQUA("AquaIcon", R.drawable.icon_4_background_sa, R.mipmap.icon_foreground_sa, R.string.AppIconAqua),
        PREMIUM("PremiumIcon", R.drawable.icon_3_background_sa, R.mipmap.icon_3_foreground_sa, R.string.AppIconPremium),
        TURBO("TurboIcon", R.drawable.icon_5_background_sa, R.mipmap.icon_5_foreground_sa, R.string.AppIconTurbo),
        NOX("NoxIcon", R.mipmap.icon_2_background_sa, R.mipmap.icon_foreground_sa, R.string.AppIconNox);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, int title) {
            this(key, background, foreground, title, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium) {
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }

        public boolean isNekoX() {
            return this == DEFAULT;
        }
    }
}
