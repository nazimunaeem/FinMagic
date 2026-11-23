package com.finmagic.data.update

import android.content.Context
import android.os.Build
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class GitHubReleaseInfo(
    val tagName: String,
    val title: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val isPrerelease: Boolean
)

sealed class UpdateCheckState {
    data object Idle : UpdateCheckState()
    data object Checking : UpdateCheckState()
    data class UpdateAvailable(val release: GitHubReleaseInfo) : UpdateCheckState()
    data class UpToDate(val currentVersion: String) : UpdateCheckState()
    data class Error(val message: String) : UpdateCheckState()
}

@Singleton
class GitHubUpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val state: StateFlow<UpdateCheckState> = _state.asStateFlow()

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
            val assets = targetRelease.getAsJsonArray("assets")
            if (assets != null) {
                for (assetElem in assets) {
                    if (!assetElem.isJsonObject) continue
                    val asset = assetElem.asJsonObject
                    val name = asset.get("name")?.asString.orEmpty()
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.get("browser_download_url")?.asString
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
                isPrerelease = isPrerelease
            )
        }

        fun isRemoteVersionNewer(
            remoteTag: String,
            currentVersionName: String,
            currentVersionCode: Long
        ): Boolean {
            val cleanRemote = remoteTag.trim().removePrefix("v").removePrefix("V")
            if (cleanRemote.startsWith("debug-", ignoreCase = true)) {
                val remoteBuild = cleanRemote.removePrefix("debug-").toLongOrNull() ?: return false
                return remoteBuild > currentVersionCode
            }
            val remoteParts = parseSemverParts(cleanRemote)
            val localParts = parseSemverParts(currentVersionName.trim().removePrefix("v").removePrefix("V"))
            if (remoteParts == null || localParts == null) {
                return cleanRemote != currentVersionName.trim().removePrefix("v").removePrefix("V")
            }
            val maxLen = maxOf(remoteParts.size, localParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r != l) return r > l
            }
            return false
        }

        private fun parseSemverParts(version: String): List<Int>? {
            val base = version.substringBefore('-').substringBefore('+')
            val parts = base.split('.').map { it.toIntOrNull() }
            if (parts.isEmpty() || parts.any { it == null }) return null
            return parts.filterNotNull()
        }
    }
}
