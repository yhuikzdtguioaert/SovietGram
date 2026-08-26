package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Turns a photo into a framed meme: ".рамка" sent as a reply to a picture is swallowed, the picture
 * is drawn into the frame that fits its shape best, and the text of the replied-to message is
 * printed under it in the usual «CAPS IN GUILLEMETS» style.
 * <p>
 * Off by default — it takes over a message the user typed, so it only does that once they ask for it
 * in SovietGram Exclusive → Мемы.
 */
public class MemeFrameHelper {

    /** The two commands that trigger it. Anything else is an ordinary message. */
    private static final List<String> COMMANDS = Arrays.asList(".рамка", ". рамка");

    /** Beyond this the result is only bigger, not better, and Telegram would recompress it anyway. */
    private static final int MAX_OUTPUT_SIDE = 1100;
    /** Below this the caption stops being readable. */
    private static final int MIN_OUTPUT_SIDE = 620;
    private static final float MIN_OUTPUT_ASPECT = 0.42f;
    private static final float MAX_OUTPUT_ASPECT = 2.4f;
    /**
     * How far the photo may be stretched to fill the window before it is cropped instead. A tenth is
     * small enough that nobody notices and large enough to save most crops.
     */
    private static final float MIN_SQUISH = 0.90f;

    /** Words a client puts in a reply preview when there is no real caption; not worth quoting. */
    private static final Set<String> IGNORED_TEXTS = new HashSet<>(Arrays.asList(
            "photo", "image", "picture", "фото", "фотография", "изображение", "картинка",
            "photo message", "сообщение с фото"));

    private static final int MAX_QUOTE_LENGTH = 220;
    private static final int MAX_QUOTE_LINES = 4;

    private MemeFrameHelper() {
    }

    /** One of the bundled frames, with the hole the photo goes into. */
    private static final class Frame {
        final int resource;
        final int width;
        final int height;
        final int left;
        final int top;
        final int right;
        final int bottom;

