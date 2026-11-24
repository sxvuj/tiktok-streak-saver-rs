package com.sxvuj.streaksaver.data

import com.sxvuj.streaksaver.rust.RustCore
import java.net.HttpURLConnection
import java.net.URL

/** Asks the GitHub releases API for the latest release and lets the Rust core compare versions. */
object UpdateChecker {
    private const val RELEASES_PAGE = "https://github.com/sxvuj/tiktok-streak-saver-rs/releases"

    /** Blocking. A failure result means the network or GitHub could not be reached. */
    fun check(currentVersion: String): Result<UpdateInfo> {
        return try {
            val conn = (URL(RustCore.latestReleaseUrl()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "streak-saver-rs/$currentVersion")
            }
            try {
                when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> Unit
                    // GitHub answers 404 for /releases/latest while a repository has no published
                    // release. That is not a failure, there is simply nothing newer to offer.
                    HttpURLConnection.HTTP_NOT_FOUND -> return Result.success(
                        UpdateInfo(
                            currentVersion = currentVersion,
                            latestVersion = currentVersion,
                            available = false,
                            notes = "",
                            apkUrl = null,
                            pageUrl = RELEASES_PAGE,
                        ),
                    )
                    HttpURLConnection.HTTP_FORBIDDEN, 429 ->
                        return Result.failure(IllegalStateException("GitHub is limiting requests, try again later"))
                    else -> return Result.failure(IllegalStateException("GitHub answered $code"))
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val reply = Reply.parse(RustCore.checkReleaseUpdateJson(currentVersion, body))
                val info = reply.obj?.let { UpdateInfo.from(it) }
                if (info != null) Result.success(info) else Result.failure(IllegalStateException(reply.error))
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
