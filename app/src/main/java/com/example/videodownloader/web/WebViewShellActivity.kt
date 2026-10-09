package com.example.videodownloader.web

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.videodownloader.R
import com.example.videodownloader.databinding.ActivityWebShellBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 可复用的离线 Web 工具外壳。
 *
 * 三个工具（lol / pubg / fangdai）共用本 Activity，靠 intent extra [WebToolRegistry.EXTRA_TOOL_ID] 区分。
 * 页面通过 appassets 源（https://appassets.androidplatform.net/assets/<id>/index.html）从本地 assets 加载，
 * 不依赖网络即可运行；路由、下载、剪贴板/分享均在本外壳内统一兜底。
 */
class WebViewShellActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebShellBinding
    private var toolId: String = "lol"

    // API 26–28 写公共相册需要运行时申请 WRITE_EXTERNAL_STORAGE
    private var storagePermissionContinuation: ((Boolean) -> Unit)? = null
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            storagePermissionContinuation?.invoke(granted)
            storagePermissionContinuation = null
        }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        fun newIntent(ctx: Context, toolId: String): Intent =
            Intent(ctx, WebViewShellActivity::class.java)
                .putExtra(WebToolRegistry.EXTRA_TOOL_ID, toolId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebShellBinding.inflate(layoutInflater)
        setContentView(binding.root)

        toolId = intent.getStringExtra(WebToolRegistry.EXTRA_TOOL_ID)?.takeIf { WebToolRegistry.byId(it) != null }
            ?: "lol"
        val spec = WebToolRegistry.byId(toolId) ?: WebToolRegistry.tools.first()

        binding.tvTitle.text = getString(spec.titleRes)
        binding.btnBack.setOnClickListener { onNavBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onNavBack()
        })

        // lol 页面：在本 app 进程内自动起本地回环服务（无需独立服务 APK / 手动启动）
        if (toolId == "lol") {
            LolEmbeddedServer.start(applicationContext)
        }

        setupWebView()
        binding.webView.loadUrl(WebToolRegistry.assetUrl(toolId))
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            // 安全：关闭 file:// / content:// 直接访问
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = false
            setSupportZoom(false)
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = true
            // 允许 window.open / target=_blank 在外壳内创建窗口
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            // 允许回环(127.0.0.1)本地服务的明文 POST；其它非回环 http 子资源在拦截器里拦截
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                return when (WebRoutePolicy.decide(url)) {
                    WebRoutePolicy.Decision.ALLOW_IN_APP -> false
                    WebRoutePolicy.Decision.BLOCK -> true
                    WebRoutePolicy.Decision.OPEN_EXTERNAL_BROWSER -> {
                        openExternal(url); true
                    }
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url ?: return super.shouldInterceptRequest(view, request)
                val urlStr = url.toString()
                // appassets 源：/assets/<tool>/... 与对外友好路径 /<tool>/... 都映射到本地 assets
                if (WebRoutePolicy.isAssetOrigin(urlStr)) {
                    val assetPath = WebRoutePolicy.resolveAssetPath(url.path)
                    if (assetPath != null) return serveAsset(assetPath)
                }
                // 任意非回环明文 http 子资源一律拦截（回环 127.0.0.1 放行给本地服务）
                if (WebRoutePolicy.isBlockedHttpSubResource(urlStr)) {
                    return WebResourceResponse("text/plain", "utf-8", null).apply {
                        setStatusCodeAndReasonPhrase(403, "Blocked")
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // 注入 blob:/data: 下载兜底，避免页面 <a download> 按钮静默失败
                view.evaluateJavascript(JS_DOWNLOAD_HOOK, null)
            }
        }

        // window.open / target=_blank：内部页在外壳内打开，外链交浏览器
        binding.webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                val transport = resultMsg.obj as? android.webkit.WebView.WebViewTransport
                    ?: return false
                val newWeb = WebView(view.context)
                newWeb.settings.javaScriptEnabled = true
                newWeb.settings.domStorageEnabled = true
                newWeb.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        routeNewWindow(url, newWeb)
                    }
                }
                transport.webView = newWeb
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                window.destroy()
                if (!binding.webView.canGoBack()) finish()
            }
        }

        // http/https 下载兜底（blob:/data: 走 JS 桥）
        binding.webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleNetworkDownload(url, contentDisposition, mimeType)
        }

        binding.webView.addJavascriptInterface(Bridge(), "NativeBridge")
        // PUBG 页面使用 AppBridge 命名，做兼容桥（不与 NativeBridge 的保存/分享重复实现）
        binding.webView.addJavascriptInterface(AppBridgeCompat(), "AppBridge")
    }

    /** window.open 目标：内部页（appassets/回环）在外壳内打开，外链交浏览器。 */
    private fun routeNewWindow(url: String?, tempWeb: WebView) {
        tempWeb.destroy()
        when (WebRoutePolicy.decide(url)) {
            WebRoutePolicy.Decision.ALLOW_IN_APP -> { if (!url.isNullOrEmpty()) binding.webView.loadUrl(url) }
            WebRoutePolicy.Decision.BLOCK -> { /* 静默丢弃 */ }
            WebRoutePolicy.Decision.OPEN_EXTERNAL_BROWSER -> openExternal(url ?: "")
        }
    }

    /** 把 appassets 请求映射到本地 assets 文件。 */
    private fun serveAsset(assetPath: String): WebResourceResponse {
        return try {
            val clean = assetPath.removePrefix("/").ifEmpty { "${toolId}/${WebToolRegistry.ASSET_INDEX}" }
            val stream = assets.open(clean)
            WebResourceResponse(mimeFor(clean), "utf-8", stream)
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", null).apply {
                setStatusCodeAndReasonPhrase(404, "Not Found")
            }
        }
    }

    private fun mimeFor(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "xml" -> "text/xml"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "mp4" -> "video/mp4"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
    }

    // ---------------- 下载 / 保存 / 分享 ----------------

    private fun downloadsDir(): File =
        File(getExternalFilesDir(null), "Downloads").apply { mkdirs() }

    private fun handleNetworkDownload(url: String?, contentDisposition: String?, mimeType: String?) {
        if (url == null) {
            toast(R.string.web_shell_download_failed); return
        }
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            // 无法直接 fetch：由注入的 JS 钩子把内容 base64 回传
            toast(R.string.web_shell_download_via_page); return
        }
        if (WebRoutePolicy.decide(url) == WebRoutePolicy.Decision.BLOCK) {
            toast(R.string.web_shell_blocked); return
        }
        val name = DownloadNaming.resolve(url, contentDisposition, mimeType)
        val isImage = mimeType?.startsWith("image/") == true
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    val body = httpClient.newCall(Request.Builder().url(url).build()).execute().body
                    body?.bytes() ?: ByteArray(0)
                }.getOrNull()
            }
            if (bytes == null) { toast(R.string.web_shell_download_failed); return@launch }
            if (isImage) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(
                        this@WebViewShellActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    if (!requestStoragePermission()) {
                        toast(R.string.web_shell_download_failed); return@launch
                    }
                }
                val uri = withContext(Dispatchers.IO) {
                    runCatching {
                        GallerySaver.save(this@WebViewShellActivity, name, bytes, mimeType!!)
                    }.getOrNull()
                }
                if (uri != null) { toast("已保存到手机相册"); offerShare(uri, mimeType!!) }
                else toast(R.string.web_shell_download_failed)
            } else {
                val out = withContext(Dispatchers.IO) {
                    runCatching {
                        File(downloadsDir(), name).apply { outputStream().use { it.write(bytes) } }
                    }.getOrNull()
                }
                if (out != null) {
                    toast(getString(R.string.web_shell_download_saved, out.name))
                    offerShare(Uri.fromFile(out), mimeType ?: "*/*")
                } else toast(R.string.web_shell_download_failed)
            }
        }
    }

    /**
     * 落盘一段 base64 内容（兼容纯 base64 或带 data: 前缀）。
     * @return 写入的文件（失败为 null）
     */
    private suspend fun saveBase64ToFile(
        fileName: String,
        data: String,
        mimeType: String?
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            var mime: String? = mimeType?.takeIf { it.isNotBlank() }
            var payload = data.trim()
            if (payload.startsWith("data:")) {
                val comma = payload.indexOf(',')
                if (comma > 0) {
                    val meta = payload.substring(5, comma)
                    mime = meta.substringBefore(';').ifBlank { null }
                    payload = payload.substring(comma + 1)
                }
            }
            val name = DownloadNaming.resolve(fileName, null, mime)
            val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            File(downloadsDir(), name).apply { outputStream().use { it.write(bytes) } }
        }.getOrNull()
    }

    /** NativeBridge.saveFile / shareImage 的统一入口。 */
    private fun onNativeSave(fileName: String, data: String, mimeType: String?, shareAfter: Boolean) {
        val isImage = mimeType?.startsWith("image/") == true
        lifecycleScope.launch {
            if (isImage) {
                // 图片直接进系统相册，图库立即可见
                val uri = saveImageToGallery(fileName, data, mimeType!!)
                runOnUiThread {
                    if (uri != null) {
                        toast("已保存到手机相册")
                        if (shareAfter) offerShare(uri, mimeType)
                    } else toast(R.string.web_shell_download_failed)
                }
            } else {
                val file = saveBase64ToFile(fileName, data, mimeType)
                runOnUiThread {
                    if (file != null) {
                        toast(getString(R.string.web_shell_download_saved, file.name))
                        // 复制文件绝对路径，页面「复制路径」按钮直接可用
                        copyToClipboard(file.absolutePath)
                        if (shareAfter) offerShare(Uri.fromFile(file), mimeType ?: "*/*")
                    } else {
                        toast(R.string.web_shell_download_failed)
                    }
                }
            }
        }
    }

    /** 解码 base64 图片并保存进相册；API 26–28 必要时先申请存储权限。 */
    private suspend fun saveImageToGallery(fileName: String, data: String, mime: String): Uri? {
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                var payload = data.trim()
                if (payload.startsWith("data:")) {
                    val comma = payload.indexOf(',')
                    if (comma > 0) payload = payload.substring(comma + 1)
                }
                android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            }.getOrNull()
        } ?: return null

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            val granted = requestStoragePermission()
            if (!granted) return null
        }
        return withContext(Dispatchers.IO) {
            runCatching { GallerySaver.save(this@WebViewShellActivity, fileName, decoded, mime) }
                .getOrNull()
        }
    }

    private suspend fun requestStoragePermission(): Boolean = suspendCancellableCoroutine { cont ->
        storagePermissionContinuation = { granted -> if (cont.isActive) cont.resume(granted) }
        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    /** 复制图片到系统剪贴板（相册 content uri），聊天/文档可直接粘贴。 */
    private fun onCopyImage(fileName: String, data: String) {
        lifecycleScope.launch {
            val uri = saveImageToGallery(fileName, data, "image/png")
            runOnUiThread {
                if (uri != null) {
                    val cm = getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newUri(contentResolver, "image", uri))
                    toast("图片已复制，可直接粘贴")
                } else toast(R.string.web_shell_download_failed)
            }
        }
    }

    private fun offerShare(uri: Uri, mime: String) {
        val shareMime = mime.let { if (it.isBlank() || it == "application/octet-stream") "application/*" else it }
        // FileProvider 的私有文件 uri 与 MediaStore 的相册 content uri 都可直接分享
        val shareable: Uri = if (uri.scheme == "content") uri
        else runCatching {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", File(uri.path!!))
        }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = shareMime
            putExtra(Intent.EXTRA_STREAM, shareable)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.web_shell_share))) }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("path", text))
        toast(R.string.web_shell_copied)
    }

    // ---------------- 导航 ----------------

    private fun openExternal(url: String) {
        // 自定义协议（如 lolzjcx://start）可能没有对应 handler，须优雅降级而非崩溃
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { toast(R.string.web_shell_no_handler) }
    }

    private fun onNavBack() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
    }

    override fun onDestroy() {
        binding.webView.apply {
            stopLoading()
            removeJavascriptInterface("NativeBridge")
            removeJavascriptInterface("AppBridge")
            destroy()
        }
        super.onDestroy()
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * 与页面 assets/<tool>/js/native-bridge.js 约定的原生桥（window.NativeBridge）。
     * 方法名与签名严格对齐页面注释：saveFile / shareImage / copyText / openPage / isReady。
     * base64 均为去 data: 前缀的纯 base64（native 侧也兼容带前缀的 dataURL）。
     */
    inner class Bridge {
        /** 保存文件到下载目录（Excel / 图片等），不主动弹分享。 */
        @android.webkit.JavascriptInterface
        fun saveFile(base64Data: String, fileName: String, mimeType: String) =
            onNativeSave(fileName, base64Data, mimeType, shareAfter = false)

        /** 保存图片并直接弹系统分享面板。 */
        @android.webkit.JavascriptInterface
        fun shareImage(base64Data: String, fileName: String) =
            onNativeSave(fileName, base64Data, "image/png", shareAfter = true)

        /** 复制文本/路径到系统剪贴板。 */
        @android.webkit.JavascriptInterface
        fun copyText(text: String) = runOnUiThread { copyToClipboard(text) }

        /** 应用内跳转到另一个离线工具页（/lol ↔ /pubg ↔ /fangdai），在当前 WebView 内打开。 */
        @android.webkit.JavascriptInterface
        fun openPage(pageKey: String): Boolean {
            val spec = WebToolRegistry.byId(pageKey) ?: return false
            runOnUiThread { binding.webView.loadUrl(WebToolRegistry.assetUrl(spec.id)) }
            return true
        }

        @android.webkit.JavascriptInterface
        fun isReady(): Boolean = true
    }

    /**
     * PUBG 页面使用的兼容桥（window.AppBridge）。
     * 与 NativeBridge 复用同一套落盘/剪贴板实现，避免重复的保存/分享逻辑：
     *  - saveImage(fileName, base64)：保存长图到下载目录，不自动弹分享
     *  - copyImage(base64)：保存 PNG 并把文件路径写入剪贴板，供聊天/文档粘贴
     * 方法名/参数顺序严格对齐 pubg/index.html 里的调用。
     */
    inner class AppBridgeCompat {
        @android.webkit.JavascriptInterface
        fun saveImage(fileName: String, base64: String): Boolean {
            onNativeSave(fileName, base64, "image/png", shareAfter = false)
            return true
        }

        @android.webkit.JavascriptInterface
        fun copyImage(base64: String): Boolean {
            // 保存进相册并把图片本身复制到剪贴板，聊天/文档可直接粘贴
            onCopyImage("pubgzjcx-长图", base64)
            return true
        }
    }
}

/**
 * 注入的下载钩子：兜底拦截页面上 <a download> 的 blob:/data: 链接，
 * 用 fetch 读成 dataURL 后回传给 NativeBridge.saveFile 落盘。
 * 已直接调用 NativeBridge 的页面不依赖此钩子；它只覆盖未走桥的下载按钮。
 */
private const val JS_DOWNLOAD_HOOK = """
(function(){
  if (window.__appShellHooked) return;
  window.__appShellHooked = true;
  function send(name, dataUrl){
    try { if (window.NativeBridge && NativeBridge.saveFile) NativeBridge.saveFile(dataUrl, name||'download', ''); } catch(e){}
  }
  document.addEventListener('click', function(e){
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    var name = a.getAttribute('download') || '';
    if (href.indexOf('blob:') === 0) {
      e.preventDefault();
      fetch(href).then(function(r){ return r.blob(); }).then(function(b){
        var fr = new FileReader();
        fr.onload = function(){ send(name, fr.result); };
        fr.readAsDataURL(b);
      }).catch(function(){});
    } else if (href.indexOf('data:') === 0) {
      e.preventDefault();
      send(name, href);
    }
  }, true);
})();
"""
