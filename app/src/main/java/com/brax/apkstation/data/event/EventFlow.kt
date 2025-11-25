package com.brax.apkstation.data.event

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central event flow for managing application-wide events
 */
@Singleton
class EventFlow @Inject constructor() {

    private val TAG = EventFlow::class.java.simpleName

    private val _busEvent = MutableSharedFlow<BusEvent>(extraBufferCapacity = 1)
    val busEvent = _busEvent.asSharedFlow()

    private val _installerEvent = MutableSharedFlow<InstallerEvent>(extraBufferCapacity = 1)
    val installerEvent = _installerEvent.asSharedFlow()
    
    private val _downloadEvent = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 1)
    val downloadEvent = _downloadEvent.asSharedFlow()

    /**
     * Send an event to the appropriate flow
     * @param event The event to send
     */
    fun send(event: Event) {
        when (event) {
            is InstallerEvent -> {
                Log.d(TAG, "Sending installer event: ${event::class.simpleName} for ${event.packageName}")
                _installerEvent.tryEmit(event)
            }
            is DownloadEvent -> {
                Log.d(TAG, "Sending download event: ${event::class.simpleName} for ${event.packageName}")
                _downloadEvent.tryEmit(event)
            }
            is BusEvent -> {
                Log.d(TAG, "Sending bus event: ${event::class.simpleName}")
                _busEvent.tryEmit(event)
            }
            else -> Log.e(TAG, "Got an unhandled event: ${event::class.simpleName}")
        }
    }
}

