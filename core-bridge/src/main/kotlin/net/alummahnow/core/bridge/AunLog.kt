package net.alummahnow.core.bridge

import android.util.Log

/**
 * The only logging entry point in the apps. Structured `key=value` context, one tag per area.
 * Never pass tokens, emails or other personal data.
 */
object AunLog {
    @Volatile var enabled: Boolean = true

    fun d(area: String, message: String, vararg context: Pair<String, Any?>) = emit(Log.DEBUG, area, message, context, null)
    fun i(area: String, message: String, vararg context: Pair<String, Any?>) = emit(Log.INFO, area, message, context, null)
    fun w(area: String, message: String, vararg context: Pair<String, Any?>) = emit(Log.WARN, area, message, context, null)
    fun e(area: String, message: String, error: Throwable? = null, vararg context: Pair<String, Any?>) =
        emit(Log.ERROR, area, message, context, error)

    internal fun format(message: String, context: Array<out Pair<String, Any?>>): String =
        if (context.isEmpty()) message else message + " " + context.joinToString(" ") { (k, v) -> "$k=$v" }

    private fun emit(priority: Int, area: String, message: String, context: Array<out Pair<String, Any?>>, error: Throwable?) {
        if (!enabled) return
        val line = format(message, context)
        runCatching {
            if (error != null) {
                Log.println(priority, "AUN/$area", line + "\n" + Log.getStackTraceString(error))
            } else {
                Log.println(priority, "AUN/$area", line)
            }
        }
    }
}
