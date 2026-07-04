package com.example.videodownloader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * B站登录页：用 WebView 加载 B站官方登录页，用户登录后从 CookieManager 提取 SESSDATA。
 *
 * 流程：
 * 1. 加载 https://passport.bilibili.com/login（B站官方登录页）
 * 2. 用户在 WebView 里输入账号密码登录（我们看不到，WebView 内部完成）
 * 3. 登录成功后 B站会跳转到首页或回调 URL，此时 CookieManager 里有 SESSDATA
 * 4. 提取 cookie，存到 BiliCookieStore，setResult(RESULT_OK) 返回
 *
 * 安全说明：
 *   - 用户账号密码只在 B站官方页面输入，本 App 不接触
 *   - cookie 存在 App 私有 SharedPreferences，不外泄
 */
class BiliLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val targetUrl = "https://passport.bilibili.com/login"
    private val checkUrl = "https://www.bilibili.com/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 先清掉旧的 cookie，避免 WebView 缓存上次登录态
        CookieManager.getInstance().removeAllCookies { }
        CookieManager.getInstance().removeSessionCookies { }

        webView = WebView(this).also { wv ->
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            wv.webViewClient = LoginWebViewClient()
            wv.webChromeClient = object : WebChromeClient() {}
        }
        setContentView(webView)

        // 必须在 WebView 创建后开启第三方 cookie（B站登录跨域）
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.loadUrl(targetUrl)
        title = "B站登录（登录后自动返回）"
    }

    private inner class LoginWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            Log.i(TAG, "onPageStarted: $url")
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            Log.i(TAG, "onPageFinished: $url")

            // 注入 cookie 同步
            CookieManager.getInstance().flush()

            // 检查是否登录成功（跳转到非登录页 + 有 SESSDATA cookie）
            if (url != null && !url.contains("passport.bilibili.com/login")) {
                val cookie = CookieManager.getInstance().getCookie("https://www.bilibili.com/")
                    ?: CookieManager.getInstance().getCookie("https://bilibili.com/")
                Log.i(TAG, "cookie: ${cookie?.take(80)}...")
                if (cookie != null && cookie.contains("SESSDATA=")) {
                    BiliCookieStore.saveCookies(this@BiliLoginActivity, cookie)
                    Log.i(TAG, "登录成功，已保存 SESSDATA")
                    setResult(Activity.RESULT_OK)
                    finish()
                }
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            // 让所有 URL 都在 WebView 内打开
            return false
        }
    }

    override fun onDestroy() {
        try { webView.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BiliLoginActivity"
    }
}
