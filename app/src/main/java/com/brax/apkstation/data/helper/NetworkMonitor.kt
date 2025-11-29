package com.brax.apkstation.data.helper

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.brax.apkstation.utils.CommonUtils.TAG
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global network connectivity monitor
 * Provides real-time network status updates via StateFlow
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val connectivityManager = 
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private val _isConnected = MutableStateFlow(checkInitialConnectivity())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    init {
        startMonitoring()
    }
    
    /**
     * Check initial connectivity state
     */
    private fun checkInitialConnectivity(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check initial connectivity", e)
            false
        }
    }
    
    /**
     * Start monitoring network changes
     */
    private fun startMonitoring() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d(TAG, "Network available: $network")
                _isConnected.value = true
            }
            
            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d(TAG, "Network lost: $network")
                _isConnected.value = false
            }
            
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val hasConnection = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                  networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                
                Log.d(TAG, "Network capabilities changed: hasConnection=$hasConnection")
                _isConnected.value = hasConnection
            }
            
            override fun onUnavailable() {
                super.onUnavailable()
                Log.d(TAG, "Network unavailable")
                _isConnected.value = false
            }
        }
        
        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.i(TAG, "Network monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }
    
    /**
     * Stop monitoring (call when app is destroyed)
     */
    fun stopMonitoring() {
        try {
            networkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
                Log.i(TAG, "Network monitoring stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback", e)
        }
    }
    
    /**
     * Manually check current connectivity status
     */
    fun checkConnectivity(): Boolean {
        val isConnected = checkInitialConnectivity()
        _isConnected.value = isConnected
        return isConnected
    }
}


