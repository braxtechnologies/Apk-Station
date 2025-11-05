package com.brax.apkstation.data.network.dto

import com.google.gson.annotations.SerializedName

/**
 * DTO for category information from /categories endpoint
 */
data class CategoryDto(
    @SerializedName("count")
    val count: Int,
    
    @SerializedName("name")
    val name: String,
    
    @SerializedName("packages")
    val packages: List<String>
)

/**
 * Response wrapper for categories endpoint
 * The response is a map of category keys to CategoryDto objects
 */
typealias CategoriesResponse = Map<String, CategoryDto>



