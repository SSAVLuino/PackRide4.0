package biz.cesena.packride4.ui.rides

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import biz.cesena.packride4.data.remote.SupabaseClientProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data class RideDto(
    val id: String,
    val name: String,
    val status: String,
    @SerialName("join_code") val joinCode: String,
    @SerialName("ride_start") val rideStart: Long
)

data class RideUi(
    val id: String,
    val name: String,
    val status: String,
    val joinCode: String,
    val rideStart: Long
)

data class RidesUiState(
    val isLoading: Boolean = true,
    val isLoggedIn: Boolean = false,
    val rides: List<RideUi> = emptyList(),
    val errorMessage: String? = null
)

@HiltViewModel
class RidesViewModel @Inject constructor(
    private val supabaseClientProvider: SupabaseClientProvider
) : ViewModel() {

    private val supabase get() = supabaseClientProvider.client

    private val _uiState = MutableStateFlow(RidesUiState())
    val uiState: StateFlow<RidesUiState> = _uiState.asStateFlow()

    init {
        checkAuthAndLoadRides()
    }

    private fun checkAuthAndLoadRides() {
        viewModelScope.launch {
            try {
                val session = supabase.auth.currentSessionOrNull()
                if (session == null) {
                    _uiState.update { it.copy(isLoading = false, isLoggedIn = false) }
                    return@launch
                }
                _uiState.update { it.copy(isLoggedIn = true) }
                loadRides()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Errore: ${e.message}") }
            }
        }
    }

    private suspend fun loadRides() {
        try {
            val userId = supabase.auth.currentUserOrNull()?.id ?: return
            val rows = supabase.postgrest
                .from("rides")
                .select {
                    filter { eq("created_by", userId) }
                    order("ride_start", Order.DESCENDING)
                    limit(50)
                }
                .decodeList<RideDto>()

            _uiState.update {
                it.copy(
                    isLoading = false,
                    rides = rows.map { dto ->
                        RideUi(dto.id, dto.name, dto.status, dto.joinCode, dto.rideStart)
                    }
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, errorMessage = "Errore caricamento uscite") }
        }
    }

    fun createRide() {
        viewModelScope.launch {
            try {
                val userId = supabase.auth.currentUserOrNull()?.id ?: return@launch
                val joinCode = (1..6).map { ('A'..'Z').random() }.joinToString("")
                supabase.postgrest.from("rides").insert(
                    mapOf(
                        "name" to "Uscita del ${java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date())}",
                        "created_by" to userId,
                        "status" to "in_progress",
                        "join_code" to joinCode,
                        "ride_start" to System.currentTimeMillis()
                    )
                )
                loadRides()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Errore nella creazione dell'uscita") }
            }
        }
    }
}
