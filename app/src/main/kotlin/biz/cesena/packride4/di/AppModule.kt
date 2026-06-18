package biz.cesena.packride4.di

import android.content.Context
import androidx.room.Room
import biz.cesena.packride4.data.local.AppDatabase
import biz.cesena.packride4.data.local.MapRegionDao
import biz.cesena.packride4.data.local.SavedRouteDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ── Room ─────────────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "packride4.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideMapRegionDao(db: AppDatabase): MapRegionDao = db.mapRegionDao()

    @Provides
    fun provideSavedRouteDao(db: AppDatabase): SavedRouteDao = db.savedRouteDao()
}
