package biz.cesena.packride4.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun FullscreenInfoPanel(
    uiState: HomeUiState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Informazioni",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Chiudi")
                }
            }

            Spacer(Modifier.height(24.dp))

            // Always-available info
            InfoRow("Ora", SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
            InfoRow("Velocità", "${uiState.speedKmh.roundToInt()} km/h")
            InfoRow("Altitudine", "${uiState.altitudeMeters.roundToInt()} m")
            InfoRow("Direzione", uiState.lastKnownPosition?.let {
                if (it.hasBearing) bearingToCardinal(it.bearing) else "—"
            } ?: "—")
            InfoRow("Precisione GPS", "±${uiState.lastKnownPosition?.accuracy?.roundToInt() ?: 0} m")

            // Navigation-only info
            if (uiState.isNavigating && uiState.route != null) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                val totalDistance = uiState.route!!.distanceMeters
                val kmDone = (totalDistance - uiState.remainingDistance) / 1000.0
                val kmRemaining = uiState.remainingDistance / 1000.0
                val elapsedMs = System.currentTimeMillis() - uiState.departureTimeMillis
                val arrivalTime = Date(System.currentTimeMillis() + uiState.remainingTime)

                InfoRow("Orario di arrivo", SimpleDateFormat("HH:mm", Locale.getDefault()).format(arrivalTime))
                InfoRow("Tempo dalla partenza", formatDuration(elapsedMs))
                InfoRow("Tempo all'arrivo", formatDuration(uiState.remainingTime))
                InfoRow("Km fatti", "%.1f km".format(kmDone))
                InfoRow("Km mancanti", "%.1f km".format(kmRemaining))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}min" else "${minutes} min"
}
