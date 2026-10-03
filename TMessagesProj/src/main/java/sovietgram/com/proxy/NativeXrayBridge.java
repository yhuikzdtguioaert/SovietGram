package sovietgram.com.proxy;

public final class NativeXrayBridge {

    static {
        System.loadLibrary("xray");
    }

    private NativeXrayBridge() {
    }

    // Returns "" on success, otherwise a human-readable error message.
    public static native String start(String configJson);

    public static native void stop();

    public static native boolean isRunning();

    // Milliseconds one HTTP request took through the server in the config, as a decimal string,
    // or "error:" and the reason. Runs its own core instance and never touches the tunnel.
    public static native String ping(String configJson, String method, String url, int timeoutMs);

    // Returns "uplinkBytes,downlinkBytes" for the proxy outbound, cumulative for
    // the lifetime of the current engine instance.
    public static native String stats();
}
