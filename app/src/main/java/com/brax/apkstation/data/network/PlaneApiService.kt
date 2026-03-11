package com.brax.apkstation.data.network

import com.brax.apkstation.data.network.dto.PlaneIntakeIssueRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Plane API Service — Feedback / Intake Issues
 * Base URL: https://projects.lunr.tech/
 */
interface PlaneApiService {

    /**
     * Create an intake work item in a Plane project.
     * POST /api/v1/workspaces/{workspace_slug}/projects/{project_id}/intake-issues/
     *
     * @param apiKey Plane API token (format: "your-api-key")
     * @param workspaceSlug The workspace slug (e.g. "brax")
     * @param projectId The project identifier (e.g. "APKSTATION")
     * @param request The intake issue details
     */
    @POST("api/v1/workspaces/{workspace_slug}/projects/{project_id}/intake-issues/")
    suspend fun createIntakeIssue(
        @Header("X-API-Key") apiKey: String,
        @Path("workspace_slug") workspaceSlug: String,
        @Path("project_id") projectId: String,
        @Body request: PlaneIntakeIssueRequest
    ): Response<Unit>
}
