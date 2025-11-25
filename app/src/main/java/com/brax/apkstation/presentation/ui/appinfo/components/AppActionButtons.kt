package com.brax.apkstation.presentation.ui.appinfo.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults.iconButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brax.apkstation.R
import com.brax.apkstation.presentation.ui.appinfo.AppDetailsData
import com.brax.apkstation.presentation.ui.components.InfoDialog
import com.brax.apkstation.presentation.ui.components.InfoDialogState
import com.brax.apkstation.presentation.ui.components.rememberInfoDialogState
import com.brax.apkstation.presentation.ui.lending.AppStatus

@Composable
fun AppActionButtons(
    appDetails: AppDetailsData,
    isConnected: Boolean,
    onInstallClick: () -> Unit,
    onOpenClick: () -> Unit,
    onUninstallClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    val context = LocalContext.current

    // Remember dialog state
    val infoDialogState = rememberInfoDialogState()

    // Show the dialog
    InfoDialog(state = infoDialogState)

    val leadingButtonColors = when (appDetails.status) {
        AppStatus.INSTALLED, AppStatus.NOT_INSTALLED -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary
        )

        AppStatus.DOWNLOADING, AppStatus.INSTALLING, AppStatus.UNINSTALLING -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiary
        )

        AppStatus.UPDATE_AVAILABLE -> ButtonDefaults.buttonColors(
            containerColor = Color(0xFFFF9800)
        )

        AppStatus.UPDATING -> ButtonDefaults.buttonColors(
            containerColor = Color(0xFFFF9800), // Orange like update button
            disabledContainerColor = Color(0xFFFF9800).copy(alpha = 0.6f), // Dimmed orange
            disabledContentColor = Color.White.copy(alpha = 0.7f) // Slightly dimmed white
        )

        AppStatus.UNAVAILABLE -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            disabledContainerColor = MaterialTheme.colorScheme.errorContainer,
            disabledContentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    }

    when (appDetails.status) {
        AppStatus.NOT_INSTALLED -> HybridAppActionButtons(
            leadingButtonText = R.string.install,
            leadingButtonColors = leadingButtonColors,
            leadingButtonOnClick = onInstallClick,
            isEnabled = isConnected
        )

        AppStatus.UPDATE_AVAILABLE -> HybridAppActionButtons(
            leadingButtonText = R.string.update,
            trailingButtonText = R.string.uninstall,
            leadingButtonColors = leadingButtonColors,
            leadingButtonOnClick = onInstallClick,
            trailingButtonOnClick = onUninstallClick,
            isRow = true,
            isEnabled = isConnected
        )

        AppStatus.INSTALLED -> HybridAppActionButtons(
            leadingButtonText = R.string.open,
            trailingButtonText = R.string.uninstall,
            leadingButtonColors = leadingButtonColors,
            leadingButtonOnClick = onOpenClick,
            trailingButtonOnClick = onUninstallClick,
            isRow = true,
            isEnabled = true
        )

        AppStatus.DOWNLOADING -> HybridAppActionButtons(
            leadingButtonText = R.string.downloading,
            trailingButtonText = R.string.action_cancel,
            leadingButtonColors = leadingButtonColors,
            trailingButtonOnClick = onCancelClick,
            isRow = true,
            hasCircularProgressIndicator = true
        )

        AppStatus.INSTALLING -> HybridAppActionButtons(
            leadingButtonText = R.string.installing,
            leadingButtonColors = leadingButtonColors,
            hasCircularProgressIndicator = true
        )

        AppStatus.UPDATING -> HybridAppActionButtons(
            leadingButtonText = R.string.button_updating,
            leadingButtonColors = leadingButtonColors,
            circularProgressIndicatorColor = Color.White.copy(alpha = 0.7f),
            hasCircularProgressIndicator = true
        )

        AppStatus.UNINSTALLING -> HybridAppActionButtons(
            leadingButtonText = R.string.uninstalling,
            leadingButtonColors = leadingButtonColors,
            hasCircularProgressIndicator = true
        )

        AppStatus.UNAVAILABLE -> HybridAppActionButtons(
            leadingButtonText = R.string.button_unavailable,
            leadingButtonColors = leadingButtonColors,
            contentDescription = "Info about unavailable status",
            dialogData = Pair(
                context.getString(R.string.app_unavailable),
                context.getString(R.string.dialog_app_unavailable_message)
            ),
            iconButtonColors = iconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            isRow = true,
            isSecondButtonIcon = true,
            infoDialogState = infoDialogState
        )

    }
}

@Composable
private fun HybridAppActionButtons(
    iconImageVector: ImageVector = Icons.Outlined.Info,
    @StringRes leadingButtonText: Int = R.string.install,
    @StringRes trailingButtonText: Int = R.string.uninstall,
    contentDescription: String = "",
    dialogData: Pair<String, String> = Pair("", ""),
    leadingButtonColors: ButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary
    ),
    iconButtonColors: IconButtonColors = iconButtonColors(
        containerColor = MaterialTheme.colorScheme.primary
    ),
    circularProgressIndicatorColor: Color = MaterialTheme.colorScheme.onTertiary,
    leadingButtonOnClick: () -> Unit = {},
    trailingButtonOnClick: () -> Unit = {},
    isRow: Boolean = false,
    isEnabled: Boolean = false,
    isSecondButtonIcon: Boolean = false,
    hasCircularProgressIndicator: Boolean = false,
    infoDialogState: InfoDialogState = rememberInfoDialogState()
) {
    if (isRow) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = leadingButtonOnClick,
                enabled = isEnabled,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                colors = leadingButtonColors
            ) {
                if (hasCircularProgressIndicator) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = circularProgressIndicatorColor,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = stringResource(leadingButtonText),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 16.sp
                )
            }

            if (isSecondButtonIcon) {
                IconButton(
                    onClick = {
                        infoDialogState.show(
                            title = dialogData.first,
                            message = dialogData.second,
                        )
                    },
                    modifier = Modifier.size(48.dp),
                    colors = iconButtonColors
                ) {
                    Icon(
                        imageVector = iconImageVector,
                        contentDescription = contentDescription
                    )
                }
            } else {
                OutlinedButton(
                    onClick = trailingButtonOnClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(
                        text = stringResource(trailingButtonText),
                        fontSize = 16.sp
                    )
                }
            }
        }
    } else {
        Button(
            onClick = leadingButtonOnClick,
            enabled = isEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = leadingButtonColors
        ) {
            Text(
                text = stringResource(leadingButtonText),
                fontSize = 16.sp
            )
        }
    }
}
