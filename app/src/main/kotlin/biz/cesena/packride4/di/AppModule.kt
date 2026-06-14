package biz.cesena.packride4.di

import android.content.Context
import androidx.room.Room
import biz.cesena.packride4.data.local.AppDatabase
import biz.cesena.packride4.data.local.MapRegionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
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

    // ── Ktor HTTP client (used for map region downloads) ──────────────────────

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(Android) {
        install(HttpTimeout) {
            // Map files are hundreds of MB — no overall request timeout,
            // just per-chunk socket/connect timeouts.
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
        // Follow redirects (CDN may redirect to regional endpoints)
        followRedirects = true
    }
}
