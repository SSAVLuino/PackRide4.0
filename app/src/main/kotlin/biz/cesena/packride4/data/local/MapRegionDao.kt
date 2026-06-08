package biz.cesena.packride4.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MapRegionDao {

    @Query("SELECT * FROM map_regions ORDER BY downloadedAt DESC")
    fun getAll(): Flow<List<MapRegion>>

    @Query("SELECT * FROM map_regions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MapRegion?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(region: MapRegion)

    @Delete
    suspend fun delete(region: MapRegion)

    @Query("DELETE FROM map_regions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT SUM(sizeBytes) FROM map_regions")
    suspend fun totalSizeBytes(): Long?
}
