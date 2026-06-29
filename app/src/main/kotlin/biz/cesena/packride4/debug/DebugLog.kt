package biz.cesena.packride4.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny in-memory log buffer so we can inspect tile/network activity on-device without adb. */
object DebugLog {
    private const val MAX_LINES = 300
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun log(message: String) {
        if (!biz.cesena.packride4.BuildConfig.DEBUG) return
        val entry = "${timeFormat.format(Date())}  $message"
        _lines.value = (_lines.value + entry).takeLast(MAX_LINES)
        Log.d("PackRideDebug", message)
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
