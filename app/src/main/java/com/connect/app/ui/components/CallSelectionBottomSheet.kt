package com.connect.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.connect.app.ui.screens.call.CallActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallSelectionBottomSheet(
    targetUsername: String,
    targetDisplayName: String,
    targetPhone: String?,
    room: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val effectiveDisplayName = targetDisplayName.ifBlank { targetUsername }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "Позвонить $effectiveDisplayName",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // 1. Connect Audio Call
            CallOptionRow(
                icon = Icons.Rounded.Call,
                title = "Аудиозвонок Connect",
                subtitle = "Звонок через интернет (Wi-Fi / сотовые данные)",
                onClick = {
                    onDismiss()
                    val intent = Intent(context, CallActivity::class.java).apply {
                        putExtra(CallActivity.EXTRA_TARGET_USER, targetUsername)
                        putExtra(CallActivity.EXTRA_TARGET_NAME, effectiveDisplayName)
                        putExtra(CallActivity.EXTRA_ROOM, room)
                        putExtra(CallActivity.EXTRA_CALL_TYPE, "audio")
                        putExtra(CallActivity.EXTRA_IS_OUTGOING, true)
                    }
                    context.startActivity(intent)
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            // 2. Connect Video Call
            CallOptionRow(
                icon = Icons.Rounded.Videocam,
                title = "Видеозвонок Connect",
                subtitle = "Звук и видео высокого качества через интернет",
                onClick = {
                    onDismiss()
                    val intent = Intent(context, CallActivity::class.java).apply {
                        putExtra(CallActivity.EXTRA_TARGET_USER, targetUsername)
                        putExtra(CallActivity.EXTRA_TARGET_NAME, effectiveDisplayName)
                        putExtra(CallActivity.EXTRA_ROOM, room)
                        putExtra(CallActivity.EXTRA_CALL_TYPE, "video")
                        putExtra(CallActivity.EXTRA_IS_OUTGOING, true)
                    }
                    context.startActivity(intent)
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            // 3. Regular Cellular Call
            val hasPhone = !targetPhone.isNullOrBlank()
            CallOptionRow(
                icon = Icons.Rounded.Phone,
                title = "Обычный звонок (по сотовой связи)",
                subtitle = if (hasPhone) "Вызов на $targetPhone через SIM-карту" else "Номер телефона не указан в профиле",
                enabled = hasPhone,
                onClick = {
                    if (hasPhone) {
                        onDismiss()
                        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                            data = Uri.parse("tel:${targetPhone?.trim()}")
                        }
                        context.startActivity(dialIntent)
                    } else {
                        Toast.makeText(context, "Номер телефона скрыт или не указан", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CallOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            ),
            modifier = Modifier.size(46.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}
