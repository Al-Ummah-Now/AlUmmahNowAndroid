package net.alummahnow.core.bridge

import net.alummahnow.core.coreVersion

/** Entry point to the Rust core (`libaun_core.so` via JNA). Loading is lazy and never crashes the app. */
object CoreRuntime {
    /** The linked aun-core version, or null when the native library cannot load on this device. */
    val version: String? by lazy {
        runCatching { coreVersion() }
            .onFailure { AunLog.w("core", "native core unavailable", "error" to it.javaClass.simpleName) }
            .getOrNull()
    }
}
