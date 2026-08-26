package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

import java.util.List;

/**
 * Sends a gallery video as a round video message — the same thing the camera records when the mic
 * button is switched to video, only from a file the user already has.
 * <p>
 * Always available, no setting: the entry point is the long press on the send button, both in the
 * attachment sheet and in the media editor, next to the other send options.
 * <p>
 * A round message has to be square, silent-friendly and under a minute, none of which the normal
 * send path arranges, so the {@link VideoEditedInfo} is built here: a centred square crop, a square
 * result size and the selection clamped to 60 seconds. After that {@code roundVideo = true} is all
 * {@link SendMessagesHelper#prepareSendingVideo} needs to produce a video message.
 */
public class RoundVideoHelper {

    /** What Telegram accepts for a round message; anything larger is re-encoded down to it anyway. */
    private static final int MAX_SIZE = 640;
    /**
     * Just under a minute. The server rejects 60s exactly often enough that the reference plugin
     * left the same margin.
     */
    private static final long MAX_DURATION_MS = 59_950L;
    /**
     * A face is normally in the upper half of a portrait video, so a square cut from the middle
     * tends to cut it off. Biasing the window up matches what the editor's own round crop does.
     */
    private static final float PORTRAIT_CROP_BIAS = 0.3f;

    private RoundVideoHelper() {
    }

    /** Whether the entry can be offered as a round video at all. */
    public static boolean canSend(@Nullable MediaController.PhotoEntry entry) {
        if (entry == null || !entry.isVideo || entry.path == null) {
            return false;
        }
        // A GIF goes out as an animation, not as a video stream, so there is nothing to make round.
        return !entry.path.toLowerCase().endsWith(".gif");
    }

    /**
     * Sends a whole selection as round messages, one message per entry, in the order given.
     * <p>
     * Both queues involved are serial, so the messages come out in that order without any extra
     * sequencing here.
     */
    public static void send(ChatActivity chatActivity, List<MediaController.PhotoEntry> entries,
                            boolean notify, int scheduleDate) {
        if (entries == null) {
            return;
        }
        for (MediaController.PhotoEntry entry : entries) {
            send(chatActivity, entry, null, notify, scheduleDate);
        }
    }

