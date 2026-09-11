package com.example.videodownloader.parser

import android.content.Context
import com.example.videodownloader.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一次解析任务的上下文。
 * @param shareUrl 从分享文案里提取出的原始链接（可能是短链）
 * @param androidContext 用于读取可选登录 Cookie（如 B站 SESSDATA），多数平台为 null
 */
class ParseContext(val shareUrl: String, val androidContext: Context?) {
    /** 短链跟随到的落地 URL，按需惰性解析并缓存（不同平台重定向策略不同，故不强制提前解析） */
    var resolvedUrl: String? = null
}

/** 单个平台解析器：返回 null 表示该平台未解析到可下载媒体（交给兜底/上层报错） */
fun interface PlatformParser {
    suspend fun parse(ctx: ParseContext): VideoInfo?
}

/**
 * 平台注册描述。
 * @param key 平台唯一标识（小写英文，与 VideoInfo.platform / DownloadManager referer 对齐）
 * @param displayName 中文展示名
 * @param referer 下载/请求该平台媒体时应携带的 Referer（防盗链）
 * @param domains 命中域名（host 相等或以 ".domain" 结尾即判定为该平台）
 */
class PlatformDef(
    val key: String,
    val displayName: String,
    val referer: String,
    val domains: List<String>,
    val parser: PlatformParser?,
)

/**
 * 平台注册表：集中负责"链接 → 平台 key"的识别，以及新平台 parser 的分发。
 *
 * 设计参考 ucmao/media-parser 的 ParserFactory + business_config(DOMAIN_TO_NAME)：
 * 一平台一解析器、域名表驱动识别；这里用 Kotlin object 单例 + 域名长度优先匹配实现等价能力。
 *
 * 老的四个平台（抖音/快手/小红书/B站）仍由 [com.example.videodownloader.VideoParser] 内的
 * 成熟逻辑处理，这里只登记域名用于识别（parser 为 null）。
 */
object PlatformRegistry {

    /** 老平台域名（走 VideoParser 既有逻辑），key 与 VideoParser.detectPlatform 保持一致 */
    private val legacyDefs = listOf(
        PlatformDef("bilibili", "B站", "https://www.bilibili.com", listOf("bilibili.com", "b23.tv"), null),
        PlatformDef("xiaohongshu", "小红书", "https://www.xiaohongshu.com", listOf("xiaohongshu.com", "xhslink.com", "xhslink.cn"), null),
        PlatformDef("kuaishou", "快手", "https://v.m.chenzhongtech.com", listOf("kuaishou.com", "chenzhongtech.com", "kwai.com", "gifshow.com"), null),
        PlatformDef("douyin", "抖音", "https://www.douyin.com", listOf("douyin.com", "iesdouyin.com", "amemv.com", "snssdk.com"), null),
    )

