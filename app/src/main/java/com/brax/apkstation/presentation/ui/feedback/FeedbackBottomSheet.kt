package com.brax.apkstation.presentation.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackBottomSheet(
    uiState: FeedbackUiState,
    onDismiss: () -> Unit,
    onTitleChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    if (!uiState.showSheet) return

    ModalBottomSheet(
        containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Share Feedback",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedTextField(
                value = uiState.title,
                onValueChange = onTitleChanged,
                label = { Text("Title") },
                placeholder = { Text("Brief summary of your feedback") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.submitting
            )
            OutlinedTextField(
                value = uiState.description,
                onValueChange = onDescriptionChanged,
                label = { Text("Description") },
                placeholder = { Text("Tell us more") },
                minLines = 4,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.submitting
            )
            Button(
                onClick = onSubmit,
                enabled = uiState.title.isNotBlank() && uiState.description.isNotBlank() && !uiState.submitting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.submitting) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Text(if (uiState.submitting) "Submitting…" else "Submit")
            }
        }
    }
}
