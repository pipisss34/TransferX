package id.transferx.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

class ResumableDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val url = inputData.getString("url") ?: return@withContext Result.failure()
        val dir = File(applicationContext.getExternalFilesDir(null), "downloads").apply { mkdirs() }
        val name = URL(url).path.substringAfterLast('/').ifBlank { "download-${id}" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir, name)
        try {
            setForeground(createForeground("Mengunduh $name", 0))
            var attempt = 0
            while (attempt < 5) {
                val offset = if (target.exists()) target.length() else 0L
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000; readTimeout = 60000; instanceFollowRedirects = true
                    if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                }
                try {
                    val code = conn.responseCode
                    if (offset > 0 && code == 200) { target.delete(); attempt++; continue }
                    if (code != 206 && code != 200) throw IllegalStateException("HTTP $code")
                    val append = offset > 0 && code == 206
                    val total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: (conn.contentLengthLong.takeIf { it >= 0 }?.let { it + if (append) offset else 0L } ?: -1L)
                    conn.inputStream.use { input ->
                        RandomAccessFile(target, "rw").use { out ->
                            if (!append) out.setLength(0) else out.seek(offset)
                            val buf = ByteArray(256 * 1024); var count: Int
                            while (input.read(buf).also { count = it } != -1) {
                                out.write(buf, 0, count)
                                val current = out.filePointer
                                if (total > 0) setProgress(workDataOf("downloaded" to current, "total" to total, "percent" to ((current * 100) / total).toInt()))
                            }
                        }
                    }
                    return@withContext Result.success(workDataOf("file" to target.absolutePath))
                } finally { conn.disconnect() }
            }
            Result.retry()
        } catch (_: Exception) { Result.retry() }
    }
    private fun createForeground(text: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("transferx", "TransferX transfers", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = NotificationCompat.Builder(applicationContext, "transferx")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("TransferX").setContentText(text)
            .setProgress(100, progress, true).setOngoing(true).build()
        return ForegroundInfo(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
