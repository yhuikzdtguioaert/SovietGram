package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import java.util.Locale;

import tw.nekomimi.nekogram.config.cell.ConfigCellColor;
import tw.nekomimi.nekogram.helpers.CustomProfilePalette;

/**
 * The look's palette: the theme colours the profile screen is repainted with.
 *
 * <p>A look can name any of the app's theme keys, and every one it names is honoured. This screen
 * offers the two dozen that are worth offering — the surfaces, the text, the links — because that is
 * the set the reference's own editor shows and the set that actually changes how a profile reads.
 * Anything a look carries beyond them stays untouched and is counted in the row at the top.
 *
 * <p>A key with no colour of its own is left alone rather than being written out as the theme's
 * current value: a palette that pins every colour would stop following the user's own light and dark
 * themes entirely.
 */
public class CustomProfilePaletteActivity extends CustomProfileListActivity {

    @Override
    protected String title() {
        return getString(R.string.CustomProfilePalette);
    }

    @Override
    protected void buildRows() {
        String group = null;
        for (String[] entry : CustomProfilePalette.KNOWN) {
            if (!entry[1].equals(group)) {
                group = entry[1];
                header(groupName(group));
            }
            final String key = entry[0];
            final Integer color = CustomProfilePalette.colorOf(key);
            final Row row = setting(label(key),
                    color == null ? getString(R.string.CustomProfilePaletteDefault) : hex(color),
                    () -> pick(key, color));
            if (color != null) {
                row.valueColor = color | 0xFF000000;
            }
            // A long press is the way back to the theme's own colour without opening the picker.
            row.onLongClick = () -> {
                CustomProfilePalette.put(key, null);
                rebuild();
            };
        }
        shadow();
        setting(getString(R.string.CustomProfilePaletteClear),
                String.valueOf(CustomProfilePalette.size()), this::confirmClear);
        info(getString(R.string.CustomProfilePaletteInfo));
    }

    private void pick(String key, Integer current) {
        if (getParentActivity() == null) {
            return;
        }
        final int start = current != null ? current : themeColor(key);
        ConfigCellColor.show(getParentActivity(), label(key), start, start, true, color -> {
            CustomProfilePalette.put(key, color);
            rebuild();
        });
    }

    private void confirmClear() {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.CustomProfilePaletteClear))
                .setMessage(getString(R.string.CustomProfilePaletteClearInfo))
                .setPositiveButton(getString(R.string.Reset), (dialog, which) -> {
                    CustomProfilePalette.clear();
                    rebuild();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    /** The colour the user's own theme gives a key, as the picker's starting point. */
    private static int themeColor(String key) {
        try {
            final java.lang.reflect.Field field = Theme.class.getField(key);
            return Theme.getColor(field.getInt(null));
        } catch (Throwable ignore) {
            return 0xFF000000;
        }
    }

    /** Each key's own label, named after the key so the strings sit beside it. */
    private static String label(String key) {
        return getString("CustomProfilePalette_" + key);
    }

    private static String groupName(String group) {
        return getString("CustomProfilePaletteGroup" + group);
    }

    private static String hex(int color) {
        return String.format(Locale.US, "#%06X", color & 0xFFFFFF);
    }
}
