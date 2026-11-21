package id.transferx.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.work.*
import androidx.work.workDataOf
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var pendingUploadUri: Uri? = null
    private val pickTusFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            pendingUploadUri = uri
            status.text = "File dipilih: ${uri.lastPathSegment ?: uri}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 12, 14, 12) }
        val title = TextView(this).apply { text = "TRANSFERX  •  Browser + Transfer Manager"; textSize = 18f }
        val address = EditText(this).apply { hint = "https://alamat-website.com"; singleLine = true; setText("https://vexfile.com/fileshare#") }
        val open = Button(this).apply { text = "Buka website"; setOnClickListener { loadAddress(address.text.toString()) } }
        val downloadUrl = EditText(this).apply { hint = "URL file untuk download (HTTP/HTTPS)"; singleLine = true }
        val download = Button(this).apply { text = "Download dengan resume"; setOnClickListener { enqueueDownload(downloadUrl.text.toString()) } }
        val tusUrl = EditText(this).apply { hint = "URL endpoint TUS (server harus mendukung TUS)"; singleLine = true }
        val tusAuth = EditText(this).apply { hint = "Authorization header (opsional, mis. Bearer …)"; singleLine = true }
        val choose = Button(this).apply { text = "Pilih file untuk upload TUS"; setOnClickListener { pickTusFile.launch(arrayOf("*/*")) } }
        val upload = Button(this).apply { text = "Upload resumable (TUS)"; setOnClickListener { enqueueTus(tusUrl.text.toString(), tusAuth.text.toString()) } }
        status = TextView(this).apply { text = "Siap. Download resume via HTTP Range; upload resumable via TUS."; textSize = 13f }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.allowFileAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                    val intent = params?.createIntent() ?: return false
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    try { filePicker.launch(intent) } catch (_: Exception) { fileCallback = null; return false }
                    return true
                }
            }
        }
        root.addView(title)
        root.addView(address, lp())
        root.addView(open, lp())
        root.addView(downloadUrl, lp())
        root.addView(download, lp())
        root.addView(tusUrl, lp())
        root.addView(tusAuth, lp())
        root.addView(choose, lp())
        root.addView(upload, lp())
        root.addView(status, lp())
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        loadAddress(address.text.toString())
    }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uris = if (result.resultCode == Activity.RESULT_OK && data != null) WebChromeClient.FileChooserParams.parseResult(result.resultCode, data) else null
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun loadAddress(raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) return
        val url = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
        web.loadUrl(url)
    }
    private fun enqueueDownload(raw: String) {
        val url = raw.trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) { status.text = "Masukkan URL HTTP/HTTPS yang valid."; return }
        val request = OneTimeWorkRequestBuilder<ResumableDownloadWorker>()
            .setInputData(workDataOf("url" to url))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("download-${url.hashCode()}", ExistingWorkPolicy.KEEP, request)
        status.text = "Download diantrikan. Status/progres tersedia di notifikasi Android. Resume perlu dukungan HTTP Range dari server."
    }
    private fun enqueueTus(endpoint: String, auth: String) {
        val uri = pendingUploadUri
        if (uri == null) { status.text = "Pilih file terlebih dahulu."; return }
        if (!endpoint.startsWith("https://")) { status.text = "Endpoint TUS harus URL HTTPS."; return }
        val request = OneTimeWorkRequestBuilder<TusUploadWorker>()
            .setInputData(workDataOf("endpoint" to endpoint, "auth" to auth, "uri" to uri.toString()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("tus-${uri.hashCode()}", ExistingWorkPolicy.KEEP, request)
        status.text = "Upload TUS diantrikan. Server wajib mendukung protokol TUS dan endpoint creation yang benar."
    }
    override fun onBackPressed() { if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed() }
    override fun onDestroy() { if (::web.isInitialized) web.destroy(); super.onDestroy() }
}
