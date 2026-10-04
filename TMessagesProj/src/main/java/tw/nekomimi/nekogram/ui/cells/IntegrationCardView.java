package tw.nekomimi.nekogram.ui.cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import tw.nekomimi.nekogram.helpers.CustomProfileExtraRows;
import tw.nekomimi.nekogram.helpers.CustomProfileIntegrations;

/**
 * The picture card of an integration row: what a Discord rich-presence card is for a song, a game or
 * anything else that is "on" right now — the cover, the title, the artist and a progress bar — and, for
 * GitHub, the year's contribution calendar with its total.
 *
 * <p>Drawn rather than assembled from views because it is two small pictures and a handful of lines of
 * text, and the progress bar has to move on its own between two fetches.
 */
public class IntegrationCardView extends View {

    private static final int[] SERVICE_COLORS = {
            0xFFD51007, // Last.fm
            0xFF2EA043, // GitHub
            0xFF66C0F4, // Steam
            0xFFFFCC00, // Yandex Music
            0xFF1DB954, // Spotify
            0xFFFF5500, // SoundCloud
            0xFFFF5500, // SoundCloud, signed in
    };
    private static final int[] GITHUB_DARK = {0xFF151B23, 0xFF033A16, 0xFF196C2E, 0xFF2EA043, 0xFF56D364};
    private static final int[] GITHUB_LIGHT = {0xFFEBEDF0, 0xFF9BE9A8, 0xFF40C463, 0xFF30A14E, 0xFF216E39};

    private final Theme.ResourcesProvider resourcesProvider;
    private final ImageReceiver cover = new ImageReceiver(this);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    @Nullable
    private CustomProfileExtraRows.Block block;
    @Nullable
    private CustomProfileIntegrations.Rich rich;
    private String coverShown = "";

