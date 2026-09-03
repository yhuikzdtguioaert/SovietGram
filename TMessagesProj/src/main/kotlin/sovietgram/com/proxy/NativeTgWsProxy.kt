package sovietgram.com.proxy

object NativeTgWsProxy {
    fun start(host: String, port: Int, dcIps: String, secret: String, verbose: Boolean): Int {
        return NativeTgWsProxyBridge.start(host, port, dcIps, secret, verbose)
    }

    fun stop(): Int {
        return NativeTgWsProxyBridge.stop()
    }

    fun setPoolSize(size: Int) {
        NativeTgWsProxyBridge.setPoolSize(size)
    }

    fun setCfProxyCacheDir(cacheDir: String) {
        NativeTgWsProxyBridge.setCfProxyCacheDir(cacheDir)
    }

    fun setCfProxyConfig(enabled: Boolean, priority: Boolean, userDomain: String) {
        NativeTgWsProxyBridge.setCfProxyConfig(enabled, priority, userDomain)
    }

    fun getStats(): String? {
        return NativeTgWsProxyBridge.getStats()
    }
}
