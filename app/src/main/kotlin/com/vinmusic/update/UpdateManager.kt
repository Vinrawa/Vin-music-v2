package com.vinmusic.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class UpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val apkUrl: String,
    val forceUpdate: Boolean,
    val releaseNotes: String
)

object UpdateManager {
    private const val TAG = "VIN_UPDATE"
    private const val UPDATE_URL = "https://raw.githubusercontent.com/Vinrawa/vin-music-v2/main/latest_version.json"
    
    private val client = OkHttpClient()
    private val gson = Gson()
    
    private var downloadId: Long = -1L

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
                return@withContext gson.fromJson(bodyStr, UpdateInfo::class.java)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates: ${e.message}")
            null
        }
    }

    fun downloadAndInstall(context: Context, updateInfo: UpdateInfo) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                // GitHub release links redirect to AWS/Azure. DownloadManager often fails with these redirects.
                // We resolve the final direct download URL using OkHttp's HEAD request.
                val headRequest = Request.Builder().url(updateInfo.apkUrl).head().build()
                val response = client.newCall(headRequest).execute()
                val finalUrl = response.request.url.toString()
                response.close()

                withContext(Dispatchers.Main) {
                    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val uri = Uri.parse(finalUrl)
                    
                    var fileName = "VinMusic_v${updateInfo.latestVersionName}.apk"
                    val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                    if (file.exists()) {
                        fileName = "VinMusic_v${updateInfo.latestVersionName}_${System.currentTimeMillis()}.apk"
                    }

                    val request = DownloadManager.Request(uri).apply {
                        setTitle("Vin Music Update")
                        setDescription("Downloading version ${updateInfo.latestVersionName}...")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName
                        )
                        setMimeType("application/vnd.android.package-archive")
                    }

                    downloadId = downloadManager.enqueue(request)
                    Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show()

                    // Register receiver to listen for completion
                    val onComplete = object : BroadcastReceiver() {
                        override fun onReceive(ctxt: Context, intent: Intent) {
                            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                            if (id == downloadId) {
                                try {
                                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                        val downloadedUri = downloadManager.getUriForDownloadedFile(downloadId)
                                        setDataAndType(downloadedUri, "application/vnd.android.package-archive")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    }
                                    ctxt.startActivity(installIntent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Install failed: ${e.message}")
                                    Toast.makeText(ctxt, "Failed to start install. Check your Downloads folder.", Toast.LENGTH_LONG).show()
                                }
                                try {
                                    ctxt.unregisterReceiver(this)
                                } catch (e: Exception) {
                                    // Ignore
                                }
                            }
                        }
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
                    } else {
                        context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Failed to start download: ${e.message}")
                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
