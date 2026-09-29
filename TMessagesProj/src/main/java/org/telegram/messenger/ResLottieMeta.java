package org.telegram.messenger;

import android.annotation.SuppressLint;

import androidx.annotation.RawRes;

import org.telegram.tgnet.SerializedData;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

public final class ResLottieMeta {

    /*
     * Packed long layout:
     *
     * bits 63..32 — raw resource ID
     * bits 31..24 — FPS
     * bit  23     — monocolor
     * bits 22..0  — frame count
     */
    public static final long NOT_FOUND = -1L;

    private static final String ASSET_NAME = "lottie_meta.bin";
    private static final int ENTRY_SIZE = 8;

    private ResLottieMeta() {
    }

    private static final class Holder {
        private static final long[] DATA = load();
    }

    private static long[] load() {
        try (InputStream stream = new BufferedInputStream(
                ApplicationLoader.applicationContext
                        .getResources()
                        .getAssets()
                        .open(ASSET_NAME))) {

            final int byteCount = stream.available();
            if (byteCount % ENTRY_SIZE != 0) {
                throw new IllegalStateException(
                        ASSET_NAME + " has invalid size: " + byteCount
                );
            }

            final int count = byteCount / ENTRY_SIZE;
            final long[] result = new long[count];
            final byte[] bytes = new byte[byteCount];
            int offset = 0;
            while (offset < byteCount) {
                int read = stream.read(bytes, offset, byteCount - offset);
                if (read < 0) {
                    throw new IOException("Unexpected end of " + ASSET_NAME);
                }
                offset += read;
            }
            final SerializedData data = new SerializedData(bytes);

            for (int i = 0; i < count; i++) {
                result[i] = data.readInt64(true);
            }

            return result;
        } catch (IOException e) {
            // Older branded builds do not ship the generated metadata asset yet.
            // Lottie can still parse those resources directly, so keep startup
            // working and use the regular metadata path for every animation.
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("Unable to load " + ASSET_NAME + "; using runtime Lottie metadata", e);
            }
            return new long[0];
        } catch (RuntimeException e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("Unable to load " + ASSET_NAME, e);
            }
            throw e;
        }
    }

    /**
     * Returns packed metadata for the resource, or {@link #NOT_FOUND}.
     */
    @SuppressLint("ResourceType")
    public static long find(@RawRes int resId) {
        final long[] data = Holder.DATA;

        int low = 0;
        int high = data.length - 1;

        while (low <= high) {
            final int middle = (low + high) >>> 1;
            final long packed = data[middle];
            final int middleResId = resIdOf(packed);

            if (middleResId < resId) {
                low = middle + 1;
            } else if (middleResId > resId) {
                high = middle - 1;
            } else {
                return packed;
            }
        }

        return NOT_FOUND;
    }
    public static int fpsOf(long packed) {
        return (int) ((packed >>> 24) & 0xFFL);
    }
    public static boolean isMonoColorOf(long packed) {
        return (packed & (1L << 23)) != 0;
    }
    public static int frameCountOf(long packed) {
        return (int) (packed & 0x7FFFFFL);
    }
    public static int resIdOf(long packed) {
        return (int) (packed >>> 32);
    }
}
