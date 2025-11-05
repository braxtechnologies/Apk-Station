package com.brax.apkstation.data.room

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

object Converters {
    @TypeConverter
    fun listFromString(value: String?): List<String> {
        val listType: Type = object : TypeToken<ArrayList<String?>?>() {}.type
        if (value.isNullOrEmpty()) return emptyList()
        return Gson().fromJson(value, listType)
    }

    @TypeConverter
    fun stringFromList(list: List<String>): String {
        if (list.isEmpty()) return ""
        return Gson().toJson(list)
    }
}
