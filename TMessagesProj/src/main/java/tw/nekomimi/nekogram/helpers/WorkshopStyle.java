package tw.nekomimi.nekogram.helpers;

import android.graphics.Color;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.frame.FrameGraphStore;
import tw.nekomimi.nekogram.config.ConfigItem;

/**
 * Turns a workshop entry into the local Custom Profile settings.
 * <p>
 * The two sides describe the same look with different vocabularies, so this is a translation rather
 * than a copy. What has to be translated:
 * <ul>
 *     <li>the fill mode enums disagree — the workshop numbers a banner 0 picture / 1 colour /
 *         2 gradient, we number it 0 none / 1 colour / 2 gradient / 3 picture / 4 video, and their
 *         single "picture" covers animations, which we have to split into 3 and 4;</li>
 *     <li>the fade and gradient centres are fractions of a side there and integer percents here, so
 *         that one slider type can drive every value in the screen;</li>
 *     <li>three separate profile text colour toggles collapse into our one, with the same priority the
 *         reference itself applies: text, then hint, then action;</li>
 *     <li>the typeface is an index into their own list, which is not ours — see {@link #FONTS} — and
 *         their last entry means "the font file this look ships", which becomes our index 7 plus a
 *         stored path;</li>
 *     <li>the glow strength runs to 100 there and to 20 here, so it is rescaled rather than clamped.</li>
 * </ul>
 * <p>
 * <b>Defaults matter as much as the values.</b> A key a work leaves out has to fall back to the
 * reference's default and not to ours, or a look that never mentions its fade angle installs at our
 * 180 and fades the wrong way. Every {@code opt*} call below therefore carries the reference's own
 * default, which is why some of them disagree with the matching {@link NekoConfig} declaration.
 * <p>
 * The units and the enums here are not guesses; they were measured over the whole published gallery
 * (131 works). Every numeric key arrives as an {@code int} in the range this code clamps to, except the
 * four centres and {@code grad_cx/cy}, which are always fractions of a side. {@code banner_mode} is 0
 * in all 89 works that ship a banner asset and never 0 in the 29 that ship none, and {@code bg_mode} is
 * 1 in all 107 that ship a background asset and 0 in the 23 that do not — so 0/1/2 is
 * picture/colour/gradient for the banner and 0/1 is colour/picture for the background, and
 * {@code banner_sound} appears only on mode 0. Worth leaving written down: the mapping looks arbitrary
 * enough to be "corrected" by somebody who has not counted.
 * <p>
 * {@code avatar_custom} — the free-form avatar outline, in 71% of the gallery — installs as our shape
 * 8 plus the points themselves, and wins over {@code avatar_shape} exactly as it does there.
 * <p>
 * Deliberately not imported, each because the effect does not exist here rather than because it was
 * overlooked:
 * <ul>
 *     <li>{@code grad_dir} — read only when the reference's own editor rebuilds its sliders; it does
 *         not reach the renderer at all (and is 0 in every published work);</li>
 *     <li>{@code bg_compat}, {@code blocks_depth} — knobs for how deeply their plugin reaches into the
 *         host app's view tree, which is not a property of the look;</li>
 *     <li>{@code name_glow_mode} — mode 0 hangs a second blurred copy of the name off the view
 *         hierarchy; we draw the shadow-layer glow, which is their mode 1, and every published work
 *         is mode 1;</li>
 *     <li>{@code banner_sound}, {@code bg_sound}, {@code has_audio}, {@code audio_volume} — audio off
 *         the banner video, which we play muted;</li>
 *     <li>{@code frame_spec}, {@code frame_file} — an avatar frame overlay. Its {@code src} is an
 *         absolute path inside the author's own device ({@code /data/user/0/com.exteragram/…}), so
 *         there is nothing for anybody else to load, their own users included;</li>
 *     <li>{@code v} — the schema version, 1 in every work so far. Worth reading the day it is not.</li>
 * </ul>
 * The rest of the look installs; nothing above changes how any of it draws.
 */
public final class WorkshopStyle {

    /**
     * Their typeface list mapped onto ours, by their index. Theirs is
     * {@code 1 sans, 2 serif, 3 monospace, 4 sans bold-italic, 5 condensed} — ours is
     * {@code 1 bold, 2 serif, 3 monospace, 4 sans, 5 light, 6 condensed} — so the families line up and
     * the two entries carrying a style do not. Their 4 lands on our bold, the closest we can draw.
     * Their 6 is not in here: it means "the file at {@code name_font_path}", see {@link #applyName}.
     */
    private static final int[] FONTS = {0, 4, 2, 3, 1, 6};

