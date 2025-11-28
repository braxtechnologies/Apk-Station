package com.brax.apkstation.data.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.app.PendingIntentCompat
import androidx.core.os.HandlerCompat
import com.brax.apkstation.app.android.StoreApplication
import com.brax.apkstation.data.event.InstallerEvent
import com.brax.apkstation.data.installer.base.InstallerBase
import com.brax.apkstation.data.model.SessionInfo
import com.brax.apkstation.data.receiver.InstallStatusReceiver
import com.brax.apkstation.data.room.entity.Download
import com.brax.apkstation.utils.CommonUtils.TAG
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installer using Android's PackageInstaller API
 */
@Singleton
class SessionInstaller @Inject constructor(
    @ApplicationContext context: Context
) : InstallerBase(context)  {

    val currentSessionId: Int?
        get() = enqueuedSessions.firstOrNull()?.lastOrNull()?.sessionId

    private val packageInstaller = context.packageManager.packageInstaller
    private val enqueuedSessions = mutableListOf<MutableSet<SessionInfo>>()

    /**
     * Callback for monitoring session lifecycle
     */
    val callback = object : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) {
            Log.d(TAG, "Session created: $sessionId")
        }

        override fun onBadgingChanged(sessionId: Int) {
            Log.d(TAG, "Session badging changed: $sessionId")
        }

        override fun onActiveChanged(sessionId: Int, active: Boolean) {
            Log.d(TAG, "Session $sessionId active: $active")
        }

        override fun onProgressChanged(sessionId: Int, progress: Float) {
            val packageName = enqueuedSessions
                .find { set -> set.any { it.sessionId == sessionId } }
                ?.first()
                ?.packageName

            if (packageName != null && progress > 0.0) {
                Log.d(TAG, "Installation progress for $packageName: ${(progress * 100).toInt()}%")
                StoreApplication.events.send(
                    InstallerEvent.Installing(
                        packageName = packageName,
                        progress = progress
                    )
                )
            }
        }

        override fun onFinished(sessionId: Int, success: Boolean) {
            Log.i(TAG, "Session $sessionId finished with success=$success")

            val sessionSet =
                enqueuedSessions.find { it.any { session -> session.sessionId == sessionId } }
                    ?: return

            // Find and remove the completed session
            val sessionToRemove = sessionSet.firstOrNull { it.sessionId == sessionId } ?: return
            sessionSet.remove(sessionToRemove)

            if (success && sessionSet.isNotEmpty()) {
                // Proceed with next session (e.g., shared lib)
                Log.i(TAG, "Proceeding with next session in set")
                commitInstall(sessionSet.first())
                return
            }

            // Remove empty sets
            val iterator = enqueuedSessions.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().isEmpty()) {
                    iterator.remove()
                }
            }

            // Proceed with the next available session
            enqueuedSessions.firstOrNull()?.firstOrNull()?.let {
                Log.i(TAG, "Starting next queued session for ${it.packageName}")
                commitInstall(it)
            }
        }
    }

    init {
        // Register session callback on main thread
        HandlerCompat.createAsync(android.os.Looper.getMainLooper()).post {
            packageInstaller.registerSessionCallback(callback)
        }
    }
    
    override suspend fun install(download: Download) {
        super.install(download)

        withContext(Dispatchers.IO) {
            val sessionSet =
                enqueuedSessions.find { set -> set.any { it.packageName == download.packageName } }
            
            if (sessionSet != null) {
                commitInstall(sessionSet.first())
            } else {
                try {
                    val file = File(download.apkLocation)
                    if (!file.exists()) {
                        throw IOException("APK file not found: ${download.apkLocation}")
                    }
                    
                    // Determine if file is a bundle or single APK
                    // First check the file type from API
                    val isBundle = when (download.fileType?.lowercase()?.trim()) {
                        "xapk", "zip" -> {
                            Log.d(TAG, "File type indicates bundle (XAPK/ZIP)")
                            true
                        }
                        "apk", null, "" -> {
                            // For APK or unknown types, check if it's actually a ZIP/bundle
                            // by inspecting the file signature
                            val actuallyIsZip = isZipFile(file)
                            if (actuallyIsZip) {
                                true
                            } else {
                                Log.d(TAG, "File type indicates single APK")
                                false
                            }
                        }
                        else -> {
                            Log.w(TAG, "Unknown file type '${download.fileType}', checking file signature")
                            isZipFile(file)
                        }
                    }
                    
                    val sessionInfoSet = mutableSetOf<SessionInfo>()
                    
                    if (isBundle) {
                        val sessionId = installBundle(file, download.packageName)
                        if (sessionId != null) {
                            sessionInfoSet.add(
                                SessionInfo(
                                    sessionId,
                                    download.packageName,
                                    download.versionCode.toLong(),
                                    download.displayName
                                )
                            )
                        }
                    } else {
                        val sessionId = installSingleApk(file, download.packageName)
                        if (sessionId != null) {
                            sessionInfoSet.add(
                                SessionInfo(
                                    sessionId,
                                    download.packageName,
                                    download.versionCode.toLong(),
                                    download.displayName
                                )
                            )
                        }
                    }
                    
                    if (sessionInfoSet.isEmpty()) {
                        Log.e(TAG, "Failed to create installation session for ${download.packageName}")
                        postError(
                            download.packageName,
                            "Failed to create installation session",
                            null
                        )
                        return@withContext
                    }

                    // Enqueue and trigger installation
                    enqueuedSessions.add(sessionInfoSet)
                    StoreApplication.enqueuedInstalls.add(download.packageName)
                    commitInstall(sessionInfoSet.first())
                } catch (e: Exception) {
                    Log.e(TAG, "Installation failed for ${download.packageName}", e)
                    postError(download.packageName, e.localizedMessage, e.stackTraceToString())
                }
            }
        }
    }
    
    private fun installSingleApk(apkFile: File, packageName: String): Int? {
        val sessionParams = createSessionParams(packageName)
        val installSessionId = packageInstaller.createSession(sessionParams)
        val session = packageInstaller.openSession(installSessionId)
        
        return try {
            // Write APK to session
            apkFile.inputStream().use { input ->
                session.openWrite(packageName, 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            
            installSessionId
        } catch (e: Exception) {
            session.abandon()
            removeFromInstallQueue(packageName)
            postError(packageName, e.localizedMessage, e.stackTraceToString())
            null
        }
    }
    
    private fun installBundle(bundleFile: File, packageName: String): Int? {
        // Extract the bundle
        val extractDir = File(bundleFile.parent, "extracted_$packageName")
        extractDir.mkdirs()
        
        return try {
            // Try to extract, fallback to single APK if it fails
            val apkFiles = try {
                extractApksFromBundle(bundleFile, extractDir)
            } catch (e: IOException) {
                // If Java extraction failed, try using unzip command (more lenient)
                Log.w(TAG, "Java ZIP extraction failed: ${e.message}")

                try {
                    extractWithUnzipCommand(bundleFile, extractDir)
                } catch (e2: Exception) {
                    Log.w(TAG, "Command-line unzip also failed: ${e2.message}")
                    extractDir.deleteRecursively()
                    return installSingleApk(bundleFile, packageName)
                }
            }
            
            if (apkFiles.isEmpty()) {
                extractDir.deleteRecursively()
                postError(packageName, "No APK files found in bundle", null)
                return null
            }
            
            // Create session and install all APKs
            val sessionParams = createSessionParams(packageName)
            val sessionId = packageInstaller.createSession(sessionParams)
            val session = packageInstaller.openSession(sessionId)
            
            try {
                // Write all APKs to session
                apkFiles.forEach { apkFile ->
                    apkFile.inputStream().use { input ->
                        session.openWrite(apkFile.name, 0, apkFile.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                }
                
                sessionId
            } catch (e: Exception) {
                session.abandon()
                removeFromInstallQueue(packageName)
                postError(packageName, e.localizedMessage, e.stackTraceToString())
                null
            } finally {
                // Clean up extracted files
                extractDir.deleteRecursively()
            }
        } catch (e: Exception) {
            extractDir.deleteRecursively()
            removeFromInstallQueue(packageName)
            postError(packageName, e.localizedMessage, e.stackTraceToString())
            null
        }
    }
    
    /**
     * Check if a file is a valid ZIP file by reading its magic bytes
     */
    private fun isZipFile(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(4)
                val bytesRead = input.read(buffer)
                // ZIP files start with PK\x03\x04 (0x504B0304)
                bytesRead >= 4 && 
                buffer[0] == 0x50.toByte() && 
                buffer[1] == 0x4B.toByte() &&
                buffer[2] == 0x03.toByte() &&
                buffer[3] == 0x04.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }
    
    private fun extractApksFromBundle(bundleFile: File, extractDir: File): List<File> {
        val apkFiles = mutableListOf<File>()
        
        // Try extraction with lenient error handling
        // Some ZIP files have invalid entries that cause exceptions
        try {
            // Use RandomAccessFile approach to read ZIP with more control
            ZipFile(bundleFile).use { zipFile ->
                val entries = try {
                    zipFile.entries()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get ZIP entries via ZipFile, trying stream approach: ${e.message}")
                    return extractViaStream(bundleFile, extractDir)
                }
                
                var validEntryCount = 0
                while (entries.hasMoreElements()) {
                    val entry = try {
                        entries.nextElement()
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping corrupted ZIP entry: ${e.message}")
                        continue
                    }
                    
                    try {
                        // Skip directories and invalid entries
                        if (entry.isDirectory) continue
                        
                        val entryName = entry.name ?: ""
                        if (entryName.isBlank() || entryName == "/" || entryName == "\\") {
                            Log.w(TAG, "Skipping invalid entry name: '$entryName'")
                            continue
                        }
                        
                        // XAPK files contain APKs at various paths (base.apk, split_*.apk, etc.)
                        if (!entryName.endsWith(".apk", ignoreCase = true)) {
                            Log.d(TAG, "Skipping non-APK: $entryName")
                            continue
                        }
                        
                        // Get safe file name
                        val fileName = entryName.substringAfterLast('/').substringAfterLast('\\')
                        if (fileName.isBlank() || fileName.contains("..")) {
                            Log.w(TAG, "Skipping unsafe filename: $entryName")
                            continue
                        }
                        
                        val apkFile = File(extractDir, fileName)
                        
                        // Extract the APK
                        zipFile.getInputStream(entry).use { input ->
                            apkFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        
                        if (apkFile.exists() && apkFile.length() > 0) {
                            apkFiles.add(apkFile)
                            validEntryCount++
                        }
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to extract ${entry?.name}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ZipFile approach failed: ${e.message}, trying stream approach")
            return extractViaStream(bundleFile, extractDir)
        }
        
        if (apkFiles.isEmpty()) {
            throw IOException("No valid APK files found in bundle")
        }
        
        return apkFiles
    }
    
    /**
     * Try extracting using command-line unzip (more lenient with corrupted files)
     */
    private fun extractWithUnzipCommand(bundleFile: File, extractDir: File): List<File> {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("unzip", "-o", bundleFile.absolutePath, "*.apk", "-d", extractDir.absolutePath)
            )
            
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val errorOutput = process.errorStream.bufferedReader().readText()
                throw IOException("unzip command failed with exit code $exitCode: $errorOutput")
            }
            
            val apkFiles = extractDir.listFiles { file -> file.extension == "apk" }?.toList() ?: emptyList()
            
            if (apkFiles.isEmpty()) {
                throw IOException("No APK files found after unzip")
            }
            
            return apkFiles
        } catch (e: Exception) {
            Log.e(TAG, "Command-line unzip failed", e)
            throw e
        }
    }
    
    private fun extractViaStream(bundleFile: File, extractDir: File): List<File> {
        val apkFiles = mutableListOf<File>()
        
        // Try with custom ZipInputStream that's more lenient
        bundleFile.inputStream().buffered().use { fileInput ->
            // Create a custom ZipInputStream without path validation
            val zipInputStream = object : java.util.zip.ZipInputStream(fileInput) {
                override fun getNextEntry(): ZipEntry? {
                    return try {
                        super.getNextEntry()
                    } catch (e: ZipException) {
                        if (e.message?.contains("Invalid zip entry path") == true) {
                            // Skip this entry and try the next one
                            Log.w(TAG, "Skipping corrupted entry, attempting next: ${e.message}")
                            null // Signal to skip
                        } else {
                            throw e
                        }
                    }
                }
            }
            
            var entryCount = 0
            var skippedBadEntries = 0
            
            while (true) {
                val entry = try {
                    var nextEntry: ZipEntry? = null
                    // Try up to 5 times to get a valid entry (in case there are multiple bad entries)
                    repeat(5) {
                        try {
                            nextEntry = zipInputStream.nextEntry
                            if (nextEntry != null) return@repeat
                        } catch (e: Exception) {
                            Log.w(TAG, "Attempt ${it + 1} failed: ${e.message}")
                            skippedBadEntries++
                        }
                    }
                    nextEntry
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read ZIP entries after $skippedBadEntries bad entries: ${e.message}")
                    break
                } ?: break
                
                entryCount++
                Log.d(TAG, "Processing entry #$entryCount: ${entry.name}")
                
                try {
                    if (entry.isDirectory) {
                        zipInputStream.closeEntry()
                        continue
                    }
                    
                    val entryName = entry.name ?: ""
                    
                    // XAPK files contain APKs at various paths
                    if (!entryName.endsWith(".apk", ignoreCase = true)) {
                        Log.d(TAG, "Skipping non-APK: $entryName")
                        zipInputStream.closeEntry()
                        continue
                    }
                    
                    val fileName = entryName.substringAfterLast('/').substringAfterLast('\\')
                    if (fileName.isBlank() || fileName.contains("..")) {
                        Log.w(TAG, "Unsafe filename: $entryName")
                        zipInputStream.closeEntry()
                        continue
                    }
                    
                    val apkFile = File(extractDir, fileName)
                    apkFile.outputStream().use { output ->
                        zipInputStream.copyTo(output)
                    }
                    
                    if (apkFile.exists() && apkFile.length() > 0) {
                        apkFiles.add(apkFile)
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract entry ${entry?.name}: ${e.message}")
                }
                
                try {
                    zipInputStream.closeEntry()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to close entry: ${e.message}")
                }
            }
        }
        
        if (apkFiles.isEmpty()) {
            throw IOException("No valid APK files found in bundle (processed entries but none were APKs)")
        }
        
        return apkFiles
    }
    
    private fun createSessionParams(packageName: String): PackageInstaller.SessionParams {
        return PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(packageName)
            setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)

            setOriginatingUid(Process.myUid())
            setInstallReason(PackageManager.INSTALL_REASON_USER)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setInstallerPackageName(context.packageName)
                setRequestUpdateOwnership(true)
            }
        }
    }
    
    /**
     * Commit the installation session
     */
    private fun commitInstall(sessionInfo: SessionInfo) {
        try {
            val session = packageInstaller.openSession(sessionInfo.sessionId)
            session.commit(getCallBackIntent(sessionInfo)!!.intentSender)
            session.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error committing session: ${e.message}", e)
            postError(sessionInfo.packageName, e.localizedMessage, e.stackTraceToString())
        }
    }

    /**
     * Get callback intent for session
     */
    private fun getCallBackIntent(sessionInfo: SessionInfo): PendingIntent? {
        val callBackIntent = Intent(context, InstallStatusReceiver::class.java).apply {
            action = InstallStatusReceiver.ACTION_INSTALL_STATUS
            setPackage(context.packageName)
            putExtra(InstallStatusReceiver.EXTRA_PACKAGE_NAME, sessionInfo.packageName)
            putExtra(InstallStatusReceiver.EXTRA_SESSION_ID, sessionInfo.sessionId)
            putExtra(InstallStatusReceiver.EXTRA_VERSION_CODE, sessionInfo.versionCode)
        }

        return PendingIntentCompat.getBroadcast(
            context,
            sessionInfo.sessionId,
            callBackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT,
            true
        )
    }
}
