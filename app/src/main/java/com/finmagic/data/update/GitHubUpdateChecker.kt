package com.finmagic.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class GitHubReleaseInfo(
    val tagName: String,
    val title: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val isPrerelease: Boolean,
    val apkFileName: String = "FinMagic-update.apk",
    val apkSizeBytes: Long = 0L
)

sealed class UpdateCheckState {
    data object Idle : UpdateCheckState()
    data object Checking : UpdateCheckState()
    data class UpdateAvailable(val release: GitHubReleaseInfo) : UpdateCheckState()
    data class UpToDate(val currentVersion: String) : UpdateCheckState()
    data class Error(val message: String) : UpdateCheckState()
}

sealed class UpdateInstallState {
    data object Idle : UpdateInstallState()
    data class Downloading(val progress: Int) : UpdateInstallState()
    data class NeedsInstallPermission(val settingsIntent: Intent) : UpdateInstallState()
    data class SignatureConflict(
        val release: GitHubReleaseInfo,
        val apkFile: File,
        val uri: Uri,
        val installedSha256: String,
        val downloadedSha256: String
    ) : UpdateInstallState()
    data class ReadyToInstall(val apkFile: File, val uri: Uri, val intent: Intent) : UpdateInstallState()
    data class Error(val message: String) : UpdateInstallState()
}

