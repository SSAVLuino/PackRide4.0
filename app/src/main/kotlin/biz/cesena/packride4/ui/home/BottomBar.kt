package biz.cesena.packride4.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Navigation
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

fun bearingToCardinal(bearing: Float): String {
    val normalized = ((bearing % 360) + 360) % 360
    return when {
        normalized < 22.5 || normalized >= 337.5 -> "N"
        normalized < 67.5 -> "NE"
        normalized < 112.5 -> "E"
        normalized < 157.5 -> "SE"
        normalized < 202.5 -> "S"
        normalized < 247.5 -> "SO"
        normalized < 292.5 -> "O"
        else -> "NO"
    }
}

data class WidgetData(val value: String, val label: String)

fun resolveWidgetData(key: String, uiState: HomeUiState): WidgetData {
    return when (key) {
        "speed" -> WidgetData("${uiState.speedKmh.roundToInt()} km/h", "Velocità")
        "altitude" -> WidgetData("${uiState.altitudeMeters.roundToInt()} m", "Altitudine")
        "direction" -> WidgetData(
            uiState.lastKnownPosition?.let { if (it.hasBearing) bearingToCardinal(it.bearing) else "—" } ?: "—",
            "Direzione"
        )
        "gps_accuracy" -> WidgetData("±${uiState.lastKnownPosition?.accuracy?.roundToInt() ?: 0} m", "GPS")
        "time" -> WidgetData(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), "Ora")
        "arrival_time" -> WidgetData(formatArrivalTime(uiState.remainingTime), "Arrivo")
        "time_from_departure" -> WidgetData(formatDurationShort(System.currentTimeMillis() - uiState.departureTimeMillis), "In viaggio")
        "time_to_arrival" -> WidgetData(formatDurationShort(uiState.remainingTime), "Tempo rimasto")
        "km_done" -> {
            val total = uiState.route?.distanceMeters ?: 0.0
            WidgetData(formatDistanceShort(total - uiState.remainingDistance), "Km fatti")
        }
        "km_remaining" -> WidgetData(formatDistanceShort(uiState.remainingDistance), "Km mancanti")
        else -> WidgetData("—", key)
    }
}

val IDLE_WIDGET_KEYS = listOf("altitude", "time", "speed", "direction", "gps_accuracy")
val NAV_WIDGET_KEYS = listOf("altitude", "time", "speed", "direction", "gps_accuracy",
    "arrival_time", "time_from_departure", "time_to_arrival", "km_done", "km_remaining")

@Composable
fun BottomBar(
    uiState: HomeUiState,
    onNavigateClick: () -> Unit,
    onMenuClick: () -> Unit,
    onLeftWidgetClick: () -> Unit,
    onRightWidgetClick: () -> Unit,
    onManeuverPanelClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val leftKey = if (uiState.isNavigating) uiState.widgetLeftNav else uiState.widgetLeftIdle
    val rightKey = if (uiState.isNavigating) uiState.widgetRightNav else uiState.widgetRightIdle
    val leftData = resolveWidgetData(leftKey, uiState)
    val rightData = resolveWidgetData(rightKey, uiState)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left group
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            tonalElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallFloatingActionButton(
                    onClick = if (uiState.isNavigating) onManeuverPanelClick else onNavigateClick,
                    shape = CircleShape,
                    containerColor = if (uiState.isNavigating && uiState.showManeuverPanel)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.primary,
                    contentColor = if (uiState.isNavigating && uiState.showManeuverPanel)
                        MaterialTheme.colorScheme.onTertiary
                    else
                        MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(
                        if (uiState.isNavigating) Icons.Filled.List else Icons.Filled.Navigation,
                        contentDescription = if (uiState.isNavigating) "Manovre" else "Dove andiamo?",
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    modifier = Modifier.clickable(onClick = onLeftWidgetClick),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = leftData.value,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Right group
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            tonalElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.clickable(onClick = onRightWidgetClick),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = rightData.value,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                SmallFloatingActionButton(
                    onClick = onMenuClick,
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Icon(Icons.Filled.Menu, contentDescription = "Menu")
                }
            }
        }
    }
}

private fun formatArrivalTime(remainingTimeMs: Long): String {
    val arrival = Date(System.currentTimeMillis() + remainingTimeMs)
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(arrival)
}

fun formatDistanceShort(meters: Double): String {
    return if (meters >= 1000) {
        "%.1f km".format(meters / 1000)
    } else {
        "${meters.roundToInt()} m"
    }
}

fun formatDurationShort(ms: Long): String {
    val totalMin = ms / 60_000
    return if (totalMin >= 60) {
        "${totalMin / 60}h ${totalMin % 60}min"
    } else {
        "${totalMin} min"
    }
}
