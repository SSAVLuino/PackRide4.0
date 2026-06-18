package biz.cesena.packride4.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MapRegion::class, SavedRoute::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mapRegionDao(): MapRegionDao
    abstract fun savedRouteDao(): SavedRouteDao
}
