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

    // Returns "uplinkBytes,downlinkBytes" for the proxy outbound, cumulative for
    // the lifetime of the current engine instance.
    public static native String stats();
}
