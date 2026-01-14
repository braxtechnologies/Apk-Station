package com.brax.apkstation.presentation.ui.downloads

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brax.apkstation.data.helper.DownloadHelper
import com.brax.apkstation.data.model.DownloadStatus
import com.brax.apkstation.data.room.dao.StoreDao
import com.brax.apkstation.data.room.entity.Download
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ActiveDownloadsViewModel @Inject constructor(
    private val downloadHelper: DownloadHelper,
    private val storeDao: StoreDao
) : ViewModel() {

    companion object {
        private const val TAG = "ActiveDownloadsVM"
    }

    /**
     * Flow of active downloads (not completed, failed, or cancelled)
     * Directly observes the DAO for real-time updates including progress changes
     */
    val activeDownloads: StateFlow<List<Download>> = storeDao.getAllDownloads()
        .onEach { downloads ->
            downloads.forEach { download ->
                Log.d(TAG, "📱 Flow emission: ${download.packageName} - ${download.status} - ${download.progress}%")
            }
        }
        .map { downloads ->
            downloads.filter { download ->
                download.status in listOf(
                    DownloadStatus.QUEUED,
                    DownloadStatus.DOWNLOADING,
                    DownloadStatus.DOWNLOADED,
                    DownloadStatus.VERIFYING,
                    DownloadStatus.INSTALLING
                )
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            emptyList()
        )

    /**
     * Cancel a specific download
     */
    fun cancelDownload(packageName: String) {
        viewModelScope.launch {
            downloadHelper.cancel(packageName)
        }
    }
}
