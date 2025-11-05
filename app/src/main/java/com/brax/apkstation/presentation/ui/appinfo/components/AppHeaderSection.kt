package com.brax.apkstation.presentation.ui.appinfo.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.brax.apkstation.R
import com.brax.apkstation.presentation.ui.appinfo.AppDetailsData

@Composable
fun AppHeaderSection(appDetails: AppDetailsData) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // App icon on the left
        Box(
            modifier = Modifier.size(100.dp),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = appDetails.icon,
                contentDescription = appDetails.name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // App info on the right
        Column(
            modifier = Modifier.weight(1f),
        ) {
            // App name
            Text(
                text = appDetails.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Author
            Text(
                text = appDetails.author ?: "-",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Version code (build number) - Line 3
            if (appDetails.installedVersionCode != null && appDetails.shouldShowInstalledVersion) {
                Text(
                    text = "${appDetails.installedVersionCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            } else {
                // Show version code from store if not installed
                appDetails.versionCode?.let {
                    Text(
                        text = "$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Version comparison - Line 4
            if (appDetails.installedVersion != null && appDetails.shouldShowInstalledVersion) {

                if (appDetails.hasUpdate) {
                    // Show: oldversion (Latest: latestversion) with orange for brackets and update
                    val versionText = buildAnnotatedString {
                        // Old version in normal color
                        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append(stringResource(R.string.version, appDetails.installedVersion))
                        }

                        append(" ")

                        // Brackets and latest version in orange
                        withStyle(
                            style = SpanStyle(
                                color = Color(0xFFFF9800),
                                fontWeight = FontWeight.Medium
                            )
                        ) {
                            appDetails.version?.let {
                                append(stringResource(R.string.latest_version, appDetails.version))
                            } ?: append("-")
                        }
                    }
                    Text(
                        text = versionText,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp
                    )
                } else {
                    // No update - just show installed version
                    Text(
                        text = stringResource(R.string.version, appDetails.installedVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            } else {
                // Not installed - show latest available version
                Text(
                    text = appDetails.version?.let {
                        stringResource(
                            R.string.version,
                            appDetails.version
                        )
                    } ?: "-",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}
