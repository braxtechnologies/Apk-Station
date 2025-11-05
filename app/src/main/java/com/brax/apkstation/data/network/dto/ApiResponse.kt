package com.brax.apkstation.data.network.dto

import com.google.gson.annotations.SerializedName

/**
 * Base API response wrapper matching Lunr API specs
 */
data class ApiResponse<T>(
    @SerializedName("serverTime")
    val serverTime: String,
    
    @SerializedName("action")
    val action: String,
    
    @SerializedName("code")
    val code: Int,
    
    @SerializedName("response")
    val response: T
)

/**
 * Paginated list response
 */
data class PaginatedResponse<T>(
    @SerializedName("items")
    val items: List<T>,
    
    @SerializedName("page")
    val page: Int,
    
    @SerializedName("pageSize")
    val pageSize: Int,
    
    @SerializedName("total")
    val total: Int
)

/**
 * Search response
 */
data class SearchResponse<T>(
    @SerializedName("items")
    val items: List<T>
)
