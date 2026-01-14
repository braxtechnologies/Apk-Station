package com.brax.apkstation.presentation.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.brax.apkstation.data.model.DownloadStatus
import com.brax.apkstation.data.room.entity.Download
import com.brax.apkstation.utils.formatFileSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveDownloadsScreen(
    onNavigateBack: () -> Unit,
    onAppClick: (packageName: String) -> Unit,
    viewModel: ActiveDownloadsViewModel = hiltViewModel()
) {
    val downloads by viewModel.activeDownloads.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Active Downloads",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (downloads.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "No active downloads",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                items(
                    items = downloads,
                    key = { "${it.packageName}_${it.progress}_${it.status}" }
                ) { download ->
                    DownloadItemCard(
                        download = download,
                        onAppClick = { onAppClick(download.packageName) },
                        onCancelClick = { viewModel.cancelDownload(download.packageName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadItemCard(
    download: Download,
    onAppClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    val isCancellable = download.status in listOf(
        DownloadStatus.QUEUED,
        DownloadStatus.DOWNLOADING,
        DownloadStatus.DOWNLOADED,
        DownloadStatus.VERIFYING
    )
    
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
                AsyncImage(
                    model = download.icon,
                    contentDescription = download.displayName,
                    modifier = Modifier.size(56.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // App info and status text
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = download.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                // Status text with percentage
                Text(
                    text = getStatusText(download),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Circular progress with cancel button overlay
            if (isCancellable) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Circular progress indicator
                    if (download.status == DownloadStatus.DOWNLOADING && download.progress > 0) {
                        // Determinate circular progress
                        CircularProgressIndicator(
                            progress = { download.progress / 100f },
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeWidth = 4.dp
                        )
                    } else {
                        // Indeterminate circular progress for queued, verifying, etc.
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeWidth = 4.dp
                        )
                    }
                    
                    // Cancel button in the center
                    IconButton(
                        onClick = onCancelClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel download",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun getStatusText(download: Download): String {
    return when (download.status) {
        DownloadStatus.QUEUED -> "Waiting in queue..."
        DownloadStatus.DOWNLOADING -> {
            val progress = download.progress
            val size = if (download.fileSize > 0) {
                " • ${formatFileSize(download.fileSize.toString())}"
            } else ""
            "Downloading $progress%$size"
        }
        DownloadStatus.DOWNLOADED -> "Downloaded, preparing..."
        DownloadStatus.VERIFYING -> "Verifying file integrity..."
        DownloadStatus.INSTALLING -> "Installing..."
        DownloadStatus.COMPLETED -> "Completed"
        DownloadStatus.FAILED -> "Failed"
        DownloadStatus.CANCELLED -> "Cancelled"
        DownloadStatus.UNAVAILABLE -> "Unavailable"
    }
}
