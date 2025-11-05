package com.brax.apkstation.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages JWT tokens for Lunr API authentication
 * Stores access token, refresh token, device UUID, and expiration time
 */
@Singleton
class TokenManager @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    
    companion object {
        private val ACCESS_TOKEN_KEY = stringPreferencesKey("jwt_access_token")
        private val REFRESH_TOKEN_KEY = stringPreferencesKey("jwt_refresh_token")
        private val DEVICE_UUID_KEY = stringPreferencesKey("device_uuid")
        private val TOKEN_EXPIRY_KEY = longPreferencesKey("token_expiry_timestamp")
        
        // Refresh token 5 minutes before expiry
        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L
    }
    
    /**
     * Save JWT tokens after enrollment or refresh
     */
    suspend fun saveTokens(
        accessToken: String,
        refreshToken: String,
        deviceUuid: String,
        expiresIn: Int
    ) {
        val expiryTimestamp = System.currentTimeMillis() + (expiresIn * 1000)
        
        dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN_KEY] = accessToken
            prefs[REFRESH_TOKEN_KEY] = refreshToken
            prefs[DEVICE_UUID_KEY] = deviceUuid
            prefs[TOKEN_EXPIRY_KEY] = expiryTimestamp
        }
    }
    
    /**
     * Update only the access token (used when refreshing)
     */
    suspend fun updateAccessToken(accessToken: String, expiresIn: Int) {
        val expiryTimestamp = System.currentTimeMillis() + (expiresIn * 1000)
        
        dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN_KEY] = accessToken
            prefs[TOKEN_EXPIRY_KEY] = expiryTimestamp
        }
    }
    
    /**
     * Get access token
     */
    fun getAccessToken(): Flow<String?> = dataStore.data.map { prefs ->
        prefs[ACCESS_TOKEN_KEY]
    }
    
    /**
     * Get access token synchronously (for use in suspend functions)
     */
    suspend fun getAccessTokenSync(): String? {
        return dataStore.data.first()[ACCESS_TOKEN_KEY]
    }
    
    /**
     * Get refresh token
     */
    fun getRefreshToken(): Flow<String?> = dataStore.data.map { prefs ->
        prefs[REFRESH_TOKEN_KEY]
    }
    
    /**
     * Get refresh token synchronously
     */
    suspend fun getRefreshTokenSync(): String? {
        return dataStore.data.first()[REFRESH_TOKEN_KEY]
    }
    
    /**
     * Get device UUID
     */
    suspend fun getDeviceUuid(): String? {
        return dataStore.data.first()[DEVICE_UUID_KEY]
    }
    
    /**
     * Check if token is expired or about to expire
     */
    suspend fun isTokenExpired(): Boolean {
        val expiry = dataStore.data.first()[TOKEN_EXPIRY_KEY] ?: 0L
        return System.currentTimeMillis() >= (expiry - REFRESH_BUFFER_MS)
    }
    
    /**
     * Check if device is enrolled (has tokens)
     */
    suspend fun isEnrolled(): Boolean {
        val accessToken = getAccessTokenSync()
        return !accessToken.isNullOrBlank()
    }
    
    /**
     * Clear all tokens (logout)
     */
    suspend fun clearTokens() {
        dataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN_KEY)
            prefs.remove(REFRESH_TOKEN_KEY)
            prefs.remove(DEVICE_UUID_KEY)
            prefs.remove(TOKEN_EXPIRY_KEY)
        }
    }
    
    /**
     * Get Bearer token formatted for Authorization header
     * Returns null if not enrolled
     */
    suspend fun getBearerToken(): String? {
        val accessToken = getAccessTokenSync()
        return if (!accessToken.isNullOrBlank()) {
            "Bearer $accessToken"
        } else {
            null
        }
    }
}

