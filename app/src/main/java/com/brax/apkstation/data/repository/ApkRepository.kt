package com.brax.apkstation.data.repository

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.brax.apkstation.data.auth.TokenManager
import com.brax.apkstation.data.model.DownloadStatus
import com.brax.apkstation.data.network.LunrApiService
import com.brax.apkstation.data.network.dto.ApkDetailsDto
import com.brax.apkstation.data.network.dto.ApkPreviewDto
import com.brax.apkstation.data.network.dto.CategoryDto
import com.brax.apkstation.data.network.dto.DownloadResponseDto
import com.brax.apkstation.data.network.dto.EnrollRequestDto
import com.brax.apkstation.data.network.dto.EnrollResponseDto
import com.brax.apkstation.data.network.dto.RefreshResponseDto
import com.brax.apkstation.data.network.dto.UpdateCheckRequestDto
import com.brax.apkstation.data.room.dao.StoreDao
import com.brax.apkstation.data.room.entity.DBApplication
import com.brax.apkstation.data.room.entity.Download
import com.brax.apkstation.presentation.ui.lending.AppStatus
import com.brax.apkstation.utils.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("TooManyFunctions", "MaxLineLength")
@Singleton
class ApkRepository @Inject constructor(
    private val apiService: LunrApiService,
    private val storeDao: StoreDao,
    private val tokenManager: TokenManager,
    @param:ApplicationContext private val context: Context
) {
    
    /**
     * Get Bearer token for API authentication
     * Automatically refreshes if token is expired
     * Enrolls device if not enrolled yet
     */
    private suspend fun getBearerToken(): String {
        // Check if enrolled
        if (!tokenManager.isEnrolled()) {
            // Enroll device first
            when (val result = enrollDevice()) {
                is Result.Success -> {}
                is Result.Error -> {
                    throw Exception("Failed to enroll device: ${result.message}")
                }
                else -> {
                    throw Exception("Failed to enroll device")
                }
            }
        }
        
        // Check if token needs refresh
        if (tokenManager.isTokenExpired()) {
            when (refreshAccessToken()) {
                is Result.Success -> {}
                is Result.Error -> {
                    // Refresh failed, try to re-enroll
                    tokenManager.clearTokens()
                    return getBearerToken() // Recursive call will enroll
                }
                else -> {}
            }
        }
        
        // Return Bearer token
        return tokenManager.getBearerToken() ?: throw Exception("No access token available")
    }
    
    // ========== Authentication Methods ==========
    
    /**
     * Enroll device to get JWT tokens
     * No authentication required for this endpoint
     * 
     * Note: Each enrollment creates a unique device UID (Android ID + timestamp).
     * This allows re-enrollment if tokens are lost (e.g., after clearing app data).
     * Each enrollment creates a new device entry on the server.
     */
    suspend fun enrollDevice(): Result<EnrollResponseDto> {
        return try {
            // Get device information
            val deviceSn = Build.SERIAL.takeIf { it != Build.UNKNOWN } ?: Build.ID
            val deviceModel = Build.MODEL
            
            // Use Android ID as base
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            
            // Always add timestamp to allow re-enrollment after app data clearing
            // This ensures users can always get new tokens if they lose them
            val deviceUid = "$androidId-${System.currentTimeMillis()}"
            
            val manufacturer = Build.MANUFACTURER
            
            val enrollRequest = EnrollRequestDto(
                deviceSn = deviceSn,
                deviceModel = deviceModel,
                imei1 = null, // IMEI requires READ_PHONE_STATE permission
                imei2 = null,
                deviceUid = deviceUid,
                manufacturer = manufacturer
            )
            
            val response = apiService.enroll(enrollRequest)
            
            if (response.isSuccessful) {
                val body = response.body()
                
                // Handle 208 - Device already enrolled
                if (body != null && body.code == 208) {
                    val errorMsg = "Device already enrolled with this identifier. This shouldn't happen with timestamp-based UIDs."
                    Log.e("ApkRepository", "Response: ${body.response}")
                    return Result.Error(errorMsg)
                }
                
                if (body != null && body.code == 200) {
                    // Response can be either EnrollResponseDto (success) or String (error)
                    when (val responseData = body.response) {
                        is Map<*, *> -> {
                            // Success case - parse as EnrollResponseDto
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val responseMap = responseData as Map<String, Any>
                                val enrollResponse = EnrollResponseDto(
                                    uuid = responseMap["uuid"] as String,
                                    accessToken = responseMap["accessToken"] as String,
                                    refreshToken = responseMap["refreshToken"] as String,
                                    expiresIn = (responseMap["expiresIn"] as Double).toInt()
                                )
                                
                                // Save tokens to secure storage
                                tokenManager.saveTokens(
                                    enrollResponse.accessToken,
                                    enrollResponse.refreshToken,
                                    enrollResponse.uuid,
                                    enrollResponse.expiresIn
                                )
                                
                                Result.Success(enrollResponse)
                            } catch (e: Exception) {
                                val errorMsg = "Failed to parse enrollment response: ${e.message}"
                                Log.e("ApkRepository", errorMsg, e)
                                Result.Error(errorMsg)
                            }
                        }
                        is String -> {
                            // Error case - response is an error message string
                            val errorMsg = "Enrollment failed: $responseData"
                            Log.e("ApkRepository", errorMsg)
                            Result.Error(errorMsg)
                        }
                        else -> {
                            val errorMsg = "Enrollment failed: Unexpected response type ${responseData.javaClass.simpleName}"
                            Log.e("ApkRepository", errorMsg)
                            Result.Error(errorMsg)
                        }
                    }
                } else {
                    val errorMsg = "Enrollment failed: API returned code ${body?.code}, response: ${body?.response}"
                    Log.e("ApkRepository", errorMsg)
                    Result.Error(errorMsg)
                }
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMsg = "HTTP ${response.code()}: ${response.message()}, body: $errorBody"
                Log.e("ApkRepository", errorMsg)
                Result.Error(errorMsg)
            }
        } catch (e: Exception) {
            Log.e("ApkRepository", "Enrollment error", e)
            Result.Error(e.message ?: "Enrollment failed")
        }
    }
    
    /**
     * Refresh access token using refresh token
     */
    suspend fun refreshAccessToken(): Result<RefreshResponseDto> {
        return try {
            val refreshToken = tokenManager.getRefreshTokenSync()
                ?: return Result.Error("No refresh token available")
            
            val response = apiService.refresh("Bearer $refreshToken")
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.code == 200) {
                    // Response can be either RefreshResponseDto (success) or String (error)
                    when (val responseData = body.response) {
                        is Map<*, *> -> {
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val responseMap = responseData as Map<String, Any>
                                val refreshResponse = RefreshResponseDto(
                                    uuid = responseMap["uuid"] as String,
                                    accessToken = responseMap["accessToken"] as String,
                                    expiresIn = (responseMap["expiresIn"] as Double).toInt()
                                )
                                
                                // Update access token
                                tokenManager.updateAccessToken(
                                    refreshResponse.accessToken,
                                    refreshResponse.expiresIn
                                )
                                
                                Result.Success(refreshResponse)
                            } catch (e: Exception) {
                                val errorMsg = "Failed to parse refresh response: ${e.message}"
                                Log.e("ApkRepository", errorMsg, e)
                                Result.Error(errorMsg)
                            }
                        }
                        is String -> {
                            val errorMsg = "Token refresh failed: $responseData"
                            Log.e("ApkRepository", errorMsg)
                            Result.Error(errorMsg)
                        }
                        else -> {
                            val errorMsg = "Token refresh failed: Unexpected response type"
                            Log.e("ApkRepository", errorMsg)
                            Result.Error(errorMsg)
                        }
                    }
                } else {
                    Result.Error("Token refresh failed: API returned code ${body?.code}, response: ${body?.response}")
                }
            } else {
                Result.Error("HTTP ${response.code()}: ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e("ApkRepository", "Token refresh error", e)
            Result.Error(e.message ?: "Token refresh failed")
        }
    }
    
    // ========== APK API Methods ==========
    
    /**
     * Fetch paginated list of APKs from API and sync with local database
     */
    suspend fun fetchApkList(
        category: String? = null,
        page: Int = 0,
        limit: Int = 20,
        sort: String = "requests"
    ): Result<List<ApkPreviewDto>> {
        return try {
            val response = apiService.getApkList(getBearerToken(), category, page, limit, sort)
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.code == 200) {
                    val items = body.response.items
                    
                    // Don't sync all apps to database
                    // Only save apps when user interacts with them (install, download, etc.)
                    
                    Result.Success(items)
                } else {
                    Result.Error("API returned code: ${body?.code}")
                }
            } else {
                Result.Error("HTTP ${response.code()}: ${response.message()}")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Unknown error occurred")
        }
    }
    
    /**
     * Search for APKs by query
     */
    suspend fun searchApks(query: String): Result<List<ApkPreviewDto>> {
        return try {
            val response = apiService.searchApks(getBearerToken(), query)
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.code == 200) {
                    Result.Success(body.response.items)
                } else if (body != null && body.code == 404) {
                    Result.Success(emptyList()) // No results found
                } else {
                    Result.Error("API returned code: ${body?.code}")
                }
            } else {
                Result.Error("HTTP ${response.code()}: ${response.message()}")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Unknown error occurred")
        }
    }

    /**
     * Retrieve application categories
     * @param onlyWithApps If true, only returns categories that have at least one app
     * @return Map of category key to category info (name, count, packages)
     */
    suspend fun getCategories(onlyWithApps: Boolean = true): Result<Map<String, CategoryDto>> {
        return try {
            val sort = if (onlyWithApps) "apps" else null
            val response = apiService.getCategories(getBearerToken(), sort)
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.code == 200) {
                    Result.Success(body.response)
                } else {
                    Result.Error("API returned code: ${body?.code}")
                }
            } else {
                Result.Error("HTTP ${response.code()}: ${response.message()}")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Unknown error occurred")
        }
    }
    
    /**
     * Get detailed information about a specific APK
     * 
     * @param uuid The unique identifier (UUID) of the application
     * @param packageName Android package name (alternative to uuid)
     * 
     * Note: One of uuid or packageName must be provided.
     * If uuid is provided, it takes precedence over packageName.
     */
    suspend fun getApkDetails(
        uuid: String? = null,
        packageName: String? = null
    ): Result<ApkDetailsDto> {
        return try {
            // Validate that at least one parameter is provided
            if (uuid.isNullOrEmpty() && packageName.isNullOrEmpty()) {
                return Result.Error("Either uuid or packageName must be provided")
            }
            
            Log.d("ApkRepository", "getApkDetails: uuid=$uuid, packageName=$packageName")
            
            val response = apiService.getApkDetails(getBearerToken(), uuid, packageName)
            
            if (response.isSuccessful) {
                val body = response.body()
                Log.d("ApkRepository", "Details response: code=${body?.code}, uuid=${body?.response?.uuid}, package=${body?.response?.packageName}")
                
                if (body != null && body.code == 200) {
                    Result.Success(body.response)
                } else if (body != null && body.code == 404) {
                    Result.Error("Package not found. The app may not be available in the store yet.")
                } else {
                    Result.Error("API returned code: ${body?.code}, response: ${body?.response}")
                }
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e("ApkRepository", "Details error: HTTP ${response.code()}, body: $errorBody")
                Result.Error("HTTP ${response.code()}: ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e("ApkRepository", "Details exception", e)
            Result.Error(e.message ?: "Unknown error occurred")
        }
    }
    
    /**
     * Get download URL for a specific APK
     * Uses /download endpoint to get actual download link with MD5
     * 
     * @param uuid Unique identifier of the APK (UUID v4)
     * @param packageName Android package name (alternative to uuid)
     * @param versionCode Optional version code for specific version
     * 
     * Note: One of uuid or packageName must be provided.
     * If uuid is provided, it takes precedence over packageName.
     */
    suspend fun getDownloadUrl(
        uuid: String? = null,
        packageName: String? = null,
        versionCode: Int? = null
    ): Result<DownloadResponseDto> {
        return try {
            // Validate that at least one parameter is provided
            if (uuid.isNullOrEmpty() && packageName.isNullOrEmpty()) {
                return Result.Error("Either uuid or packageName must be provided")
            }
            
            Log.d("ApkRepository", "getDownloadUrl: uuid=$uuid, packageName=$packageName, versionCode=$versionCode")
            
            val response = apiService.getDownloadUrl(getBearerToken(), uuid, packageName, versionCode)
            
            if (response.isSuccessful) {
                val body = response.body()
                Log.d("ApkRepository", "Download response: code=${body?.code}, response type=${body?.response?.type}")
                
                if (body != null && body.code == 200) {
                    Result.Success(body.response)
                } else if (body != null && body.code == 404) {
                    Result.Error("App not found. It may not be available in the store yet.")
                } else if (body != null && body.code == 408) {
                    // Request timeout - APK is being fetched from external source
                    Result.Error("Request timed out. The app is being prepared. Please try again in a moment.")
                } else {
                    Result.Error("API returned code: ${body?.code}, response: ${body?.response}")
                }
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e("ApkRepository", "Download URL error: HTTP ${response.code()}, body: $errorBody")
                
                // Handle HTTP 408 timeout
                if (response.code() == 408) {
                    Result.Error("Request timed out. The app is being prepared. Please try again in a moment.")
                } else {
                    Result.Error("HTTP ${response.code()}: ${response.message()}")
                }
            }
        } catch (e: Exception) {
            Log.e("ApkRepository", "Download URL exception", e)
            
            // Handle timeout exceptions specifically
            when (e) {
                is SocketTimeoutException -> {
                    Result.Error("Request timed out. The app is being prepared from external source. Please try again in a few minutes.")
                }
                else -> {
                    Result.Error(e.message ?: "Unknown error occurred")
                }
            }
        }
    }


    /**
     * Check for updates for multiple apps at once
     * POST /updates
     *
     * @param apps Map of package name to current version code
     * @return Map of package name to ApkVersionDto (only for apps with updates)
     */
    suspend fun checkForUpdates(apps: Map<String, Int>): Result<Map<String, com.brax.apkstation.data.network.dto.ApkVersionDto>> {
        return try {
            val request = UpdateCheckRequestDto(apps)
            val response = apiService.checkForUpdates(getBearerToken(), request)
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.code == 200) {
                    // Filter out null values (apps with no updates)
                    val updatesMap = body.response.apps.filterValues { it != null }
                        .mapValues { it.value!! }
                    Result.Success(updatesMap)
                } else {
                    Result.Error("API returned code: ${body?.code}")
                }
            } else {
                Result.Error("HTTP ${response.code()}: ${response.message()}")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Unknown error occurred")
        }
    }
    
    // ========== Local Database Operations ==========
    
    /**
     * Get all apps from local database (non-Flow version)
     */
    suspend fun getAllAppsFromDbNoFlow(): List<DBApplication> {
        return storeDao.getAllApplicationsNoFlow()
    }
    
    /**
     * Search apps in local database
     */
    suspend fun searchAppsInDb(query: String): List<DBApplication> {
        return storeDao.searchForApps(query)
    }
    
    /**
     * Get app by package name from local database
     */
    suspend fun getAppByPackageName(packageName: String): DBApplication? {
        return storeDao.findApplicationByPackageName(packageName)
    }

    // ========== Private Helper Methods ==========
    
    /**
     * Save app to database when user interacts with it
     * This should be called when user clicks install, download, etc.
     */
    suspend fun saveAppToDatabase(details: ApkDetailsDto, status: AppStatus) {
        val dbApp = apkDetailsToDatabaseModel(details, status)
        storeDao.insertApplication(dbApp)
    }
    
    /**
     * Save APK details to database (public method for ViewModel access)
     */
    suspend fun saveApkDetailsToDb(details: ApkDetailsDto, status: AppStatus) {
        saveAppToDatabase(details, status)
    }

    /**
     * Save download to database
     */
    suspend fun saveDownloadToDb(download: Download) {
        storeDao.insertDownload(download)
    }
    
    /**
     * Delete download from database
     */
    suspend fun deleteDownload(packageName: String) {
        storeDao.deleteDownload(packageName)
    }
    
    /**
     * Update download status
     */
    suspend fun updateDownloadStatus(packageName: String, status: DownloadStatus) {
        storeDao.updateDownloadStatus(packageName, status)
    }
    
    /**
     * Get download from database
     */
    suspend fun getDownload(packageName: String): Download? {
        return storeDao.getDownload(packageName)
    }

    /**
     * Convert detailed APK info to DBApplication
     */
    fun apkDetailsToDatabaseModel(details: ApkDetailsDto, status: AppStatus = AppStatus.NOT_INSTALLED): DBApplication {
        // Get the latest version's download URL and info
        val latestVersion = details.versions.firstOrNull()
        
        return DBApplication(
            packageName = details.packageName,
            uuid = details.uuid,
            name = details.name,
            version = latestVersion?.version ?: details.version,
            versionCode = latestVersion?.versionCode ?: details.versionCode,
            downloadUrl = latestVersion?.url,
            type = 2,
            fileType = latestVersion?.fileType ?: details.fileType,
            icon = details.icon,
            author = details.author,
            rating = details.rating?.takeIf { it.isNotBlank() }?.toFloatOrNull()?.toString(),
            size = latestVersion?.fileSize ?: details.fileSize,
            category = details.category,
            contentRating = details.contentRating,
            description = details.description,
            images = details.images,
            status = status
        )
    }
    
    /**
     * Update version info in database (used after installation to update hasUpdate flag)
     */
    suspend fun updateVersionInfo(packageName: String, latestVersionCode: Int, hasUpdate: Boolean) {
        storeDao.updateApplicationVersionInfo(packageName, latestVersionCode, hasUpdate)
    }
    
    /**
     * Get all applications from database as Flow
     * Automatically emits when AppStatusHelper or other components update the database
     */
    fun getAllApplications(): Flow<List<DBApplication>> {
        return storeDao.getAllApplications()
    }
    
    // ========== Favorites Operations ==========

    /**
     * Get all favorite apps from local database (non-Flow version)
     */
    suspend fun getFavoriteAppsNoFlow(): List<DBApplication> {
        return storeDao.getFavoriteApplicationsNoFlow()
    }
    
    /**
     * Toggle favorite status for an app
     * If app doesn't exist in database, it will be fetched from API and added
     */
    suspend fun toggleFavorite(packageName: String, isFavorite: Boolean) {
        val app = storeDao.findApplicationByPackageName(packageName)
        if (app != null) {
            // App exists in database, just update favorite status
            storeDao.updateFavoriteStatus(packageName, isFavorite)
        } else if (isFavorite) {
            // App doesn't exist but user wants to favorite it
            // Fetch from API and save to database
            when (val result = getApkDetails(packageName = packageName)) {
                is Result.Success -> {
                    val details = result.data
                    val dbApp = apkDetailsToDatabaseModel(details, AppStatus.NOT_INSTALLED)
                    dbApp.isFavorite = true
                    storeDao.insertApplication(dbApp)
                }
                else -> {
                    // Failed to fetch, ignore
                    Log.w("ApkRepository", "Failed to fetch app details for favorite: $packageName")
                }
            }
        }
    }
    
    /**
     * Check if an app is favorited
     */
    suspend fun isFavorite(packageName: String): Boolean {
        val app = storeDao.findApplicationByPackageName(packageName)
        return app?.isFavorite ?: false
    }
}
