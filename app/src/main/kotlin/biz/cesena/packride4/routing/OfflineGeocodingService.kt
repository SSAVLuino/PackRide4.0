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
    @Volatile private var currentSearchId = 0L

    private fun geocodingDir() = File(context.filesDir, "geocoding")

    fun isAvailable(): Boolean =
        geocodingDir().let { dir ->
            dir.exists() && dir.listFiles()?.any { it.name.endsWith(".db") } == true
        }

    fun cancelPending() {
        currentSearchId++
    }

    fun search(query: String, limit: Int = 6): List<GeocodingResult> {
        if (query.length < 3) return emptyList()

        val searchId = ++currentSearchId
        val results = mutableListOf<GeocodingResult>()
        val trimmed = query.trim()

        val dir = geocodingDir()
        if (!dir.exists()) return emptyList()

        val dbFiles = dir.listFiles()?.filter { it.name.endsWith(".db") } ?: return emptyList()

        for (dbFile in dbFiles) {
            if (searchId != currentSearchId) return emptyList()
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

    data class PoiResult(val name: String, val category: String, val lat: Double, val lon: Double, val distanceMeters: Double = 0.0)

    fun findPoisNearby(lat: Double, lon: Double, radiusMeters: Double = 1000.0, limit: Int = 50): List<PoiResult> {
        val dir = geocodingDir()
        if (!dir.exists()) return emptyList()
        val dbFiles = dir.listFiles()?.filter { it.name.endsWith(".db") } ?: return emptyList()

        val margin = radiusMeters / 111_000.0
        val results = mutableListOf<PoiResult>()

        for (dbFile in dbFiles) {
            val db = getDb(dbFile) ?: continue
            try {
                val cursor = db.rawQuery(
                    """
                    SELECT name, type, category, lat, lon FROM places
                    WHERE category IS NOT NULL
                    AND lat BETWEEN ? AND ?
                    AND lon BETWEEN ? AND ?
                    """.trimIndent(),
                    arrayOf(
                        (lat - margin).toString(), (lat + margin).toString(),
                        (lon - margin).toString(), (lon + margin).toString()
                    )
                )
                cursor.use {
                    while (it.moveToNext()) {
                        val pLat = it.getDouble(3)
                        val pLon = it.getDouble(4)
                        val dist = haversine(lat, lon, pLat, pLon)
                        if (dist <= radiusMeters) {
                            results.add(PoiResult(it.getString(0), it.getString(2) ?: "", pLat, pLon, dist))
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("offline-geocoding: nearby query error: ${e.message}")
            }
        }

        results.sortBy { it.distanceMeters }
        DebugLog.log("offline-geocoding: found ${results.size} POIs within ${radiusMeters.toInt()}m")
        return results.take(limit)
    }

    fun findPoisInBounds(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, limit: Int = 200): List<PoiResult> {
        val dir = geocodingDir()
        if (!dir.exists()) return emptyList()
        val dbFiles = dir.listFiles()?.filter { it.name.endsWith(".db") } ?: return emptyList()

        val results = mutableListOf<PoiResult>()
        for (dbFile in dbFiles) {
            val db = getDb(dbFile) ?: continue
            try {
                val cursor = db.rawQuery(
                    """
                    SELECT name, category, lat, lon FROM places
                    WHERE category IS NOT NULL
                    AND lat BETWEEN ? AND ?
                    AND lon BETWEEN ? AND ?
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString(), limit.toString())
                )
                cursor.use {
                    while (it.moveToNext()) {
                        results.add(PoiResult(it.getString(0), it.getString(1) ?: "", it.getDouble(2), it.getDouble(3)))
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("offline-geocoding: bounds query error: ${e.message}")
            }
            if (results.size >= limit) break
        }
        DebugLog.log("offline-geocoding: found ${results.size} POIs in bounds")
        return results.take(limit)
    }

    fun findFuelAlongRoute(routePoints: List<Pair<Double, Double>>, radiusMeters: Double = 500.0): List<PoiResult> {
        if (routePoints.isEmpty()) return emptyList()

        val dir = geocodingDir()
        if (!dir.exists()) return emptyList()
        val dbFiles = dir.listFiles()?.filter { it.name.endsWith(".db") } ?: return emptyList()

        // Bounding box of the route with margin (~0.005° ≈ 500m)
        val margin = radiusMeters / 111_000.0
        val minLat = routePoints.minOf { it.first } - margin
        val maxLat = routePoints.maxOf { it.first } + margin
        val minLon = routePoints.minOf { it.second } - margin
        val maxLon = routePoints.maxOf { it.second } + margin

        val results = mutableListOf<PoiResult>()

        for (dbFile in dbFiles) {
            val db = getDb(dbFile) ?: continue
            try {
                val cursor = db.rawQuery(
                    """
                    SELECT name, category, lat, lon FROM places
                    WHERE category = 'fuel'
                    AND lat BETWEEN ? AND ?
                    AND lon BETWEEN ? AND ?
                    """.trimIndent(),
                    arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString())
                )
                cursor.use {
                    while (it.moveToNext()) {
                        val lat = it.getDouble(2)
                        val lon = it.getDouble(3)
                        if (distanceToPolyline(lat, lon, routePoints) <= radiusMeters) {
                            results.add(PoiResult(it.getString(0), it.getString(1), lat, lon))
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("offline-geocoding: fuel query error on ${dbFile.name}: ${e.message}")
            }
        }

        DebugLog.log("offline-geocoding: found ${results.size} fuel stations along route")
        return results
    }

    private fun distanceToPolyline(lat: Double, lon: Double, points: List<Pair<Double, Double>>): Double {
        var minDist = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val d = distanceToSegment(lat, lon, points[i].first, points[i].second, points[i + 1].first, points[i + 1].second)
            if (d < minDist) minDist = d
        }
        return minDist
    }

    private fun distanceToSegment(pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dx = bLon - aLon
        val dy = bLat - aLat
        if (dx == 0.0 && dy == 0.0) return haversine(pLat, pLon, aLat, aLon)
        val t = ((pLon - aLon) * dx + (pLat - aLat) * dy) / (dx * dx + dy * dy)
        val clamped = t.coerceIn(0.0, 1.0)
        return haversine(pLat, pLon, aLat + clamped * dy, aLon + clamped * dx)
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
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
