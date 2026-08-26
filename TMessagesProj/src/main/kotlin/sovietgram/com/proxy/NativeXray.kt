package sovietgram.com.proxy

object NativeXray {
    /**
     * (Re)starts the native Xray core with the given JSON config.
     * Returns an empty string on success, otherwise a human-readable error message.
     */
    fun start(configJson: String): String {
        return NativeXrayBridge.start(configJson).orEmpty()
    }

    fun stop() {
        NativeXrayBridge.stop()
    }

    fun isRunning(): Boolean {
        return NativeXrayBridge.isRunning()
    }

    /**
     * Cumulative proxy-outbound traffic as `uplink to downlink` byte counts, or
     * null when the engine is down or the counters are not available yet.
     */
    fun trafficBytes(): Pair<Long, Long>? {
        val raw = runCatching { NativeXrayBridge.stats() }.getOrNull().orEmpty()
        val parts = raw.split(',')
        if (parts.size != 2) {
            return null
        }
        val up = parts[0].trim().toLongOrNull() ?: return null
        val down = parts[1].trim().toLongOrNull() ?: return null
        return up to down
    }
}
