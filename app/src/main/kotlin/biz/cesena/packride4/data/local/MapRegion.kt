package biz.cesena.packride4.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a downloaded offline map region (MBTiles file).
 */
@Entity(tableName = "map_regions")
data class MapRegion(
    @PrimaryKey val id: String,           // e.g. "emilia-romagna"
    val name: String,                      // Human-readable name
    val filePath: String,                  // Absolute path to .mbtiles file
    val downloadedAt: Long,                // Unix ms
    val sizeBytes: Long,                   // File size in bytes
    val bboxMinLon: Double,
    val bboxMinLat: Double,
    val bboxMaxLon: Double,
    val bboxMaxLat: Double
)
