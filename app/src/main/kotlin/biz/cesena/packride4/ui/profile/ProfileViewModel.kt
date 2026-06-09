package biz.cesena.packride4.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biz.cesena.packride4.data.remote.SupabaseClientProvider
import biz.cesena.packride4.utils.ConnectivityUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val isConnected: Boolean = true,
    val isAuthenticated: Boolean = false,
    val userEmail: String? = null,
    // Auth form fields
    val email: String = "",
    val password: String = "",
    val isSignUp: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val resetEmailSent: Boolean = false
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supabaseClientProvider: SupabaseClientProvider
) : ViewModel() {

    private val supabase get() = supabaseClientProvider.client

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val connected = ConnectivityUtils.isConnected(context)
        _uiState.update { it.copy(isConnected = connected) }
        if (!connected) return
        viewModelScope.launch {
            val session = supabase.auth.currentSessionOrNull()
            val email = session?.user?.email
            _uiState.update { it.copy(isAuthenticated = email != null, userEmail = email) }
        }
    }

    fun onEmailChange(value: String) =
        _uiState.update { it.copy(email = value, emailError = null, errorMessage = null) }

    fun onPasswordChange(value: String) =
        _uiState.update { it.copy(password = value, passwordError = null, errorMessage = null) }

    fun toggleMode() =
        _uiState.update { it.copy(isSignUp = !it.isSignUp, errorMessage = null, resetEmailSent = false) }

    fun signIn() {
        if (!validate()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                supabase.auth.signInWith(Email) {
                    email = _uiState.value.email.trim()
                    password = _uiState.value.password
                }
                val userEmail = supabase.auth.currentSessionOrNull()?.user?.email
                _uiState.update { it.copy(isAuthenticated = true, userEmail = userEmail) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = friendlyError(e)) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun signUp() {
        if (!validate()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                supabase.auth.signUpWith(Email) {
                    email = _uiState.value.email.trim()
                    password = _uiState.value.password
                }
                val userEmail = supabase.auth.currentSessionOrNull()?.user?.email
                _uiState.update { it.copy(isAuthenticated = true, userEmail = userEmail) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = friendlyError(e)) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun sendPasswordReset() {
        val email = _uiState.value.email.trim()
        if (email.isBlank()) {
            _uiState.update { it.copy(emailError = "Inserisci l'email") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                supabase.auth.resetPasswordForEmail(email)
                _uiState.update { it.copy(resetEmailSent = true) }
            } catch (_: Exception) {
                _uiState.update { it.copy(resetEmailSent = true) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                supabase.auth.signOut()
            } catch (_: Exception) {}
            _uiState.update { it.copy(isAuthenticated = false, userEmail = null) }
        }
    }

    private fun validate(): Boolean {
        var valid = true
        if (_uiState.value.email.isBlank()) {
            _uiState.update { it.copy(emailError = "Email obbligatoria") }
            valid = false
        }
        if (_uiState.value.password.length < 6) {
            _uiState.update { it.copy(passwordError = "Minimo 6 caratteri") }
            valid = false
        }
        return valid
    }

    private fun friendlyError(e: Exception): String = when {
        e.message?.contains("Invalid login credentials", ignoreCase = true) == true ->
            "Email o password errati"
        e.message?.contains("User already registered", ignoreCase = true) == true ->
            "Email già registrata — accedi invece"
        e.message?.contains("network", ignoreCase = true) == true ->
            "Errore di rete — controlla la connessione"
        else -> "Errore: ${e.message}"
    }
}
