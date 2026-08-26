package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellColor;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellSelectBox;
import tw.nekomimi.nekogram.config.cell.ConfigCellSlider;
import tw.nekomimi.nekogram.config.cell.ConfigCellText;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextInput;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextDynamic;
import tw.nekomimi.nekogram.helpers.CustomProfileFrame;
import tw.nekomimi.nekogram.helpers.CustomProfileGfx;
import tw.nekomimi.nekogram.helpers.CustomProfileHelper;
import tw.nekomimi.nekogram.helpers.CustomProfileMedia;
import tw.nekomimi.nekogram.helpers.WorkshopHelper;
import tw.nekomimi.nekogram.helpers.frame.FrameGraphStore;

/**
 * The "Кастомизация" screen: everything that repaints the user's own profile page.
 * <p>
 * Unlike the other settings screens here the visible rows depend heavily on each other — picking a
 * gradient banner swaps in seven rows, picking a picture swaps in one — so instead of inserting and
 * removing rows one at a time this screen rebuilds the whole list from {@link #buildRows()} whenever
 * anything changes. With this many interdependent rows that is both shorter and harder to get wrong
 * than tracking each row's neighbour.
 */
@SuppressWarnings("unused")
public class CustomProfileActivity extends BaseNekoXSettingsActivity {

    private static final int REQUEST_BANNER = 1611;
    private static final int REQUEST_BACKGROUND = 1612;
    private static final int REQUEST_NAME_FONT = 1613;
    private static final int REQUEST_THOUGHT_FONT = 1614;

    /** The typeface index that means "the file this look ships" rather than a family everybody has. */
    private static final int FONT_BUNDLED = 7;

    private ListAdapter listAdapter;

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell headerBanner = new ConfigCellHeader(getString(R.string.CustomProfileHeaderBanner));
    private final AbstractConfigCell bannerTypeRow = new ConfigCellSelectBox(null, NekoConfig.customProfileBannerType, new String[]{
            getString(R.string.CustomProfileFillNone),
            getString(R.string.CustomProfileFillColor),
            getString(R.string.CustomProfileFillGradient),
            getString(R.string.CustomProfileFillImage),
            getString(R.string.CustomProfileFillVideo),
    }, null);
    private final AbstractConfigCell bannerColorRow = new ConfigCellColor(NekoConfig.customProfileBannerColor, 0xFF3390EC);
    private final AbstractConfigCell bannerPathRow = new ConfigCellText("customProfileBannerPath", null, () -> pickMedia(REQUEST_BANNER));
    private final AbstractConfigCell bannerAlphaRow = new ConfigCellSlider(NekoConfig.customProfileBannerAlpha, 0, 100, "%");
    private final AbstractConfigCell bannerDimRow = new ConfigCellSlider(NekoConfig.customProfileBannerDim, 0, 100, "%");
    private final AbstractConfigCell bannerFadeRow = new ConfigCellSelectBox(null, NekoConfig.customProfileBannerFade, fadeOptions(), null);
    private final AbstractConfigCell bannerFadeAngleRow = new ConfigCellSlider(NekoConfig.customProfileBannerFadeAngle, 0, 360, "°");
    private final AbstractConfigCell bannerFadeRadiusRow = new ConfigCellSlider(NekoConfig.customProfileBannerFadeRadius, 20, 200, "%");
    private final AbstractConfigCell bannerFadeCenterXRow = new ConfigCellSlider(NekoConfig.customProfileBannerFadeCenterX, 0, 100, "%");
    private final AbstractConfigCell bannerFadeCenterYRow = new ConfigCellSlider(NekoConfig.customProfileBannerFadeCenterY, 0, 100, "%");
    private final AbstractConfigCell showEmojiRow = new ConfigCellTextCheck(NekoConfig.customProfileShowEmoji);

