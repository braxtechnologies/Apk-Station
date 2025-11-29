package com.brax.apkstation.presentation.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * State holder for InfoDialog
 */
class InfoDialogState {
    var isVisible by mutableStateOf(false)
        private set
    
    var title by mutableStateOf("")
        private set
    
    var message by mutableStateOf("")
        private set
    
    var firstButtonText by mutableStateOf("Got it")
        private set
    
    var secondButtonText by mutableStateOf<String?>(null)
        private set
    
    var onFirstButtonClick by mutableStateOf({})
        private set
    
    var onSecondButtonClick by mutableStateOf({})
        private set
    
    /**
     * Show the dialog with customizable content
     * 
     * @param title Dialog title
     * @param message Dialog message/body
     * @param firstButtonText Text for the first (primary) button. Default: "Got it"
     * @param secondButtonText Optional text for the second button. If null, only one button is shown.
     * @param onFirstButtonClick Callback when first button is clicked. Default: closes dialog
     * @param onSecondButtonClick Callback when second button is clicked. Default: closes dialog
     */
    fun show(
        title: String,
        message: String,
        firstButtonText: String = "Got it",
        secondButtonText: String? = null,
        onFirstButtonClick: (() -> Unit)? = null,
        onSecondButtonClick: (() -> Unit)? = null
    ) {
        this.title = title
        this.message = message
        this.firstButtonText = firstButtonText
        this.secondButtonText = secondButtonText
        this.onFirstButtonClick = onFirstButtonClick ?: { dismiss() }
        this.onSecondButtonClick = onSecondButtonClick ?: { dismiss() }
        isVisible = true
    }
    
    /**
     * Dismiss/hide the dialog
     */
    fun dismiss() {
        isVisible = false
    }
}

/**
 * Remember an InfoDialogState across recompositions
 */
@Composable
fun rememberInfoDialogState(): InfoDialogState {
    return remember { InfoDialogState() }
}

/**
 * Composable function to display the InfoDialog
 * 
 * @param state The InfoDialogState that controls the dialog
 */
@Composable
fun InfoDialog(state: InfoDialogState) {
    if (state.isVisible) {
        AlertDialog(
            onDismissRequest = { state.dismiss() },
            title = {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Start
                )
            },
            text = {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        state.onFirstButtonClick()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = state.firstButtonText,
                        fontSize = 14.sp
                    )
                }
            },
            dismissButton = if (state.secondButtonText != null) {
                {
                    TextButton(
                        onClick = {
                            state.onSecondButtonClick()
                        }
                    ) {
                        Text(
                            text = state.secondButtonText!!,
                            fontSize = 14.sp
                        )
                    }
                }
            } else null,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        )
    }
}
