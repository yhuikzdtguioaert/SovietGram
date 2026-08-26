package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;

import java.util.ArrayList;
import java.util.Locale;

import tw.nekomimi.nekogram.helpers.CustomProfileAnchors;
import tw.nekomimi.nekogram.helpers.CustomProfileHeaderLayout;
import tw.nekomimi.nekogram.helpers.PopupHelper;

/**
 * Where the header's parts sit: the avatar, the name, the status, the buttons.
 *
 * <p>Two screens in one class. Opened with no part, it shows the preset picker, one row per part and
 * the handful of settings that belong to the header as a whole; opened with a part, it shows that
 * part's own offsets, turn and size, and the anchor that ties it to another part.
 *
 * <p>Editing anything here switches the look to the custom preset, because there is nothing else it
 * could mean: the two built-in presets are fixed by definition.
 */
public class CustomProfileHeaderActivity extends CustomProfileListActivity {

    private static final int PART_NONE = -1;

    private final int part;

    public CustomProfileHeaderActivity() {
        this(PART_NONE);
    }

    private CustomProfileHeaderActivity(int part) {
        this.part = part;
    }

    @Override
    protected String title() {
        return part == PART_NONE
                ? getString(R.string.CustomProfileHeaderLayout)
                : partName(part);
    }

    @Override
    protected void buildRows() {
        if (part == PART_NONE) {
            buildTop();
        } else {
            buildPart();
        }
    }

    // ---------------------------------------------------------------- the whole header

    private void buildTop() {
        final int preset = CustomProfileHeaderLayout.preset();
        header(getString(R.string.CustomProfileHeaderLayout));
        setting(getString(R.string.CustomProfileHeaderPreset), presetName(preset), this::pickPreset);
        shadow();

        if (preset != CustomProfileHeaderLayout.PRESET_CUSTOM) {
            info(getString(R.string.CustomProfileHeaderPresetInfo));
            return;
        }

        header(getString(R.string.CustomProfileHeaderParts));
        final CustomProfileHeaderLayout.Element[] elements = CustomProfileHeaderLayout.elements();
        for (int i = 0; i < elements.length; i++) {
            final int at = i;
            setting(partName(i), summary(elements[i], i),
                    () -> presentFragment(new CustomProfileHeaderActivity(at)));
        }
        shadow();

        final CustomProfileHeaderLayout.Extras extras = CustomProfileHeaderLayout.ownExtras();
        header(getString(R.string.CustomProfileHeaderExtras));
        setting(getString(R.string.CustomProfileHeaderNameAnchor),
                nameAnchorName(extras.nameAnchor), this::pickNameAnchor);
        check(getString(R.string.CustomProfileHeaderActionsPlain), extras.plainContent,
                () -> CustomProfileHeaderLayout.setExtras(CustomProfileHeaderLayout.makeExtras(
                        extras.nameAnchor, extras.anchorAlways, !extras.plainContent,
                        extras.contentScaleX, extras.contentScaleY)));
        setting(getString(R.string.CustomProfileHeaderActionsScaleX),
                percent(extras.contentScaleX),
                () -> askNumber(getString(R.string.CustomProfileHeaderActionsScaleX),
                        extras.contentScaleX, CustomProfileHeaderLayout.CONTENT_SCALE_MIN,
                        CustomProfileHeaderLayout.CONTENT_SCALE_MAX,
                        value -> CustomProfileHeaderLayout.setExtras(
                                CustomProfileHeaderLayout.makeExtras(extras.nameAnchor,
                                        extras.anchorAlways, extras.plainContent, value,
                                        extras.contentScaleY))));
        setting(getString(R.string.CustomProfileHeaderActionsScaleY),
                percent(extras.contentScaleY),
                () -> askNumber(getString(R.string.CustomProfileHeaderActionsScaleY),
                        extras.contentScaleY, CustomProfileHeaderLayout.CONTENT_SCALE_MIN,
                        CustomProfileHeaderLayout.CONTENT_SCALE_MAX,
                        value -> CustomProfileHeaderLayout.setExtras(
                                CustomProfileHeaderLayout.makeExtras(extras.nameAnchor,
                                        extras.anchorAlways, extras.plainContent,
                                        extras.contentScaleX, value))));
        check(getString(R.string.CustomProfileHeaderAnchorAlways), extras.anchorAlways,
                () -> CustomProfileHeaderLayout.setExtras(CustomProfileHeaderLayout.makeExtras(
                        extras.nameAnchor, !extras.anchorAlways, extras.plainContent,
                        extras.contentScaleX, extras.contentScaleY)));
        info(getString(R.string.CustomProfileHeaderAnchorAlwaysInfo));

        setting(getString(R.string.CustomProfileHeaderReset), null, this::confirmReset);
        shadow();
    }

