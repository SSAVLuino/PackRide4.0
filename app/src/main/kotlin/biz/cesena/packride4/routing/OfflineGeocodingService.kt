package biz.cesena.packride4.routing

import android.database.sqlite.SQLiteDatabase
import biz.cesena.packride4.data.download.AVAILABLE_REGIONS
import biz.cesena.packride4.data.download.MapDownloadManager
import biz.cesena.packride4.debug.DebugLog
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineGeocodingService @Inject constructor(
    private val downloadManager: MapDownloadManager
) {
    private val openDbs = mutableMapOf<String, SQLiteDatabase>()

    fun isAvailable(lat: Double, lon: Double): Boolean {
        val region = AVAILABLE_REGIONS.find { it.containsPoint(lat, lon) } ?: return false
        return downloadManager.isGeocodingReady(region.id)
    }

    fun search(query: String, lat: Double, lon: Double, limit: Int = 6): List<GeocodingResult> {
        if (query.length < 2) return emptyList()

        val region = AVAILABLE_REGIONS.find { it.containsPoint(lat, lon) && downloadManager.isGeocodingReady(it.id) }
            ?: return emptyList()

        val db = getDb(region.id) ?: return emptyList()
        val results = mutableListOf<GeocodingResult>()
        val ftsQuery = query.trim().split("\\s+".toRegex()).joinToString(" ") { "$it*" }

        try {
            val cursor = db.rawQuery(
                """
                SELECT p.name, p.type, p.category, p.lat, p.lon, p.city, p.street
                FROM places_fts fts
                JOIN places p ON p.id = fts.rowid
                WHERE places_fts MATCH ?
                ORDER BY rank
                LIMIT ?
                """.trimIndent(),
                arrayOf(ftsQuery, limit.toString())
            )
            cursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(0)
                    val type = it.getString(1) ?: ""
                    val category = it.getString(2) ?: ""
                    val resultLat = it.getDouble(3)
                    val resultLon = it.getDouble(4)
                    val city = it.getString(5) ?: ""
                    val street = it.getString(6) ?: ""

                    val displayName = when {
                        category.isNotBlank() -> "$name ($category)"
                        else -> name
                    }
                    val address = listOf(street, city).filter { s -> s.isNotBlank() }.joinToString(", ")
                    results.add(GeocodingResult(displayName, address, resultLat, resultLon))
                }
            }
        } catch (e: Exception) {
            DebugLog.log("offline-geocoding: query error on ${region.id}: ${e.message}")
        }

        DebugLog.log("offline-geocoding: ${results.size} results for \"$query\" in ${region.id}")
        return results
    }

    private fun getDb(regionId: String): SQLiteDatabase? {
        openDbs[regionId]?.let { if (it.isOpen) return it }
        val file = downloadManager.geocodingDbFile(regionId)
        if (!file.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).also {
                openDbs[regionId] = it
            }
        } catch (e: Exception) {
            DebugLog.log("offline-geocoding: cannot open DB for $regionId: ${e.message}")
            null
        }
    }

    fun close() {
        openDbs.values.forEach { it.close() }
        openDbs.clear()
    }
}
