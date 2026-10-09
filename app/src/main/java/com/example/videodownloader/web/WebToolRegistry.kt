package com.example.videodownloader.web

import com.example.videodownloader.R

/**
 * 三个离线 Web 工具的统一注册信息。
 * 首页卡片（MainActivity）与 WebView 外壳（WebViewShellActivity）共用同一份配置，
 * 避免两处 id / 标题漂移。
 *
 * 页面资产本身（assets/lol、assets/pubg、assets/fangdai 下的 HTML）由业务侧维护，
 * 本外壳只负责通过 appassets 源加载，不复制也不修改这些页面。
 */
object WebToolRegistry {

    data class Spec(
        val id: String,
        val titleRes: Int,
        val descRes: Int,
        val iconRes: Int,
        val iconBgRes: Int
    )

    const val EXTRA_TOOL_ID = "extra_tool_id"
    const val ASSET_INDEX = "index.html"

    val tools: List<Spec> = listOf(
        Spec(
            id = "lol",
            titleRes = R.string.tool_lol_title,
            descRes = R.string.tool_lol_desc,
            iconRes = android.R.drawable.ic_menu_search,
            iconBgRes = R.drawable.icon_grad_lol
        ),
        Spec(
            id = "pubg",
            titleRes = R.string.tool_pubg_title,
            descRes = R.string.tool_pubg_desc,
            iconRes = android.R.drawable.ic_menu_compass,
            iconBgRes = R.drawable.icon_grad_pubg
        ),
        Spec(
            id = "fangdai",
            titleRes = R.string.tool_fangdai_title,
            descRes = R.string.tool_fangdai_desc,
            iconRes = android.R.drawable.ic_menu_info_details,
            iconBgRes = R.drawable.icon_grad_fangdai
        )
    )

    /** 纯 id 列表（无资源读取，供顺序/单测使用） */
    val ids: List<String> get() = tools.map { it.id }

    fun byId(id: String?): Spec? = tools.firstOrNull { it.id == id }

    /** 该工具在 appassets 源下的入口 URL */
    fun assetUrl(id: String): String =
        "https://${WebRoutePolicy.ASSET_ORIGIN_HOST}/assets/$id/$ASSET_INDEX"
}
