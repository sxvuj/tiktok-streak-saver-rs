package com.sxvuj.streaksaver.data

import com.sxvuj.streaksaver.rust.RustCore
import java.net.HttpURLConnection
import java.net.URL

/** Asks the GitHub releases API for the latest release and lets the Rust core compare versions. */
object UpdateChecker {
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
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    return Result.failure(IllegalStateException("GitHub answered ${conn.responseCode}"))
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
