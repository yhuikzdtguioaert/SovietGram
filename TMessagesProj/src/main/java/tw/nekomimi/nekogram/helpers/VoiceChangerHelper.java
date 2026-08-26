package tw.nekomimi.nekogram.helpers;

import com.google.android.exoplayer2.audio.Sonic;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Reshapes the microphone signal on its way to the encoder, so a voice message, a call or a voice
 * chat all go out in a different voice. The shift happens before encoding, which means the waveform
 * preview, the duration, the upload path and the call's own echo cancelling keep working untouched.
 *
 * <p>Sonic is fed with {@code speed = 1}, so a preset only moves the pitch and never the tempo:
 * a five second recording stays five seconds long whichever voice is picked.
 *
 * <p>A recorder and a call can be live at the same time, and each is handed PCM at its own rate and
 * channel count, so every stream keeps its own Sonic queue in a {@link Session}. The static methods
 * drive the recorder's session; the call's is reached through {@link #call()}.
 */
public final class VoiceChangerHelper {

    /** The rate the voice recorder hands over, and the fallback for a stream that reports none. */
    private static final int DEFAULT_SAMPLE_RATE = 48000;
    private static final int RING_MOD_HZ = 70;

    private static final Session recorderSession = new Session();
    private static final Session callSession = new Session();

    private VoiceChangerHelper() {
    }

    /** The session the VoIP capture thread rewrites its frames through. */
    public static Session call() {
        return callSession;
    }

    /** @return the pitch factor of a preset, {@code 1} for anything that leaves the voice alone. */
    private static float pitchOf(int preset) {
        return switch (preset) {
            case 1 -> 1.25f;
            case 2 -> 1.60f;
            case 3 -> 1.85f;
            case 4 -> 0.80f;
            case 5 -> 0.65f;
            case 6 -> 0.55f;
            default -> 1f;
        };
    }

    /** Reads the config once, when recording starts, so a mid-recording change cannot split a take. */
    public static void start() {
        recorderSession.start(DEFAULT_SAMPLE_RATE, 1);
    }

    public static void release() {
        recorderSession.release();
    }

    /** @see Session#process(ByteBuffer, int) */
    public static void process(ByteBuffer buffer, int length) {
        recorderSession.process(buffer, length);
    }

    /**
     * One independent stream of PCM. Nothing here is shared with another stream, so a voice message
     * recorded while a call is up cannot pick up the call's Sonic queue or its ring modulator phase.
     */
    public static final class Session {

        private int sampleRate = DEFAULT_SAMPLE_RATE;
        private int channels = 1;
        /** Never hold back more than a tenth of a second, so a preset cannot drift a stream late. */
        private int maxPending = DEFAULT_SAMPLE_RATE / 10;

        private Sonic sonic;
        private short[] pending = new short[0];
        private int pendingCount;
        private short[] scratch = new short[0];
        private float ringPhase;
        private float pitch = 1f;
        private boolean ringMod;
        private volatile boolean active;

        private Session() {
        }

        /** True once {@link #start} has found a preset that actually changes the voice. */
        public boolean isActive() {
            return active;
        }

        /**
         * Reads the config and drops whatever the previous stream left behind. {@code sampleRate} and
         * {@code channels} describe the interleaved PCM {@link #process} is about to be handed; a
         * call negotiates its own rate, so it is never safe to assume the recorder's.
         */
        public void start(int sampleRate, int channels) {
            final int preset = NekoConfig.voiceChangerPreset.Int();
            final boolean enabled = NekoConfig.voiceChangerEnabled.Bool();
            synchronized (this) {
                this.sampleRate = sampleRate > 0 ? sampleRate : DEFAULT_SAMPLE_RATE;
                this.channels = channels > 0 ? channels : 1;
                maxPending = this.sampleRate / 10 * this.channels;
                ringMod = enabled && preset == 7;
                pitch = enabled ? pitchOf(preset) : 1f;
                active = enabled && (ringMod || pitch != 1f);
                reset();
            }
        }

        public void release() {
            synchronized (this) {
                active = false;
                reset();
            }
        }

        private void reset() {
            sonic = null;
            pendingCount = 0;
            ringPhase = 0f;
        }

        /**
         * Rewrites {@code length} bytes of PCM at the start of {@code buffer} in place. The buffer is a
         * direct one the encoder reads through its native address, so it must never be swapped out for
         * another, and the position and limit it arrives with are restored before returning.
         */
        public void process(ByteBuffer buffer, int length) {
            if (!active || buffer == null || length < 2) {
                return;
            }
            synchronized (this) {
                if (!active) {
                    return;
                }
                try {
                    processInternal(buffer, length);
                } catch (Exception ignore) {
                    // A failed frame is better sent unchanged than not sent at all.
                }
            }
        }

        private void processInternal(ByteBuffer buffer, int length) {
            final int count = Math.min(length, buffer.capacity()) / 2;
            if (count <= 0) {
                return;
            }
            final int position = buffer.position();
            final int limit = buffer.limit();
            final ByteOrder order = buffer.order();
            buffer.order(ByteOrder.nativeOrder());
            try {
                if (scratch.length < count) {
                    scratch = new short[count];
                }
                buffer.limit(count * 2).position(0);
                buffer.asShortBuffer().get(scratch, 0, count);
                if (pitch != 1f) {
                    shift(count);
                }
                if (ringMod) {
                    ringModulate(count);
                }
                buffer.position(0);
                buffer.asShortBuffer().put(scratch, 0, count);
            } finally {
                buffer.limit(limit).position(position);
                buffer.order(order);
            }
        }

        /**
         * Runs the frame through Sonic. Sonic buffers a little before it produces anything, so the
         * first frames are padded with silence and the queue settles at a fixed delay after that.
         */
        private void shift(int count) {
            if (sonic == null) {
                sonic = new Sonic(sampleRate, channels, 1f, pitch, sampleRate);
            }
            sonic.queueInput(ShortBuffer.wrap(scratch, 0, count));
            // Sonic reports its output in bytes, so halving it gives interleaved samples whatever
            // the channel count is — the same unit count and scratch are measured in.
            int available = sonic.getOutputSize() / 2;
            if (available > 0) {
                ensurePending(pendingCount + available);
                ShortBuffer out = ShortBuffer.wrap(pending, pendingCount, available);
                sonic.getOutput(out);
                pendingCount += available;
            }
            if (pendingCount > maxPending) {
                final int drop = pendingCount - maxPending;
                System.arraycopy(pending, drop, pending, 0, pendingCount - drop);
                pendingCount -= drop;
            }
            final int take = Math.min(count, pendingCount);
            System.arraycopy(pending, 0, scratch, 0, take);
            for (int a = take; a < count; a++) {
                scratch[a] = 0;
            }
            System.arraycopy(pending, take, pending, 0, pendingCount - take);
            pendingCount -= take;
        }

        private void ensurePending(int needed) {
            if (pending.length < needed) {
                short[] grown = new short[Math.max(needed, pending.length * 2)];
                System.arraycopy(pending, 0, grown, 0, pendingCount);
                pending = grown;
            }
        }

        /** The metallic half of the robot preset: a slow carrier riding on top of the voice. */
        private void ringModulate(int count) {
            final float step = (float) (2 * Math.PI * RING_MOD_HZ / sampleRate);
            final float twoPi = (float) (2 * Math.PI);
            for (int a = 0; a < count; a++) {
                // Amplitude modulation rather than a true ring: the voice keeps its shape and only
                // gains the buzz, where a full multiply would gate it into clicks.
                float gain = 0.45f + 0.55f * (float) Math.sin(ringPhase);
                // One step per frame, so the carrier keeps its pitch on a stereo stream too.
                if (channels <= 1 || (a % channels) == channels - 1) {
                    ringPhase += step;
                    if (ringPhase > twoPi) {
                        ringPhase -= twoPi;
                    }
                }
                int value = (int) (scratch[a] * gain);
                scratch[a] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
            }
        }
    }
}
