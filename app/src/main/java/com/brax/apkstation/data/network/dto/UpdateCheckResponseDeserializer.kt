package com.brax.apkstation.data.network.dto

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type

/**
 * Custom deserializer for UpdateCheckResponseDto
 * 
 * The API returns inconsistent types for apps with/without updates:
 * - App WITH update: "package.name": { version, url, etc... }
 * - App WITHOUT update: "package.name": []  (empty array)
 * 
 * This deserializer handles both cases and converts empty arrays to null
 */
class UpdateCheckResponseDeserializer : JsonDeserializer<UpdateCheckResponseDto> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): UpdateCheckResponseDto {
        val jsonObject = json.asJsonObject
        val appsObject = jsonObject.getAsJsonObject("apps")
        
        val appsMap = mutableMapOf<String, ApkVersionDto?>()
        
        for ((packageName, element) in appsObject.entrySet()) {
            // Check if it's an array (empty array means no update)
            val updateInfo = if (element.isJsonArray) {
                // Empty array = no update available
                null
            } else if (element.isJsonObject) {
                // Object = update available
                context.deserialize<ApkVersionDto>(element, ApkVersionDto::class.java)
            } else {
                // null or other = no update
                null
            }
            
            appsMap[packageName] = updateInfo
        }
        
        return UpdateCheckResponseDto(apps = appsMap)
    }
}
