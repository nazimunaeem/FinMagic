package com.finmagic.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateCheckerTest {

    @Test
    fun semverComparison_detectsNewerPatchAndMinor() {
        assertTrue(GitHubUpdateChecker.isRemoteVersionNewer("v1.0.1", "1.0.0", 1))
        assertTrue(GitHubUpdateChecker.isRemoteVersionNewer("1.2.0", "1.1.9", 2))
        assertFalse(GitHubUpdateChecker.isRemoteVersionNewer("v1.0.0", "1.0.0", 1))
        assertFalse(GitHubUpdateChecker.isRemoteVersionNewer("0.9.5", "1.0.0", 1))
    }

    @Test
    fun debugTagComparison_usesVersionCode() {
        assertTrue(GitHubUpdateChecker.isRemoteVersionNewer("debug-12", "1.0.0", 5))
        assertFalse(GitHubUpdateChecker.isRemoteVersionNewer("debug-5", "1.0.0", 5))
        assertFalse(GitHubUpdateChecker.isRemoteVersionNewer("debug-3", "1.0.0", 5))
    }

    @Test
    fun findNewerRelease_picksFirstNonDraftWithApkAsset() {
        val json = """
            [
              {
                "tag_name": "v2.0.0",
                "name": "Draft Release",
                "draft": true,
                "prerelease": false,
                "html_url": "https://github.com/nazimunaeem/FinMagic/releases/tag/v2.0.0",
                "assets": []
              },
              {
                "tag_name": "v1.1.0",
                "name": "FinMagic v1.1.0",
                "body": "Added web portal & account types",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/nazimunaeem/FinMagic/releases/tag/v1.1.0",
                "assets": [
                  {
                    "name": "FinMagic-v1.1.0.apk",
                    "browser_download_url": "https://github.com/nazimunaeem/FinMagic/releases/download/v1.1.0/FinMagic-v1.1.0.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val release = GitHubUpdateChecker.findNewerRelease(json, "1.0.0", 1)
        assertNotNull(release)
        assertEquals("v1.1.0", release!!.tagName)
        assertEquals(
            "https://github.com/nazimunaeem/FinMagic/releases/download/v1.1.0/FinMagic-v1.1.0.apk",
            release.apkDownloadUrl
        )
    }

    @Test
    fun findNewerRelease_returnsNullWhenUpToDate() {
        val json = """
            [
              {
                "tag_name": "v1.0.0",
                "name": "FinMagic v1.0.0",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/nazimunaeem/FinMagic/releases/tag/v1.0.0",
                "assets": []
              }
            ]
        """.trimIndent()

        assertNull(GitHubUpdateChecker.findNewerRelease(json, "1.0.0", 1))
    }
}
