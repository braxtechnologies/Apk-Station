package com.brax.apkstation.presentation.ui.appinfo.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brax.apkstation.R

@Composable
fun DescriptionSection(
    description: String,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.about_app),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        val displayText = description.ifBlank { "-" }
        val shouldShowReadMore = description.length > 300 // Show "Read more" for long descriptions
        
        Column {
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (isExpanded) Int.MAX_VALUE else 8,
                overflow = if (isExpanded) TextOverflow.Visible else TextOverflow.Ellipsis
            )
            
            if (shouldShowReadMore) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isExpanded) "Read less" else "Read more",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onToggle)
                )
            }
        }
    }
}
