package tw.nekomimi.nekogram.helpers.lyrics;

import java.util.ArrayList;
import java.util.List;

/** What a lyrics provider came back with for one track. */
public class LyricsResult {

    public final List<LyricLine> lines = new ArrayList<>();
    /** True when the lines carry timestamps and can follow playback. */
    public boolean synced;
    /** True when the track is known to have no words at all. */
    public boolean instrumental;
    /** Provider name to credit under the lyrics. */
    public String source;
    /** How well the provider's track details matched the one playing, 0..1. */
    public float confidence;

    public boolean isEmpty() {
        return lines.isEmpty();
    }
}
