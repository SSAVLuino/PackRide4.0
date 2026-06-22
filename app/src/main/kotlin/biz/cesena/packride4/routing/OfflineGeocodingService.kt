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
        if (query.length < 3) return emptyList()

        val results = mutableListOf<GeocodingResult>()
        val trimmed = query.trim()

        val dir = geocodingDir()
        if (!dir.exists()) return emptyList()

        val dbFiles = dir.listFiles()?.filter { it.name.endsWith(".db") } ?: return emptyList()

        for (dbFile in dbFiles) {
            val db = getDb(dbFile) ?: continue
            ensureIndex(db)
            try {
                val cursor = db.rawQuery(
                    """
                    SELECT name, type, category, lat, lon, city, street
                    FROM places
                    WHERE name LIKE ?
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf("$trimmed%", limit.toString())
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

    private val indexedDbs = mutableSetOf<String>()

    private fun ensureIndex(db: SQLiteDatabase) {
        val path = db.path ?: return
        if (path in indexedDbs) return
        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_places_name ON places(name COLLATE NOCASE)")
            indexedDbs.add(path)
            DebugLog.log("offline-geocoding: index created/verified for $path")
        } catch (e: Exception) {
            DebugLog.log("offline-geocoding: index creation failed: ${e.message}")
        }
    }

    private fun getDb(file: File): SQLiteDatabase? {
        val key = file.absolutePath
        openDbs[key]?.let { if (it.isOpen) return it }
        return try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).also {
                openDbs[key] = it
            }
        } catch (e: Exception) {
            DebugLog.log("offline-geocoding: cannot open ${file.name}: ${e.message}")
            null
        }
    }
}
