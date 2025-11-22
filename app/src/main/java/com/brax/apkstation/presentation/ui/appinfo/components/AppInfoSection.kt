package com.brax.apkstation.presentation.ui.appinfo.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.brax.apkstation.R
import com.brax.apkstation.presentation.ui.appinfo.AppDetailsData

@Composable
fun AppInfoSection(appDetails: AppDetailsData) {
    val hasAnyInfo = appDetails.size.isNullOrEmpty().not()
            || appDetails.rating.isNullOrEmpty().not()
            || appDetails.contentRating.isNullOrEmpty().not()

    if (hasAnyInfo)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            InfoItem(
                label = stringResource(R.string.rating),
                value = appDetails.rating ?: "-"
            )
            InfoItem(
                label = stringResource(R.string.size),
                value = appDetails.size ?: "-"
            )
            InfoItem(
                label = stringResource(R.string.pegi),
                value = appDetails.contentRating ?: "-"
            )
        }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
