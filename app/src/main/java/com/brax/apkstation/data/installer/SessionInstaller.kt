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
import androidx.core.net.toUri
import com.brax.apkstation.data.receiver.InstallStatusReceiver
import com.brax.apkstation.data.room.dao.StoreDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installer using Android's PackageInstaller API
 * Works on Android 5.0+ (API 21+)
 */
@Singleton
class SessionInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val storeDao: StoreDao
) : AppInstaller {
    
    private val TAG = "SessionInstaller"
    private val packageInstaller = context.packageManager.packageInstaller
    
    override suspend fun install(packageName: String, sessionId: Long) {
        withContext(Dispatchers.IO) {
            try {
                val download = storeDao.getDownload(packageName)
                    ?: throw IOException("Download not found for $packageName")
                
                val file = File(download.apkLocation)
                if (!file.exists()) {
                    throw IOException("APK file not found: ${download.apkLocation}")
                }
                
                Log.i(TAG, "Installing $packageName from ${file.absolutePath}, fileType: '${download.fileType}', downloadSessionId: $sessionId")
                
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
                            Log.i(TAG, "File type says APK but file is actually ZIP/XAPK - treating as bundle")
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
                
                if (isBundle) {
                    Log.i(TAG, "Installing as bundle (XAPK/ZIP)")
                    installBundle(file, packageName, sessionId)
                } else {
                    Log.i(TAG, "Installing as single APK")
                    installSingleApk(file, packageName, sessionId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Installation failed for $packageName", e)
                throw e
            }
        }
    }
    
    private suspend fun installSingleApk(apkFile: File, packageName: String, downloadSessionId: Long) {
        val sessionParams = createSessionParams(packageName)
        val installSessionId = packageInstaller.createSession(sessionParams)
        val session = packageInstaller.openSession(installSessionId)
        
        try {
            // Write APK to session
            apkFile.inputStream().use { input ->
                session.openWrite(packageName, 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            
            Log.i(TAG, "APK written to session $installSessionId")
            
            // Commit session
            commitSession(session, installSessionId, packageName, downloadSessionId)
        } catch (e: Exception) {
            session.abandon()
            throw IOException("Failed to install APK: ${e.message}", e)
        }
    }
    
    private suspend fun installBundle(bundleFile: File, packageName: String, downloadSessionId: Long) {
        // Extract the bundle
        val extractDir = File(bundleFile.parent, "extracted_$packageName")
        extractDir.mkdirs()
        
        try {
            // Extract all APK files from the bundle
            Log.i(TAG, "Extracting bundle: ${bundleFile.name}")
            
            // Try to extract, fallback to single APK if it fails
            val apkFiles = try {
                extractApksFromBundle(bundleFile, extractDir)
            } catch (e: IOException) {
                // If Java extraction failed, try using unzip command (more lenient)
                Log.w(TAG, "Java ZIP extraction failed: ${e.message}")
                Log.i(TAG, "Trying command-line unzip as fallback")
                
                try {
                    extractWithUnzipCommand(bundleFile, extractDir)
                } catch (e2: Exception) {
                    Log.w(TAG, "Command-line unzip also failed: ${e2.message}")
                    Log.i(TAG, "Attempting to install as single APK instead")
                    extractDir.deleteRecursively()
                    installSingleApk(bundleFile, packageName, downloadSessionId)
                    return
                }
            }
            
            if (apkFiles.isEmpty()) {
                throw IOException("No APK files found in bundle")
            }
            
            Log.i(TAG, "Found ${apkFiles.size} APK files in bundle")
            
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
                
                Log.i(TAG, "All APKs written to session $sessionId")
                
                // Commit session
                commitSession(session, sessionId, packageName, downloadSessionId)
            } catch (e: Exception) {
                session.abandon()
                throw IOException("Failed to install bundle: ${e.message}", e)
            } finally {
                // Clean up extracted files
                extractDir.deleteRecursively()
            }
        } catch (e: Exception) {
            extractDir.deleteRecursively()
            throw e
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
        } catch (e: Exception) {
            false
        }
    }
    
    private fun extractApksFromBundle(bundleFile: File, extractDir: File): List<File> {
        val apkFiles = mutableListOf<File>()
        
        // Try extraction with lenient error handling
        // Some ZIP files have invalid entries that cause exceptions
        try {
            // Use RandomAccessFile approach to read ZIP with more control
            java.util.zip.ZipFile(bundleFile).use { zipFile ->
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
                        
                        Log.i(TAG, "Found APK in bundle: $entryName")
                        
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
                            Log.i(TAG, "Extracted APK #$validEntryCount: ${apkFile.name} (${apkFile.length()} bytes)")
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
        
        Log.i(TAG, "Successfully extracted ${apkFiles.size} APK(s) from bundle")
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
            
            Log.i(TAG, "Command-line unzip extracted ${apkFiles.size} APK(s)")
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
                    } catch (e: java.util.zip.ZipException) {
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
                    
                    Log.i(TAG, "Found APK in stream: $entryName")
                    
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
                        Log.i(TAG, "Stream extracted: ${apkFile.name} (${apkFile.length()} bytes)")
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
            
            Log.i(TAG, "Stream extraction complete: processed $entryCount valid entries, skipped $skippedBadEntries bad entries")
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
    
    private fun commitSession(
        session: PackageInstaller.Session,
        sessionId: Int,
        packageName: String,
        downloadSessionId: Long
    ) {
        val intent = Intent(context, InstallStatusReceiver::class.java).apply {
            action = InstallStatusReceiver.ACTION_INSTALL_STATUS
            setPackage(context.packageName)
            putExtra(InstallStatusReceiver.EXTRA_PACKAGE_NAME, packageName)
            putExtra(InstallStatusReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(InstallStatusReceiver.EXTRA_DOWNLOAD_SESSION_ID, downloadSessionId)
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            flags
        )
        
        // Commit the session
        // Note: On Android 14+ (API 34+), if app is in background, the install prompt
        // may not show immediately. Android will show a notification that user can tap
        // to proceed with the installation. This is a platform restriction we cannot bypass.
        session.commit(pendingIntent.intentSender)
        session.close()
        
        Log.i(TAG, "Session $sessionId committed for $packageName")
    }
    
    override fun uninstall(packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = "package:$packageName".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    override fun isSupported(): Boolean {
        return true
    }
}