        Frame(int resource, int width, int height, int left, int top, int right, int bottom) {
            this.resource = resource;
            this.width = width;
            this.height = height;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private static final Frame VERTICAL = new Frame(R.raw.meme_frame_vertical, 593, 800, 138, 132, 450, 670);
    private static final Frame HORIZONTAL = new Frame(R.raw.meme_frame_horizontal, 1280, 1098, 208, 192, 1059, 883);

    /**
     * Whether this outgoing message is the command rather than something to send.
     *
     * @return true when the send path should stop and call {@link #handle} instead.
     */
    public static boolean isCommand(@Nullable String message) {
        return NekoConfig.memeFrameEnabled.Bool()
                && message != null
                && COMMANDS.contains(message.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Builds the meme off the main thread and sends it in place of the command.
     *
     * @param replyTo the message the command replied to; its photo is the one that gets framed and
     *                its text becomes the caption.
     */
    public static void handle(AccountInstance account, long dialogId, @Nullable MessageObject replyTo,
                              boolean notify, int scheduleDate) {
        if (replyTo == null || !isPhoto(replyTo)) {
            error(getString(R.string.MemeFrameNoPhoto));
            return;
        }
        final File source = account.getFileLoader().getPathToMessage(replyTo.messageOwner);
        if (source == null || !source.exists() || source.length() == 0) {
            error(getString(R.string.MemeFrameNotLoaded));
            return;
        }
        final String quote = quoteText(replyTo);
        Utilities.globalQueue.postRunnable(() -> {
            File result = null;
            try {
                result = build(source, quote);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final File made = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (made == null) {
                    error(getString(R.string.MemeFrameFailed));
                    return;
                }
                send(account, dialogId, made, replyTo, notify, scheduleDate);
            });
        });
    }

    private static boolean isPhoto(MessageObject message) {
        // A sticker and a round video are photos as far as the media type goes, so ask the object
        // rather than the media class.
        return message.getDocument() == null && message.messageOwner != null
                && message.messageOwner.media instanceof TLRPC.TL_messageMediaPhoto;
    }

    private static void error(String text) {
        BulletinFactory.global().createErrorBulletin(text).show();
    }

    private static void send(AccountInstance account, long dialogId, File file, MessageObject replyTo,
                             boolean notify, int scheduleDate) {
        SendMessagesHelper.prepareSendingPhoto(account, file.getAbsolutePath(), null, dialogId,
                replyTo, null, null, null, null, null, null, 0, null, notify, scheduleDate, 0, null, 0);
    }

    // ---------------------------------------------------------------- drawing

    /** Draws the meme and writes it out; null when the photo could not be read. */
    @Nullable
    private static File build(File source, String quote) throws Exception {
        Bitmap photo = decode(source);
        if (photo == null) {
            return null;
        }
        photo = applyExif(photo, source);

        final int photoWidth = photo.getWidth();
        final int photoHeight = photo.getHeight();
        final Frame frame = chooseFrame(photoWidth, photoHeight);
        final float aspect = targetAspect(frame, photoWidth, photoHeight);

        int[] size = targetSize(photoWidth, photoHeight, aspect);
        int outWidth = size[0];
        int outHeight = size[1];
        int captionHeight = Math.max(78, Math.min(138, (int) (outWidth * 0.13f)));
        // The caption is added below the frame, so the total can overshoot the cap the frame alone
        // respected; shrink both together rather than letting the picture grow.
        final int total = Math.max(outWidth, outHeight + captionHeight);
        if (total > MAX_OUTPUT_SIDE) {
            final float scale = (float) MAX_OUTPUT_SIDE / total;
            outWidth = Math.max(1, (int) (outWidth * scale));
            outHeight = Math.max(1, (int) (outHeight * scale));
            captionHeight = Math.max(70, Math.min(126, (int) (outWidth * 0.13f)));
        }

        final Bitmap result = Bitmap.createBitmap(outWidth, outHeight + captionHeight, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(result);

        final Bitmap frameBitmap = decodeFrame(frame, outWidth, outHeight);
        final Rect window = scaledWindow(frame, outWidth, outHeight);
        final Bitmap filled = fill(photo, window.width(), window.height());
        if (photo != filled) {
            photo.recycle();
        }

        final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(filled, window.left, window.top, bitmapPaint);
        if (frameBitmap != null) {
            canvas.drawBitmap(frameBitmap, 0, 0, bitmapPaint);
            frameBitmap.recycle();
        }
        filled.recycle();

        drawQuote(canvas, quote, outWidth, outHeight, captionHeight);

        final File out = new File(getCacheDir(), "framed_meme_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream stream = new FileOutputStream(out)) {
            result.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } finally {
            result.recycle();
        }
        return out;
    }

    private static File getCacheDir() {
        File dir = ApplicationLoader.applicationContext.getExternalCacheDir();
        if (dir == null) {
            dir = ApplicationLoader.applicationContext.getCacheDir();
        }
        return dir;
    }

    /** Reads the photo at a size that leaves room for the compose without blowing up memory. */
    @Nullable
    private static Bitmap decode(File file) {
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        final int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        // Twice the output is enough detail after the downscale, and keeps the crop sharp.
        while (longest / sample > MAX_OUTPUT_SIDE * 2) {
            sample *= 2;
        }
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    /** Cameras store the orientation in the tag rather than the pixels, so rotate before measuring. */
    private static Bitmap applyExif(Bitmap bitmap, File file) {
        int degrees = 0;
        try {
            final int orientation = new ExifInterface(file.getAbsolutePath())
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                degrees = 90;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                degrees = 180;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                degrees = 270;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (degrees == 0) {
            return bitmap;
        }
        final Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        final Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
    }

    /** Whichever frame needs the least reshaping to hold a photo of this shape. */
    private static Frame chooseFrame(int width, int height) {
        return score(VERTICAL, width, height) <= score(HORIZONTAL, width, height) ? VERTICAL : HORIZONTAL;
    }

    private static float score(Frame frame, int width, int height) {
        final float base = (float) frame.width / frame.height;
        return Math.abs(targetAspect(frame, width, height) - base) / base;
    }

    /**
     * The aspect the finished picture wants: the photo's own shape corrected for the fact that the
     * hole in the frame is not the same shape as the frame itself.
     */
    private static float targetAspect(Frame frame, int width, int height) {
        final float photoAspect = Math.max(0.05f, (float) width / Math.max(1, height));
        final float windowWidth = (float) (frame.right - frame.left) / frame.width;
        final float windowHeight = (float) (frame.bottom - frame.top) / frame.height;
        final float aspect = photoAspect * windowHeight / windowWidth;
        return Math.max(MIN_OUTPUT_ASPECT, Math.min(MAX_OUTPUT_ASPECT, aspect));
    }

    private static int[] targetSize(int width, int height, float aspect) {
        final int longSide = Math.max(MIN_OUTPUT_SIDE, Math.min(MAX_OUTPUT_SIDE, Math.max(width, height)));
        if (aspect >= 1f) {
            return new int[]{longSide, Math.max(1, (int) (longSide / aspect))};
        }
        return new int[]{Math.max(1, (int) (longSide * aspect)), longSide};
    }

    @Nullable
    private static Bitmap decodeFrame(Frame frame, int width, int height) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        final Bitmap raw = BitmapFactory.decodeResource(
                ApplicationLoader.applicationContext.getResources(), frame.resource, options);
        if (raw == null) {
            return null;
        }
        if (raw.getWidth() == width && raw.getHeight() == height) {
            return raw;
        }
        final Bitmap scaled = Bitmap.createScaledBitmap(raw, width, height, true);
        if (scaled != raw) {
            raw.recycle();
        }
        return scaled;
    }

    /** The hole in the frame, in the coordinates of the finished picture. */
    private static Rect scaledWindow(Frame frame, int width, int height) {
        final int left = width * frame.left / frame.width;
        final int top = height * frame.top / frame.height;
        final int right = width * frame.right / frame.width;
        final int bottom = height * frame.bottom / frame.height;
        return new Rect(left, top, Math.max(left + 1, right), Math.max(top + 1, bottom));
    }

    /**
     * Fits the photo to the window: stretched slightly when that is enough to avoid losing anything,
     * centre-cropped when it is not.
     */
    private static Bitmap fill(Bitmap photo, int windowWidth, int windowHeight) {
        final float photoAspect = (float) photo.getWidth() / Math.max(1, photo.getHeight());
        final float windowAspect = (float) windowWidth / Math.max(1, windowHeight);
        final float ratio = photoAspect / Math.max(0.0001f, windowAspect);

        int fillWidth;
        int fillHeight;
        if (ratio < 1f) {
            final float squish = Math.max(MIN_SQUISH, ratio);
            fillWidth = windowWidth;
            fillHeight = (int) (windowHeight / squish);
        } else {
            final float squish = Math.max(MIN_SQUISH, 1f / ratio);
            fillHeight = windowHeight;
            fillWidth = (int) (windowWidth / squish);
        }
        // A pixel of slack on each side, so rounding never leaves a transparent seam at the edge.
        fillWidth = Math.max(windowWidth, fillWidth) + 2;
        fillHeight = Math.max(windowHeight, fillHeight) + 2;

        Bitmap scaled = Bitmap.createScaledBitmap(photo, fillWidth, fillHeight, true);
        final int x = Math.max(0, (scaled.getWidth() - windowWidth) / 2);
        final int y = Math.max(0, (scaled.getHeight() - windowHeight) / 2);
        final int cropWidth = Math.min(windowWidth, scaled.getWidth() - x);
        final int cropHeight = Math.min(windowHeight, scaled.getHeight() - y);
        Bitmap cropped = Bitmap.createBitmap(scaled, x, y, cropWidth, cropHeight);
        if (cropped != scaled) {
            scaled.recycle();
        }
        if (cropped.getWidth() != windowWidth || cropped.getHeight() != windowHeight) {
            final Bitmap exact = Bitmap.createScaledBitmap(cropped, windowWidth, windowHeight, true);
            if (exact != cropped) {
                cropped.recycle();
            }
            cropped = exact;
        }
        return cropped;
    }

    // ---------------------------------------------------------------- caption

    /** The black strip under the picture with the quote centred in it. */
    private static void drawQuote(Canvas canvas, String text, int width, int top, int height) {
        final Paint background = new Paint();
        background.setColor(Color.argb(255, 10, 10, 10));
        canvas.drawRect(0, top, width, top + height, background);

        if (TextUtils.isEmpty(text)) {
            return;
        }
        final String quote = "«" + text.trim().toUpperCase(Locale.ROOT) + "»";

        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.WHITE);
        fill.setTypeface(Typeface.DEFAULT_BOLD);
        final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.argb(180, 0, 0, 0));
        shadow.setTypeface(Typeface.DEFAULT_BOLD);

        final float maxTextWidth = width * 0.88f;
        float fontSize = Math.max(28f, Math.min(74f, width / 9.4f));
        List<String> lines;
        while (true) {
            fill.setTextSize(fontSize);
            lines = wrap(quote, fill, maxTextWidth);
            float widest = 0;
            for (String line : lines) {
                widest = Math.max(widest, fill.measureText(line));
            }
            final float totalHeight = lines.size() * fontSize * 1.18f;
            if (fontSize <= 20f || (widest <= maxTextWidth && totalHeight <= height * 0.82f)) {
                break;
            }
            fontSize -= 2f;
        }
        fill.setTextSize(fontSize);
        shadow.setTextSize(fontSize);

        final float lineHeight = fontSize * 1.18f;
        float y = top + Math.max(4f, height * 0.08f) + fontSize * 0.78f;
        for (String line : lines) {
            final float x = (width - fill.measureText(line)) / 2f;
            canvas.drawText(line, x + 2f, y + 2f, shadow);
            canvas.drawText(line, x, y, fill);
            y += lineHeight;
        }
    }

    /** Greedy word wrap, capped at four lines with an ellipsis when the text does not fit. */
    private static List<String> wrap(String text, Paint paint, float maxWidth) {
        final List<String> lines = new ArrayList<>();
        final String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();
        boolean truncated = false;
        for (String word : words) {
            final String candidate = current.length() == 0 ? word : current + " " + word;
            if (paint.measureText(candidate) <= maxWidth || current.length() == 0) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            lines.add(current.toString());
            current.setLength(0);
            if (lines.size() >= MAX_QUOTE_LINES) {
                truncated = true;
                break;
            }
            current.append(word);
        }
        if (!truncated && current.length() > 0 && lines.size() < MAX_QUOTE_LINES) {
            lines.add(current.toString());
        } else if (current.length() > 0) {
            truncated = true;
        }
        if (lines.isEmpty()) {
            lines.add(text);
        }
        if (truncated) {
            final int last = lines.size() - 1;
            String tail = lines.get(last);
            while (tail.length() > 0 && ".,!?;:".indexOf(tail.charAt(tail.length() - 1)) >= 0) {
                tail = tail.substring(0, tail.length() - 1);
            }
            lines.set(last, tail + "...");
        }
        return lines;
    }

    /** The text of the replied-to message, or an empty string when there is nothing worth quoting. */
    private static String quoteText(MessageObject message) {
        String candidate = firstNonEmpty(
                message.caption == null ? null : message.caption.toString(),
                message.messageText == null ? null : message.messageText.toString(),
                message.messageOwner == null ? null : message.messageOwner.message);
        if (candidate == null && message.messageOwner != null && message.messageOwner.media != null) {
            // Pre-layer-70 photos kept the caption on the media rather than on the message.
            candidate = firstNonEmpty(message.messageOwner.media.captionLegacy);
        }
        if (candidate == null) {
            return "";
        }
        String cleaned = candidate.replaceAll("\\s+", " ").trim();
        if (IGNORED_TEXTS.contains(cleaned.toLowerCase(Locale.ROOT))) {
            return "";
        }
        if (cleaned.length() > MAX_QUOTE_LENGTH) {
            cleaned = cleaned.substring(0, MAX_QUOTE_LENGTH).trim();
        }
        return cleaned;
    }

    @Nullable
    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }
}