    private final AbstractConfigCell headerGradient = new ConfigCellHeader(getString(R.string.CustomProfileHeaderGradient));
    private final AbstractConfigCell gradientRadialRow = new ConfigCellTextCheck(NekoConfig.customProfileGradientRadial);
    private final AbstractConfigCell gradientCountRow = new ConfigCellSelectBox(null, NekoConfig.customProfileGradientCount, new String[]{"2", "3"}, new int[]{2, 3}, null);
    private final AbstractConfigCell gradientColor1Row = new ConfigCellColor(NekoConfig.customProfileGradientColor1, 0xFF2B5876);
    private final AbstractConfigCell gradientColor2Row = new ConfigCellColor(NekoConfig.customProfileGradientColor2, 0xFF4E4376);
    private final AbstractConfigCell gradientColor3Row = new ConfigCellColor(NekoConfig.customProfileGradientColor3, 0xFF8E2DE2);
    private final AbstractConfigCell gradientAngleRow = new ConfigCellSlider(NekoConfig.customProfileGradientAngle, 0, 360, "°");
    private final AbstractConfigCell gradientRadiusRow = new ConfigCellSlider(NekoConfig.customProfileGradientRadius, 20, 200, "%");
    private final AbstractConfigCell gradientCenterXRow = new ConfigCellSlider(NekoConfig.customProfileGradientCenterX, 0, 100, "%");
    private final AbstractConfigCell gradientCenterYRow = new ConfigCellSlider(NekoConfig.customProfileGradientCenterY, 0, 100, "%");

    private final AbstractConfigCell headerBackground = new ConfigCellHeader(getString(R.string.CustomProfileHeaderBackground));
    // No gradient here — the list background is drawn behind the rows, and the gradient section
    // belongs to the banner. Hence the value map: the entries skip 2.
    private final AbstractConfigCell backgroundTypeRow = new ConfigCellSelectBox(null, NekoConfig.customProfileBackgroundType, new String[]{
            getString(R.string.CustomProfileFillNone),
            getString(R.string.CustomProfileFillColor),
            getString(R.string.CustomProfileFillImage),
            getString(R.string.CustomProfileFillVideo),
    }, new int[]{0, 1, 3, 4}, null);
    private final AbstractConfigCell backgroundColorRow = new ConfigCellColor(NekoConfig.customProfileBackgroundColor, 0xFF000000);
    private final AbstractConfigCell backgroundPathRow = new ConfigCellText("customProfileBackgroundPath", null, () -> pickMedia(REQUEST_BACKGROUND));
    private final AbstractConfigCell backgroundAlphaRow = new ConfigCellSlider(NekoConfig.customProfileBackgroundAlpha, 0, 100, "%");
    private final AbstractConfigCell backgroundDimRow = new ConfigCellSlider(NekoConfig.customProfileBackgroundDim, 0, 100, "%");
    private final AbstractConfigCell backgroundFadeRow = new ConfigCellSelectBox(null, NekoConfig.customProfileBackgroundFade, fadeOptions(), null);
    private final AbstractConfigCell backgroundFadeAngleRow = new ConfigCellSlider(NekoConfig.customProfileBackgroundFadeAngle, 0, 360, "°");
    private final AbstractConfigCell backgroundFadeRadiusRow = new ConfigCellSlider(NekoConfig.customProfileBackgroundFadeRadius, 20, 200, "%");
    private final AbstractConfigCell backgroundFadeCenterXRow = new ConfigCellSlider(NekoConfig.customProfileBackgroundFadeCenterX, 0, 100, "%");
    private final AbstractConfigCell backgroundFadeCenterYRow = new ConfigCellSlider(NekoConfig.customProfileBackgroundFadeCenterY, 0, 100, "%");

    private final AbstractConfigCell headerBlocks = new ConfigCellHeader(getString(R.string.CustomProfileHeaderBlocks));
    private final AbstractConfigCell blocksEnabledRow = new ConfigCellTextCheck(NekoConfig.customProfileBlocksEnabled);
    private final AbstractConfigCell blocksColorRow = new ConfigCellColor(NekoConfig.customProfileBlocksColor, 0xFF1C1C1E);
    private final AbstractConfigCell blocksAlphaRow = new ConfigCellSlider(NekoConfig.customProfileBlocksAlpha, 0, 100, "%");
    private final AbstractConfigCell blocksBlurRow = new ConfigCellSlider(NekoConfig.customProfileBlocksBlur, 0, 100, "%");