    /** Their index for a bundled font file, and ours. */
    private static final int FONT_BUNDLED_THERE = 6;
    private static final int FONT_BUNDLED_HERE = 7;

    private WorkshopStyle() {
    }

    /**
     * Downloads whatever files the work needs, then writes the whole style into the config.
     * Runs off the main thread and answers on it.
     * <p>
     * A download that fails is not an install that fails: the look is applied anyway, minus that one
     * file, and the callback carries a note saying which one. The alternative — reporting success and
     * leaving the user with a themed profile and no banner — is what made this look broken rather than
     * incomplete.
     */
    public static void install(WorkshopHelper.Work work, WorkshopHelper.Callback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            final Pulled banner = pull(work.assets, true, "banner");
            final Pulled background = pull(work.assets, false, "bg", "background");
            final PulledFont font = pullFont(work.assets);
            final String missing = missing(banner, background);
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    apply(work.config, banner.media(), background.media(), font);
                    CustomProfileHelper.onSettingsChanged();
                    callback.onResult(Boolean.TRUE, missing);
                } catch (Throwable e) {
                    FileLog.e(e);
                    callback.onResult(null, e.getMessage());
                }
            });
        });
    }

    /**
     * Installs a frame from the workshop's second gallery: the work's spec becomes the frame the
     * avatar wears, and nothing else about the look is touched.
     *
     * <p>Nothing is downloaded here. A frame's layers name either one of the eight built-in shapes or
     * a public URL, and the pictures are fetched by the painter the first time it needs them — which
     * is also what makes a frame travel to other users on its own, with no asset of ours behind it.
     */
    public static void installFrame(WorkshopHelper.Work work, WorkshopHelper.Callback<Boolean> callback) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                final String spec = work.config == null ? "" : work.config.optString("frame_spec", "");
                if (CustomProfileFrame.parse(spec).isEmpty()) {
                    callback.onResult(null, "frame is empty");
                    return;
                }
                NekoConfig.customProfileFrameSpec.setConfigString(spec);
                // The stored graph described the frame that was on before this one; the studio lays
                // out a fresh one for whatever was just installed.
                FrameGraphStore.forgetGraph();
                NekoConfig.customProfileEnabled.setConfigBool(true);
                CustomProfileHelper.onSettingsChanged();
                callback.onResult(Boolean.TRUE, null);
            } catch (Throwable e) {
                FileLog.e(e);
                callback.onResult(null, e.getMessage());
            }
        });
    }

    /**
     * Which picture slots the work declares but we could not fetch, and why, or null when everything it
     * declares arrived.
     *
     * <p>The reason is included rather than just the slot name because the two causes need completely
     * different things from the user: an asset the workshop host cuts off mid-transfer is nobody's fault
     * and nothing they can do, while one refused for its size is theirs to shrink. Reporting only
     * "banner" left both looking like the same unexplained failure.
     *
     * <p>The font is left out on purpose — a name in the wrong typeface is not worth interrupting
     * somebody over.
     */
    @Nullable
    private static String missing(Pulled banner, Pulled background) {
        final StringBuilder missing = new StringBuilder();
        note(missing, "banner", banner);
        note(missing, "background", background);
        return missing.length() == 0 ? null : missing.toString();
    }

    private static void note(StringBuilder missing, String slot, Pulled pulled) {
        if (pulled.failure() == null) {
            return;
        }
        if (missing.length() > 0) {
            missing.append("; ");
        }
        missing.append(slot).append(" — ").append(pulled.failure());
    }

    /**
     * A downloaded asset: where it landed, whether it has to be played rather than decoded, and the
     * descriptor that says where the bytes came from — kept so the look can be synced to other
     * SovietGram users, who fetch the same asset from the same host. See {@link CustomProfileMedia}.
     */
    private record Media(String path, boolean video, String descriptor) {
    }

    /**
     * What came of fetching one slot: the stored asset, or the reason there isn't one. Both null means
     * the work declares no media for the slot at all, which is not a failure and must not be reported
     * as one — most looks are a colour or a gradient and declare nothing.
     */
    private record Pulled(@Nullable Media media, @Nullable String failure) {
        static final Pulled NONE = new Pulled(null, null);
    }

    /**
     * Downloads the first slot the work actually declares and stores it.
     *
     * <p>A failure is recorded rather than swallowed: the look still installs, falling back to the flat
     * colour it also carries, but {@link #missing} can then say which slot went missing and why instead
     * of leaving a themed profile with an unexplained empty banner.
     */
    private static Pulled pull(@Nullable JSONObject assets, boolean banner, String... slots) {
        String failure = null;
        for (String slot : slots) {
            for (WorkshopHelper.MediaRef ref : WorkshopHelper.mediaSources(assets, slot)) {
                try {
                    final byte[] data = WorkshopHelper.downloadMedia(ref);
                    final String path = CustomProfileHelper.importBytes(data, banner);
                    if (path == null) {
                        failure = "could not be saved";
                        continue;
                    }
                    // The file decides, never the declared mime: "image/webp" is a still in most
                    // published works and an animation in the rest, and the two have to be drawn by
                    // different decoders. See CustomProfileFormat.
                    return new Pulled(new Media(path, CustomProfileFormat.moving(path),
                            descriptorFor(ref, data, banner)), null);
                } catch (Throwable e) {
                    // Logged without a stack trace: every cause here is a remote host behaving badly,
                    // not a fault of ours, and the reason itself travels to the user in the install
                    // note. The next source the work declares is still worth trying — see
                    // WorkshopHelper.mediaSources.
                    FileLog.e("WorkshopStyle: " + slot + " unavailable: " + e.getMessage());
                    failure = reason(e);
                }
            }
        }
        return failure == null ? Pulled.NONE : new Pulled(null, failure);
    }

    /** A failure as something worth putting in front of a user, never an empty string. */
    private static String reason(Throwable e) {
        final String message = e.getMessage();
        return TextUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
    }

    /**
     * The look's own font file, stored locally, or {@code null} when it ships none or it could not be
     * fetched. Not published anywhere: a peer's copy of this look draws the name in their own font,
     * because a font is not a picture and {@code /v1/media} is for pictures.
     */
    @Nullable
    private static PulledFont pullFont(@Nullable JSONObject assets) {
        for (WorkshopHelper.MediaRef ref : WorkshopHelper.mediaSources(assets, "font")) {
            try {
                final byte[] data = WorkshopHelper.downloadMedia(ref);
                final String path = CustomProfileHelper.importFont(data);
                if (path == null) {
                    continue;
                }
                // Published straight away, from the bytes already in hand: a font is a file on this
                // phone and nothing else, so without this the look's typeface is the one part of it
                // that never reaches anybody else. The descriptor is what a peer fetches by.
                return new PulledFont(path, CustomProfileMedia.publish(CustomProfileMedia.SLOT_FONT,
                        data, ref.mime));
            } catch (Throwable e) {
                FileLog.e("WorkshopStyle: font unavailable: " + e.getMessage());
            }
        }
        return null;
    }

    /** A look's own typeface: where it landed, and where a peer can fetch the same bytes. */
    private record PulledFont(String path, @Nullable String descriptor) {
    }

    /**
     * Where a peer should fetch this asset from once the look is synced.
     *
     * <p>Our own API first, always, even for an asset that carries a public URL. The bytes are
     * already in hand here — they were just downloaded to install the look — so publishing them
     * costs one upload and nothing else, and it is what makes the picture reachable for everybody:
     * the workshop's own assets are GitHub release downloads, and a peer whose network cannot
     * reach GitHub saw a look with no banner at all while the installer saw it perfectly. That was
     * the whole of "some looks transfer and some don't" — the ones that worked were the older works,
     * whose assets are a bare sha with no URL to prefer, so they were published as a matter of course.
     *
     * <p>The public URL stays as the fallback, for an asset past what {@code /v1/media} accepts.
     * Empty when neither is possible, which is the honest answer: the peer draws the look's flat
     * colour instead of being pointed at a picture nobody but the installer can fetch.
     */
    private static String descriptorFor(WorkshopHelper.MediaRef ref, byte[] data, boolean banner) {
        final String published = CustomProfileMedia.publish(banner, data, ref.mime);
        if (published != null) {
            return published;
        }
        return CustomProfileMedia.describe(ref);
    }

    private static void apply(JSONObject c, @Nullable Media banner, @Nullable Media background,
                              @Nullable PulledFont font) {
        if (c == null) {
            return;
        }
        applyBanner(c, banner);
        applyGradient(c);
        applyBackground(c, background);
        applyBlocks(c);
        applyAvatar(c);
        applyName(c, font);
        applyFrame(c);
        applyThought(c);
        applyPalette(c);
        applyRows(c);
        applyHeader(c);
        applyExtraBlocks(c);
        NekoConfig.customProfileEnabled.setConfigBool(true);
    }

    private static void applyBanner(JSONObject c, @Nullable Media media) {
        final int mode = c.optInt("banner_mode");
        final int type;
        if (!c.optBoolean("banner_enabled", true)) {
            type = 0;
        } else if (mode == 1) {
            type = 1;
        } else if (mode == 2) {
            type = 2;
        } else if (media == null) {
            // A picture we could not download would draw nothing, so fall back to a flat colour.
            type = c.has("banner_color") ? 1 : 0;
        } else {
            // A downloaded animation must play as type 4, a still as type 3 — the reference stores
            // both under the same "picture" mode.
            type = media.video() ? 4 : 3;
        }
        set(NekoConfig.customProfileBannerType, type);
        color(c, "banner_color", NekoConfig.customProfileBannerColor);
        // Installing a look replaces it whole, so a work with no banner media must not leave the
        // previous one's file behind for a later type change to resurrect.
        NekoConfig.customProfileBannerPath.setConfigString(media == null ? "" : media.path());
        CustomProfileMedia.remember(true, media == null ? "" : media.descriptor());
        set(NekoConfig.customProfileBannerAlpha, clamp(c.optInt("banner_alpha", 100), 0, 100));
        set(NekoConfig.customProfileBannerDim, clamp(c.optInt("banner_dim"), 0, 100));
        set(NekoConfig.customProfileBannerFade, clamp(c.optInt("banner_fade"), 0, 2));
        set(NekoConfig.customProfileBannerFadeAngle, angle(c.optInt("banner_fade_angle")));
        set(NekoConfig.customProfileBannerFadeRadius, clamp(c.optInt("banner_fade_radius", 100), 20, 200));
        set(NekoConfig.customProfileBannerFadeCenterX, center(c, "banner_fade_cx"));
        set(NekoConfig.customProfileBannerFadeCenterY, center(c, "banner_fade_cy"));
        NekoConfig.customProfileShowEmoji.setConfigBool(c.optBoolean("show_emoji", true));
    }

    private static void applyGradient(JSONObject c) {
        NekoConfig.customProfileGradientRadial.setConfigBool(c.optInt("grad_type") == 1);
        // Both keys can carry the count, and the reference reads them in this order: grad_count wins
        // where it is present, and grad_use3 only decides what its default is. The two disagree in
        // published works, so reading them the other way round installs a third colour into a look
        // that states two.
        final int count = clamp(c.optInt("grad_count", c.optBoolean("grad_use3") ? 3 : 2), 2, 3);
        set(NekoConfig.customProfileGradientCount, count);
        color(c, "grad_c1", NekoConfig.customProfileGradientColor1);
        color(c, "grad_c2", NekoConfig.customProfileGradientColor2);
        color(c, "grad_c3", NekoConfig.customProfileGradientColor3);
        set(NekoConfig.customProfileGradientAngle, angle(c.optInt("grad_angle")));
        set(NekoConfig.customProfileGradientRadius, clamp(c.optInt("grad_radius", 100), 20, 200));
        set(NekoConfig.customProfileGradientCenterX, center(c, "grad_cx"));
        set(NekoConfig.customProfileGradientCenterY, center(c, "grad_cy"));
    }

    private static void applyBackground(JSONObject c, @Nullable Media media) {
        final int type;
        if (!c.optBoolean("bg_enabled", false)) {
            type = 0;
        } else if (c.optInt("bg_mode") == 1) {
            // Their bg_mode 1 is "picture", which covers animations too — several published works
            // ship an mp4 here, so it has to resolve to our type 4 rather than to a still.
            type = media == null ? 1 : (media.video() ? 4 : 3);
        } else {
            type = 1;
        }
        set(NekoConfig.customProfileBackgroundType, type);
        color(c, "bg_color", NekoConfig.customProfileBackgroundColor);
        NekoConfig.customProfileBackgroundPath.setConfigString(media == null ? "" : media.path());
        CustomProfileMedia.remember(false, media == null ? "" : media.descriptor());
        set(NekoConfig.customProfileBackgroundAlpha, clamp(c.optInt("bg_alpha", 100), 0, 100));
        set(NekoConfig.customProfileBackgroundDim, clamp(c.optInt("bg_dim"), 0, 100));
        set(NekoConfig.customProfileBackgroundFade, clamp(c.optInt("bg_fade"), 0, 2));
        set(NekoConfig.customProfileBackgroundFadeAngle, angle(c.optInt("bg_fade_angle")));
        set(NekoConfig.customProfileBackgroundFadeRadius, clamp(c.optInt("bg_fade_radius", 100), 20, 200));
        set(NekoConfig.customProfileBackgroundFadeCenterX, center(c, "bg_fade_cx"));
        set(NekoConfig.customProfileBackgroundFadeCenterY, center(c, "bg_fade_cy"));
    }

    private static void applyBlocks(JSONObject c) {
        NekoConfig.customProfileBlocksEnabled.setConfigBool(c.optBoolean("blocks_color_enabled"));
        color(c, "blocks_color", NekoConfig.customProfileBlocksColor);
        set(NekoConfig.customProfileBlocksAlpha, clamp(c.optInt("blocks_alpha", 100), 0, 100));
        set(NekoConfig.customProfileBlocksBlur, clamp(c.optInt("blocks_blur"), 0, 100));
    }

    private static void applyAvatar(JSONObject c) {
        // Their shape numbers are ours: 0 a circle, 1 a rounded square, 2 a square, and so on to 8,
        // which is the free-form outline below. Anything outside that is drawn as a circle rather
        // than as whichever shape happens to share the number.
        //
        // The free-form outline is worn only when the look asks for it — shape 8 means "the points
        // in avatar_custom" and nothing else does.
        //
        // It used to be worn whenever a look carried points at all, on the reading that carrying
        // them was the same as asking for them. It is not: their editor keeps the last outline you
        // traced even after you pick another shape, so a third of the gallery ships one it does not
        // use. Six published looks were drawn as somebody's leftover doodle instead of the shape
        // they asked for — and because those outlines cover only 62–78% of the avatar's box (one of
        // them 6%), the frame, which follows the same outline, came out small and sitting inside the
        // avatar rather than around it.
        final String points = c.optString("avatar_custom", "");
        final int shape = c.optInt("avatar_shape");
        final boolean custom = shape == 8 && CustomProfileGfx.parsePoints(points).length >= 6;
        NekoConfig.customProfileAvatarPoints.setConfigString(custom ? points : "");
        set(NekoConfig.customProfileAvatarShape,
                custom ? 8 : (shape >= 0 && shape <= 7 ? shape : 0));
        // Their radius is in dp and so is ours, so this is a straight copy. The 50 is not a narrowing:
        // CustomProfileGfx.shapePath clamps the corner to half the avatar's side, and dp(50) is already
        // past that on the profile header's avatar — so the one published work asking for 64 draws
        // exactly the same as it would unclamped.
        set(NekoConfig.customProfileAvatarRadius, clamp(c.optInt("avatar_radius", 18), 0, 50));
        set(NekoConfig.customProfileAvatarSmoothing, clamp(c.optInt("avatar_round"), 0, 100));
        set(NekoConfig.customProfileAvatarAlpha, clamp(c.optInt("avatar_alpha", 100), 0, 100));
        set(NekoConfig.customProfileAvatarDim, clamp(c.optInt("avatar_dim"), 0, 100));
        // Both of these mean exactly what they mean there now: the fade is how transparent the rim
        // ends up and the radius is where the feather starts, with the picture untouched inside it.
        // They used to be dropped, on the belief that ours wiped the avatar outwards from the middle
        // instead — so a look with avatar_fade 60 installed with no fade at all.
        set(NekoConfig.customProfileAvatarFade, clamp(c.optInt("avatar_fade"), 0, 100));
        set(NekoConfig.customProfileAvatarFadeRadius, clamp(c.optInt("avatar_fade_radius", 50), 0, 100));
    }

    private static void applyName(JSONObject c, @Nullable PulledFont font) {
        NekoConfig.customProfileNameColorEnabled.setConfigBool(c.optBoolean("name_color_enabled"));
        color(c, "name_color", NekoConfig.customProfileNameColor);
        applyTextColor(c);
        NekoConfig.customProfileNameGlow.setConfigBool(c.optBoolean("name_glow_enabled"));
        color(c, "name_glow_color", NekoConfig.customProfileNameGlowColor);
        set(NekoConfig.customProfileNameGlowRadius, clamp(c.optInt("name_glow_radius", 12), 0, 40));
        // Their strength runs to 100, ours to 20. Their default is 100 — a full-strength glow — so a
        // look that turns the glow on without stating a strength was installing at under a third of it.
        set(NekoConfig.customProfileNameGlowStrength, clamp(clamp(c.optInt("name_glow_strength", 100), 0, 100) / 5, 0, 20));
        final int fx = c.optInt("name_fx");
        set(NekoConfig.customProfileNameFx, fx >= 0 && fx <= 7 ? fx : 0);
        set(NekoConfig.customProfileNameFxSpeed, fxSpeed(c.optInt("name_fx_speed", 100)));
        set(NekoConfig.customProfileNameFxAngle, angle(c.optInt("name_grad_angle")));
        color(c, "name_grad_c1", NekoConfig.customProfileNameFxColor1);
        color(c, "name_grad_c2", NekoConfig.customProfileNameFxColor2);
        applyFont(c.optInt("name_font"), font);
        // Out of range means "not a size", not "the nearest size": the reference resets it to 100, and
        // a look asking for 400 wants a big name rather than the largest we happen to allow.
        final int size = c.optInt("name_size", 100);
        set(NekoConfig.customProfileNameSize, size < 50 || size > 200 ? 100 : size);
    }

    /**
     * The rows the look invents for itself. Kept as written, minus anything with no renderer, which
     * {@link CustomProfileExtraRows} decides — storing the rest would put empty rows on the profile.
     */
    private static void applyExtraBlocks(JSONObject c) {
        final Object blocks = c.opt("custom_profile_blocks");
        final String json = blocks == null ? "" : blocks.toString();
        NekoConfig.customProfileExtraBlocks.setConfigString(
                CustomProfileExtraRows.parse(json).isEmpty() ? "" : json);
        adoptBlockPictures();
    }

    /**
     * Takes the pictures of the look's own rows into our own storage.
     *
     * <p>The workshop hosts three things for a work — the preview, the banner and the background —
     * and nothing else, so a row's picture is either an address on somebody else's host or a file on
     * the author's phone. The file cannot be rescued: those bytes exist nowhere but there, and a row
     * pointing at one is left alone and simply draws nothing. An address can be, and is: the bytes
     * are fetched once and re-hosted here, so the row keeps working when that host goes away and,
     * more to the point, travels to everyone who looks at this profile.
     *
     * <p>In the background, because it is a download per row and the look is already installed and
     * on screen by the time it finishes.
     */
    private static void adoptBlockPictures() {
        final List<CustomProfileExtraRows.Block> blocks = CustomProfileExtraRows.stored();
        boolean any = false;
        for (CustomProfileExtraRows.Block block : blocks) {
            if (block.media.isEmpty() && CustomProfileExtraRows.fetchable(block.picture())) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        final long owner = SovietGramAccountScope.owner();
        Utilities.globalQueue.postRunnable(() -> {
            final List<String> descriptors = new ArrayList<>();
            for (CustomProfileExtraRows.Block block : blocks) {
                String descriptor = "";
                if (block.media.isEmpty() && CustomProfileExtraRows.fetchable(block.picture())) {
                    try {
                        // Their own transport: the addresses are GitHub releases as often as not,
                        // and this is what falls back to the workshop's proxy when one is blocked.
                        final byte[] data = WorkshopHelper.download(block.picture(), "");
                        descriptor = CustomProfileMedia.publishLoose(data, null);
                    } catch (Throwable e) {
                        FileLog.e("WorkshopStyle: row picture unavailable: " + e.getMessage());
                    }
                }
                descriptors.add(descriptor == null ? "" : descriptor);
            }
            AndroidUtilities.runOnUIThread(() -> {
                // The look may have been replaced, or the account switched, while this was running.
                if (!SovietGramAccountScope.isOwner(owner)) {
                    return;
                }
                final List<CustomProfileExtraRows.Block> current = CustomProfileExtraRows.stored();
                if (current.size() != blocks.size()) {
                    return;
                }
                boolean changed = false;
                for (int i = 0; i < current.size(); i++) {
                    final String descriptor = descriptors.get(i);
                    if (!descriptor.isEmpty() && current.get(i).media.isEmpty()) {
                        current.get(i).media = descriptor;
                        changed = true;
                    }
                }
                if (changed) {
                    CustomProfileExtraRows.store(current);
                }
            });
        });
    }

    /**
     * The header layout: which preset, and the anchors when the author placed the parts by hand.
     * <p>
     * The plain avatar nudge lives in two keys of its own rather than in the anchor map, so a look
     * that only moves the avatar is turned into a hand-made layout carrying exactly that.
     */
    private static void applyHeader(JSONObject c) {
        final int preset = c.optInt("experimental_header_layout", 0);
        final int avatarX = c.optInt("experimental_avatar_x", 0);
        final int avatarY = c.optInt("experimental_avatar_y", 0);
        if (preset == 4) {
            set(NekoConfig.customProfileHeaderLayout, 4);
            final Object config = c.opt("experimental_header_layout_config");
            NekoConfig.customProfileHeaderConfig.setConfigString(config == null ? "" : config.toString());
            return;
        }
        if (preset == 1) {
            set(NekoConfig.customProfileHeaderLayout, 1);
            NekoConfig.customProfileHeaderConfig.setConfigString("");
            return;
        }
        if (avatarX != 0 || avatarY != 0) {
            set(NekoConfig.customProfileHeaderLayout, 4);
            NekoConfig.customProfileHeaderConfig.setConfigString(
                    "{\"anchor_avatar_x\":" + avatarX + ",\"anchor_avatar_y\":" + avatarY + "}");
            return;
        }
        set(NekoConfig.customProfileHeaderLayout, 0);
        NekoConfig.customProfileHeaderConfig.setConfigString("");
    }

    /**
     * What the look says about the profile's own rows: the order it wants them in and the ones it
     * hides. Both arrive as JSON arrays of the reference's row ids and are kept verbatim.
     */
    private static void applyRows(JSONObject c) {
        final Object order = c.opt("profile_block_order");
        NekoConfig.customProfileBlockOrder.setConfigString(order == null ? "" : order.toString());
        // Theirs is a comma-separated string here rather than an array, so it is normalised into one.
        final String hidden = c.optString("experimental_hidden_sections", "").trim();
        NekoConfig.customProfileHiddenSections.setConfigString(hidden.isEmpty() ? "" : asJsonArray(hidden));
    }

    /** A comma-separated list as a JSON array, which is what {@link CustomProfileRows} reads. */
    private static String asJsonArray(String commaSeparated) {
        if (commaSeparated.startsWith("[")) {
            return commaSeparated;
        }
        final org.json.JSONArray array = new org.json.JSONArray();
        for (String part : commaSeparated.split(",")) {
            final String id = part.trim();
            if (!id.isEmpty()) {
                array.put(id);
            }
        }
        return array.toString();
    }

    /**
     * The look's palette, kept as the work wrote it.
     * <p>
     * It arrives either as an object or, from the older works, as a string holding one — the same
     * two shapes the assets come in. Stored only when it resolves to at least one usable key, so a
     * palette written against keys this app does not have leaves the theme alone instead of half
     * repainting it.
     */
    private static void applyPalette(JSONObject c) {
        final Object raw = c.opt("profile_palette");
        final String json = raw == null ? "" : raw.toString();
        NekoConfig.customProfilePalette.setConfigString(
                CustomProfilePalette.parse(json).size() == 0 ? "" : json);
    }

    /**
     * The thought bubble: its text, its two colours and whose typeface it borrows.
     * <p>
     * Trimmed and trimmed-of-whitespace here rather than at the draw, which is what the reference's
     * own normalise does — a look whose thought is 300 characters of spaces should install as no
     * thought, not as an empty bubble.
     */
    private static void applyThought(JSONObject c) {
        final String text = c.optString("thought_text", "").trim();
        NekoConfig.customProfileThoughtText.setConfigString(
                text.length() > 200 ? text.substring(0, 200) : text);
        color(c, "thought_text_color", NekoConfig.customProfileThoughtTextColor);
        color(c, "thought_bg_color", NekoConfig.customProfileThoughtBackground);
        final int font = c.optInt("thought_font");
        set(NekoConfig.customProfileThoughtFont, font > 0 && font < FONTS.length ? FONTS[font] : 0);
        NekoConfig.customProfileThoughtFontCopy.setConfigBool(c.optBoolean("thought_font_copy", true));
    }

    /**
     * The avatar frame a look carries, kept verbatim.
     * <p>
     * Stored as the work wrote it rather than translated, because a frame is already portable on its
     * own terms: its layers name either a built-in shape or a public URL. A work that carries none
     * clears whatever the previous look wore — installing a look replaces it whole.
     * <p>
     * A frame whose layers point inside the author's own phone ({@code /data/user/0/…}) is stored
     * too and simply draws nothing: those bytes are unreachable for everybody including, in the
     * reference itself, everybody but the author.
     */
    private static void applyFrame(JSONObject c) {
        final String spec = c.optString("frame_spec", "");
        NekoConfig.customProfileFrameSpec.setConfigString(
                CustomProfileFrame.parse(spec).isEmpty() ? "" : spec);
        FrameGraphStore.forgetGraph();
    }

    /**
     * The one text colour, off their three toggles.
     * <p>
     * They colour the name's subtitle, the hint lines and the action buttons separately; we have a
     * single "other text" colour. The collapse is not a guess — it is what their own renderer does
     * before drawing: enabled if any of the three is, and the colour taken from the first one that is
     * on, in this order. A look that turns all three on with the same colour, which is the common case,
     * therefore installs exactly.
     */
    private static void applyTextColor(JSONObject c) {
        final boolean text = c.optBoolean("profile_text_color_enabled");
        final boolean hint = c.optBoolean("profile_hint_color_enabled");
        final boolean action = c.optBoolean("profile_action_color_enabled");
        NekoConfig.customProfileTextColorEnabled.setConfigBool(text || hint || action);
        if (text) {
            color(c, "profile_text_color", NekoConfig.customProfileTextColor);
        } else if (hint) {
            color(c, "profile_hint_color", NekoConfig.customProfileTextColor);
        } else if (action) {
            color(c, "profile_action_color", NekoConfig.customProfileTextColor);
        }
    }

    /**
     * The typeface, and the font file when the look ships one. A bundled font whose bytes did not
     * arrive falls back to the view's own font rather than to some other family — the look asked for a
     * specific typeface, and a wrong one is worse than the familiar one.
     */
    private static void applyFont(int font, @Nullable PulledFont pulled) {
        if (font == FONT_BUNDLED_THERE) {
            final boolean usable = pulled != null && !TextUtils.isEmpty(pulled.path());
            NekoConfig.customProfileNameFontPath.setConfigString(usable ? pulled.path() : "");
            set(NekoConfig.customProfileNameFont, usable ? FONT_BUNDLED_HERE : 0);
            // Empty when the upload could not happen: a peer then reads the name in their own font,
            // which is the same answer as before this was syncable and better than pointing them at
            // bytes nobody serves.
            CustomProfileMedia.remember(CustomProfileMedia.SLOT_FONT,
                    usable && pulled.descriptor() != null ? pulled.descriptor() : "");
            return;
        }
        // Whatever the previous look bundled is not this look's font.
        NekoConfig.customProfileNameFontPath.setConfigString("");
        CustomProfileMedia.remember(CustomProfileMedia.SLOT_FONT, "");
        set(NekoConfig.customProfileNameFont, font > 0 && font < FONTS.length ? FONTS[font] : 0);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * One colour of the look, as {@code #AARRGGBB} or the same digits with the {@code #} left off —
     * the reference accepts both and published works carry both.
     *
     * <p>A key the work omits is written as <em>our own default</em>, which is the reference's default
     * for that key (they were taken from it), and not left as it was. Leaving it was the bug behind
     * "the theme applies, but not all of it": installing a look replaces it whole, so a work that
     * states no banner colour is stating the default one — while the config still held the previous
     * look's, which is what got drawn wherever the new look falls back to a colour. A picture that
     * fails to download falls back to exactly that colour, so this decided what half the failures
     * looked like.
     */
    private static void color(JSONObject c, String key, ConfigItem item) {
        item.setConfigInt(parseColor(c.optString(key), defaultColor(item)));
    }

    private static int defaultColor(ConfigItem item) {
        return item.defaultValue instanceof Integer value ? value : 0;
    }

    private static int parseColor(@Nullable String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return fallback;
        }
        if (text.charAt(0) != '#') {
            text = "#" + text;
        }
        try {
            return Color.parseColor(text);
        } catch (Throwable ignore) {
            return fallback;
        }
    }

    /**
     * A centre as our integer percent. Theirs is a fraction of the side, written out at full float
     * precision — 0.30433860421180725 becomes 30, which is a third of a percent of the banner's width
     * away from where they would put it.
     */
    private static int center(JSONObject c, String key) {
        final double value = c.optDouble(key, 0.5);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 50;
        }
        return clamp((int) Math.round(value * 100), 0, 100);
    }

    /**
     * Their animation speed. Anything down at or below 5 is not a slow effect but a stalled one, and
     * the reference reads it as a look that never set the key: back to 100.
     */
    private static int fxSpeed(int speed) {
        return speed <= 5 ? 100 : clamp(speed, 10, 300);
    }

    /** Degrees, wrapped rather than clamped: their sliders run past 360 and 361 means 1, not 360. */
    private static int angle(int degrees) {
        final int wrapped = degrees % 360;
        return wrapped < 0 ? wrapped + 360 : wrapped;
    }

    private static void set(ConfigItem item, int value) {
        item.setConfigInt(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