    private void pickPreset() {
        if (getParentActivity() == null) {
            return;
        }
        final ArrayList<String> names = new ArrayList<>();
        names.add(presetName(CustomProfileHeaderLayout.PRESET_TELEGRAM));
        names.add(presetName(CustomProfileHeaderLayout.PRESET_LEFT));
        names.add(presetName(CustomProfileHeaderLayout.PRESET_CUSTOM));
        final int[] values = {CustomProfileHeaderLayout.PRESET_TELEGRAM,
                CustomProfileHeaderLayout.PRESET_LEFT, CustomProfileHeaderLayout.PRESET_CUSTOM};
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == CustomProfileHeaderLayout.preset()) {
                checked = i;
            }
        }
        PopupHelper.show(names, getString(R.string.CustomProfileHeaderPreset), checked,
                getParentActivity(), index -> {
                    CustomProfileHeaderLayout.setPreset(values[index]);
                    rebuild();
                });
    }

    private void pickNameAnchor() {
        if (getParentActivity() == null) {
            return;
        }
        final CustomProfileHeaderLayout.Extras extras = CustomProfileHeaderLayout.ownExtras();
        final ArrayList<String> names = new ArrayList<>();
        for (int i = CustomProfileHeaderLayout.NAME_CENTER; i <= CustomProfileHeaderLayout.NAME_RIGHT; i++) {
            names.add(nameAnchorName(i));
        }
        PopupHelper.show(names, getString(R.string.CustomProfileHeaderNameAnchor),
                extras.nameAnchor, getParentActivity(), index -> {
                    CustomProfileHeaderLayout.setExtras(CustomProfileHeaderLayout.makeExtras(
                            index, extras.anchorAlways, extras.plainContent,
                            extras.contentScaleX, extras.contentScaleY));
                    rebuild();
                });
    }

    private void confirmReset() {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.CustomProfileHeaderReset))
                .setMessage(getString(R.string.CustomProfileHeaderResetInfo))
                .setPositiveButton(getString(R.string.Reset), (dialog, which) -> {
                    CustomProfileHeaderLayout.resetCustom();
                    rebuild();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    // ---------------------------------------------------------------- one part

    private void buildPart() {
        final CustomProfileHeaderLayout.Element element = CustomProfileHeaderLayout.elements()[part];

        header(getString(R.string.CustomProfileHeaderOffset));
        // Kept as tenths of a percent of the header's width, which is what the layout stores and
        // what makes the same look sit the same on a phone and on a tablet.
        setting(getString(R.string.CustomProfileHeaderShiftX), percent(Math.round(element.x * 100f)),
                () -> askNumber(getString(R.string.CustomProfileHeaderShiftX),
                        Math.round(element.x * 100f), -45, 45,
                        value -> put(CustomProfileHeaderLayout.makeElement(value / 100f, element.y,
                                element.rotate, element.scaleX, element.scaleY))));
        setting(getString(R.string.CustomProfileHeaderShiftY), percent(Math.round(element.y * 100f)),
                () -> askNumber(getString(R.string.CustomProfileHeaderShiftY),
                        Math.round(element.y * 100f), -45, 45,
                        value -> put(CustomProfileHeaderLayout.makeElement(element.x, value / 100f,
                                element.rotate, element.scaleX, element.scaleY))));
        setting(getString(R.string.CustomProfileHeaderRotate),
                Math.round(element.rotate) + "°",
                () -> askNumber(getString(R.string.CustomProfileHeaderRotate),
                        Math.round(element.rotate), -180, 180,
                        value -> put(CustomProfileHeaderLayout.makeElement(element.x, element.y,
                                value, element.scaleX, element.scaleY))));
        setting(getString(R.string.CustomProfileHeaderScaleX),
                percent(Math.round(element.scaleX * 100f)),
                () -> askNumber(getString(R.string.CustomProfileHeaderScaleX),
                        Math.round(element.scaleX * 100f), 10, 300,
                        value -> put(CustomProfileHeaderLayout.makeElement(element.x, element.y,
                                element.rotate, value / 100f, element.scaleY))));
        setting(getString(R.string.CustomProfileHeaderScaleY),
                percent(Math.round(element.scaleY * 100f)),
                () -> askNumber(getString(R.string.CustomProfileHeaderScaleY),
                        Math.round(element.scaleY * 100f), 10, 300,
                        value -> put(CustomProfileHeaderLayout.makeElement(element.x, element.y,
                                element.rotate, element.scaleX, value / 100f))));
        shadow();

        final int target = CustomProfileHeaderLayout.anchorOf(part,
                CustomProfileHeaderLayout.ANCHOR_TARGET);
        header(getString(R.string.CustomProfileHeaderAnchor));
        setting(getString(R.string.CustomProfileHeaderAnchorTarget), targetName(target),
                this::pickTarget);
        if (target != CustomProfileAnchors.TARGET_NONE) {
            pointRow(R.string.CustomProfileHeaderAnchorToX, CustomProfileHeaderLayout.ANCHOR_TO_X);
            pointRow(R.string.CustomProfileHeaderAnchorToY, CustomProfileHeaderLayout.ANCHOR_TO_Y);
            pointRow(R.string.CustomProfileHeaderAnchorFromX, CustomProfileHeaderLayout.ANCHOR_FROM_X);
            pointRow(R.string.CustomProfileHeaderAnchorFromY, CustomProfileHeaderLayout.ANCHOR_FROM_Y);
            offsetRow(R.string.CustomProfileHeaderAnchorOffsetX,
                    CustomProfileHeaderLayout.ANCHOR_OFFSET_X);
            offsetRow(R.string.CustomProfileHeaderAnchorOffsetY,
                    CustomProfileHeaderLayout.ANCHOR_OFFSET_Y);
        }
        info(getString(R.string.CustomProfileHeaderAnchorInfo));
    }

    private void pointRow(int titleRes, int key) {
        final int value = CustomProfileHeaderLayout.anchorOf(part, key);
        setting(getString(titleRes), pointName(value), () -> {
            if (getParentActivity() == null) {
                return;
            }
            final ArrayList<String> names = new ArrayList<>();
            for (int i = CustomProfileAnchors.POINT_START; i <= CustomProfileAnchors.POINT_END; i++) {
                names.add(pointName(i));
            }
            PopupHelper.show(names, getString(titleRes), value, getParentActivity(), index -> {
                CustomProfileHeaderLayout.setAnchor(part, key, index);
                rebuild();
            });
        });
    }

    private void offsetRow(int titleRes, int key) {
        final int value = CustomProfileHeaderLayout.anchorOf(part, key);
        setting(getString(titleRes), value + " dp",
                () -> askNumber(getString(titleRes), value,
                        -CustomProfileAnchors.OFFSET_LIMIT, CustomProfileAnchors.OFFSET_LIMIT,
                        picked -> CustomProfileHeaderLayout.setAnchor(part, key, picked)));
    }

    private void pickTarget() {
        if (getParentActivity() == null) {
            return;
        }
        final ArrayList<String> names = new ArrayList<>();
        for (int i = CustomProfileAnchors.TARGET_NONE; i <= CustomProfileAnchors.TARGET_LAST; i++) {
            names.add(targetName(i));
        }
        PopupHelper.show(names, getString(R.string.CustomProfileHeaderAnchorTarget),
                CustomProfileHeaderLayout.anchorOf(part, CustomProfileHeaderLayout.ANCHOR_TARGET),
                getParentActivity(), index -> {
                    CustomProfileHeaderLayout.setAnchor(part,
                            CustomProfileHeaderLayout.ANCHOR_TARGET, index);
                    rebuild();
                });
    }

    private void put(CustomProfileHeaderLayout.Element element) {
        CustomProfileHeaderLayout.setElement(part, element);
    }

    // ---------------------------------------------------------------- labels

    private static String summary(CustomProfileHeaderLayout.Element element, int part) {
        if (element.isIdle()
                && CustomProfileHeaderLayout.anchorOf(part,
                CustomProfileHeaderLayout.ANCHOR_TARGET) == CustomProfileAnchors.TARGET_NONE) {
            return getString(R.string.CustomProfileHeaderPartIdle);
        }
        return String.format(Locale.US, "%d, %d",
                Math.round(element.x * 100f), Math.round(element.y * 100f));
    }

    private static String percent(int value) {
        return value + "%";
    }

    private static String partName(int part) {
        return getString("CustomProfileHeaderPart_" + CustomProfileAnchors.name(part));
    }

    private static String presetName(int preset) {
        return getString(switch (preset) {
            case CustomProfileHeaderLayout.PRESET_LEFT -> R.string.CustomProfileHeaderPresetLeft;
            case CustomProfileHeaderLayout.PRESET_CUSTOM -> R.string.CustomProfileHeaderPresetCustom;
            default -> R.string.CustomProfileHeaderPresetTelegram;
        });
    }

    private static String nameAnchorName(int value) {
        return getString(switch (value) {
            case CustomProfileHeaderLayout.NAME_LEFT -> R.string.CustomProfileHeaderNameAnchorLeft;
            case CustomProfileHeaderLayout.NAME_RIGHT -> R.string.CustomProfileHeaderNameAnchorRight;
            default -> R.string.CustomProfileHeaderNameAnchorCenter;
        });
    }

    private static String pointName(int point) {
        return getString(switch (point) {
            case CustomProfileAnchors.POINT_START -> R.string.CustomProfileHeaderPointStart;
            case CustomProfileAnchors.POINT_END -> R.string.CustomProfileHeaderPointEnd;
            default -> R.string.CustomProfileHeaderPointCenter;
        });
    }

    private static String targetName(int target) {
        if (target == CustomProfileAnchors.TARGET_NONE) {
            return getString(R.string.CustomProfileHeaderTargetNone);
        }
        if (target == CustomProfileAnchors.TARGET_SCREEN) {
            return getString(R.string.CustomProfileHeaderTargetScreen);
        }
        if (target == CustomProfileAnchors.TARGET_THOUGHT) {
            return getString(R.string.CustomProfileHeaderTargetThought);
        }
        final int other = CustomProfileAnchors.partOf(target);
        return other < 0 ? getString(R.string.CustomProfileHeaderTargetNone) : partName(other);
    }
}