    private final AbstractConfigCell headerAvatar = new ConfigCellHeader(getString(R.string.CustomProfileHeaderAvatar));
    private final AbstractConfigCell avatarShapeRow = new ConfigCellSelectBox(null, NekoConfig.customProfileAvatarShape, new String[]{
            getString(R.string.CustomProfileShapeCircle),
            getString(R.string.CustomProfileShapeRounded),
            getString(R.string.CustomProfileShapeSquare),
            getString(R.string.CustomProfileShapeHexagon),
            getString(R.string.CustomProfileShapePentagon),
            getString(R.string.CustomProfileShapeStar),
            getString(R.string.CustomProfileShapeHeart),
            getString(R.string.CustomProfileShapeFlower),
    }, null);
    private final AbstractConfigCell avatarRadiusRow = new ConfigCellSlider(NekoConfig.customProfileAvatarRadius, 0, 50, "dp");
    private final AbstractConfigCell avatarSmoothingRow = new ConfigCellSlider(NekoConfig.customProfileAvatarSmoothing, 0, 100, "%");
    private final AbstractConfigCell avatarAlphaRow = new ConfigCellSlider(NekoConfig.customProfileAvatarAlpha, 0, 100, "%");
    private final AbstractConfigCell avatarDimRow = new ConfigCellSlider(NekoConfig.customProfileAvatarDim, 0, 100, "%");
    // Not one of the three fade modes: the avatar's fade is a strength, 0 off .. 100 fully
    // transparent at the rim, feathered inward from where avatarFadeRadius starts. That is what the
    // reference plugin stores in avatar_fade, and modelling it as a mode was why an imported look's
    // avatar came out unfeathered.
    private final AbstractConfigCell avatarFadeRow = new ConfigCellSlider(NekoConfig.customProfileAvatarFade, 0, 100, "%");
    private final AbstractConfigCell avatarFadeRadiusRow = new ConfigCellSlider(NekoConfig.customProfileAvatarFadeRadius, 0, 100, "%");
    private final AbstractConfigCell storyRingRow = new ConfigCellTextCheck(NekoConfig.customProfileStoryRing);

