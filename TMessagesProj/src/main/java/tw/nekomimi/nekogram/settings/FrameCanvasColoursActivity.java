package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;

import java.util.Locale;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.cell.ConfigCellColor;
import tw.nekomimi.nekogram.helpers.frame.FrameCanvasSkin;

/**
 * The twenty-five colours of the user's own canvas theme.
 *
 * <p>Opening this screen switches the canvas to that theme, because there is nothing else changing
 * these colours could be for.
 */
public class FrameCanvasColoursActivity extends CustomProfileListActivity {

    @Override
    protected String title() {
        return getString(R.string.CustomProfileFrameCanvasColours);
    }

    @Override
    protected void buildRows() {
        final FrameCanvasSkin skin = current();
        for (int i = 0; i < FrameCanvasSkin.ROLES; i++) {
            final int index = i;
            final int color = skin.role(i);
            final Row row = setting(roleName(i),
                    String.format(Locale.US, "#%06X", color & 0xFFFFFF), () -> pick(index, color));
            row.valueColor = color | 0xFF000000;
        }
        shadow();
        setting(getString(R.string.CustomProfileFrameCanvasReset), null, this::confirmReset);
        info(getString(R.string.CustomProfileFrameCanvasColoursInfo));
    }

    private FrameCanvasSkin current() {
        final FrameCanvasSkin stored =
                FrameCanvasSkin.parse(NekoConfig.customProfileFrameCanvasCustom.String());
        return stored == null ? FrameCanvasSkin.DARK : stored;
    }

    private void pick(int index, int color) {
        if (getParentActivity() == null) {
            return;
        }
        ConfigCellColor.show(getParentActivity(), roleName(index), color, color, true, picked -> {
            store(current().with(index, picked));
            rebuild();
        });
    }

    private void confirmReset() {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.CustomProfileFrameCanvasReset))
                .setMessage(getString(R.string.CustomProfileFrameCanvasResetInfo))
                .setPositiveButton(getString(R.string.Reset), (dialog, which) -> {
                    store(FrameCanvasSkin.DARK);
                    rebuild();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private static void store(FrameCanvasSkin skin) {
        NekoConfig.customProfileFrameCanvasCustom.setConfigString(skin.encode());
        NekoConfig.customProfileFrameCanvasSkin.setConfigInt(FrameCanvasSkin.MODE_CUSTOM);
    }

    private static String roleName(int index) {
        return getString("FrameSkinRole" + FrameCanvasSkin.KEYS[index]);
    }
}
