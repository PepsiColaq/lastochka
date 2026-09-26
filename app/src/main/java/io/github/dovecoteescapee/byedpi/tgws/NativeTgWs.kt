package io.github.dovecoteescapee.byedpi.tgws

import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Bindings to libtgwsproxy.so (MTProto → WebSocket).
 */
interface TgWsLibrary : Library {
    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun FreeString(p: Pointer)
}

object NativeTgWs {
    private const val TAG = "NativeTgWs"

    @Volatile
    private var lib: TgWsLibrary? = null

    @Synchronized
    fun ensureLoaded(): TgWsLibrary {
        lib?.let { return it }
        try {
            System.setProperty("jna.nosys", "true")
            System.setProperty("jna.nounpack", "true")
            // Prefer explicit System.loadLibrary so Android ClassLoader finds jniLibs
            try {
                System.loadLibrary("jnidispatch")
            } catch (_: Throwable) { /* JNA may load it */ }
            try {
                System.loadLibrary("tgwsproxy")
            } catch (e: Throwable) {
                Log.w(TAG, "System.loadLibrary(tgwsproxy) failed, JNA will try", e)
            }
            val loaded = Native.load("tgwsproxy", TgWsLibrary::class.java)
            lib = loaded
            Log.i(TAG, "libtgwsproxy loaded")
            return loaded
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load libtgwsproxy", e)
            throw e
        }
    }

    fun startProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int =
        ensureLoaded().StartProxy(host, port, dcIps, secret, verbose)

    fun stopProxy(): Int = try {
        lib?.StopProxy() ?: 0
    } catch (_: Throwable) {
        0
    }

    fun setPoolSize(size: Int) {
        ensureLoaded().SetPoolSize(size)
    }

    fun setCfProxyCacheDir(cacheDir: String) {
        ensureLoaded().SetCfProxyCacheDir(cacheDir)
    }

    fun setCfProxyConfig(enabled: Boolean, priority: Boolean, userDomain: String) {
        ensureLoaded().SetCfProxyConfig(
            if (enabled) 1 else 0,
            if (priority) 1 else 0,
            userDomain,
        )
    }

    fun getSecretWithPrefix(): String? {
        val ptr = ensureLoaded().GetSecretWithPrefix() ?: return null
        return try {
            ptr.getString(0)
        } finally {
            ensureLoaded().FreeString(ptr)
        }
    }
}
