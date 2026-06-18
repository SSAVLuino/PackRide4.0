package biz.cesena.packride4.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedRouteDao {
    @Query("SELECT * FROM saved_routes ORDER BY savedAt DESC")
    fun getAll(): Flow<List<SavedRoute>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(route: SavedRoute): Long

    @Delete
    suspend fun delete(route: SavedRoute)
}
