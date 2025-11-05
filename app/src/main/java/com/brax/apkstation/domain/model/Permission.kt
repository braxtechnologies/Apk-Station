package com.brax.apkstation.domain.model

data class Permission(
    val id: Int,
    val title: String,
    val description: String,
    val granted: Boolean = false,
    val optional: Boolean = false
)
