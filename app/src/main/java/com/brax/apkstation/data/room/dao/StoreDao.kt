package com.brax.apkstation.data.room.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.brax.apkstation.data.model.DownloadStatus
import com.brax.apkstation.data.room.entity.DBApplication
import com.brax.apkstation.data.room.entity.Download
import com.brax.apkstation.presentation.ui.lending.AppStatus
import kotlinx.coroutines.flow.Flow

@Suppress("TooManyFunctions", "MaxLineLength")
@Dao
interface StoreDao {

    //Download DAO
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: Download)

    @Update
    suspend fun updateDownload(download: Download)

    @Query("SELECT * FROM download WHERE packageName = :packageName")
    suspend fun getDownload(packageName: String): Download?

    @Query("SELECT * FROM download")
    fun getAllDownloads(): Flow<List<Download>>
    
    @Query("SELECT * FROM download")
    suspend fun getAllDownloadsList(): List<Download>

    @Query("DELETE FROM download WHERE packageName = :packageName")
    suspend fun deleteDownload(packageName: String)

    @Query("DELETE FROM download")
    fun deleteAllDownloads()
    
    @Query("UPDATE download SET progress = :progress WHERE packageName = :packageName")
    suspend fun updateDownloadProgress(packageName: String, progress: Int)
    
    @Query("UPDATE download SET status = :status WHERE packageName = :packageName")
    suspend fun updateDownloadStatus(packageName: String, status: DownloadStatus)
    
    @Query("UPDATE download SET apkLocation = :location WHERE packageName = :packageName")
    suspend fun updateApkLocation(packageName: String, location: String)
    
    @Query("UPDATE download SET url = :url, md5 = :md5 WHERE packageName = :packageName")
    suspend fun updateDownloadUrl(packageName: String, url: String, md5: String?)

    //Application DAO
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApplication(dbApplication: DBApplication)

    @Update
    suspend fun updateApplication(dbApplication: DBApplication)

    @Query("SELECT * FROM application WHERE packageName LIKE :packageName LIMIT 1")
    suspend fun findApplicationByPackageName(packageName: String): DBApplication?

    @Query("SELECT * FROM application WHERE packageName LIKE :packageName LIMIT 1")
    fun getAndTrackApplication(packageName: String): Flow<DBApplication?>

    @Query("SELECT * FROM application ORDER BY name ASC")
    fun getAllApplications(): Flow<List<DBApplication>>

    @Query("SELECT * FROM application")
    suspend fun getAllApplicationsNoFlow(): List<DBApplication>

    @Query("SELECT * FROM application WHERE name LIKE '%' || :query || '%'")
    suspend fun searchForApps(query: String): List<DBApplication>

    @Query("DELETE FROM application WHERE packageName = :packageName")
    suspend fun deleteApplication(packageName: String)

    @Delete
    suspend fun deleteApplication(dbApplication: DBApplication)

    @Query("DELETE FROM application")
    fun deleteAllApplications()
    
    // Update-related queries
    @Query("SELECT * FROM application WHERE status = 'INSTALLED'")
    suspend fun getInstalledApplications(): List<DBApplication>
    
    @Query("UPDATE application SET latestVersionCode = :latestVersionCode, hasUpdate = :hasUpdate WHERE packageName = :packageName")
    suspend fun updateApplicationVersionInfo(packageName: String, latestVersionCode: Int, hasUpdate: Boolean)
    
    @Query("SELECT * FROM application WHERE hasUpdate = 1")
    fun getApplicationsWithUpdates(): Flow<List<DBApplication>>
    
    @Query("SELECT * FROM application WHERE hasUpdate = 1")
    suspend fun getApplicationsWithUpdatesNoFlow(): List<DBApplication>
    
    @Query("UPDATE application SET hasUpdate = :hasUpdate WHERE packageName = :packageName")
    suspend fun updateApplicationHasUpdate(packageName: String, hasUpdate: Boolean)
    
    @Query("UPDATE application SET hasUpdate = 0")
    suspend fun clearAllUpdateFlags()
    
    @Query("SELECT * FROM application WHERE status = :status")
    suspend fun getApplicationsByStatus(status: AppStatus): List<DBApplication>
    
    // Favorites queries
    @Query("SELECT * FROM application WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavoriteApplications(): Flow<List<DBApplication>>
    
    @Query("SELECT * FROM application WHERE isFavorite = 1 ORDER BY name ASC")
    suspend fun getFavoriteApplicationsNoFlow(): List<DBApplication>
    
    @Query("UPDATE application SET isFavorite = :isFavorite WHERE packageName = :packageName")
    suspend fun updateFavoriteStatus(packageName: String, isFavorite: Boolean)
    
    @Query("UPDATE application SET status = :status WHERE packageName = :packageName")
    suspend fun updateApplicationStatus(packageName: String, status: AppStatus)
    
    @Query("UPDATE application SET status = :status, latestVersionCode = :latestVersionCode, hasUpdate = :hasUpdate WHERE packageName = :packageName")
    suspend fun updateApplicationInstallStatus(
        packageName: String, 
        status: AppStatus, 
        latestVersionCode: Int, 
        hasUpdate: Boolean
    )
}
