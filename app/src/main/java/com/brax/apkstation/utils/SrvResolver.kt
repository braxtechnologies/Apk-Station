package com.brax.apkstation.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.minidns.hla.ResolverApi
import org.minidns.record.SRV

/**
 * Utility class for resolving SRV records to determine API endpoint URLs
 * Uses MiniDNS library for Android-compatible DNS resolution
 */
object SrvResolver {
    private const val TAG = "SrvResolver"
    
    /**
     * Default fallback API URL if SRV resolution fails
     */
    private const val DEFAULT_API_URL = "https://api.braxtech.net/apk/v2/"
    
    /**
     * SRV record to query for API endpoint
     */
    private const val SRV_RECORD = "_https._tcp.api.braxtech.net"
    
    /**
     * Cached resolved URL to avoid repeated DNS lookups
     */
    @Volatile
    private var cachedApiUrl: String? = null
    
    /**
     * Resolves the API URL using SRV DNS record lookup
     * 
     * @return Resolved API URL or fallback URL if resolution fails
     */
    suspend fun resolveApiUrl(): String = withContext(Dispatchers.IO) {
        // Return cached URL if available
        cachedApiUrl?.let { 
            Log.d(TAG, "Using cached API URL: $it")
            return@withContext it 
        }
        
        try {
            Log.d(TAG, "Attempting to resolve SRV record: $SRV_RECORD")
            val srvRecords = querySrvRecord(SRV_RECORD)
            
            if (srvRecords.isNotEmpty()) {
                // Sort by priority (lower is better) and weight (higher is better)
                val bestRecord = srvRecords
                    .sortedWith(compareBy<SrvRecordData> { it.priority }.thenByDescending { it.weight })
                    .first()
                
                // Build URL - if port is 443, omit it from URL for cleaner appearance
                val resolvedUrl = if (bestRecord.port == 443) {
                    "https://${bestRecord.target}/apk/v2/"
                } else {
                    "https://${bestRecord.target}:${bestRecord.port}/apk/v2/"
                }
                
                Log.i(TAG, "✅ Resolved API URL via SRV: $resolvedUrl (priority: ${bestRecord.priority}, weight: ${bestRecord.weight})")
                
                cachedApiUrl = resolvedUrl
                return@withContext resolvedUrl
            } else {
                Log.w(TAG, "No SRV records found for $SRV_RECORD, using fallback")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve SRV record: ${e.message}", e)
        }
        
        // Fallback to default URL
        Log.i(TAG, "Using default API URL: $DEFAULT_API_URL")
        cachedApiUrl = DEFAULT_API_URL
        return@withContext DEFAULT_API_URL
    }
    
    /**
     * Query DNS for SRV records using MiniDNS
     * 
     * @param srvName The SRV record name to query
     * @return List of SRV records found
     */
    private fun querySrvRecord(srvName: String): List<SrvRecordData> {
        val records = mutableListOf<SrvRecordData>()
        
        try {
            Log.d(TAG, "Querying SRV record: $srvName")
            
            // Use MiniDNS ResolverApi to query SRV records
            val result = ResolverApi.INSTANCE.resolve(srvName, SRV::class.java)
            
            if (result.wasSuccessful()) {
                // Get the answer section which contains the SRV records
                val answerSection = result.answersOrEmptySet
                
                Log.d(TAG, "SRV query successful, found ${answerSection.size} record(s)")
                
                answerSection.forEach { record ->
                    // The record itself is the SRV object in MiniDNS
                    if (record is SRV) {
                        val srvData = SrvRecordData(
                            priority = record.priority,
                            weight = record.weight,
                            port = record.port,
                            target = record.target.toString().trimEnd('.') // Remove trailing dot
                        )
                        records.add(srvData)
                        Log.d(TAG, "Found SRV record: priority=${srvData.priority}, weight=${srvData.weight}, port=${srvData.port}, target=${srvData.target}")
                    } else {
                        Log.w(TAG, "Unexpected record type: ${record?.javaClass?.simpleName}")
                    }
                }
            } else {
                Log.w(TAG, "SRV query failed with response code: ${result.responseCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNS lookup failed for $srvName: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        
        return records
    }
    
    /**
     * Clear the cached API URL (useful for testing or forcing re-resolution)
     */
    fun clearCache() {
        cachedApiUrl = null
        Log.d(TAG, "API URL cache cleared")
    }
}

/**
 * Data class representing a DNS SRV record
 */
private data class SrvRecordData(
    val priority: Int,
    val weight: Int,
    val port: Int,
    val target: String
)

