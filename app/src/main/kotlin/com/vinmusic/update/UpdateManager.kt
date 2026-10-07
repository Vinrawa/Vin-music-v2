package com.vinmusic.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class UpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String?,
    val apkUrl: String?,
    val forceUpdate: Boolean = false,
    val releaseNotes: String? = null,
    /** SHA-256 hex digest of the APK. Verified before install when present — publish it in latest_version.json with every release. */
    val sha256: String? = null
)

object UpdateManager {
    private const val TAG = "VIN_UPDATE"
    private const val UPDATE_URL = "https://raw.githubusercontent.com/Vinrawa/vin-music-v2/main/latest_version.json"

    // Only these hosts may serve update APKs — the manifest JSON is plain text in
    // a public repo, so a compromised/tampered manifest must not be able to point
    // the installer at an arbitrary server. Add hosts here if the download moves.
    private val ALLOWED_HOSTS = setOf(
        "vinrawa.github.io",
        "github.com",
        "www.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    private val client = OkHttpClient()
    private val gson = Gson()

    private var downloadId: Long = -1L
    private var pendingFile: File? = null

    /** SHA-256 from the manifest for the APK currently being downloaded, if published. */
    @Volatile
    private var expectedSha256: String? = null

    suspend fun checkUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(UPDATE_URL)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Update check failed with code ${response.code}")
                    return@withContext null
                }

                val bodyStr = response.body?.string() ?: return@withContext null
                try {
                    // Gson leaves missing fields null even for non-null Kotlin types,
                    // so validate the fields we actually rely on.
                    gson.fromJson(bodyStr, UpdateInfo::class.java)
                        ?.takeIf { !it.apkUrl.isNullOrBlank() }
                } catch (e: Exception) {
                    Log.e(TAG, "Malformed update manifest: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates: ${e.message}")
            null
        }
    }

    fun downloadAndInstall(context: Context, updateInfo: UpdateInfo) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!isAllowedUpdateUrl(updateInfo.apkUrl)) {
                    Log.e(TAG, "Refusing to download from non-allowlisted URL")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Update blocked: untrusted download source.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // GitHub release links redirect to AWS/Azure. DownloadManager often fails with these redirects.
                // We resolve the final direct download URL using OkHttp's HEAD request — and re-check the host,
                // since the redirect target is what we actually download from.
                val headRequest = Request.Builder().url(updateInfo.apkUrl!!).head().build()
                val finalUrl = client.newCall(headRequest).execute().use { response ->
                    val url = response.request.url.toString()
                    if (!isAllowedUpdateUrl(url)) {
                        Log.e(TAG, "Redirected to non-allowlisted host: $url")
                        return@use null
                    }
                    url
                } ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Update blocked: untrusted download source.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Download into app-private external storage instead of public /Downloads.
                // Nothing else can tamper with the file between download and install (TOCTOU).
                val updatesDir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
                updatesDir.listFiles()?.forEach { it.delete() } // drop stale/partial APKs
                val destFile = File(updatesDir, "VinMusic_v${updateInfo.latestVersionName ?: "update"}.apk")

                val request = DownloadManager.Request(Uri.parse(finalUrl)).apply {
                    setTitle("Vin Music Update")
                    setDescription("Downloading version ${updateInfo.latestVersionName ?: ""}...")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationUri(Uri.fromFile(destFile))
                    setMimeType("application/vnd.android.package-archive")
                }

                pendingFile = destFile
                expectedSha256 = updateInfo.sha256?.trim()?.takeIf { it.isNotEmpty() }
                withContext(Dispatchers.Main) {
                    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

                    val onComplete = object : BroadcastReceiver() {
                        override fun onReceive(ctxt: Context, intent: Intent) {
                            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                            if (id != downloadId) return
                            // Always unregister, whatever the outcome — a failed/cancelled
                            // download used to leak this receiver (and its Activity ref).
                            try { ctxt.unregisterReceiver(this) } catch (_: Exception) {}
                            handleDownloadComplete(ctxt, id)
                        }
                    }

                    ContextCompat.registerReceiver(
                        context,
                        onComplete,
                        IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )

                    downloadId = downloadManager.enqueue(request)
                    Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Failed to start download: ${e.message}")
                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleDownloadComplete(ctxt: Context, id: Long) {
        val downloadManager = ctxt.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val file = pendingFile

        val succeeded = queryStatus(downloadManager, id) == DownloadManager.STATUS_SUCCESSFUL
        if (!succeeded || file == null || !file.exists()) {
            file?.delete()
            Toast.makeText(ctxt, "Update download failed.", Toast.LENGTH_LONG).show()
            return
        }

        // Hashing a ~90 MB APK must not run on the main thread.
        CoroutineScope(Dispatchers.IO).launch {
            val expected = expectedSha256
            if (expected.isNullOrBlank()) {
                Log.e(TAG, "Refusing update without SHA-256 integrity hash")
                file.delete()
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctxt, "Update blocked: missing security signature.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val actual = sha256Hex(file)
            if (actual == null || !actual.equals(expected, ignoreCase = true)) {
                Log.e(TAG, "APK integrity check failed (expected $expected, got $actual)")
                file.delete()
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctxt, "Update failed integrity check. Not installing.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            Log.i(TAG, "APK integrity verified (${actual.take(12)}…)")

            withContext(Dispatchers.Main) {
                startInstall(ctxt, file)
            }
        }
    }

    private fun queryStatus(dm: DownloadManager, id: Long): Int? {
        var cursor: Cursor? = null
        return try {
            cursor = dm.query(DownloadManager.Query().setFilterById(id))
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (idx >= 0) cursor.getInt(idx) else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Status query failed: ${e.message}")
            null
        } finally {
            cursor?.close()
        }
    }

    private fun startInstall(ctxt: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(ctxt, "${ctxt.packageName}.fileprovider", file)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            ctxt.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed: ${e.message}")
            Toast.makeText(ctxt, "Failed to start install.", Toast.LENGTH_LONG).show()
        }
    }

    private fun isAllowedUpdateUrl(urlStr: String?): Boolean = runCatching {
        if (urlStr.isNullOrBlank()) return false
        val uri = Uri.parse(urlStr)
        val host = uri.host?.lowercase() ?: return false
        uri.scheme.equals("https", ignoreCase = true) && (
            host in ALLOWED_HOSTS ||
            host.endsWith(".github.com") ||
            host.endsWith(".githubusercontent.com") ||
            host.endsWith(".amazonaws.com")
        )
    }.getOrDefault(false)

    private fun sha256Hex(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        digest.digest().joinToString("") { b -> "%02x".format(b) }
    } catch (e: Exception) {
        Log.e(TAG, "Hashing failed: ${e.message}")
        null
    }
}