    private final AbstractConfigCell headerName = new ConfigCellHeader(getString(R.string.CustomProfileHeaderName));
    private final AbstractConfigCell nameColorEnabledRow = new ConfigCellTextCheck(NekoConfig.customProfileNameColorEnabled);
    private final AbstractConfigCell nameColorRow = new ConfigCellColor(NekoConfig.customProfileNameColor, 0xFFFFFFFF);
    private final AbstractConfigCell textColorEnabledRow = new ConfigCellTextCheck(NekoConfig.customProfileTextColorEnabled);
    private final AbstractConfigCell textColorRow = new ConfigCellColor(NekoConfig.customProfileTextColor, 0xFFFFFFFF);
    private final AbstractConfigCell nameGlowRow = new ConfigCellTextCheck(NekoConfig.customProfileNameGlow);
    private final AbstractConfigCell nameGlowColorRow = new ConfigCellColor(NekoConfig.customProfileNameGlowColor, 0xFF3390EC);
    private final AbstractConfigCell nameGlowRadiusRow = new ConfigCellSlider(NekoConfig.customProfileNameGlowRadius, 0, 40, "dp");
    private final AbstractConfigCell nameGlowStrengthRow = new ConfigCellSlider(NekoConfig.customProfileNameGlowStrength, 0, 20);
    private final AbstractConfigCell nameFxRow = new ConfigCellSelectBox(null, NekoConfig.customProfileNameFx, new String[]{
            getString(R.string.CustomProfileFxNone),
            getString(R.string.CustomProfileFxPulse),
            getString(R.string.CustomProfileFxGradient),
            getString(R.string.CustomProfileFxShimmer),
            getString(R.string.CustomProfileFxRainbow),
            getString(R.string.CustomProfileFxNeon),
            getString(R.string.CustomProfileFxFire),
            getString(R.string.CustomProfileFxIce),
    }, null);
    private final AbstractConfigCell nameFxSpeedRow = new ConfigCellSlider(NekoConfig.customProfileNameFxSpeed, 10, 300, "%");
    private final AbstractConfigCell nameFxAngleRow = new ConfigCellSlider(NekoConfig.customProfileNameFxAngle, 0, 360, "°");
    private final AbstractConfigCell nameFxColor1Row = new ConfigCellColor(NekoConfig.customProfileNameFxColor1, 0xFF3390EC);
    private final AbstractConfigCell nameFxColor2Row = new ConfigCellColor(NekoConfig.customProfileNameFxColor2, 0xFFB388FF);
    // The last entry is index 7 — the font file a workshop look brought with it. Picking it without
    // such a file simply leaves the name in the view's own typeface, same as "Default".
    private final AbstractConfigCell nameFontRow = new ConfigCellSelectBox(null, NekoConfig.customProfileNameFont, new String[]{
            getString(R.string.CustomProfileFontDefault),
            getString(R.string.CustomProfileFontBold),
            getString(R.string.CustomProfileFontSerif),
            getString(R.string.CustomProfileFontMono),
            getString(R.string.CustomProfileFontSans),
            getString(R.string.CustomProfileFontLight),
            getString(R.string.CustomProfileFontCondensed),
            getString(R.string.CustomProfileFontBundled),
    }, null);
    private final AbstractConfigCell nameSizeRow = new ConfigCellSlider(NekoConfig.customProfileNameSize, 50, 200, "%");
    // Only shown once the typeface index says "this look's own file": every other index names a
    // family the phone already has and there is nothing to pick.
    private final AbstractConfigCell nameFontPathRow = new ConfigCellTextDynamic(
            () -> getString(R.string.CustomProfileFontFile),
            () -> fileName(NekoConfig.customProfileNameFontPath.String()),
            () -> pickFont(REQUEST_NAME_FONT));

    // The frame the avatar wears. Its own gallery and its own editor; taking it off is one row
    // rather than a trip through either.
    private final AbstractConfigCell headerFrame = new ConfigCellHeader(getString(R.string.CustomProfileHeaderFrame));
    private final AbstractConfigCell frameGalleryRow = new ConfigCellText("CustomProfileFrames",
            () -> presentFragment(new WorkshopActivity(WorkshopHelper.KIND_FRAME)));
    private final AbstractConfigCell frameStudioRow = new ConfigCellText("CustomProfileFrameStudio",
            () -> presentFragment(new FrameStudioActivity()));
    private final AbstractConfigCell frameClearRow = new ConfigCellTextDynamic(
            () -> getString(R.string.CustomProfileFrameClear),
            () -> frameSummary(), this::clearFrame);

    private final AbstractConfigCell headerLayoutRow = new ConfigCellText("CustomProfileHeaderLayout",
            () -> presentFragment(new CustomProfileHeaderActivity()));
    private final AbstractConfigCell paletteRow = new ConfigCellText("CustomProfilePalette",
            () -> presentFragment(new CustomProfilePaletteActivity()));
    private final AbstractConfigCell extraRowsRow = new ConfigCellText("CustomProfileExtraRows",
            () -> presentFragment(new CustomProfileBlocksActivity()));
    private final AbstractConfigCell profileRowsRow = new ConfigCellText("CustomProfileRows",
            () -> presentFragment(new CustomProfileRowsActivity()));

    // The free-form avatar outline. A look can carry one but nothing here can draw one, so the row
    // says whether there is one and offers to take it off — which is the whole of what can be done
    // with it without a canvas to trace on.
    private final AbstractConfigCell avatarOutlineRow = new ConfigCellTextDynamic(
            () -> getString(R.string.CustomProfileAvatarOutline),
            () -> outlineSummary(), this::clearOutline);

