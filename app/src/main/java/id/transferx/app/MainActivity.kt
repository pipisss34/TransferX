package id.transferx.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.*
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var address: EditText
    private var pendingUploadUri: Uri? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val pickTusFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            pendingUploadUri = uri
            status.text = "File dipilih: ${uri.lastPathSegment ?: uri}"
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uris = if (result.resultCode == Activity.RESULT_OK && data != null)
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, data) else null
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 24, 32)
        window.navigationBarColor = Color.rgb(16, 24, 32)
        setContentView(createLayout())
        loadAddress(address.text.toString())
    }

    private fun createLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // Compact 1DM-style header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(12), dp(9))
            setBackgroundColor(Color.rgb(25, 34, 46))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(42), dp(42)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, 0, 0) }
        brand.addView(TextView(this).apply {
            text = "TRANSFERX"; textSize = 18f; setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        brand.addView(TextView(this).apply {
            text = "Browser + Transfer Manager"; textSize = 11f
            setTextColor(Color.rgb(185, 198, 211))
        })
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val menu = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_more)
            setColorFilter(Color.WHITE)
            background = null
            contentDescription = "Menu"
            setOnClickListener { showMainMenu(it) }
        }
        header.addView(menu, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header)

        // Address bar with a separate Go button
        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(8), dp(5))
            setBackgroundColor(Color.rgb(239, 242, 246))
        }
        address = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            hint = "Cari atau masukkan alamat website"
            setText("https://vexfile.com/fileshare#")
            setPadding(dp(10), 0, dp(8), 0)
            background = null
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                    loadAddress(text.toString()); true
                } else false
            }
        }
        addressRow.addView(address, LinearLayout.LayoutParams(0, dp(46), 1f))
        val go = TextView(this).apply {
            text = "BUKA"; textSize = 12f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(0, 135, 170)); gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(8), 0)
            setOnClickListener { loadAddress(address.text.toString()) }
        }
        addressRow.addView(go)
        root.addView(addressRow)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.allowFileAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!address.hasFocus() && url != null) address.setText(url)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                    val intent = params?.createIntent() ?: return false
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try { filePicker.launch(intent); true }
                    catch (_: Exception) { fileCallback = null; false }
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this).apply {
            text = "Siap"
            textSize = 11f
            setTextColor(Color.rgb(90, 100, 112))
            setPadding(dp(12), dp(5), dp(12), dp(5))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setBackgroundColor(Color.rgb(248, 249, 251))
        }
        root.addView(status)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(25, 34, 46))
        }
        bottom.addView(navButton("Browser", android.R.drawable.ic_menu_view) { web.visibility = View.VISIBLE; status.text = "Browser aktif" })
        bottom.addView(navButton("Download", android.R.drawable.stat_sys_download) { showDownloadDialog() })
        bottom.addView(navButton("Upload", android.R.drawable.stat_sys_upload) { showUploadDialog() })
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        return root
    }

    private fun navButton(label: String, icon: Int, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(4))
            val image = ImageView(this@MainActivity).apply {
                setImageResource(icon); setColorFilter(Color.WHITE)
            }
            addView(image, LinearLayout.LayoutParams(dp(21), dp(21)))
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 11f; setTextColor(Color.WHITE)
            })
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
    }

    private fun showMainMenu(anchor: View) {
        val items = arrayOf("Download dengan resume", "Upload resumable (TUS)", "Muat ulang halaman", "Kembali", "Maju")
        AlertDialog.Builder(this).setTitle("TransferX")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDownloadDialog()
                    1 -> showUploadDialog()
                    2 -> web.reload()
                    3 -> if (web.canGoBack()) web.goBack()
                    4 -> if (web.canGoForward()) web.goForward()
                }
            }.show()
    }

    private fun showDownloadDialog() {
        val field = EditText(this).apply {
            hint = "https://server.com/file.zip"
            setSingleLine(true)
            textSize = 14f
        }
        val box = dialogBox().apply { addView(field) }
        AlertDialog.Builder(this).setTitle("Download dengan resume")
            .setMessage("Masukkan URL file HTTP/HTTPS. Resume memerlukan dukungan HTTP Range dari server.")
            .setView(box)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Mulai download") { _, _ -> enqueueDownload(field.text.toString()) }
            .show()
    }

    private fun showUploadDialog() {
        val endpoint = EditText(this).apply { hint = "URL endpoint TUS (HTTPS)"; setSingleLine(true); textSize = 14f }
        val auth = EditText(this).apply { hint = "Authorization (opsional: Bearer ...)"; setSingleLine(true); textSize = 14f }
        val chosen = TextView(this).apply {
            text = pendingUploadUri?.lastPathSegment ?: "Belum ada file dipilih"
            textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, dp(8), 0, dp(8))
        }
        val choose = Button(this).apply {
            text = "Pilih file"
            setOnClickListener { pickTusFile.launch(arrayOf("*/*")) }
        }
        val box = dialogBox().apply { addView(endpoint); addView(auth); addView(choose); addView(chosen) }
        AlertDialog.Builder(this).setTitle("Upload resumable (TUS)")
            .setMessage("Hanya untuk server yang mendukung protokol TUS.")
            .setView(box)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Mulai upload", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (pendingUploadUri == null) {
                            Toast.makeText(this, "Pilih file terlebih dahulu", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        enqueueTus(endpoint.text.toString(), auth.text.toString())
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun dialogBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(8), dp(22), dp(4))
    }

    private fun loadAddress(raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) return
        val url = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
        address.setText(url)
        web.loadUrl(url)
        status.text = "Membuka website..."
    }

    private fun enqueueDownload(raw: String) {
        val url = raw.trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            status.text = "Masukkan URL HTTP/HTTPS yang valid."; return
        }
        val request = OneTimeWorkRequestBuilder<ResumableDownloadWorker>()
            .setInputData(workDataOf("url" to url))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("download-${url.hashCode()}", ExistingWorkPolicy.KEEP, request)
        status.text = "Download diantrikan. Progres tersedia di notifikasi Android."
    }

    private fun enqueueTus(endpoint: String, auth: String) {
        val uri = pendingUploadUri
        if (uri == null) { status.text = "Pilih file terlebih dahulu."; return }
        if (!endpoint.startsWith("https://")) { status.text = "Endpoint TUS harus URL HTTPS."; return }
        val request = OneTimeWorkRequestBuilder<TusUploadWorker>()
            .setInputData(workDataOf("endpoint" to endpoint, "auth" to auth, "uri" to uri.toString()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("tus-${uri.hashCode()}", ExistingWorkPolicy.KEEP, request)
        status.text = "Upload TUS diantrikan. Server wajib mendukung protokol TUS."
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
