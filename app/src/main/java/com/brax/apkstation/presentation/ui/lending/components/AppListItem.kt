package com.brax.apkstation.presentation.ui.lending.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.brax.apkstation.R
import com.brax.apkstation.presentation.ui.lending.AppItem
import com.brax.apkstation.presentation.ui.lending.AppStatus

@Composable
fun AppListItem(
    app: AppItem,
    isConnected: Boolean,
    onAppClick: () -> Unit,
    onActionClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clickable(onClick = onAppClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (app.icon != null) {
                    AsyncImage(
                        model = app.icon,
                        contentDescription = app.name,
                        modifier = Modifier.size(56.dp)
                    )
                } else if (app.iconDrawable != null) {
                    AsyncImage(
                        model = app.iconDrawable,
                        contentDescription = app.name,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // App info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = app.name.ifBlank { "-" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val appInfoTextResId = when (app.status) {
                    AppStatus.INSTALLED -> R.string.app_installed
                    AppStatus.UPDATE_AVAILABLE -> R.string.update_available
                    AppStatus.REQUESTING -> R.string.requesting
                    AppStatus.REQUESTED -> R.string.requested
                    AppStatus.UNAVAILABLE -> R.string.unavailable
                    AppStatus.DOWNLOADING -> R.string.downloading
                    AppStatus.INSTALLING -> R.string.installing
                    AppStatus.UPDATING -> R.string.updating
                    AppStatus.UNINSTALLING -> R.string.uninstalling

                    // NOT_INSTALLED is handled differently
                    else -> 0
                }

                val textColor = when (app.status) {
                    AppStatus.INSTALLED -> MaterialTheme.colorScheme.primary
                    AppStatus.UPDATE_AVAILABLE -> Color(0xFFFF9800) // Orange
                    AppStatus.REQUESTING -> MaterialTheme.colorScheme.tertiary
                    AppStatus.REQUESTED -> MaterialTheme.colorScheme.onSurfaceVariant
                    AppStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
                    AppStatus.DOWNLOADING -> MaterialTheme.colorScheme.tertiary
                    AppStatus.INSTALLING -> MaterialTheme.colorScheme.tertiary
                    AppStatus.UPDATING -> Color(0xFFFF9800) // Orange
                    AppStatus.UNINSTALLING -> MaterialTheme.colorScheme.tertiary

                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                when (app.status) {
                    AppStatus.NOT_INSTALLED -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        app.author?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))

                        // Build info string with | separator
                        val infoItems = buildList {
                            app.version?.takeIf { it.isNotEmpty() }?.let { add("v$it") }
                            app.size?.takeIf { it.isNotEmpty() }?.let { add(it) }
                            app.rating?.takeIf { it.isNotEmpty() }?.let { add("★ $it") }
                        }

                        if (infoItems.isNotEmpty()) {
                            Text(
                                text = infoItems.joinToString(" | "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> {
                        Text(
                            text = stringResource(appInfoTextResId),
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Action button (fixed width for consistency)
            // Don't show button for REQUESTED and UNAVAILABLE - status text is enough
            if (app.status != AppStatus.REQUESTED && app.status != AppStatus.UNAVAILABLE) {
                val buttonTextResId = when (app.status) {
                    AppStatus.INSTALLED -> R.string.open
                    AppStatus.UPDATE_AVAILABLE -> R.string.update
                    AppStatus.NOT_INSTALLED -> R.string.install
                    AppStatus.DOWNLOADING -> R.string.action_cancel

                    else -> 0
                }

                val buttonContainerColor = when (app.status) {
                    AppStatus.INSTALLED -> MaterialTheme.colorScheme.tertiary
                    AppStatus.UPDATE_AVAILABLE -> Color(0xFFFF9800) // Orange
                    AppStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.primary
                    AppStatus.DOWNLOADING -> MaterialTheme.colorScheme.error

                    else -> MaterialTheme.colorScheme.tertiary
                }

                val isTextColorWhite = app.status == AppStatus.UPDATE_AVAILABLE

                Box(
                    modifier = Modifier.width(90.dp)
                ) {
                    // No button for REQUESTING, INSTALLING, UNINSTALLING states - 0
                    // REQUESTED and UNAVAILABLE are handled by the outer if condition
                    if (buttonTextResId != 0) {
                        ActionButton(
                            buttonTextResId = buttonTextResId,
                            buttonColor = buttonContainerColor,
                            isConnected = isConnected,
                            isTextColorWhite = isTextColorWhite,
                            onActionClick = onActionClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    @StringRes buttonTextResId: Int,
    buttonColor: Color,
    isConnected: Boolean,
    isTextColorWhite: Boolean,
    onActionClick: () -> Unit
) {
    Button(
        onClick = onActionClick,
        enabled = isConnected,
        colors = ButtonDefaults.buttonColors(
            containerColor = buttonColor
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (isTextColorWhite) {
            Text(
                text = stringResource(buttonTextResId),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White // White text on orange background
            )
        } else {
            Text(
                text = stringResource(buttonTextResId),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