    // The thought: the bubble beside the avatar. Its typeface follows the name's unless told
    // otherwise, which is the reference's own default and the reason the font row is hidden until
    // the copy switch is turned off.
    private final AbstractConfigCell headerThought = new ConfigCellHeader(getString(R.string.CustomProfileHeaderThought));
    private final AbstractConfigCell thoughtTextRow = new ConfigCellTextInput(null, NekoConfig.customProfileThoughtText,
            getString(R.string.CustomProfileThoughtHint), null);
    private final AbstractConfigCell thoughtTextColorRow = new ConfigCellColor(NekoConfig.customProfileThoughtTextColor, 0xFFFFFFFF);
    private final AbstractConfigCell thoughtBackgroundRow = new ConfigCellColor(NekoConfig.customProfileThoughtBackground, 0xCC0A0A1D);
    private final AbstractConfigCell thoughtFontCopyRow = new ConfigCellTextCheck(NekoConfig.customProfileThoughtFontCopy);
    private final AbstractConfigCell thoughtFontPathRow = new ConfigCellTextDynamic(
            () -> getString(R.string.CustomProfileFontFile),
            () -> fileName(NekoConfig.customProfileThoughtFontPath.String()),
            () -> pickFont(REQUEST_THOUGHT_FONT));
    private final AbstractConfigCell thoughtFontRow = new ConfigCellSelectBox(null, NekoConfig.customProfileThoughtFont, new String[]{
            getString(R.string.CustomProfileFontDefault),
            getString(R.string.CustomProfileFontBold),
            getString(R.string.CustomProfileFontSerif),
            getString(R.string.CustomProfileFontMono),
            getString(R.string.CustomProfileFontSans),
            getString(R.string.CustomProfileFontLight),
            getString(R.string.CustomProfileFontCondensed),
            getString(R.string.CustomProfileFontBundled),
    }, null);

    private final AbstractConfigCell exportRow = new ConfigCellText("CustomProfileExport", null, this::exportSettings);
    private final AbstractConfigCell importRow = new ConfigCellText("CustomProfileImport", null, this::importSettings);
    private final AbstractConfigCell resetRow = new ConfigCellText("CustomProfileReset", null, this::resetSettings);

    public CustomProfileActivity() {
        buildRows();
    }

