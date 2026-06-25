package biz.cesena.packride4.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun FullscreenInfoPanel(
    uiState: HomeUiState,
    selectingSide: String?,
    onSelectValue: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keys = if (uiState.isNavigating) NAV_WIDGET_KEYS else IDLE_WIDGET_KEYS
    val currentKey = when {
        selectingSide == "left" && uiState.isNavigating -> uiState.widgetLeftNav
        selectingSide == "left" -> uiState.widgetLeftIdle
        selectingSide == "right" && uiState.isNavigating -> uiState.widgetRightNav
        selectingSide == "right" -> uiState.widgetRightIdle
        else -> null
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selectingSide != null) "Scegli dato per widget" else "Informazioni",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Chiudi")
                }
            }

            Spacer(Modifier.height(16.dp))

            keys.forEach { key ->
                val data = resolveWidgetData(key, uiState)
                val isSelected = key == currentKey

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .then(if (selectingSide != null) Modifier.clickable { onSelectValue(key) } else Modifier),
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected && selectingSide != null)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(data.label, style = MaterialTheme.typography.bodyLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                data.value,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (isSelected && selectingSide != null) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Default.Check, null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
