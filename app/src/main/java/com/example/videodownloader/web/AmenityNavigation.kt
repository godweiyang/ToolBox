package com.example.videodownloader.web

import java.net.URI

/** Exact origins and paths only. This policy never handles login secrets. */
object AmenityNavigation {
    const val ASSET_HOST = "appassets.androidplatform.net"
    const val SCORE_URL = "https://$ASSET_HOST/assets/amenity/index.html"
    const val MAP_URL = "https://map.ke.com/map/310000/ESF/"

    private fun uri(raw: String): URI? = runCatching { URI(raw) }.getOrNull()
    fun isScore(raw: String): Boolean = uri(raw)?.let {
        it.scheme == "https" && it.host == ASSET_HOST && it.rawUserInfo == null &&
            it.port == -1 && it.path == "/assets/amenity/index.html" && it.rawQuery == null
    } == true
    fun isBeike(raw: String): Boolean = uri(raw)?.let {
        it.scheme == "https" && it.rawUserInfo == null && it.port == -1 &&
            (it.host == "ke.com" || it.host?.endsWith(".ke.com") == true)
    } == true
    fun isMap(raw: String): Boolean = isBeike(raw) && uri(raw)?.host == "map.ke.com"
    fun isProfile(raw: String): Boolean = uri(raw)?.let {
        isBeike(raw) && Regex("[a-z]{2,12}\\.ke\\.com").matches(it.host.orEmpty()) &&
            Regex("/(?:xiaoqu/\\d+/|ershoufang/\\d+\\.html)").matches(it.path.orEmpty()) &&
            it.rawQuery == null && it.rawFragment == null
    } == true
    fun isCommunity(raw: String): Boolean = isProfile(raw) && uri(raw)?.path?.startsWith("/xiaoqu/") == true
    fun city(raw: String): String? = if (isMap(raw))
        Regex("^/map/(\\d{6})/").find(uri(raw)?.path.orEmpty())?.groupValues?.get(1) else null
    fun assetPath(raw: String): String? = uri(raw)?.let {
        val prefix = "/assets/amenity/"
        if (it.scheme != "https" || it.host != ASSET_HOST || it.rawUserInfo != null ||
            it.port != -1 || !it.path.startsWith(prefix)) return@let null
        val path = it.path.removePrefix("/assets/")
        if (path.split('/').any { part -> part == ".." || part == "." } ||
            path.contains('\\') || path.contains('\u0000')) null else path
    }
    fun isLocal(raw: String): Boolean = uri(raw)?.let {
        it.scheme == "http" && it.host == "127.0.0.1" && it.port == 17532 && it.rawUserInfo == null
    } == true
}
