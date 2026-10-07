package tw.nekomimi.nekogram.helpers.remote;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android's monotonically increasing app build is the only install/update ordering key. */
public final class IndependentVersionComparator {
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private IndependentVersionComparator() {
    }

    public static boolean isUpdate(int remoteCode, String remoteName, int localCode, String localName) {
        // versionName describes Telegram sources, not Android installability. Equal/older
        // builds must never reappear just because that label (or a commit suffix) differs.
        // Missing build metadata is not sufficient evidence of an installable update.
        return remoteCode > 0 && remoteCode > localCode;
    }

    public static int compareReleaseNames(String left, String right) {
        int[] a = numericVersion(left);
        int[] b = numericVersion(right);
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private static int[] numericVersion(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new int[0];
        }
        String release = value.trim();
        int dash = release.indexOf('-');
        int plus = release.indexOf('+');
        int suffix = dash < 0 ? plus : plus < 0 ? dash : Math.min(dash, plus);
        if (suffix >= 0) {
            release = release.substring(0, suffix);
        }
        Matcher matcher = NUMBER.matcher(release);
        ArrayList<Integer> parts = new ArrayList<>();
        while (matcher.find() && parts.size() < 4) {
            try {
                parts.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
                parts.add(0);
            }
        }
        int[] result = new int[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            result[i] = parts.get(i);
        }
        return result;
    }
}
