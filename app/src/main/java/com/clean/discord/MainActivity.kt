package com.clean.discord

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var windowRoot: FrameLayout
    private lateinit var rootContainer: FrameLayout
    private lateinit var statusBarBg: View
    private lateinit var navBarBg: View
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var splashView: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var mediaPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var storagePermissionLauncher: ActivityResultLauncher<String>
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingDownload: (() -> Unit)? = null
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private val pendingNotificationCallbacks = mutableListOf<Int>()
    private lateinit var notificationBridge: NotificationBridge

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isFirstLoad = true
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "DiscordApp"
        private const val START_URL = "https://discord.com/app"

        // Discord's web client blocks or nags mobile browsers, so present as desktop Chrome.
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        /** Hosts that stay inside the app. Everything else opens in the system browser. */
        fun isInternalHost(host: String): Boolean {
            val h = host.lowercase()
            val domains = listOf(
                "discord.com", "discordapp.com", "discordapp.net", "discord.gg",
                "discord.media", "discord.new", "discord.gift", "discordstatus.com", "dis.gd",
                // Login captcha
                "hcaptcha.com",
            )
            return domains.any { h == it || h.endsWith(".$it") }
        }

        /** Rewrites links that Discord's web client can't open directly into ones it can. */
        fun normalizeIncomingUrl(uri: Uri): String? {
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "https" && scheme != "http") return null
            val host = uri.host?.lowercase() ?: return null
            if (!isInternalHost(host)) return null
            if (host == "discord.gg") {
                val code = uri.path?.trim('/')?.takeIf { it.isNotEmpty() } ?: return START_URL
                return "https://discord.com/invite/$code"
            }
            return uri.buildUpon().scheme("https").build().toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.WEB_DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        NotificationBridge.ensureChannels(this)
        KeepAliveService.start(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        windowRoot = findViewById(R.id.windowRoot)
        rootContainer = findViewById(R.id.rootContainer)
        statusBarBg = findViewById(R.id.statusBarBg)
        navBarBg = findViewById(R.id.navBarBg)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        splashView = findViewById(R.id.splashView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        findViewById<Button>(R.id.btnRetry).setOnClickListener {
            errorView.visibility = View.GONE
            webView.reload()
        }

        setupWindowInsets()
        setupLaunchers()
        setupWebView()
        setupBackNavigation()

        intent?.let { pendingNotificationClick = notificationIdFrom(it) }
        val incoming = intent?.data?.let { normalizeIncomingUrl(it) }
        when {
            incoming != null -> webView.loadUrl(incoming)
            savedInstanceState != null -> webView.restoreState(savedInstanceState)
            else -> webView.loadUrl(START_URL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationIdFrom(intent)?.let { id ->
            webView.evaluateJavascript("window.__dnClick && window.__dnClick($id)", null)
            return
        }
        intent.data?.let { normalizeIncomingUrl(it) }?.let { webView.loadUrl(it) }
    }

    private var pendingNotificationClick: Int? = null

    private fun notificationIdFrom(intent: Intent): Int? =
        intent.getIntExtra(NotificationBridge.EXTRA_NOTIFICATION_ID, -1).takeIf { it >= 0 }

    private fun setupWindowInsets() {
        // Keep content clear of the status bar, nav bar and keyboard so the message box stays visible.
        ViewCompat.setOnApplyWindowInsetsListener(windowRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (customView == null) {
                rootContainer.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            } else {
                rootContainer.setPadding(0, 0, 0, 0)
            }
            statusBarBg.layoutParams = statusBarBg.layoutParams.apply { height = bars.top }
            navBarBg.layoutParams = navBarBg.layoutParams.apply { height = bars.bottom }
            insets
        }
        applyBarColors(ContextCompat.getColor(this, R.color.bg), ContextCompat.getColor(this, R.color.bg))
    }

    /** Paints the status/nav bar areas and picks light or dark bar icons to stay readable. */
    private fun applyBarColors(top: Int, bottom: Int) {
        statusBarBg.setBackgroundColor(top)
        navBarBg.setBackgroundColor(bottom)
        windowRoot.setBackgroundColor(bottom)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = ColorUtils.calculateLuminance(top) > 0.5
            isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(bottom) > 0.5
        }
    }

    private fun setupLaunchers() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            val uris: Array<Uri>? = if (result.resultCode == RESULT_OK && data != null) {
                val clip = data.clipData
                if (clip != null && clip.itemCount > 0) {
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            } else null
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

        mediaPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            pendingPermissionRequest?.let { grantAllowedResources(it) }
            pendingPermissionRequest = null
            // Re-start so the service picks up the microphone type now that it's allowed.
            KeepAliveService.start(this)
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val result = if (granted) "granted" else "denied"
            pendingNotificationCallbacks.forEach {
                webView.evaluateJavascript("window.__dnPerm && window.__dnPerm($it, '$result')", null)
            }
            pendingNotificationCallbacks.clear()
            if (granted) KeepAliveService.start(this)
        }

        storagePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) pendingDownload?.invoke()
            else Toast.makeText(this, R.string.storage_permission_required, Toast.LENGTH_SHORT).show()
            pendingDownload = null
        }
    }

    private fun requestNotificationPermission(callbackId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !NotificationBridge.canPost(this)) {
            pendingNotificationCallbacks += callbackId
            if (pendingNotificationCallbacks.size == 1) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            val result = if (NotificationBridge.canPost(this)) "granted" else "denied"
            webView.evaluateJavascript("window.__dnPerm && window.__dnPerm($callbackId, '$result')", null)
        }
    }

    private fun androidPermissionFor(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        else -> null
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Grants the web resources whose Android permission we hold; denies if none. */
    private fun grantAllowedResources(request: PermissionRequest) {
        val allowed = request.resources.filter { res ->
            val perm = androidPermissionFor(res)
            perm != null && hasPermission(perm)
        }
        if (allowed.isEmpty()) {
            Toast.makeText(this, R.string.media_permission_denied, Toast.LENGTH_SHORT).show()
            request.deny()
        } else {
            request.grant(allowed.toTypedArray())
        }
    }

    private fun handleWebPermissionRequest(request: PermissionRequest) {
        val host = request.origin?.host ?: ""
        if (!isInternalHost(host)) {
            request.deny()
            return
        }
        val missing = request.resources
            .mapNotNull { androidPermissionFor(it) }
            .filterNot { hasPermission(it) }
        if (missing.isEmpty()) {
            grantAllowedResources(request)
        } else {
            pendingPermissionRequest?.deny()
            pendingPermissionRequest = request
            mediaPermissionLauncher.launch(missing.distinct().toTypedArray())
        }
    }

    private fun configureWebSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.userAgentString = USER_AGENT
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        configureWebSettings(webView.settings)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Keep the page process as important as the app even in the background, so Android
            // doesn't kill it (and reload Discord) while another app like the file picker is open.
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun saveBlobData(base64Data: String, mimeType: String, fileName: String) {
                runOnUiThread { saveBase64ToDownloads(base64Data, mimeType, fileName) }
            }
        }, "AndroidDownloader")

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun setBarColors(top: String, bottom: String) {
                val t = runCatching { Color.parseColor(top) }.getOrNull() ?: return
                val b = runCatching { Color.parseColor(bottom) }.getOrNull() ?: return
                runOnUiThread { applyBarColors(t, b) }
            }
        }, "AndroidTheme")

        webView.addJavascriptInterface(object {
            /** Sends a real Enter key to the focused message box (used by the send button). */
            @JavascriptInterface
            fun pressEnter() {
                runOnUiThread {
                    webView.requestFocus()
                    val now = SystemClock.uptimeMillis()
                    webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
                    webView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
                }
            }
        }, "AndroidInput")

        if (!::notificationBridge.isInitialized) {
            notificationBridge = NotificationBridge(this) { requestNotificationPermission(it) }
        }
        webView.addJavascriptInterface(notificationBridge, "AndroidNotifier")
        installPageScripts()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                errorView.visibility = View.GONE
                if (!hasDocumentStartScripts) pageScripts.forEach { view?.evaluateJavascript(it, null) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                if (!hasDocumentStartScripts) pageScripts.forEach { view?.evaluateJavascript(it, null) }
                pendingNotificationClick?.let { id ->
                    pendingNotificationClick = null
                    view?.evaluateJavascript("window.__dnClick && window.__dnClick($id)", null)
                }
                CookieManager.getInstance().flush()
                if (isFirstLoad) {
                    isFirstLoad = false
                    splashView.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .withEndAction { rootContainer.removeView(splashView) }
                        .start()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                Log.w(TAG, "WebError ${error?.errorCode}: ${error?.description} for ${request?.url}")
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    errorView.visibility = View.VISIBLE
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return routeUrl(uri, request.isForMainFrame, view)
            }

            // If Android kills the page process anyway, swap in a fresh WebView instead of
            // letting the whole app crash and restart.
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Log.w(TAG, "Render process gone; recreating WebView")
                if (view === webView) replaceWebView()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress >= 100) progressBar.visibility = View.GONE
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { handleWebPermissionRequest(request) }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                if (pendingPermissionRequest == request) pendingPermissionRequest = null
            }

            // Discord opens most outbound links with window.open / target=_blank.
            // Catch the popup's first URL, then route it like a normal link.
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val catcher = WebView(this@MainActivity)
                catcher.webViewClient = object : WebViewClient() {
                    private var handled = false
                    private fun handle(uri: Uri) {
                        if (handled) return
                        handled = true
                        if (!routeUrl(uri, true, webView)) webView.loadUrl(uri.toString())
                        catcher.post { catcher.destroy() }
                    }

                    override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                        req?.url?.let { handle(it) }
                        return true
                    }

                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                        if (url != null && url != "about:blank") handle(Uri.parse(url))
                    }
                }
                transport.webView = catcher
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val chooser = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    val types = params?.acceptTypes?.filter { it.isNotBlank() }.orEmpty()
                    if (types.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                }
                return try {
                    fileChooserLauncher.launch(Intent.createChooser(chooser, null))
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                ViewCompat.requestApplyInsets(rootContainer)
            }

            override fun onHideCustomView() {
                fullscreenContainer.removeAllViews()
                fullscreenContainer.visibility = View.GONE
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                ViewCompat.requestApplyInsets(rootContainer)
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (url.startsWith("blob:")) {
                downloadBlob(url, mimetype, URLUtil.guessFileName(url, contentDisposition, mimetype))
            } else {
                startFileDownload(url, userAgent, contentDisposition, mimetype)
            }
        }
    }

    private fun replaceWebView() {
        val old = webView
        val url = old.url ?: START_URL
        val parent = old.parent as ViewGroup
        val index = parent.indexOfChild(old)
        val params = old.layoutParams
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        parent.removeView(old)
        old.destroy()

        webView = WebView(this).apply {
            id = R.id.webView
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.bg))
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        parent.addView(webView, index, params)
        hasDocumentStartScripts = false
        setupWebView()
        webView.loadUrl(url)
    }

    private val pageScripts by lazy {
        listOf("notifications.js", "mobile-layout.js", "theme.js").map { name ->
            assets.open(name).bufferedReader().use { it.readText() }
        }
    }
    private var hasDocumentStartScripts = false

    /** Runs our scripts before Discord's own code, so the Notification API exists when Discord checks. */
    private fun installPageScripts() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val origins = setOf("https://discord.com", "https://ptb.discord.com", "https://canary.discord.com")
        pageScripts.forEach { WebViewCompat.addDocumentStartJavaScript(webView, it, origins) }
        hasDocumentStartScripts = true
    }

    /**
     * Returns true when the URL was handled outside the WebView (or swallowed),
     * false when the WebView should load it.
     */
    private fun routeUrl(uri: Uri, isMainFrame: Boolean, view: WebView?): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        when (scheme) {
            "http", "https" -> {
                // Iframes (captcha, embeds) always stay in the WebView.
                if (!isMainFrame) return false
                val host = uri.host ?: return false
                if (isInternalHost(host)) {
                    if (host.equals("discord.gg", ignoreCase = true)) {
                        normalizeIncomingUrl(uri)?.let { view?.loadUrl(it); return true }
                    }
                    return false
                }
                openExternally(uri)
                return true
            }
            "about", "data", "blob", "javascript" -> return false
            "intent" -> {
                try {
                    val parsed = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    val fallback = parsed.getStringExtra("browser_fallback_url")
                    parsed.addCategory(Intent.CATEGORY_BROWSABLE)
                    parsed.component = null
                    parsed.selector = null
                    try {
                        startActivity(parsed)
                    } catch (_: Exception) {
                        fallback?.let { openExternally(Uri.parse(it)) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Bad intent URI: ${e.message}")
                }
                return true
            }
            else -> {
                // mailto:, tel:, spotify:, steam:, etc.
                openExternally(uri)
                return true
            }
        }
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: Exception) {
            Log.w(TAG, "No app for $uri")
        }
    }

    private fun withStoragePermission(action: () -> Unit) {
        val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P || hasPermission(perm)) {
            action()
        } else {
            pendingDownload = action
            storagePermissionLauncher.launch(perm)
        }
    }

    private fun startFileDownload(url: String, userAgent: String, contentDisposition: String?, mimetype: String?) {
        withStoragePermission {
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    mimetype?.let { setMimeType(it) }
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    addRequestHeader("User-Agent", userAgent)
                    setTitle(fileName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, R.string.downloading_file, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadBlob(blobUrl: String, mimeType: String?, fileName: String) {
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val js = """
            (function() {
                fetch(${jsString(blobUrl)}).then(r => r.blob()).then(b => {
                    const reader = new FileReader();
                    reader.onloadend = () => AndroidDownloader.saveBlobData(
                        reader.result.split(',')[1], ${jsString(mime)}, ${jsString(fileName)});
                    reader.readAsDataURL(b);
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun jsString(s: String) =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"

    private fun saveBase64ToDownloads(base64Data: String, mimeType: String, fileName: String) {
        withStoragePermission {
            backgroundExecutor.execute {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: error("insert failed")
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        java.io.File(dir, fileName).writeBytes(bytes)
                    }
                    runOnUiThread { Toast.makeText(this, "Saved to Downloads", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    Log.e(TAG, "Blob save failed", e)
                    runOnUiThread { Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    webView.webChromeClient?.onHideCustomView()
                    return
                }
                // Let the page close a dialog or return from a chat to the channel list first.
                webView.evaluateJavascript("window.__dmBack ? window.__dmBack() : false") { handled ->
                    if (handled == "true") return@evaluateJavascript
                    // Keep the activity alive so voice calls continue.
                    moveTaskToBack(true)
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onStart() {
        super.onStart()
        webView.evaluateJavascript("window.__dnSetHidden && window.__dnSetHidden(false)", null)
    }

    override fun onStop() {
        super.onStop()
        webView.evaluateJavascript("window.__dnSetHidden && window.__dnSetHidden(true)", null)
    }

    override fun onPause() {
        super.onPause()
        // Deliberately not calling webView.onPause(): it would cut audio in voice calls.
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        pendingPermissionRequest?.deny()
        if (isFinishing) KeepAliveService.stop(this)
        notificationBridge.shutdown()
        webView.destroy()
        backgroundExecutor.shutdown()
        super.onDestroy()
    }
}
