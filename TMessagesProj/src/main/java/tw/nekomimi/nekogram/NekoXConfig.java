package tw.nekomimi.nekogram;

import static org.telegram.messenger.LocaleController.getString;

import android.content.res.Configuration;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.util.Locale;

public class NekoXConfig {

    public static final int TITLE_TYPE_TEXT = 0;
    public static final int TITLE_TYPE_ICON = 1;
    public static final int TITLE_TYPE_MIX = 2;

    public static int currentAppId() {
        return BuildConfig.APP_ID;
    }

    public static String currentAppHash() {
        return BuildConfig.APP_HASH;
    }

    public static String formatLang(String name) {
        if (name == null || name.isEmpty()) {
            return getString(R.string.Default);
        } else {
            String[] parts = name.split("-");
            Locale locale;
            if (parts.length > 1) {
                locale = new Locale(parts[0], parts[1]);
            } else {
                locale = new Locale(parts[0]);
            }
            return locale.getDisplayName(LocaleController.getInstance().currentLocale);
        }
    }

    public static int getNotificationColor() {
        int color = 0;
        Configuration configuration = ApplicationLoader.applicationContext.getResources().getConfiguration();
        boolean isDark = (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (isDark) {
            color = 0xffffffff;
        } else {
            if (Theme.getActiveTheme().hasAccentColors()) {
                color = Theme.getActiveTheme().getAccentColor(Theme.getActiveTheme().currentAccentId);
            }
            if (Theme.getActiveTheme().isDark() || color == 0) {
                color = Theme.getColor(Theme.key_actionBarDefault);
            }
            // too bright
            if (AndroidUtilities.computePerceivedBrightness(color) >= 0.721f) {
                color = Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader) | 0xff000000;
            }
        }
        return color;
    }

}
