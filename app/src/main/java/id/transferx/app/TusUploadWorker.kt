package id.transferx.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.io.FileInputStream
import java.net.URL

/** Minimal TUS 1.0 client. Endpoint must be the server's TUS creation endpoint. */
class TusUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val endpoint = inputData.getString("endpoint") ?: return@withContext Result.failure()
        val auth = inputData.getString("auth").orEmpty()
        val uri = inputData.getString("uri")?.let(Uri::parse) ?: return@withContext Result.failure()
        val resolver = applicationContext.contentResolver
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size <= 0) return@withContext Result.failure(workDataOf("error" to "Ukuran file tidak diketahui"))
        try {
            setForeground(foreground("Menyiapkan upload TUS"))
            val prefs = applicationContext.getSharedPreferences("tus_sessions", Context.MODE_PRIVATE)
            val key = "${uri}_${size}_${endpoint.hashCode()}"
            var uploadUrl = prefs.getString(key, null)
            if (uploadUrl == null) {
                val create = URL(endpoint).openConnection() as HttpURLConnection
                create.requestMethod = "POST"; create.doOutput = true; create.connectTimeout = 30000; create.readTimeout = 30000
                create.setRequestProperty("Tus-Resumable", "1.0.0"); create.setRequestProperty("Upload-Length", size.toString())
                if (auth.isNotBlank()) create.setRequestProperty("Authorization", auth)
                create.connect()
                if (create.responseCode !in 200..299) throw IllegalStateException("TUS create HTTP ${create.responseCode}")
                val location = create.getHeaderField("Location") ?: throw IllegalStateException("Server tidak mengembalikan Location")
                uploadUrl = URL(URL(endpoint), location).toString()
                prefs.edit().putString(key, uploadUrl).apply(); create.disconnect()
            }
            var offset = queryOffset(uploadUrl, auth)
            val chunkSize = 8 * 1024 * 1024
            while (offset < size) {
                val amount = minOf(chunkSize.toLong(), size - offset).toInt()
                val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("File tidak bisa dibaca")
                val bytes = pfd.use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).use { input ->
                        val channel = input.channel
                        channel.position(offset)
                        val buffer = java.nio.ByteBuffer.allocate(amount)
                        while (buffer.hasRemaining() && channel.read(buffer) != -1) { }
                        buffer.flip()
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    }
                }
                if (bytes.isEmpty()) throw IllegalStateException("Data file habis sebelum ukuran yang diharapkan")
                val patch = URL(uploadUrl).openConnection() as HttpURLConnection
                patch.requestMethod = "PATCH"; patch.doOutput = true; patch.connectTimeout = 30000; patch.readTimeout = 120000
                patch.setRequestProperty("Tus-Resumable", "1.0.0"); patch.setRequestProperty("Upload-Offset", offset.toString())
                patch.setRequestProperty("Content-Type", "application/offset+octet-stream")
                if (auth.isNotBlank()) patch.setRequestProperty("Authorization", auth)
                patch.setFixedLengthStreamingMode(bytes.size); patch.outputStream.use { it.write(bytes) }
                if (patch.responseCode != 204) throw IllegalStateException("TUS PATCH HTTP ${patch.responseCode}")
                offset = patch.getHeaderField("Upload-Offset")?.toLongOrNull() ?: (offset + bytes.size)
                patch.disconnect()
                setProgress(workDataOf("uploaded" to offset, "total" to size, "percent" to (offset * 100 / size).toInt()))
            }
            prefs.edit().remove(key).apply()
            Result.success(workDataOf("uploadUrl" to uploadUrl))
        } catch (_: Exception) { Result.retry() }
    }
    private fun queryOffset(url: String, auth: String): Long {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"; c.setRequestProperty("Tus-Resumable", "1.0.0")
        if (auth.isNotBlank()) c.setRequestProperty("Authorization", auth)
        val code = c.responseCode
        if (code !in 200..299) throw IllegalStateException("TUS HEAD HTTP $code")
        val offset = c.getHeaderField("Upload-Offset")?.toLongOrNull() ?: 0L
        c.disconnect(); return offset
    }
    private fun foreground(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("transferx", "TransferX transfers", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(applicationContext, "transferx").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("TransferX").setContentText(text).setOngoing(true).build()
        return ForegroundInfo(1002, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
