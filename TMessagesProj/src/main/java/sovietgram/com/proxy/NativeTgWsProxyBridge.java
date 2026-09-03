package sovietgram.com.proxy;

public final class NativeTgWsProxyBridge {

    static {
        System.loadLibrary("tgwsproxy");
    }

    private NativeTgWsProxyBridge() {
    }

    public static native int start(String host, int port, String dcIps, String secret, boolean verbose);

    public static native int stop();

    public static native void setPoolSize(int size);

    public static native void setCfProxyCacheDir(String cacheDir);

    public static native void setCfProxyConfig(boolean enabled, boolean priority, String userDomain);

    public static native String getStats();
}
