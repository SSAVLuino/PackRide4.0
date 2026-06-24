package biz.cesena.packride4.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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

@Composable
fun BottomBar(
    uiState: HomeUiState,
    onNavigateClick: () -> Unit,
    onMenuClick: () -> Unit,
    onLeftWidgetClick: () -> Unit,
    onRightWidgetClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    onClick = onNavigateClick,
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Filled.Navigation, contentDescription = "Dove andiamo?")
                }
                Spacer(Modifier.width(8.dp))
                InfoWidget(
                    line1 = "${uiState.speedKmh.roundToInt()} km/h",
                    line2 = if (uiState.isNavigating && uiState.route != null) {
                        "Arrivo ${formatArrivalTime(uiState.remainingTime)}"
                    } else {
                        val bearing = uiState.lastKnownPosition?.bearing ?: 0f
                        bearingToCardinal(bearing)
                    },
                    onClick = onLeftWidgetClick,
                )
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
                InfoWidget(
                    line1 = if (uiState.isNavigating && uiState.route != null) {
                        formatDistanceShort(uiState.remainingDistance)
                    } else {
                        "${uiState.altitudeMeters.roundToInt()} m"
                    },
                    line2 = if (uiState.isNavigating && uiState.route != null) {
                        formatDurationShort(uiState.remainingTime)
                    } else {
                        "GPS ±${uiState.lastKnownPosition?.accuracy?.roundToInt() ?: 0}m"
                    },
                    onClick = onRightWidgetClick,
                )
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

@Composable
private fun InfoWidget(
    line1: String,
    line2: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = line1,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = line2,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatArrivalTime(remainingTimeMs: Long): String {
    val arrival = Date(System.currentTimeMillis() + remainingTimeMs)
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(arrival)
}

private fun formatDistanceShort(meters: Double): String {
    return if (meters >= 1000) {
        "%.1f km".format(meters / 1000)
    } else {
        "${meters.roundToInt()} m"
    }
}

private fun formatDurationShort(ms: Long): String {
    val totalMin = ms / 60_000
    return if (totalMin >= 60) {
        "${totalMin / 60}h ${totalMin % 60}min"
    } else {
        "${totalMin} min"
    }
}
