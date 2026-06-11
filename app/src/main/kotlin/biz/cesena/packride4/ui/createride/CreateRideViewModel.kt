package biz.cesena.packride4.ui.createride

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biz.cesena.packride4.data.remote.SupabaseClientProvider
import biz.cesena.packride4.utils.ConnectivityUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

data class CreateRideUiState(
    val isConnected: Boolean = true,
    val rideName: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

@HiltViewModel
class CreateRideViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val supabaseClientProvider: SupabaseClientProvider
) : ViewModel() {

    private val supabase get() = supabaseClientProvider.client

    private val _uiState = MutableStateFlow(CreateRideUiState())
    val uiState: StateFlow<CreateRideUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(isConnected = ConnectivityUtils.isConnected(context)) }
    }

    fun onRideNameChange(value: String) =
        _uiState.update { it.copy(rideName = value, errorMessage = null) }

    fun createRide() {
        val name = _uiState.value.rideName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Inserisci un nome per l'uscita") }
            return
        }
        if (!ConnectivityUtils.isConnected(context)) {
            _uiState.update { it.copy(isConnected = false) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val userId = supabase.auth.currentSessionOrNull()?.user?.id
                    ?: throw IllegalStateException("Utente non autenticato")

                val joinCode = (1..6)
                    .map { ('A'..'Z').random() }
                    .joinToString("")

                supabase.postgrest["rides"].insert(
                    buildJsonObject {
                        put("name", name)
                        put("created_by", userId)
                        put("status", "in_progress")
                        put("join_code", joinCode)
                        put("ride_start", System.currentTimeMillis())
                    }
                )
                _uiState.update {
                    it.copy(
                        successMessage = "Uscita \"$name\" creata! Codice: $joinCode",
                        rideName = ""
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = friendlyError(e)) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun dismissSuccess() = _uiState.update { it.copy(successMessage = null) }

    private fun friendlyError(e: Exception): String = when {
        e.message?.contains("network", ignoreCase = true) == true ->
            "Errore di rete — controlla la connessione"
        e.message?.contains("not authenticated", ignoreCase = true) == true ||
                e.message?.contains("Utente non autenticato") == true ->
            "Devi essere autenticato per creare un'uscita"
        else -> "Errore: ${e.message}"
    }
}
