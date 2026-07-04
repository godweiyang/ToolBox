package com.example.videodownloader

import android.content.Context
import android.content.SharedPreferences

/**
 * B站登录 cookie 持久化存储。
 *
 * B站 1080p+ 画质需要 SESSDATA cookie，非登录态只能拿到 480p。
 * 用户在 [BiliLoginActivity] 的 WebView 里登录后，从 CookieManager 提取
 * SESSDATA 存到这里，后续解析和下载时带上。
 *
 * 注意：SESSDATA 有时效性（通常半年），过期后需重新登录。
 */
object BiliCookieStore {
    private const val PREF_NAME = "bili_cookie"
    private const val KEY_SESSDATA = "SESSDATA"
    private const val KEY_BILI_JCT = "bili_jct"
    private const val KEY_DedeUserID = "DedeUserID"
    private const val KEY_LOGIN_TIME = "login_time"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 保存 cookie（从 CookieManager.getCookie(url) 解析出来的键值对） */
    fun saveCookies(ctx: Context, cookieString: String) {
        val map = parseCookieString(cookieString)
        prefs(ctx).edit().apply {
            map[KEY_SESSDATA]?.let { putString(KEY_SESSDATA, it) }
            map[KEY_BILI_JCT]?.let { putString(KEY_BILI_JCT, it) }
            map[KEY_DedeUserID]?.let { putString(KEY_DedeUserID, it) }
            putLong(KEY_LOGIN_TIME, System.currentTimeMillis())
            apply()
        }
    }

    /** 获取 SESSDATA，未登录返回 null */
    fun getSessdata(ctx: Context): String? =
        prefs(ctx).getString(KEY_SESSDATA, null)

    /** 是否已登录（有 SESSDATA） */
    fun isLoggedIn(ctx: Context): Boolean =
        !getSessdata(ctx).isNullOrBlank()

    /** 完整 cookie 请求头值，形如 "SESSDATA=xxx; bili_jct=yyy" */
    fun getCookieHeader(ctx: Context): String? {
        val sd = getSessdata(ctx) ?: return null
        val sb = StringBuilder("SESSDATA=").append(sd)
        prefs(ctx).getString(KEY_BILI_JCT, null)?.let {
            sb.append("; bili_jct=").append(it)
        }
        prefs(ctx).getString(KEY_DedeUserID, null)?.let {
            sb.append("; DedeUserID=").append(it)
        }
        return sb.toString()
    }

    /** 登出：清除所有 cookie */
    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    /** 登录时间戳（ms），用于判断是否过期 */
    fun getLoginTime(ctx: Context): Long =
        prefs(ctx).getLong(KEY_LOGIN_TIME, 0L)

    /** 解析 "k1=v1; k2=v2" 形式的 cookie 字符串为 Map */
    private fun parseCookieString(s: String): Map<String, String> {
        return s.split(";")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx > 0) {
                    part.substring(0, idx).trim() to part.substring(idx + 1).trim()
                } else null
            }
            .toMap()
    }
}