    private static String[] fadeOptions() {
        return new String[]{
                getString(R.string.CustomProfileFadeNone),
                getString(R.string.CustomProfileFadeLinear),
                getString(R.string.CustomProfileFadeRadial),
        };
    }
    /**
     * Rebuilds the visible list. Every row is re-appended from scratch, so a row's neighbours never
     * have to know about it; the trade-off is that callers must follow this with a full adapter
     * refresh. A divider always closes a section because {@link CellGroup#needSetDivider} reads the
     * row that follows.
     */
    private void buildRows() {
        cellGroup.rows.clear();

        final int bannerType = NekoConfig.customProfileBannerType.Int();
        cellGroup.appendCell(headerBanner);
        cellGroup.appendCell(bannerTypeRow);
        if (bannerType == 1) {
            cellGroup.appendCell(bannerColorRow);
        }
        if (bannerType == 3 || bannerType == 4) {
            cellGroup.appendCell(bannerPathRow);
        }
        if (bannerType != 0) {
            cellGroup.appendCell(bannerAlphaRow);
            cellGroup.appendCell(bannerDimRow);
            cellGroup.appendCell(bannerFadeRow);
            // Both fade kinds use the radius and the centre — for a linear fade the radius is the
            // length of the run from opaque to clear and the centre is the point it is struck
            // through, for a radial one they are the circle. Only the angle is linear-only.
            final int fade = NekoConfig.customProfileBannerFade.Int();
            if (fade != 0) {
                if (fade == 1) {
                    cellGroup.appendCell(bannerFadeAngleRow);
                }
                cellGroup.appendCell(bannerFadeRadiusRow);
                cellGroup.appendCell(bannerFadeCenterXRow);
                cellGroup.appendCell(bannerFadeCenterYRow);
            }
        }
        cellGroup.appendCell(showEmojiRow);
        cellGroup.appendCell(new ConfigCellDivider());

        if (bannerType == 2) {
            cellGroup.appendCell(headerGradient);
            cellGroup.appendCell(gradientRadialRow);
            cellGroup.appendCell(gradientCountRow);
            cellGroup.appendCell(gradientColor1Row);
            cellGroup.appendCell(gradientColor2Row);
            if (NekoConfig.customProfileGradientCount.Int() >= 3) {
                cellGroup.appendCell(gradientColor3Row);
            }
            // The radius is the size of both kinds: the circle for a radial gradient, the length of
            // the run for a linear one. Only a radial gradient can be moved off centre — a linear one
            // is always struck through the middle of the banner, as in the reference plugin.
            if (NekoConfig.customProfileGradientRadial.Bool()) {
                cellGroup.appendCell(gradientRadiusRow);
                cellGroup.appendCell(gradientCenterXRow);
                cellGroup.appendCell(gradientCenterYRow);
            } else {
                cellGroup.appendCell(gradientAngleRow);
                cellGroup.appendCell(gradientRadiusRow);
            }
            cellGroup.appendCell(new ConfigCellDivider());
        }

        final int backgroundType = NekoConfig.customProfileBackgroundType.Int();
        cellGroup.appendCell(headerBackground);
        cellGroup.appendCell(backgroundTypeRow);
        if (backgroundType == 1) {
            cellGroup.appendCell(backgroundColorRow);
        }
        if (backgroundType == 3 || backgroundType == 4) {
            cellGroup.appendCell(backgroundPathRow);
        }
        if (backgroundType != 0) {
            cellGroup.appendCell(backgroundAlphaRow);
            cellGroup.appendCell(backgroundDimRow);
            cellGroup.appendCell(backgroundFadeRow);
            final int fade = NekoConfig.customProfileBackgroundFade.Int();
            if (fade != 0) {
                if (fade == 1) {
                    cellGroup.appendCell(backgroundFadeAngleRow);
                }
                cellGroup.appendCell(backgroundFadeRadiusRow);
                cellGroup.appendCell(backgroundFadeCenterXRow);
                cellGroup.appendCell(backgroundFadeCenterYRow);
            }
        }
        cellGroup.appendCell(new ConfigCellDivider());

        cellGroup.appendCell(headerBlocks);
        cellGroup.appendCell(blocksEnabledRow);
        if (NekoConfig.customProfileBlocksEnabled.Bool()) {
            cellGroup.appendCell(blocksColorRow);
            cellGroup.appendCell(blocksAlphaRow);
            cellGroup.appendCell(blocksBlurRow);
        }
        cellGroup.appendCell(new ConfigCellDivider());

        cellGroup.appendCell(headerAvatar);
        cellGroup.appendCell(avatarShapeRow);
        if (NekoConfig.customProfileAvatarShape.Int() == 1) {
            cellGroup.appendCell(avatarRadiusRow);
            cellGroup.appendCell(avatarSmoothingRow);
        }
        cellGroup.appendCell(avatarAlphaRow);
        cellGroup.appendCell(avatarDimRow);
        cellGroup.appendCell(avatarFadeRow);
        if (NekoConfig.customProfileAvatarFade.Int() != 0) {
            cellGroup.appendCell(avatarFadeRadiusRow);
        }
        cellGroup.appendCell(storyRingRow);
        if (!NekoConfig.customProfileAvatarPoints.String().isEmpty()) {
            cellGroup.appendCell(avatarOutlineRow);
        }
        cellGroup.appendCell(new ConfigCellDivider());

        cellGroup.appendCell(headerFrame);
        cellGroup.appendCell(frameGalleryRow);
        cellGroup.appendCell(frameStudioRow);
        cellGroup.appendCell(frameClearRow);
        cellGroup.appendCell(new ConfigCellDivider());

        buildNameRows();
        buildThoughtRows();

        // The four parts of a look that are lists rather than settings, each behind its own screen.
        cellGroup.appendCell(headerLayoutRow);
        cellGroup.appendCell(paletteRow);
        cellGroup.appendCell(extraRowsRow);
        cellGroup.appendCell(profileRowsRow);
        cellGroup.appendCell(new ConfigCellDivider());

        cellGroup.appendCell(exportRow);
        cellGroup.appendCell(importRow);
        cellGroup.appendCell(resetRow);
        cellGroup.appendCell(new ConfigCellDivider());

        addRowsToMap(cellGroup);
    }
    /** The bubble's own section: its text first, then the two colours, then whose typeface it uses. */
    private void buildThoughtRows() {
        cellGroup.appendCell(headerThought);
        cellGroup.appendCell(thoughtTextRow);
        cellGroup.appendCell(thoughtTextColorRow);
        cellGroup.appendCell(thoughtBackgroundRow);
        cellGroup.appendCell(thoughtFontCopyRow);
        if (!NekoConfig.customProfileThoughtFontCopy.Bool()) {
            cellGroup.appendCell(thoughtFontRow);
            if (NekoConfig.customProfileThoughtFont.Int() == FONT_BUNDLED) {
                cellGroup.appendCell(thoughtFontPathRow);
            }
        }
        cellGroup.appendCell(new ConfigCellDivider());
    }

