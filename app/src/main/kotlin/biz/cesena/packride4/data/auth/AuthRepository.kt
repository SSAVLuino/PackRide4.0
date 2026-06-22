package biz.cesena.packride4.data.auth

import android.content.Context
import biz.cesena.packride4.BuildConfig
import biz.cesena.packride4.debug.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY
    private val prefs = context.getSharedPreferences("packride_auth", Context.MODE_PRIVATE)

    private val _isLoggedIn = MutableStateFlow(prefs.getString(KEY_ACCESS_TOKEN, null) != null)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _userEmail = MutableStateFlow(prefs.getString(KEY_USER_EMAIL, null))
    val userEmail: StateFlow<String?> = _userEmail.asStateFlow()

    val accessToken: String? get() = prefs.getString(KEY_ACCESS_TOKEN, null)

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        return try {
            val url = "$supabaseUrl/auth/v1/token?grant_type=password"
            val body = JSONObject().apply {
                put("email", email)
                put("password", password)
            }
            val response = postJson(url, body.toString())
            val json = JSONObject(response)

            val token = json.getString("access_token")
            val refreshToken = json.getString("refresh_token")
            val userEmail = json.getJSONObject("user").getString("email")

            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, token)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putString(KEY_USER_EMAIL, userEmail)
                .apply()

            _isLoggedIn.value = true
            _userEmail.value = userEmail
            DebugLog.log("auth: logged in as $userEmail")
            Result.success(Unit)
        } catch (e: Exception) {
            DebugLog.log("auth: sign in failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _isLoggedIn.value = false
        _userEmail.value = null
        DebugLog.log("auth: signed out")
    }

    private fun postJson(urlString: String, jsonBody: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", anonKey)
            doOutput = true
        }
        conn.outputStream.bufferedWriter().use { it.write(jsonBody) }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
            conn.disconnect()
            throw Exception(parseErrorMessage(error))
        }
        val result = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return result
    }

    private fun parseErrorMessage(error: String): String {
        return try {
            val json = JSONObject(error)
            json.optString("error_description", json.optString("msg", json.optString("message", error)))
        } catch (e: Exception) { error }
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_EMAIL = "user_email"
    }
}
