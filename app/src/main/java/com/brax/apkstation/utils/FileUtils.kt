package com.brax.apkstation.utils

import kotlin.math.roundToInt

/**
 * Formats file size from bytes to human-readable format
 * 
 * @param sizeInBytes The file size in bytes as a string
 * @return Formatted size string (e.g., "123 KB", "45.6 MB", "1.23 GB")
 */
fun formatFileSize(sizeInBytes: String?): String {
    if (sizeInBytes.isNullOrEmpty()) return "Unknown"
    
    return try {
        val bytes = sizeInBytes.toLongOrNull() ?: return "Unknown"
        
        when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> {
                // KB: Round to whole number
                val kb = (bytes / 1024.0).roundToInt()
                "$kb KB"
            }
            bytes < 1024 * 1024 * 1024 -> {
                // MB: Round to whole number
                val mb = (bytes / (1024.0 * 1024)).roundToInt()
                "$mb MB"
            }
            else -> {
                // GB: Show as GB.MB with 2 decimal places
                val gb = bytes / (1024.0 * 1024 * 1024)
                String.format("%.2f GB", gb)
            }
        }
    } catch (e: Exception) {
        "Unknown"
    }
}