@Singleton
class GitHubUpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val state: StateFlow<UpdateCheckState> = _state.asStateFlow()

    private val _installState = MutableStateFlow<UpdateInstallState>(UpdateInstallState.Idle)
    val installState: StateFlow<UpdateInstallState> = _installState.asStateFlow()

    val installedVersionName: String by lazy {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    val installedVersionCode: Long by lazy {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (_: Exception) {
            1L
        }
    }

    suspend fun checkForUpdates(manual: Boolean = false): UpdateCheckState {
        _state.value = UpdateCheckState.Checking
        return try {
            val json = withContext(Dispatchers.IO) { fetchReleasesJson() }
            val available = findNewerRelease(json, installedVersionName, installedVersionCode)
            val result = if (available != null) {
                UpdateCheckState.UpdateAvailable(available)
            } else {
                UpdateCheckState.UpToDate(installedVersionName)
            }
            _state.value = result
            result
        } catch (e: Exception) {
            val err = UpdateCheckState.Error(e.message ?: "Unable to check GitHub releases")
            _state.value = if (manual) err else UpdateCheckState.Idle
            err
        }
    }

    fun dismissUpdate() {
        _state.value = UpdateCheckState.Idle
        _installState.value = UpdateInstallState.Idle
    }

    fun resetInstallState() {
        _installState.value = UpdateInstallState.Idle
    }

    suspend fun downloadAndPrepareInstall(release: GitHubReleaseInfo): UpdateInstallState {
        val downloadUrl = release.apkDownloadUrl?.takeIf { it.isNotBlank() }
        if (downloadUrl == null) {
            val err = UpdateInstallState.Error("No .apk asset attached to ${release.tagName}. Open release page instead.")
            _installState.value = err
            return err
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val perm = UpdateInstallState.NeedsInstallPermission(intent)
            _installState.value = perm
            return perm
        }

        _installState.value = UpdateInstallState.Downloading(0)
        return withContext(Dispatchers.IO) {
            try {
                val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
                updatesDir.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }
                val safeName = release.apkFileName.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                val apkFile = File(updatesDir, safeName.ifBlank { "FinMagic-update.apk" })

                val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "FinMagic-Android/$installedVersionName")
                }
                if (conn.responseCode !in 200..299) {
                    val err = UpdateInstallState.Error("APK download failed (HTTP ${conn.responseCode})")
                    _installState.value = err
                    return@withContext err
                }

                val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSizeBytes
                conn.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buf = ByteArray(16 * 1024)
                        var downloaded = 0L
                        var read: Int
                        var lastPct = -1
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val pct = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    _installState.value = UpdateInstallState.Downloading(pct)
                                }
                            }
                        }
                    }
                }
                _installState.value = UpdateInstallState.Downloading(100)

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)

                val installedCerts = getInstalledSignatures()
                val archiveCerts = getArchiveSignatures(apkFile.absolutePath)
                if (installedCerts.isNotEmpty() && archiveCerts.isNotEmpty() &&
                    installedCerts.intersect(archiveCerts).isEmpty()
                ) {
                    val conflict = UpdateInstallState.SignatureConflict(
                        release = release,
                        apkFile = apkFile,
                        uri = uri,
                        installedSha256 = installedCerts.first(),
                        downloadedSha256 = archiveCerts.first()
                    )
                    _installState.value = conflict
                    return@withContext conflict
                }

                val ready = UpdateInstallState.ReadyToInstall(
                    apkFile = apkFile,
                    uri = uri,
                    intent = buildInstallIntent(uri)
                )
                _installState.value = ready
                ready
            } catch (e: Exception) {
                val err = UpdateInstallState.Error(e.message ?: "APK download failed")
                _installState.value = err
                err
            }
        }
    }

    fun buildInstallIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun getInstalledSignatures(): Set<String> {
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, flags)
            extractCertHashes(pkgInfo)
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun getArchiveSignatures(apkPath: String): Set<String> {
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val pkgInfo = context.packageManager.getPackageArchiveInfo(apkPath, flags) ?: return emptySet()
            extractCertHashes(pkgInfo)
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun extractCertHashes(pkgInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        } ?: return emptySet()

        val md = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            md.digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
        }.toSet()
    }

    private fun fetchReleasesJson(): String {
        val conn = (URL(RELEASES_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "FinMagic-Android/$installedVersionName")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("GitHub returned HTTP $code")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val REPO_OWNER = "nazimunaeem"
        const val REPO_NAME = "FinMagic"
        const val RELEASES_API_URL =
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=10"

        fun findNewerRelease(
            releasesJson: String,
            currentVersionName: String,
            currentVersionCode: Long
        ): GitHubReleaseInfo? {
            val root = JsonParser.parseString(releasesJson)
            val candidateObjects = when {
                root.isJsonArray -> root.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
                root.isJsonObject -> listOf(root.asJsonObject)
                else -> return null
            }

            val nonDrafts = candidateObjects.filter { it.get("draft")?.asBoolean != true }
            if (nonDrafts.isEmpty()) return null

            // Prefer the newest Latest (non-prerelease) release; fall back to prereleases only if no Latest release exists.
            val latestStable = nonDrafts.firstOrNull { it.get("prerelease")?.asBoolean != true }
            val targetRelease = latestStable ?: nonDrafts.first()

            val tag = targetRelease.get("tag_name")?.asString?.trim().orEmpty()
            if (tag.isBlank()) return null
            val htmlUrl = targetRelease.get("html_url")?.asString?.trim().orEmpty()
            val title = targetRelease.get("name")?.asString?.trim().orEmpty().ifBlank { tag }
            val body = targetRelease.get("body")?.asString?.trim().orEmpty()
            val isPrerelease = targetRelease.get("prerelease")?.asBoolean == true

            var apkUrl: String? = null
            var apkFileName = "FinMagic-$tag.apk"
            var apkSizeBytes = 0L
            val assets = targetRelease.getAsJsonArray("assets")
            if (assets != null) {
                for (assetElem in assets) {
                    if (!assetElem.isJsonObject) continue
                    val asset = assetElem.asJsonObject
                    val name = asset.get("name")?.asString.orEmpty()
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.get("browser_download_url")?.asString
                        if (name.isNotBlank()) apkFileName = name
                        apkSizeBytes = asset.get("size")?.asLong ?: 0L
                        break
                    }
                }
            }

            if (!isRemoteVersionNewer(tag, currentVersionName, currentVersionCode)) {
                return null
            }

            return GitHubReleaseInfo(
                tagName = tag,
                title = title,
                releaseNotes = body,
                htmlUrl = htmlUrl,
                apkDownloadUrl = apkUrl,
                isPrerelease = isPrerelease,
                apkFileName = apkFileName,
                apkSizeBytes = apkSizeBytes
            )
        }

        fun isRemoteVersionNewer(
            remoteTag: String,
            currentVersionName: String,
            currentVersionCode: Long
        ): Boolean {
            val cleanRemote = normalizeTagOrVersion(remoteTag)
            if (cleanRemote.startsWith("debug-", ignoreCase = true)) {
                val remoteBuild = cleanRemote.removePrefix("debug-").toLongOrNull() ?: return false
                return remoteBuild > currentVersionCode
            }
            val cleanLocal = normalizeTagOrVersion(currentVersionName)
            val remoteParts = parseSemverParts(cleanRemote)
            val localParts = parseSemverParts(cleanLocal)
            if (remoteParts == null || localParts == null) {
                return cleanRemote != cleanLocal
            }
            val maxLen = maxOf(remoteParts.size, localParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r != l) return r > l
            }
            return false
        }

        private fun normalizeTagOrVersion(raw: String): String =
            raw.trim()
                .removePrefix("FinMagic_")
                .removePrefix("FinMagic-")
                .removePrefix("finmagic_")
                .removePrefix("finmagic-")
                .removePrefix("v")
                .removePrefix("V")

        private fun parseSemverParts(version: String): List<Int>? {
            val base = version.substringBefore('-').substringBefore('+')
            val parts = base.split('.').map { it.toIntOrNull() }
            if (parts.isEmpty() || parts.any { it == null }) return null
            return parts.filterNotNull()
        }
    }
}