    private void buildNameRows() {
        cellGroup.appendCell(headerName);
        cellGroup.appendCell(nameColorEnabledRow);
        if (NekoConfig.customProfileNameColorEnabled.Bool()) {
            cellGroup.appendCell(nameColorRow);
        }
        cellGroup.appendCell(textColorEnabledRow);
        if (NekoConfig.customProfileTextColorEnabled.Bool()) {
            cellGroup.appendCell(textColorRow);
        }
        cellGroup.appendCell(nameGlowRow);
        if (NekoConfig.customProfileNameGlow.Bool()) {
            cellGroup.appendCell(nameGlowColorRow);
            cellGroup.appendCell(nameGlowRadiusRow);
            cellGroup.appendCell(nameGlowStrengthRow);
        }
        cellGroup.appendCell(nameFxRow);
        final int fx = NekoConfig.customProfileNameFx.Int();
        if (fx != 0) {
            cellGroup.appendCell(nameFxSpeedRow);
        }
        // Only the two-colour effects have anything to point an angle at or a palette to pick.
        if (fx == 2 || fx == 3) {
            cellGroup.appendCell(nameFxAngleRow);
            cellGroup.appendCell(nameFxColor1Row);
            cellGroup.appendCell(nameFxColor2Row);
        }
        cellGroup.appendCell(nameFontRow);
        if (NekoConfig.customProfileNameFont.Int() == FONT_BUNDLED) {
            cellGroup.appendCell(nameFontPathRow);
        }
        cellGroup.appendCell(nameSizeRow);
        cellGroup.appendCell(new ConfigCellDivider());
    }