    public IntegrationCardView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        cover.setRoundRadius(AndroidUtilities.dp(10));
        cover.setParentView(this);
    }

    public void set(CustomProfileExtraRows.Block block, CustomProfileIntegrations.Rich rich) {
        this.block = block;
        this.rich = rich;
        final String url = rich.track != null ? rich.track.cover : "";
        if (!url.equals(coverShown)) {
            coverShown = url;
            if (url.startsWith("https://")) {
                cover.setImage(ImageLocation.getForPath(url), "144_144", null, null, null, 0);
            } else {
                cover.setImageBitmap((android.graphics.drawable.Drawable) null);
            }
        }
        requestLayout();
        invalidate();
    }

    /** Whether the card moves by itself: a track that is playing. */
    public boolean isLive() {
        return rich != null && rich.track != null && rich.track.playing && rich.track.durationMs > 0;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        cover.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cover.onDetachedFromWindow();
    }

    private int dp(float value) {
        return AndroidUtilities.dp(value);
    }

    private boolean dark() {
        return resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark();
    }

    private int primary() {
        return block != null && block.titleColor != 0 ? block.titleColor
                : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
    }

    private int secondary() {
        return block != null && block.valueColor != 0 ? block.valueColor
                : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider);
    }

    private int accent() {
        final int service = rich != null ? rich.service : 4;
        return SERVICE_COLORS[Math.max(0, Math.min(SERVICE_COLORS.length - 1, service))];
    }

    private int cardColor() {
        if (block != null && block.iconBackground != 0) return block.iconBackground;
        return dark() ? 0x1CFFFFFF : 0x12000000;
    }

    private float radius() {
        return dp(block != null ? Math.max(8, block.radius) : 16);
    }

    // ------------------------------------------------------------------ geometry

    private int weeks() {
        final CustomProfileIntegrations.Graph graph = rich != null ? rich.graph : null;
        if (graph == null) return 53;
        return (firstWeekday(graph.from) + graph.levels.length() + 6) / 7;
    }

    /** 0 = Sunday, as on GitHub's calendar, for a yyyy-mm-dd date. */
    private static int firstWeekday(String date) {
        final Calendar calendar = parse(date);
        return calendar == null ? 0 : calendar.get(Calendar.DAY_OF_WEEK) - 1;
    }

    private static Calendar parse(String date) {
        try {
            final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
            calendar.clear();
            calendar.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(5, 7)) - 1,
                    Integer.parseInt(date.substring(8, 10)));
            return calendar;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private float pitch(int width) {
        final float inner = width - dp(32) - dp(24) - dp(26);
        return Math.max(dp(4), inner / Math.max(1, weeks()));
    }

    public int heightFor(int width) {
        if (rich != null && rich.graph != null && rich.track == null) {
            return dp(6) + dp(12) + dp(20) + dp(8) + dp(14) + (int) (7 * pitch(width)) + dp(8) + dp(18) + dp(12) + dp(6);
        }
        return dp(6) + dp(14) + dp(16) + dp(10) + dp(72) + dp(14) + dp(6);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, heightFor(width));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        if (rich == null || block == null) {
            return;
        }
        final int width = getMeasuredWidth();
        rect.set(dp(16), dp(6), width - dp(16), getMeasuredHeight() - dp(6));
        fill.setColor(cardColor());
        canvas.drawRoundRect(rect, radius(), radius(), fill);
        if (rich.track != null) {
            drawTrack(canvas, rect);
        } else if (rich.graph != null) {
            drawGraph(canvas, rect);
        }
    }

    private String heading(CustomProfileIntegrations.Track track) {
        final String service = CustomProfileIntegrations.serviceName(rich.service);
        if (rich.service == 2) {
            return LocaleController.formatString(R.string.CustomProfileIntegrationGamePlaying, service);
        }
        if (rich.service == 5) {
            return LocaleController.formatString(track.liked
                    ? R.string.CustomProfileIntegrationLikedOn : R.string.CustomProfileIntegrationLatestOn, service);
        }
        if (track.liked) {
            return LocaleController.formatString(R.string.CustomProfileIntegrationLikedOn, service);
        }
        if (track.stale) {
            return LocaleController.formatString(R.string.CustomProfileIntegrationLastPlayed, service);
        }
        return LocaleController.formatString(track.playing
                ? R.string.CustomProfileIntegrationListening : R.string.CustomProfileIntegrationPaused, service);
    }

    private void drawTrack(Canvas canvas, RectF card) {
        final CustomProfileIntegrations.Track track = rich.track;
        final float left = card.left + dp(14);
        final float right = card.right - dp(14);

        // The line above the cover: a dot in the service's colour and what is going on.
        fill.setColor(accent());
        canvas.drawCircle(left + dp(3), card.top + dp(14) + dp(6), dp(3), fill);
        text.setTypeface(AndroidUtilities.bold());
        text.setTextSize(dp(12));
        text.setColor(secondary());
        canvas.drawText(TextUtils.ellipsize(heading(track), text, right - left - dp(12), TextUtils.TruncateAt.END).toString(),
                left + dp(12), card.top + dp(14) + dp(11), text);

        final float coverTop = card.top + dp(14) + dp(16) + dp(10);
        final float coverSize = dp(72);
        cover.setImageCoords(left, coverTop, coverSize, coverSize);
        if (coverShown.isEmpty() || !cover.hasImageSet()) {
            fill.setColor(accent());
            fill.setAlpha(60);
            rect.set(left, coverTop, left + coverSize, coverTop + coverSize);
            canvas.drawRoundRect(rect, dp(10), dp(10), fill);
            fill.setAlpha(255);
            text.setTypeface(AndroidUtilities.bold());
            text.setTextSize(dp(28));
            text.setColor(accent());
            final String initial = track.title.isEmpty() ? "♪" : track.title.substring(0, track.title.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
            canvas.drawText(initial, left + (coverSize - text.measureText(initial)) / 2f, coverTop + coverSize / 2f + dp(10), text);
        }
        cover.draw(canvas);

        final float textLeft = left + coverSize + dp(12);
        final float textWidth = right - textLeft;
        text.setTypeface(AndroidUtilities.bold());
        text.setTextSize(dp(16));
        text.setColor(primary());
        canvas.drawText(TextUtils.ellipsize(track.title, text, textWidth, TextUtils.TruncateAt.END).toString(),
                textLeft, coverTop + dp(18), text);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(dp(14));
        text.setColor(secondary());
        if (!track.artist.isEmpty()) {
            canvas.drawText(TextUtils.ellipsize(track.artist, text, textWidth, TextUtils.TruncateAt.END).toString(),
                    textLeft, coverTop + dp(38), text);
        }
        if (track.durationMs > 0 && !track.playing && track.progressMs == 0) {
            // Something that is not being played (a latest upload, a last listened track) has a length
            // but no position: the bar would only ever sit at zero.
            text.setTextSize(dp(12));
            text.setColor(secondary());
            canvas.drawText(clock(track.durationMs), textLeft, coverTop + coverSize - dp(2), text);
        } else if (track.durationMs > 0) {
            final long position = Math.min(track.positionNow(), track.durationMs);
            final float barY = coverTop + coverSize - dp(20);
            final float barLeft = textLeft;
            final float barRight = right;
            // Track, then the played part.
            fill.setColor(secondary());
            fill.setAlpha(70);
            rect.set(barLeft, barY - dp(1.5f), barRight, barY + dp(1.5f));
            canvas.drawRoundRect(rect, dp(2), dp(2), fill);
            fill.setColor(primary());
            final float done = barLeft + (barRight - barLeft) * (position / (float) track.durationMs);
            rect.set(barLeft, barY - dp(1.5f), Math.max(barLeft + dp(3), done), barY + dp(1.5f));
            canvas.drawRoundRect(rect, dp(2), dp(2), fill);
            text.setTextSize(dp(12));
            text.setColor(secondary());
            canvas.drawText(clock(position), barLeft, coverTop + coverSize - dp(2), text);
            final String total = clock(track.durationMs);
            canvas.drawText(total, barRight - text.measureText(total), coverTop + coverSize - dp(2), text);
        } else if (!track.album.isEmpty() && !track.album.equals(track.title)) {
            text.setTextSize(dp(13));
            canvas.drawText(TextUtils.ellipsize(track.album, text, textWidth, TextUtils.TruncateAt.END).toString(),
                    textLeft, coverTop + dp(56), text);
        }
        if (isLive()) {
            postInvalidateDelayed(1000);
        }
    }

    private static String clock(long millis) {
        final long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void drawGraph(Canvas canvas, RectF card) {
        final CustomProfileIntegrations.Graph graph = rich.graph;
        final int[] colors = dark() ? GITHUB_DARK : GITHUB_LIGHT;
        final float left = card.left + dp(12);
        final float pitch = pitch(getMeasuredWidth());
        final float gap = Math.max(dp(1), pitch * 0.2f);
        final float size = pitch - gap;
        final float gridLeft = left + dp(26);

        text.setTypeface(AndroidUtilities.bold());
        text.setTextSize(dp(14));
        text.setColor(primary());
        final String total = LocaleController.formatString(R.string.CustomProfileIntegrationContribs,
                java.text.NumberFormat.getIntegerInstance().format(graph.total));
        canvas.drawText(TextUtils.ellipsize(total, text, card.right - left - dp(12), TextUtils.TruncateAt.END).toString(),
                left, card.top + dp(12) + dp(15), text);

        final float monthsY = card.top + dp(12) + dp(20) + dp(8) + dp(11);
        final float gridTop = card.top + dp(12) + dp(20) + dp(8) + dp(14);
        final int offset = firstWeekday(graph.from);
        final String[] months = new DateFormatSymbols(LocaleController.getInstance().getCurrentLocale()).getShortMonths();
        final Calendar start = parse(graph.from);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(dp(10));
        text.setColor(secondary());

        // Month names above the first week that starts a month, unless it would sit on the previous one.
        float lastLabelEnd = -1;
        if (start != null) {
            int lastMonth = -1;
            for (int week = 0; week < weeks(); week++) {
                final Calendar day = (Calendar) start.clone();
                day.add(Calendar.DAY_OF_YEAR, week * 7 - offset);
                // The column's Sunday may precede the first day of the data; use its last day for the month.
                day.add(Calendar.DAY_OF_YEAR, 6);
                final int month = day.get(Calendar.MONTH);
                if (month != lastMonth) {
                    lastMonth = month;
                    final float x = gridLeft + week * pitch;
                    final String label = months[month];
                    if (x >= lastLabelEnd + dp(4) && x + text.measureText(label) <= card.right - dp(8)) {
                        canvas.drawText(label, x, monthsY, text);
                        lastLabelEnd = x + text.measureText(label);
                    }
                }
            }
        }
        // Mon, Wed, Fri down the left.
        final String[] days = new DateFormatSymbols(LocaleController.getInstance().getCurrentLocale()).getShortWeekdays();
        for (int row = 1; row <= 5; row += 2) {
            canvas.drawText(days[row + 1], left, gridTop + row * pitch + size - dp(1), text);
        }
        for (int i = 0; i < graph.levels.length(); i++) {
            final int slot = offset + i;
            final int week = slot / 7;
            final int row = slot % 7;
            final int level = Math.max(0, Math.min(4, graph.levels.charAt(i) - '0'));
            fill.setColor(colors[level]);
            rect.set(gridLeft + week * pitch, gridTop + row * pitch, gridLeft + week * pitch + size, gridTop + row * pitch + size);
            canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), fill);
        }

        // Less ▢▢▢▢▢ More
        final float legendY = gridTop + 7 * pitch + dp(8) + dp(12);
        final String more = LocaleController.getString(R.string.CustomProfileIntegrationMore);
        final String less = LocaleController.getString(R.string.CustomProfileIntegrationLess);
        float x = card.right - dp(12) - text.measureText(more);
        canvas.drawText(more, x, legendY, text);
        x -= dp(4) + size;
        for (int level = 4; level >= 0; level--) {
            fill.setColor(colors[level]);
            rect.set(x, legendY - size + dp(1), x + size, legendY + dp(1));
            canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), fill);
            x -= dp(3) + size;
        }
        canvas.drawText(less, x + dp(3) + size - dp(4) - text.measureText(less), legendY, text);
    }
}
