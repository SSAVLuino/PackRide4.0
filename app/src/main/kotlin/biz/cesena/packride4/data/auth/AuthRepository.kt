package biz.cesena.packride4.data.auth

import biz.cesena.packride4.debug.DebugLog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.gotrue
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.gotrue.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val supabase: SupabaseClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _userEmail = MutableStateFlow<String?>(null)
    val userEmail: StateFlow<String?> = _userEmail.asStateFlow()

    init {
        scope.launch {
            supabase.gotrue.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        _isLoggedIn.value = true
                        _userEmail.value = status.session.user?.email
                        DebugLog.log("auth: logged in as ${_userEmail.value}")
                    }
                    is SessionStatus.NotAuthenticated -> {
                        _isLoggedIn.value = false
                        _userEmail.value = null
                        DebugLog.log("auth: not authenticated")
                    }
                    else -> {}
                }
            }
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        return try {
            supabase.gotrue.loginWith(Email) {
                this.email = email
                this.password = password
            }
            Result.success(Unit)
        } catch (e: Exception) {
            DebugLog.log("auth: sign in failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        try {
            supabase.gotrue.logout()
        } catch (e: Exception) {
            DebugLog.log("auth: sign out error: ${e.message}")
        }
    }
}
