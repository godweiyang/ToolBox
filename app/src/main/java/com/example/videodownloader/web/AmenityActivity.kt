package com.example.videodownloader.web

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import com.example.videodownloader.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Three retained WebViews. Only the trusted bundled score page has a native interface. */
class AmenityActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS).build()
    private lateinit var status: TextView
    private lateinit var score: WebView
    private lateinit var map: WebView
    private lateinit var profile: WebView
    private lateinit var frame: FrameLayout
    private var active: WebView? = null
    private var city = "310000"
    private var disposed = false
    private var acquired = false
    private var profileUrl: String? = null
    private val refresh = object : Runnable {
        override fun run() {
            if (disposed) return
            thread(isDaemon = true) {
                val state = runCatching {
                    val data = JSONObject(local("status")).getJSONObject("data")
                    when {
                        data.optBoolean("connected") && data.optBoolean("profileConnected") -> "贝壳地图与详情已连接"
                        data.optBoolean("connected") -> "地图已连接 · 详情需登录或打开对应小区"
                        else -> "内嵌服务已就绪 · 请在贝壳地图页登录"
                    }
                }.getOrElse { AmenityEmbeddedServer.failure ?: "内嵌服务连接中…" }
                main.post { if (!disposed) status.text = state }
            }
            main.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AmenityEmbeddedServer.acquire(applicationContext)
        acquired = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundResource(R.drawable.ios_bg_gradient)
        }
        val top = LinearLayout(this)
        fun button(parent: LinearLayout, label: String, action: () -> Unit) {
            parent.addView(Button(this).apply { text = label; setOnClickListener { action() } },
                LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        button(top, "返回") { navBack() }
        button(top, getString(R.string.amenity_score)) { show(score) }
        button(top, getString(R.string.amenity_map)) { show(map) }
        button(top, getString(R.string.amenity_profile)) { show(profile) }
        root.addView(top)
        status = TextView(this).apply { text = getString(R.string.amenity_connecting); textSize = 14f; setPadding(dp(12), dp(6), dp(12), dp(6)) }
        root.addView(status)
        frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        score = createView(true); map = createView(false); profile = createView(false)
        score.addJavascriptInterface(ScoreBridge(), "AmenityApp")
        setContentView(root)
        score.loadUrl(AmenityNavigation.SCORE_URL)
        profile.loadUrl("https://sh.ke.com/xiaoqu/")
        map.loadUrl(AmenityNavigation.MAP_URL)
        show(score)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navBack()
        })
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun local(path: String): String = client.newCall(Request.Builder()
        .url("http://127.0.0.1:17532/api/housing/$path").build()).execute().use {
            if (!it.isSuccessful) throw IllegalStateException("HTTP ${it.code}")
            it.body?.string() ?: throw IllegalStateException("服务响应为空")
        }
    private fun show(view: WebView) {
        listOf(score, map, profile).forEach { it.visibility = if (it === view) View.VISIBLE else View.GONE }
        active = view
    }
    private fun navBack() {
        val view = active
        if (view !== score) {
            if (view?.canGoBack() == true) view.goBack() else show(score)
            return
        }
        finish()
    }
    private fun external(raw: String) {
        if (!raw.startsWith("https://")) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw))) }
            .onFailure { Toast.makeText(this, "没有可用浏览器", Toast.LENGTH_SHORT).show() }
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun createView(trusted: Boolean): WebView {
        val web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true; loadWithOverviewMode = true
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = true
            if (!trusted) userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        }
        fun navigate(raw: String): Boolean {
            if (trusted) { if (AmenityNavigation.isScore(raw)) return false; external(raw); return true }
            if (AmenityNavigation.isBeike(raw)) {
                if (web === map && AmenityNavigation.isCommunity(raw)) {
                    profileUrl = raw; profile.loadUrl(raw); show(profile); return true
                }
                return false
            }
            external(raw); return true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = navigate(request.url.toString())
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val raw = request.url.toString()
                if (trusted && request.isForMainFrame && !AmenityNavigation.isScore(raw)) {
                    return WebResourceResponse("text/plain", "utf-8", null).apply { setStatusCodeAndReasonPhrase(403, "Blocked") }
                }
                if (trusted) AmenityNavigation.assetPath(raw)?.let { path ->
                    return runCatching { WebResourceResponse("text/html", "utf-8", assets.open(path)) }.getOrElse {
                        WebResourceResponse("text/plain", "utf-8", null).apply { setStatusCodeAndReasonPhrase(404, "Not Found") }
                    }
                }
                if (request.url.scheme == "http" && !AmenityNavigation.isLocal(raw))
                    return WebResourceResponse("text/plain", "utf-8", null).apply { setStatusCodeAndReasonPhrase(403, "Blocked") }
                return null
            }
            override fun onPageFinished(view: WebView, raw: String) {
                if (trusted || disposed) return
                AmenityNavigation.city(raw)?.let { nextCity ->
                    if (nextCity != city) {
                        city = nextCity
                        val detailUrl = profile.url.orEmpty()
                        if (AmenityNavigation.isProfile(detailUrl)) attach(profile, detailUrl, 0)
                    }
                }
                if (AmenityNavigation.isCommunity(raw)) profileUrl = raw
                if (AmenityNavigation.isMap(raw) || AmenityNavigation.isProfile(raw)) attach(view, raw, 0)
            }
            // Default SSL-error handler cancels. Never accept invalid certificates.
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, message: android.os.Message): Boolean {
                if (!gesture) return false
                val popup = WebView(this@AmenityActivity)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        val raw = request.url.toString()
                        if (!trusted && AmenityNavigation.isBeike(raw)) {
                            if (AmenityNavigation.isCommunity(raw)) { profileUrl = raw; profile.loadUrl(raw); show(profile) }
                            else web.loadUrl(raw)
                        } else external(raw)
                        popup.destroy(); return true
                    }
                }
                (message.obj as WebView.WebViewTransport).webView = popup
                message.sendToTarget(); return true
            }
        }
        frame.addView(web, FrameLayout.LayoutParams(-1, -1))
        return web
    }
    private fun attach(web: WebView, expectedUrl: String, attempt: Int) {
        if (disposed || web.url != expectedUrl) return
        val currentCity = city
        thread(isDaemon = true) {
            val code = runCatching { local("bridge.js") }.getOrNull()
            main.post {
                if (disposed || web.url != expectedUrl) return@post
                if (code != null) web.evaluateJavascript("window.__amenityCity=" + JSONObject.quote(currentCity) + ";" + code, null)
                else if (attempt < 10) main.postDelayed({ attach(web, expectedUrl, attempt + 1) }, 1000)
                else status.text = "内嵌连接失败，请退出小区对比后重试"
            }
        }
    }
    inner class ScoreBridge {
        @JavascriptInterface fun showMap() { main.post { if (!disposed) show(map) } }
        @JavascriptInterface fun showProfile() { main.post {
            if (disposed) return@post
            if (profileUrl == null) { status.text = "先在地图中点开一个小区，再进入详情登录"; show(map) }
            else show(profile)
        } }
        @JavascriptInterface fun prepareProfile(raw: String): Boolean {
            if (!AmenityNavigation.isCommunity(raw)) return false
            main.post {
                if (!disposed && (profileUrl != raw || !AmenityNavigation.isProfile(profile.url.orEmpty()))) {
                    profileUrl = raw; profile.loadUrl(raw)
                }
            }
            return true
        }
    }
    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() {
        disposed = true; main.removeCallbacksAndMessages(null)
        if (::score.isInitialized) listOf(score, map, profile).forEach { it.stopLoading(); it.destroy() }
        if (acquired) AmenityEmbeddedServer.release()
        super.onDestroy()
    }
}
