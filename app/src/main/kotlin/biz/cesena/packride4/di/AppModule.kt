package biz.cesena.packride4.di

import android.content.Context
import androidx.room.Room
import biz.cesena.packride4.data.local.AppDatabase
import biz.cesena.packride4.data.local.MapRegionDao
import biz.cesena.packride4.data.remote.SupabaseClientProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
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

    // ── Supabase ─────────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideSupabaseClientProvider(): SupabaseClientProvider = SupabaseClientProvider()

    // ── Ktor HTTP client (used for map region downloads) ──────────────────────

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(Android) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000   // large files need longer socket timeout
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // Follow redirects (CDN may redirect to regional endpoints)
        followRedirects = true
    }
}