    /**
     * Sends one gallery entry as a round message.
     *
     * @param editedInfo what the editor produced for this entry, if it was opened; the trim and the
     *                   crop the user made there are kept and only squared up. When null the entry's
     *                   own {@code editedInfo} is used, which is where the attachment sheet keeps it.
     */
    public static void send(ChatActivity chatActivity, MediaController.PhotoEntry entry,
                            @Nullable VideoEditedInfo editedInfo, boolean notify, int scheduleDate) {
        if (chatActivity == null || !canSend(entry)) {
            return;
        }
        // Probing the file reads it, so it cannot happen on the main thread; the send itself has to.
        final VideoEditedInfo known = editedInfo != null ? editedInfo : entry.editedInfo;
        Utilities.globalQueue.postRunnable(() -> {
            VideoEditedInfo info = known;
            if (info == null) {
                try {
                    info = SendMessagesHelper.createCompressionSettings(entry.path, 0);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (info == null) {
                info = fallbackInfo(entry);
            }
            final VideoEditedInfo prepared = info;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    apply(prepared, entry);
                    dispatch(chatActivity, entry, prepared, notify, scheduleDate);
                } catch (Exception e) {
                    FileLog.e(e);
                    BulletinFactory.of(chatActivity)
                            .createErrorBulletin(getString(R.string.RoundVideoSendError))
                            .show();
                }
            });
        });
    }

    /** Everything the probe would have filled in, taken from what the gallery already knows. */
    private static VideoEditedInfo fallbackInfo(MediaController.PhotoEntry entry) {
        VideoEditedInfo info = new VideoEditedInfo();
        info.originalPath = entry.path;
        info.originalWidth = entry.width;
        info.originalHeight = entry.height;
        info.rotationValue = entry.orientation;
        info.originalDuration = entry.duration * 1000L * 1000L;
        info.estimatedDuration = entry.duration * 1000L;
        info.framerate = 30;
        info.bitrate = 1_000_000;
        info.estimatedSize = info.estimatedDuration * info.bitrate / 8 / 1000;
        return info;
    }
    /** Turns a normal video description into a round one, in place. */
    private static void apply(VideoEditedInfo info, MediaController.PhotoEntry entry) {
        final long durationMs = durationMs(info, entry);
        if (durationMs > 0) {
            clampDuration(info, durationMs);
        }

        int width = info.originalWidth > 0 ? info.originalWidth : entry.width;
        int height = info.originalHeight > 0 ? info.originalHeight : entry.height;
        if (width <= 0) {
            width = MAX_SIZE;
        }
        if (height <= 0) {
            height = MAX_SIZE;
        }

        final int size = Math.min(Math.min(width, height), MAX_SIZE);
        MediaController.CropState crop = info.cropState;
        if (crop == null) {
            crop = new MediaController.CropState();
        }
        if (!crop.initied) {
            square(crop, width, height);
        } else {
            squareExisting(crop, width, height);
        }
        crop.transformWidth = size;
        crop.transformHeight = size;
        info.cropState = crop;

        info.roundVideo = true;
        // The reference plugin mutes here. Doing that adds TL_documentAttributeAnimated further down
        // prepareSendingVideo, which makes the result a GIF rather than a video message, and a round
        // message is allowed sound anyway — so the audio is kept.
        info.muted = false;
        info.resultWidth = size;
        info.resultHeight = size;
    }

    /** The clip length in milliseconds, whichever of the two sources actually knows it. */
    private static long durationMs(VideoEditedInfo info, MediaController.PhotoEntry entry) {
        // originalDuration is microseconds when the probe filled it in, but callers that built the
        // object by hand have been seen to leave milliseconds in it, hence the magnitude check.
        final long known = info.originalDuration;
        if (known > 100_000L) {
            return known / 1000L;
        }
        if (known > 0) {
            return known;
        }
        return entry.duration > 100_000L ? entry.duration : entry.duration * 1000L;
    }

    /** Trims the selection down to what the server takes, keeping the user's start point if it can. */
    private static void clampDuration(VideoEditedInfo info, long durationMs) {
        info.originalDuration = durationMs * 1000L;

        final float end = info.end > 0f ? info.end : 1f;
        final float selectedMs = (end - info.start) * durationMs;
        if (selectedMs <= MAX_DURATION_MS) {
            return;
        }

        final float window = (float) MAX_DURATION_MS / durationMs;
        float newStart = info.start;
        float newEnd = info.start + window;
        if (newEnd > 1f) {
            newEnd = 1f;
            newStart = Math.max(0f, newEnd - window);
        }
        info.start = newStart;
        info.end = newEnd;
        info.startTime = (long) (newStart * durationMs * 1000L);
        info.endTime = (long) (newEnd * durationMs * 1000L);
        info.estimatedDuration = MAX_DURATION_MS;
        if (info.estimatedSize > 0) {
            info.estimatedSize = (long) (info.estimatedSize * (MAX_DURATION_MS / selectedMs));
        }
    }

    /** A square window over the untouched frame: centred across, biased up when the video is tall. */
    private static void square(MediaController.CropState crop, int width, int height) {
        crop.cropPw = width > height ? (float) height / width : 1f;
        crop.cropPh = height > width ? (float) width / height : 1f;
        crop.cropPx = width > height ? (1f - crop.cropPw) / 2f : 0f;
        crop.cropPy = height > width ? (1f - crop.cropPh) * PORTRAIT_CROP_BIAS : 0f;
        crop.initied = true;
    }

    /**
     * Shrinks a crop the user already made in the editor to the largest square inside it, moving the
     * window only along the axis that had to give, so what they framed stays framed.
     */
    private static void squareExisting(MediaController.CropState crop, int width, int height) {
        final float pw = crop.cropPw > 0f ? crop.cropPw : 1f;
        final float ph = crop.cropPh > 0f ? crop.cropPh : 1f;
        final float cropWidth = width * pw;
        final float cropHeight = height * ph;
        final float target = Math.min(cropWidth, cropHeight);

        final float newPw = target / width;
        final float newPh = target / height;
        if (cropWidth > cropHeight) {
            crop.cropPx += (pw - newPw) / 2f;
        } else if (cropHeight > cropWidth) {
            crop.cropPy += (ph - newPh) / 2f;
        }
        crop.cropPw = newPw;
        crop.cropPh = newPh;
    }

    /**
     * Hands the entry to the chat's own send path rather than calling
     * {@link SendMessagesHelper#prepareSendingVideo} directly, so the reply, the quote, the quick
     * reply, the paid-message confirmation and the instant-camera close animation all still happen.
     */
    private static void dispatch(ChatActivity chatActivity, MediaController.PhotoEntry entry,
                                 VideoEditedInfo info, boolean notify, int scheduleDate) {
        chatActivity.sendMedia(entry, info, notify, scheduleDate, 0, false, 0);
    }
}