    @SuppressLint("NotifyDataSetChanged")
    private void rebuild() {
        buildRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void pickMedia(int requestCode) {
        if (getParentActivity() == null) {
            return;
        }
        // Whichever slot is being picked for decides the filter: both can hold an animation, so the
        // picker must offer videos for the background too, not only for the banner.
        final ConfigItem type = requestCode == REQUEST_BANNER
                ? NekoConfig.customProfileBannerType : NekoConfig.customProfileBackgroundType;
        final boolean video = type.Int() == 4;
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(video ? "video/*" : "image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception ignore) {
        }
    }

    /**
     * Picks a typeface file. Any file is offered rather than a mime filter: the pickers on most
     * phones do not classify {@code .ttf} and {@code .otf} consistently, and a file that turns out
     * not to be a font simply fails to load rather than doing any harm.
     */
    private void pickFont(int requestCode) {
        if (getParentActivity() == null) {
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception ignore) {
        }
    }

    private static String fileName(String path) {
        if (path == null || path.isEmpty()) {
            return getString(R.string.CustomProfileFontFileNone);
        }
        final int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        final String name = slash < 0 ? path : path.substring(slash + 1);
        return name.length() > 22 ? name.substring(0, 21) + "…" : name;
    }

    private static String frameSummary() {
        final int layers = CustomProfileFrame.frame().layers().size();
        return layers == 0 ? getString(R.string.CustomProfileFrameNone) : String.valueOf(layers);
    }

    private void clearFrame() {
        if (CustomProfileFrame.frame().isEmpty()) {
            return;
        }
        FrameGraphStore.clear();
        rebuild();
    }

    private static String outlineSummary() {
        final float[] points = CustomProfileGfx.parsePoints(NekoConfig.customProfileAvatarPoints.String());
        return points.length < 6 ? getString(R.string.CustomProfileFrameNone)
                : String.valueOf(points.length / 2);
    }

    private void clearOutline() {
        NekoConfig.customProfileAvatarPoints.setConfigString("");
        CustomProfileHelper.onSettingsChanged();
        rebuild();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_NAME_FONT || requestCode == REQUEST_THOUGHT_FONT) {
            final boolean forName = requestCode == REQUEST_NAME_FONT;
            final String path = CustomProfileHelper.importFont(data.getData(), forName);
            if (path == null) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.UnknownError)).show();
                return;
            }
            (forName ? NekoConfig.customProfileNameFontPath
                    : NekoConfig.customProfileThoughtFontPath).setConfigString(path);
            final int slot = forName
                    ? CustomProfileMedia.SLOT_FONT : CustomProfileMedia.SLOT_THOUGHT_FONT;
            // The descriptor still names the font being replaced, and the new one exists nowhere but
            // this phone until the upload lands — so readers are told to stop looking for either
            // rather than being served the old one meanwhile.
            CustomProfileMedia.forget(slot);
            CustomProfileMedia.publishAsync(slot, path);
            CustomProfileHelper.onSettingsChanged();
            rebuild();
            return;
        }
        if (requestCode != REQUEST_BANNER && requestCode != REQUEST_BACKGROUND) {
            return;
        }
        final boolean banner = requestCode == REQUEST_BANNER;
        final String path = CustomProfileHelper.importMedia(data.getData(), banner);
        if (path == null) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.UnknownError)).show();
            return;
        }
        (banner ? NekoConfig.customProfileBannerPath : NekoConfig.customProfileBackgroundPath).setConfigString(path);
        CustomProfileHelper.onSettingsChanged();
        rebuild();
    }

    private void exportSettings() {
        CustomProfileHelper.exportToClipboard();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.copy, getString(R.string.CustomProfileExported)).show();
    }

    private void importSettings() {
        if (CustomProfileHelper.importFromClipboard()) {
            rebuild();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.CustomProfileImported)).show();
        } else {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.CustomProfileImportFailed)).show();
        }
    }

    private void resetSettings() {
        CustomProfileHelper.resetAll();
        rebuild();
    }
    @Override
    protected RecyclerListView.SelectionAdapter getListAdapter() {
        return listAdapter;
    }

    @Override
    protected CellGroup getCellGroup() {
        return cellGroup;
    }

    @Override
    protected String getSettingsPrefix() {
        return "custom_profile";
    }

    @SuppressLint("NewApi")
    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        setupDefaultListeners();

        cellGroup.callBackSettingsChanged = (key, newValue) -> {
            CustomProfileHelper.onSettingsChanged();
            // Nearly every key here either shows or hides a row, so a blanket rebuild is cheaper to
            // reason about than a list of the keys that happen to be structural.
            rebuild();
        };

        return superView;
    }

    @Override
    public int getBaseGuid() {
        return 15000;
    }

    @Override
    public int getDrawable() {
        return R.drawable.sovietgram_exclusive;
    }

    @Override
    public String getTitle() {
        return getString(R.string.CustomProfileTitle);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }
    }
}
