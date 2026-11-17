package io.github.dovecoteescapee.byedpi.core

import android.util.Log

/**
 * Process-wide environment variables via our own JNI (see native-lib.c).
 *
 * `android.system.Os.setenv` is a hidden API, so this is the only sanctioned
 * way to publish ALL_PROXY for third-party native code (reqwest inside
 * libtgwsproxy.so) on modern Android.
 */
object NativeEnv {
    private const val TAG = "NativeEnv"

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("byedpi")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "libbyedpi not loaded", e)
            false
        }
    }

    private val PROXY_KEYS = listOf(
        "ALL_PROXY", "all_proxy",
        "HTTPS_PROXY", "https_proxy",
        "HTTP_PROXY", "http_proxy",
    )

    /** Route every proxy-aware native client through 127.0.0.1:[port]. */
    fun setSocksProxy(port: Int): Boolean {
        if (!loaded) return false
        // socks5h resolves DNS on the proxy side, which is what we want:
        // the CF front domains must be resolved through the desync tunnel.
        val value = "socks5h://127.0.0.1:$port"
        var ok = false
        for (key in PROXY_KEYS) {
            if (setEnv(key, value)) ok = true
        }
        if (ok) Log.i(TAG, "native SOCKS proxy -> $value")
        return ok
    }

    fun clearSocksProxy() {
        if (!loaded) return
        for (key in PROXY_KEYS) {
            runCatching { jniUnsetEnv(key) }
        }
    }

    fun setEnv(key: String, value: String): Boolean = try {
        loaded && jniSetEnv(key, value) == 0
    } catch (e: Throwable) {
        Log.w(TAG, "setenv $key", e)
        false
    }

    private external fun jniSetEnv(key: String, value: String): Int

    private external fun jniUnsetEnv(key: String): Int
}
