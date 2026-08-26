package tw.nekomimi.nekogram.helpers.lyrics;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns LRC text into timed lines, and marks the instrumental stretches between them so the viewer
 * shows a note instead of leaving the last sung line highlighted for half a minute.
 */
public final class LrcParser {

    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern BRACKETED = Pattern.compile("^\\[.+]$");

    private static final double MUSIC_GAP_THRESHOLD_SEC = 5.0;
    private static final String MUSIC_GAP_MARKER = "♪";

    private LrcParser() {
    }

    /**
     * @param duration track length in seconds, or 0 when unknown — only used to decide whether the
     *                 last line is followed by a long instrumental outro.
     */
    public static List<LyricLine> parse(String lrc, double duration) {
        List<LyricLine> lines = new ArrayList<>();
        List<Double> emptyEvents = new ArrayList<>();
        if (TextUtils.isEmpty(lrc)) {
            return lines;
        }
        for (String raw : lrc.split("\n")) {
            Matcher matcher = TIMESTAMP.matcher(raw);
            List<Double> times = new ArrayList<>();
            while (matcher.find()) {
                times.add(toSeconds(matcher.group(1), matcher.group(2), matcher.group(3)));
            }
            if (times.isEmpty()) {
                // Metadata tags such as [ar:...] never match, so they drop out here.
                continue;
            }
            String text = TIMESTAMP.matcher(raw).replaceAll("").trim();
            if (BRACKETED.matcher(text).matches()) {
                // Section headers like [Chorus].
                continue;
            }
            for (int a = 0; a < times.size(); a++) {
                double time = times.get(a);
                if (text.isEmpty()) {
                    emptyEvents.add(time);
                } else {
                    lines.add(new LyricLine(time, text));
                }
            }
        }
        Collections.sort(lines, (a, b) -> Double.compare(a.time, b.time));
        Collections.sort(emptyEvents);
        return injectMusicGapMarkers(lines, emptyEvents, duration);
    }

    private static double toSeconds(String minutes, String seconds, String fraction) {
        double time = Integer.parseInt(minutes) * 60.0 + Integer.parseInt(seconds);
        if (fraction != null) {
            if (fraction.length() == 1) {
                time += Integer.parseInt(fraction) / 10.0;
            } else if (fraction.length() == 2) {
                time += Integer.parseInt(fraction) / 100.0;
            } else {
                time += Integer.parseInt(fraction.substring(0, 3)) / 1000.0;
            }
        }
        return time;
    }

    /**
     * Inserts a note wherever the song goes quiet for {@link #MUSIC_GAP_THRESHOLD_SEC} or more. A
     * line's end is taken from a blank LRC timestamp inside the gap when the file has one; otherwise
     * it is estimated from how long the line takes to sing.
     */
    private static List<LyricLine> injectMusicGapMarkers(List<LyricLine> lines, List<Double> emptyEvents, double duration) {
        List<LyricLine> out = new ArrayList<>();
        if (lines.isEmpty()) {
            return out;
        }
        if (lines.get(0).time >= MUSIC_GAP_THRESHOLD_SEC) {
            out.add(new LyricLine(0.0, MUSIC_GAP_MARKER, true));
        }
        for (int a = 0; a < lines.size(); a++) {
            LyricLine line = lines.get(a);
            out.add(line);
            double currentStart = line.time;
            double nextStart;
            if (a + 1 < lines.size()) {
                nextStart = lines.get(a + 1).time;
            } else if (duration > 0) {
                nextStart = duration;
            } else {
                continue;
            }
            if (nextStart <= currentStart) {
                continue;
            }
            double lineEnd = -1;
            for (int b = 0; b < emptyEvents.size(); b++) {
                double event = emptyEvents.get(b);
                if (event > currentStart && event < nextStart) {
                    lineEnd = Math.min(Math.max(event, currentStart), nextStart);
                    break;
                }
            }
            if (lineEnd < 0) {
                double estimate = Math.min(10.0, Math.max(2.0, line.text.length() / 7.0));
                lineEnd = Math.min(Math.max(currentStart + estimate, currentStart), nextStart);
            }
            if (nextStart - lineEnd < MUSIC_GAP_THRESHOLD_SEC) {
                continue;
            }
            LyricLine last = out.get(out.size() - 1);
            if (last.pause && Math.abs(last.time - lineEnd) < 0.05) {
                continue;
            }
            out.add(new LyricLine(lineEnd, MUSIC_GAP_MARKER, true));
        }
        Collections.sort(out, (a, b) -> Double.compare(a.time, b.time));
        return out;
    }

    /** Lyrics that arrived without timestamps — kept line for line, blank rows included. */
    public static List<LyricLine> parsePlain(String plain) {
        List<LyricLine> lines = new ArrayList<>();
        if (TextUtils.isEmpty(plain)) {
            return lines;
        }
        for (String raw : plain.split("\n")) {
            lines.add(new LyricLine(-1, raw.trim()));
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).text.isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