    /** 新增平台（parser 子包内实现，均为匿名可跑通的直链解析） */
    private val newDefs = listOf(
        // —— 腾讯系社区 ——
        PlatformDef("weishi", "微视", "https://isee.weishi.qq.com", listOf("weishi.qq.com"), WeishiParser),
        PlatformDef("quanminkge", "全民K歌", "https://kg.qq.com", listOf("kg.qq.com"), QuanminkgeParser),
        // —— 长视频/中视频社区 ——
        PlatformDef("acfun", "AcFun", "https://www.acfun.cn", listOf("acfun.cn", "acfun.com"), AcfunParser),
        PlatformDef("huya", "虎牙", "https://www.huya.com", listOf("huya.com"), HuyaParser),
        PlatformDef("xinpianchang", "新片场", "https://www.xinpianchang.com", listOf("xinpianchang.com"), XinpianchangParser),
        // —— 字节系（西瓜/头条走 VOD，快影/剪映走签名 API）——
        PlatformDef("xigua", "西瓜视频", "https://www.ixigua.com", listOf("ixigua.com"), XiguaParser),
        PlatformDef("toutiao", "今日头条", "https://m.toutiao.com", listOf("toutiao.com", "jinritoutiao.com"), ToutiaoParser),
        PlatformDef("kwaiying", "快影", "https://share.kwaiying.com", listOf("kwaiying.com"), KwaiyingParser),
        PlatformDef("jianying", "剪映", "https://lv.ulikecam.com", listOf("ulikecam.com", "capcut.cn", "capcut.com"), JianyingParser),
        // —— 百度系 ——
        PlatformDef("haokan", "好看视频", "https://haokan.baidu.com", listOf("haokan.baidu.com"), HaokanParser),
        PlatformDef("lishipin", "梨视频", "https://www.pearvideo.com", listOf("pearvideo.com", "pearvideo.cn"), LishipinParser),
        // —— 微博系（绿洲域名更长，需排在 weibo.cn 前面，见 detect 的长度排序）——
        PlatformDef("lvzhou", "绿洲", "https://oasis.weibo.cn", listOf("oasis.weibo.cn"), LvzhouParser),
        PlatformDef("weibo", "微博", "https://weibo.com", listOf("weibo.com", "weibo.cn", "t.cn"), WeiboParser),
        // —— 知乎 ——
        PlatformDef("zhihu", "知乎", "https://www.zhihu.com", listOf("zhihu.com"), ZhihuParser),
        // —— 皮皮虾 / 皮皮搞笑（同体系不同域名）——
        PlatformDef("pipixia", "皮皮虾", "https://h5.pipix.com", listOf("pipix.com", "pipixia.com"), PipixiaParser),
        PlatformDef("pipigaoxiao", "皮皮搞笑", "https://h5.pipigx.com", listOf("pipigx.com"), PipigaoxiaoParser),
        // —— 最右 / Soul / 得物 / LOFTER / 美拍 / 配音秀 ——
        PlatformDef("zuiyou", "最右", "https://share.xiaochuankeji.cn", listOf("xiaochuankeji.cn", "zuiyou.com"), ZuiyouParser),
        PlatformDef("soul", "Soul", "https://w13.soulsmile.cn", listOf("soulsmile.cn", "soulapp.cn"), SoulParser),
        PlatformDef("dewu", "得物", "https://m.dewu.com", listOf("dewu.com", "poizon.com", "poizon.cn"), DewuParser),
        PlatformDef("lofter", "LOFTER", "https://www.lofter.com", listOf("lofter.com"), LofterParser),
        PlatformDef("meipai", "美拍", "https://www.meipai.com", listOf("meipai.com"), MeipaiParser),
        PlatformDef("peiyinxiu", "配音秀", "https://www.peiyinxiu.com", listOf("peiyinxiu.com"), PeiyinxiuParser),
        // —— 番茄小说 / 红果短剧（SSR 页面，走通用 HTML 提取器）——
        PlatformDef("fanqie", "番茄/红果", "https://fanqienovel.com", listOf("fanqienovel.com", "changdunovel.com"), FanqieParser),
    )

    private val allDefs: List<PlatformDef> = newDefs + legacyDefs

    /** 域名 → def，按域名长度降序，保证更具体的子域（如 oasis.weibo.cn、haokan.baidu.com）优先命中 */
    private val domainIndex: List<Pair<String, PlatformDef>> =
        allDefs.flatMap { def -> def.domains.map { it to def } }
            .sortedByDescending { it.first.length }

    /** 识别链接所属平台 key；未命中返回 null */
    fun detect(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = ParserSupport.host(url)
        if (host.isBlank()) return null
        for ((domain, def) in domainIndex) {
            if (host == domain || host.endsWith(".$domain")) return def.key
        }
        return null
    }

    fun defOf(key: String): PlatformDef? = allDefs.firstOrNull { it.key == key }

    fun displayName(key: String): String = defOf(key)?.displayName ?: key
    fun refererFor(key: String): String = defOf(key)?.referer ?: ""

    /** 是否为需要走 VideoParser 老逻辑的平台 */
    fun isLegacy(key: String): Boolean = defOf(key)?.parser == null

    /** 用新平台 parser 解析（已切到 IO 线程）；老平台/未注册返回 null */
    suspend fun parse(key: String, ctx: ParseContext): VideoInfo? {
        val def = defOf(key) ?: return null
        val parser = def.parser ?: return null
        return withContext(Dispatchers.IO) {
            try {
                parser.parse(ctx)
            } catch (e: Exception) {
                android.util.Log.w("PlatformRegistry", "解析 ${def.displayName} 失败: ${e.message}")
                null
            }
        }
    }

    /** 所有已支持平台的中文名（用于输入提示文案） */
    fun supportedDisplayNames(): List<String> =
        (legacyDefs + newDefs).map { it.displayName }
}
