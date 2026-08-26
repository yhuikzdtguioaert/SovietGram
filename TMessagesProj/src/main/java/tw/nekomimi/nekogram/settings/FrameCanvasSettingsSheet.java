package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.PopupHelper;
import tw.nekomimi.nekogram.helpers.frame.FrameCanvasSkin;
import tw.nekomimi.nekogram.helpers.frame.FrameCanvasThemes;

/**
 * How the node canvas is painted and how its wires are drawn.
 *
 * <p>Settings of this device, not part of the look: two people editing the same frame should not be
 * arguing about the colour of the board. None of these are exported.
 */
public final class FrameCanvasSettingsSheet {

    private FrameCanvasSettingsSheet() {
    }

    public static void show(BaseFragment fragment, Runnable onChanged) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        // Warmed here rather than when the canvas opens: by the time this sheet is used the answer
        // has usually arrived, and an empty list simply means only the built-in themes are offered.
        FrameCanvasThemes.sync(null);

        final ArrayList<String> options = new ArrayList<>();
        options.add(getString(R.string.CustomProfileFrameCanvasTheme));
        options.add(getString(R.string.CustomProfileFrameWire));
        options.add(getString(NekoConfig.customProfileFrameWireDodge.Bool()
                ? R.string.CustomProfileFrameDodgeOn : R.string.CustomProfileFrameDodgeOff));
        options.add(getString(R.string.CustomProfileFrameCanvasColours));
        PopupHelper.show(options, getString(R.string.CustomProfileFrameCanvasSettings), -1, context,
                picked -> {
                    if (picked == 0) {
                        pickTheme(fragment, context, onChanged);
                    } else if (picked == 1) {
                        pickWire(context, onChanged);
                    } else if (picked == 2) {
                        NekoConfig.customProfileFrameWireDodge.toggleConfigBool();
                        onChanged.run();
                    } else {
                        fragment.presentFragment(new FrameCanvasColoursActivity());
                    }
                });
    }

    private static void pickTheme(BaseFragment fragment, Context context, Runnable onChanged) {
        final ArrayList<String> names = new ArrayList<>();
        final ArrayList<int[]> values = new ArrayList<>();
        names.add(getString(R.string.CustomProfileFrameCanvasClient));
        values.add(new int[]{FrameCanvasSkin.MODE_CLIENT, 0});
        names.add(getString(R.string.CustomProfileFrameCanvasDark));
        values.add(new int[]{FrameCanvasSkin.MODE_DARK, 0});
        names.add(getString(R.string.CustomProfileFrameCanvasLight));
        values.add(new int[]{FrameCanvasSkin.MODE_LIGHT, 0});
        names.add(getString(R.string.CustomProfileFrameCanvasCustom));
        values.add(new int[]{FrameCanvasSkin.MODE_CUSTOM, 0});
        for (FrameCanvasThemes.Theme theme : FrameCanvasThemes.list()) {
            names.add(theme.name);
            values.add(new int[]{FrameCanvasSkin.MODE_SERVER, theme.id});
        }

        int checked = 0;
        final int mode = NekoConfig.customProfileFrameCanvasSkin.Int();
        final int id = NekoConfig.customProfileFrameCanvasTheme.Int();
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i)[0] == mode && (mode != FrameCanvasSkin.MODE_SERVER
                    || values.get(i)[1] == id)) {
                checked = i;
            }
        }
        PopupHelper.show(names, getString(R.string.CustomProfileFrameCanvasTheme), checked, context,
                picked -> {
                    NekoConfig.customProfileFrameCanvasSkin.setConfigInt(values.get(picked)[0]);
                    NekoConfig.customProfileFrameCanvasTheme.setConfigInt(values.get(picked)[1]);
                    onChanged.run();
                });
    }

    private static void pickWire(Context context, Runnable onChanged) {
        final ArrayList<String> names = new ArrayList<>();
        names.add(getString(R.string.CustomProfileFrameWireCurve));
        names.add(getString(R.string.CustomProfileFrameWireStraight));
        names.add(getString(R.string.CustomProfileFrameWireElbow));
        PopupHelper.show(names, getString(R.string.CustomProfileFrameWire),
                NekoConfig.customProfileFrameWireLine.Int(), context, picked -> {
                    NekoConfig.customProfileFrameWireLine.setConfigInt(picked);
                    onChanged.run();
                });
    }
}
