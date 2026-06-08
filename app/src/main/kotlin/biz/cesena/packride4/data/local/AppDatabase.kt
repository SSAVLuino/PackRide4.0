package biz.cesena.packride4.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MapRegion::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mapRegionDao(): MapRegionDao
}
