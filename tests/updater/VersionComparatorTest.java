import tw.nekomimi.nekogram.helpers.remote.IndependentVersionComparator;

/** Exercises the production comparator without Android or Gradle. */
public final class VersionComparatorTest {
    private static int passed;
    private static int failed;
    private static void check(String name, boolean expected, int remoteCode, String remoteName,
                              int localCode, String localName) {
        boolean actual = IndependentVersionComparator.isUpdate(remoteCode, remoteName, localCode, localName);
        if (actual != expected) {
            failed++;
            System.err.println("FAIL: " + name + " expected=" + expected + " actual=" + actual);
        } else {
            passed++;
        }
    }
    public static void main(String[] args) {
        check("installed build with higher source label must not re-notify", false, 1261, "12.11.0", 1261, "12.10.6");
        check("older build cannot become an update through source version", false, 1259, "13.0.0", 1261, "12.10.6");
        check("new build can keep identical source version", true, 1261, "12.10.6", 1259, "12.10.6");
        check("new build remains authoritative after source version rollback", true, 1262, "12.9.0", 1261, "12.10.6");
        check("exact installed release", false, 1261, "12.10.6", 1261, "12.10.6");
        check("unknown remote build cannot rely on source semver", false, 0, "13.0.0", 1261, "12.10.6");
        check("negative remote build is invalid", false, -1, "13.0.0", 1261, "12.10.6");
        check("commit suffix cannot change installed build", false, 1261, "12.10.6-abcdef9", 1261, "12.10.6-0000001");
        check("source semver ordering is irrelevant to equal build", false, 1261, "12.10.10", 1261, "12.10.9");
        check("1260 filename on real 1259 still claims newer: metadata cannot prove archive", true, 1260, "12.10.6", 1259, "12.10.6");
        check("real patch 1261 stops mislabeled 1260 offer", false, 1260, "12.10.6", 1261, "12.10.6");
        System.out.println("VersionComparatorTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError("Updater regression failures: " + failed);
    }
}
