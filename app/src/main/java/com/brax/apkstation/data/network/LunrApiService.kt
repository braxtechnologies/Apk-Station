package com.brax.apkstation.data.network

import com.brax.apkstation.data.network.dto.ApiResponse
import com.brax.apkstation.data.network.dto.ApkDetailsDto
import com.brax.apkstation.data.network.dto.ApkPreviewDto
import com.brax.apkstation.data.network.dto.CategoriesResponse
import com.brax.apkstation.data.network.dto.DownloadResponseDto
import com.brax.apkstation.data.network.dto.EnrollRequestDto
import com.brax.apkstation.data.network.dto.PaginatedResponse
import com.brax.apkstation.data.network.dto.SearchResponse
import com.brax.apkstation.data.network.dto.UpdateCheckRequestDto
import com.brax.apkstation.data.network.dto.UpdateCheckResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * Lunr API Service - Apk Station endpoints
 * Base URL: Dynamically resolved via SRV record "_https._tcp.api.braxtech.net"
 * Fallback: https://api.braxtech.net/apk/
 */
interface LunrApiService {

    /**
     * Enroll a new device to get JWT tokens
     * POST /enroll
     * 
     * This endpoint does NOT require authentication
     * Note: response can be either EnrollResponseDto (success) or String (error)
     */
    @retrofit2.http.POST("enroll")
    suspend fun enroll(
        @retrofit2.http.Body enrollRequest: EnrollRequestDto
    ): Response<ApiResponse<Any>>

    /**
     * Refresh JWT access token using refresh token
     * GET /refresh
     * 
     * @param authorization Bearer refresh token (format: "Bearer {refresh_token}")
     * Note: response can be either RefreshResponseDto (success) or String (error)
     */
    @GET("refresh")
    suspend fun refresh(
        @Header("Authorization") authorization: String
    ): Response<ApiResponse<Any>>

    /**
     * Logout and revoke tokens
     * GET /logout
     * 
     * @param authorization Bearer token (access or refresh)
     */
    @GET("logout")
    suspend fun logout(
        @Header("Authorization") authorization: String
    ): Response<ApiResponse<String>>

    /**
     * Ping API to check service availability
     * GET /ping
     * 
     * Note: This endpoint does NOT require authentication according to the spec
     */
    @GET("ping")
    suspend fun ping(): Response<ApiResponse<String>>

    /**
     * Retrieve paginated list of all available APKs
     * GET /list
     * 
     * @param authorization Bearer token (format: "Bearer {token}")
     * @param category Optional filter by category
     * @param page Page number (default: 0)
     * @param limit Results per page (default: 20)
     */
    @GET("list")
    suspend fun getApkList(
        @Header("Authorization") authorization: String,
        @Query("category") category: String? = null,
        @Query("page") page: Int = 0,
        @Query("limit") limit: Int = 20,
        @Query("sort") sort: String = "requests"
    ): Response<ApiResponse<PaginatedResponse<ApkPreviewDto>>>

    /**
     * Search for APKs by name or package
     * GET /search
     * 
     * @param authorization Bearer token (format: "Bearer {token}")
     * @param query Application name or package name
     */
    @GET("search")
    suspend fun searchApks(
        @Header("Authorization") authorization: String,
        @Query("q") query: String
    ): Response<ApiResponse<SearchResponse<ApkPreviewDto>>>

    /**
     * Get detailed information about a specific APK
     * GET /details
     * 
     * @param authorization Bearer token (format: "Bearer {token}")
     * @param uuid The unique identifier (UUID) of the application
     * @param packageName Android package name (alternative to uuid)
     * 
     * Note: One of uuid or packageName must be provided
     */
    @GET("details")
    suspend fun getApkDetails(
        @Header("Authorization") authorization: String,
        @Query("uuid") uuid: String? = null,
        @Query("package") packageName: String? = null
    ): Response<ApiResponse<ApkDetailsDto>>

    /**
     * Download APK file or request remote retrieval
     * GET /download
     * 
     * @param authorization Bearer token (format: "Bearer {token}")
     * @param uuid Unique identifier of the APK (UUID v4)
     * @param packageName Android package name (alternative to uuid)
     * @param versionCode Optional version code for specific version
     * @return Download URL with MD5 checksum or request trigger
     */
    @GET("download")
    suspend fun getDownloadUrl(
        @Header("Authorization") authorization: String,
        @Query("uuid") uuid: String? = null,
        @Query("package") packageName: String? = null,
        @Query("versionCode") versionCode: Int? = null
    ): Response<ApiResponse<DownloadResponseDto>>

    /**
     * Request an APK to be added to the store
     * GET /request?package={packageName}
     *
     * @param authorization Bearer token (format: "Bearer {token}")
     * @param packageName Package name of the APK to request
     * @return 202 if accepted, 200 if already exists with download details
     */
    @GET("request")
    suspend fun requestApk(
        @Header("Authorization") authorization: String,
        @Query("package") packageName: String
    ): Response<ApiResponse<Any>> // Can be String ("Accepted") or download details

    /**
     * Check for APK updates
     * POST /updates
     *
     * @param authorization Bearer token (format: "Bearer {token}")
     * @param request Map of package names to their current version codes
     * @return Update information for apps that have updates available
     */
    @retrofit2.http.POST("updates")
    suspend fun checkForUpdates(
        @Header("Authorization") authorization: String,
        @retrofit2.http.Body request: UpdateCheckRequestDto
    ): Response<ApiResponse<UpdateCheckResponseDto>>

    /**
     * Retrieve application categories
     * GET /categories
     *
     * @param authorization Bearer token (format: "Bearer {token}")
     * @param sort Optional filter - when set to "apps", only returns categories with at least one app
     * @return Map of category keys to category information (count, name, packages)
     */
    @GET("categories")
    suspend fun getCategories(
        @Header("Authorization") authorization: String,
        @Query("sort") sort: String? = null
    ): Response<ApiResponse<CategoriesResponse>>
}
