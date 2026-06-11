package biz.cesena.packride4.data.remote

import biz.cesena.packride4.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper so that Hilt can inject the SupabaseClient without exposing the raw singleton.
 * The actual URL and anon key are injected at build time via BuildConfig (local.properties).
 */
@Singleton
class SupabaseClientProvider @Inject constructor() {

    val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
        install(Auth)
        install(Postgrest)
        install(Realtime)
        install(Storage)
    }
}
