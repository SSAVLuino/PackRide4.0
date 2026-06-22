package biz.cesena.packride4.routing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import biz.cesena.packride4.debug.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineGeocodingService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val openDbs = mutableMapOf<String, SQLiteDatabase>()

    private fun geocodingDir() = File(context.filesDir, "geocoding")

    fun isAvailable(): Boolean =
        geocodingDir().let { dir ->
            dir.exists() && dir.listFiles()?.any { it.name.endsWith(".db") } == true
        }

    fun search(query: String, limit: Int = 6): List<GeocodingResult> {
        if (query.length < 2) return emptyList()

        val results = mutableListOf<GeocodingResult>()
        val likePattern = "%${query.trim()}%"

        val dir = geocodingDir()
        if (!dir.exists()) return emptyList()

        val dbFiles = dir.listFiles()?.filter { it.name.endsWith(".db") } ?: return emptyList()

        for (dbFile in dbFiles) {
            val db = getDb(dbFile) ?: continue
            try {
                val cursor = db.rawQuery(
                    """
                    SELECT name, type, category, lat, lon, city, street
                    FROM places
                    WHERE name LIKE ? OR city LIKE ? OR street LIKE ?
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(likePattern, likePattern, likePattern, limit.toString())
                )
                cursor.use {
                    while (it.moveToNext()) {
                        val name = it.getString(0)
                        val category = it.getString(2) ?: ""
                        val lat = it.getDouble(3)
                        val lon = it.getDouble(4)
                        val city = it.getString(5) ?: ""
                        val street = it.getString(6) ?: ""

                        val displayName = when {
                            category.isNotBlank() -> "$name ($category)"
                            else -> name
                        }
                        val address = listOf(street, city).filter { s -> s.isNotBlank() }.joinToString(", ")
                        results.add(GeocodingResult(displayName, address, lat, lon))
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("offline-geocoding: query error on ${dbFile.name}: ${e.message}")
            }

            if (results.size >= limit) break
        }

        DebugLog.log("offline-geocoding: ${results.size} results for \"$query\"")
        return results.take(limit)
    }

    private fun getDb(file: File): SQLiteDatabase? {
        val key = file.absolutePath
        openDbs[key]?.let { if (it.isOpen) return it }
        return try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).also {
                openDbs[key] = it
            }
        } catch (e: Exception) {
            DebugLog.log("offline-geocoding: cannot open ${file.name}: ${e.message}")
            null
        }
    }
}
