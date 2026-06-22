package biz.cesena.packride4.debug

import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RoutingDebugDump {

    private val dir: File by lazy {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "PackRide").also { it.mkdirs() }
    }

    var enabled = false

    fun save(engine: String, destination: String, rawJson: String) {
        if (!biz.cesena.packride4.BuildConfig.DEBUG || !enabled) return
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeDest = destination.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(40)
            val file = File(dir, "${ts}_${engine}_${safeDest}.json")
            file.writeText(rawJson)
            DebugLog.log("debug-dump: saved ${file.absolutePath} (${rawJson.length} chars)")
        } catch (e: Exception) {
            DebugLog.log("debug-dump: failed to save: ${e.message}")
        }
    }
}
