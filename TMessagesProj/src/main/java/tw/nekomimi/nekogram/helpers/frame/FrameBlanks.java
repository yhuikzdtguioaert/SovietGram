package tw.nekomimi.nekogram.helpers.frame;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import androidx.annotation.Nullable;

import org.telegram.messenger.FileLog;

import java.util.HashMap;
import java.util.Map;

/**
 * The eight pictures a frame can use without fetching anything: a ribbon, a ring, a stroke, a bead,
 * a dot, a gem, a star, a spark.
 *
 * <p>Drawn in code at the reference's own sizes, because those sizes are what its measurements
 * assume — a ribbon is 8×48 and a star is 64×64, and a layer's {@code width} is read against the
 * picture's height. Cached forever: there are eight of them and they are tiny.
 */
public final class FrameBlanks {

    public static final String SCHEME = "blank:";

    public static final String RIM = "blank:rim";
    public static final String RING = "blank:ring";
    public static final String STRIPE = "blank:stripe";
    public static final String BEAD = "blank:bead";
    public static final String DOT = "blank:dot";
    public static final String GEM = "blank:gem";
    public static final String STAR = "blank:star";
    public static final String SPARK = "blank:spark";

    public static final String[] ALL = {RIM, RING, STRIPE, BEAD, DOT, GEM, STAR, SPARK};

    private static final Map<String, Bitmap> CACHE = new HashMap<>();

    private FrameBlanks() {
    }

    public static boolean is(@Nullable String src) {
        return src != null && src.startsWith(SCHEME);
    }

    /** The bare name of a blank, for a picker to label. Not a blank, not a name. */
    @Nullable
    public static String name(@Nullable String src) {
        return is(src) ? src.substring(SCHEME.length()) : null;
    }

    @Nullable
    public static Bitmap bitmap(@Nullable String src) {
        if (!is(src)) {
            return null;
        }
        synchronized (CACHE) {
            final Bitmap cached = CACHE.get(src);
            if (cached != null && !cached.isRecycled()) {
                return cached;
            }
            final Bitmap drawn = draw(src);
            if (drawn != null) {
                CACHE.put(src, drawn);
            }
            return drawn;
        }
    }

    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    @Nullable
    private static Bitmap draw(String src) {
        try {
            return switch (src) {
                case RIM -> rim();
                case RING -> ring();
                case STRIPE -> stripe();
                case BEAD -> bead();
                case DOT -> dot();
                case GEM -> gem();
                case STAR -> star();
                case SPARK -> spark();
                default -> null;
            };
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static Bitmap rim() {
        final Bitmap bitmap = Bitmap.createBitmap(8, 48, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, 48,
                new int[]{0xFF9A9A9A, 0xFFFFFFFF, 0xFFB4B4B4, 0xFF8C8C8C},
                new float[]{0f, 0.35f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 8, 48, p);
        return bitmap;
    }

    private static Bitmap ring() {
        final Bitmap bitmap = Bitmap.createBitmap(8, 48, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        canvas.drawRect(0, 0, 8, 48, p);
        return bitmap;
    }

    private static Bitmap stripe() {
        final Bitmap bitmap = Bitmap.createBitmap(24, 48, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        final Path path = new Path();
        path.moveTo(24 * 0.62f, 0);
        path.lineTo(24, 0);
        path.lineTo(24 * 0.38f, 48);
        path.lineTo(0, 48);
        path.close();
        canvas.drawPath(path, p);
        return bitmap;
    }

    private static Bitmap bead() {
        final Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(64 * 0.38f, 64 * 0.34f, 64 * 0.66f,
                new int[]{0xFFFFFFFF, 0xFFDCDCDC, 0xFF8E8E8E},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(32, 32, 31, p);
        return bitmap;
    }

    private static Bitmap dot() {
        final Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        canvas.drawCircle(16, 16, 15, p);
        return bitmap;
    }

    private static Bitmap gem() {
        final Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        path.moveTo(32, 1);
        path.lineTo(63, 32);
        path.lineTo(32, 63);
        path.lineTo(1, 32);
        path.close();
        p.setShader(new LinearGradient(0, 0, 64, 64,
                new int[]{0xFFFFFFFF, 0xFFC9C9C8, 0xFF9051D0},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(path, p);
        p.setShader(null);
        p.setColor(0x66FFFFFF);
        final Path highlight = new Path();
        highlight.moveTo(32, 1);
        highlight.lineTo(63, 32);
        highlight.lineTo(32, 32);
        highlight.close();
        canvas.drawPath(highlight, p);
        return bitmap;
    }

    private static Bitmap star() {
        final Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        final Path path = new Path();
        for (int i = 0; i < 10; i++) {
            final float radius = i % 2 == 0 ? 31f : 32f * 0.42f;
            final double angle = i * Math.PI / 5 - Math.PI / 2;
            final float x = 32 + (float) Math.cos(angle) * radius;
            final float y = 32 + (float) Math.sin(angle) * radius;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
        canvas.drawPath(path, p);
        return bitmap;
    }

    private static Bitmap spark() {
        final Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        final float waist = 64 * 0.14f;
        final float near = 32 + waist;
        final float far = 32 - waist;
        final Path path = new Path();
        path.moveTo(32, 0);
        path.quadTo(near, far, 64, 32);
        path.quadTo(near, near, 32, 64);
        path.quadTo(far, near, 0, 32);
        path.quadTo(far, far, 32, 0);
        path.close();
        canvas.drawPath(path, p);
        return bitmap;
    }
}
