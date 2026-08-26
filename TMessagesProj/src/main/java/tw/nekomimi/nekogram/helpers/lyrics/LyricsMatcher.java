package tw.nekomimi.nekogram.helpers.lyrics;

import android.text.TextUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a lyrics search hit is actually the track that is playing. Titles come back from
 * providers with remix tags, featured artists and transliterations attached, so the comparison is
 * done on a stripped-down form of each string and scored rather than matched outright.
 */
public final class LyricsMatcher {

    private static final Pattern PARENS = Pattern.compile("\\([^)]*\\)|\\[[^]]*]");
    private static final Pattern FEAT = Pattern.compile("(?i)\\b(feat\\.?|ft\\.?)\\b.*$");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}_]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** Titles so generic that a strong artist match cannot rescue them. */
    private static final String[] AMBIGUOUS = {"intro", "outro", "interlude", "untitled", "track"};

    private LyricsMatcher() {
    }

    /**
     * Reduces a title or artist to letters, digits and single spaces: brackets and everything after
     * a "feat." are dropped, since providers and tags disagree about them constantly.
     */
    public static String normalizeKey(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        String out = value.toLowerCase();
        out = PARENS.matcher(out).replaceAll(" ");
        out = FEAT.matcher(out).replaceAll(" ");
        out = NON_WORD.matcher(out).replaceAll(" ");
        out = SPACES.matcher(out).replaceAll(" ");
        return out.trim();
    }

    public static boolean isAmbiguous(String normalizedTitle) {
        for (String word : AMBIGUOUS) {
            if (normalizedTitle.equals(word) || normalizedTitle.startsWith(word + " ")) {
                return true;
            }
        }
        return false;
    }

    /**
     * How alike two normalized strings are, 0..1. Trigram overlap catches reordering and small
     * spelling drift; edit distance takes over when the strings are too short for trigrams to say
     * much. Tracks that differ only by a number ("Part 1" vs "Part 2") are pushed apart on purpose.
     */
    public static float similarity(String a, String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) {
            return 0f;
        }
        String sa = a.replace(" ", "");
        String sb = b.replace(" ", "");
        float trigram = trigramSimilarity(sa, sb);
        float base;
        if (trigram >= 0.9f) {
            base = trigram;
        } else {
            base = Math.max(trigram, levenshteinRatio(sa, sb));
        }
        return base * digitPenalty(a, b);
    }

    private static Set<String> trigrams(String value) {
        Set<String> out = new HashSet<>();
        if (value.isEmpty()) {
            return out;
        }
        if (value.length() < 3) {
            out.add(value);
            return out;
        }
        for (int a = 0; a + 3 <= value.length(); a++) {
            out.add(value.substring(a, a + 3));
        }
        return out;
    }

    private static float trigramSimilarity(String a, String b) {
        Set<String> sa = trigrams(a);
        Set<String> sb = trigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0f;
        }
        int shared = 0;
        for (String value : sa) {
            if (sb.contains(value)) {
                shared++;
            }
        }
        return shared / (float) Math.max(sa.size(), sb.size());
    }

    private static float levenshteinRatio(String a, String b) {
        int min = Math.min(a.length(), b.length());
        if (min == 0) {
            return 0f;
        }
        return Math.max(0f, 1f - levenshtein(a, b) / (float) min);
    }

    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** Halves the score when both strings are numbered but the numbers disagree. */
    private static float digitPenalty(String a, String b) {
        Set<String> da = digitGroups(a);
        Set<String> db = digitGroups(b);
        if (da.isEmpty() || db.isEmpty()) {
            return 1f;
        }
        for (String value : da) {
            if (db.contains(value)) {
                return 1f;
            }
        }
        return 0.5f;
    }

    private static Set<String> digitGroups(String value) {
        Set<String> out = new HashSet<>();
        Matcher matcher = DIGITS.matcher(value);
        while (matcher.find()) {
            out.add(matcher.group());
        }
        return out;
    }

    /** How confident we are overall, used to pick between providers. */
    public static float confidence(float titleSim, float artistSim, boolean synced) {
        float value = titleSim * 0.6f + artistSim * 0.4f;
        if (synced) {
            value += 0.05f;
        }
        return value;
    }
}
