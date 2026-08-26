package tw.nekomimi.nekogram.helpers.lyrics;

/**
 * One line of a song. {@link #time} is the second the line is sung at, or {@code -1} for lyrics
 * that came without timestamps.
 */
public class LyricLine {

    public final double time;
    public final String text;
    /** A stretch of music with no words — rendered as a single note instead of a lyric. */
    public final boolean pause;

    public LyricLine(double time, String text, boolean pause) {
        this.time = time;
        this.text = text;
        this.pause = pause;
    }

    public LyricLine(double time, String text) {
        this(time, text, false);
    }
}
