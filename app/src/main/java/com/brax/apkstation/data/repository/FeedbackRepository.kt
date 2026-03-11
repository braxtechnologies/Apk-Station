package com.brax.apkstation.data.repository

import com.brax.apkstation.BuildConfig
import com.brax.apkstation.data.network.PlaneApiService
import com.brax.apkstation.data.network.dto.PlaneIntakeIssueRequest
import com.brax.apkstation.data.network.dto.PlaneIssueBody
import com.brax.apkstation.utils.Result
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedbackRepository @Inject constructor(
    private val planeApiService: PlaneApiService
) {

    suspend fun submitFeedback(title: String, description: String): Result<Unit> {
        return try {
            val descriptionHtml = if (description.isNotBlank()) "<p>$description</p>" else null
            val request = PlaneIntakeIssueRequest(
                issue = PlaneIssueBody(
                    name = title,
                    descriptionHtml = descriptionHtml
                )
            )
            val response = planeApiService.createIntakeIssue(
                apiKey = BuildConfig.PLANE_API_KEY,
                workspaceSlug = BuildConfig.PLANE_WORKSPACE_SLUG,
                projectId = BuildConfig.PLANE_PROJECT_ID,
                request = request
            )
            if (response.isSuccessful) {
                Result.Success(Unit)
            } else {
                Result.Error("Failed to submit feedback (${response.code()})")
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Unknown error")
        }
    }
}
