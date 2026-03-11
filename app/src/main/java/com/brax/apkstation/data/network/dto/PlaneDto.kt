package com.brax.apkstation.data.network.dto

import com.google.gson.annotations.SerializedName

/**
 * Request body for POST /api/v1/workspaces/{workspace_slug}/projects/{project_id}/intake-issues/
 */
data class PlaneIntakeIssueRequest(
    @SerializedName("issue")
    val issue: PlaneIssueBody
)

data class PlaneIssueBody(
    @SerializedName("name")
    val name: String,
    @SerializedName("description_html")
    val descriptionHtml: String? = null
)

