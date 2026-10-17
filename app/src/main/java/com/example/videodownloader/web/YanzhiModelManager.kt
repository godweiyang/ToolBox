package com.example.videodownloader.web

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 颜值分析三路 ONNX 模型的本机持久化管理器。
 *
 * 模型不随 APK 打包。用户首次进入功能后主动下载，文件写入 filesDir，
 * APP 更新不会删除；只有卸载或清除应用数据才会移除。
 */
internal class YanzhiModelManager(context: Context) {
    data class ModelSpec(
        val fileName: String,
        val bytes: Long,
        val sha256Prefix: String
    ) {
        val urls: List<String> = buildList {
            // 国内首选：gh-proxy 加速代理公开仓库 storage 的 Release 附件（匿名可下、支持 Range
            // 续传），多个公共域名互为备份，任一失败自动切下一个。
            for (prefix in YanzhiModelManager.GH_PROXY_PREFIXES) {
                add(prefix + YanzhiModelManager.GITHUB_STORAGE_RELEASE + "/" + fileName)
            }
            // 国内直连备选：Gitee Release 附件。该源不支持 Range，遇到 200 会自动整文件重下。
            add("https://gitee.com/godweiyang/storage/releases/download/${YanzhiModelManager.GITEE_MODEL_TAG}/$fileName")
            // 最终兜底：GitHub Pages（godweiyang.com 同源静态资源）
            add("https://godweiyang.com/yanzhi/model/$fileName")
        }
    }

    data class Progress(
        val loaded: Long,
        val total: Long,
        val currentFile: String,
        val message: String
    )

    companion object {
        // v2：三路审美模型全部为 int8 QDQ（合计约 32MB，v1 的 huber 为 fp32 合计约 64MB），
        // 量化后 pearson 0.99995、均分差 0.22，打分几乎无损。
        const val VERSION = "v2"
        // Gitee / GitHub 公开仓库 storage 的 Release tag，随模型版本（VERSION）联动
        const val GITEE_MODEL_TAG = "yanzhi-models-" + VERSION
        const val GITHUB_STORAGE_RELEASE =
            "https://github.com/godweiyang/storage/releases/download/" + GITEE_MODEL_TAG
        // 已实测可匿名下载且支持 Range 的 gh-proxy 加速域名（按可用性排序）
        val GH_PROXY_PREFIXES = listOf(
            "https://gh-proxy.org/",
            "https://gh-proxy.cc/",
            "https://gh-proxy.com/"
        )
        val SPECS = listOf(
            ModelSpec("corrected_huber_qdq.onnx", 11_236_812L, "e3fe18ed48522682"),
            ModelSpec("nima_qdq.onnx", 11_236_888L, "96708cbd9dfa2481"),
            ModelSpec("rank_qdq.onnx", 11_236_813L, "a3c6d1405cc15f86")
        )
        val TOTAL_BYTES: Long = SPECS.sumOf { it.bytes }
    }

    private val modelDir = File(context.filesDir, "yanzhi_models/$VERSION").apply { mkdirs() }
    private val readyMarker = File(modelDir, ".ready")
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun modelFile(name: String): File? =
        SPECS.firstOrNull { it.fileName == name }?.let { File(modelDir, it.fileName) }

    fun installedBytes(): Long = SPECS.sumOf { spec ->
        modelFile(spec.fileName)?.takeIf { it.isFile && it.length() == spec.bytes }?.length() ?: 0L
    }

    fun isReady(): Boolean = readyMarker.isFile && installedBytes() == TOTAL_BYTES

    suspend fun downloadAll(onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        readyMarker.delete()
        var completed = 0L
        for (spec in SPECS) {
            val target = modelFile(spec.fileName)!!
            if (isValid(target, spec)) {
                completed += spec.bytes
                onProgress(Progress(completed, TOTAL_BYTES, spec.fileName, "已存在本机"))
                continue
            }
            if (target.exists()) target.delete()
            val partial = File(modelDir, "${spec.fileName}.part")
            var lastError: Throwable? = null
            for ((sourceIndex, url) in spec.urls.withIndex()) {
                try {
                    onProgress(
                        Progress(
                            completed + partial.length().coerceAtMost(spec.bytes),
                            TOTAL_BYTES,
                            spec.fileName,
                            if (sourceIndex == 0) "正在下载" else "正在切换下载源"
                        )
                    )
                    downloadOne(url, partial, spec, completed, onProgress)
                    if (!isValid(partial, spec)) throw IllegalStateException("模型校验失败")
                    if (target.exists()) target.delete()
                    if (!partial.renameTo(target)) {
                        partial.copyTo(target, overwrite = true)
                        partial.delete()
                    }
                    completed += spec.bytes
                    onProgress(Progress(completed, TOTAL_BYTES, spec.fileName, "下载完成"))
                    lastError = null
                    break
                } catch (t: Throwable) {
                    lastError = t
                    // 完整长度但校验失败时不能继续 Range 416 复用坏文件；切源前重新下载。
                    if (partial.length() >= spec.bytes) partial.delete()
                }
            }
            if (lastError != null) throw lastError
        }
        readyMarker.writeText(VERSION, Charsets.UTF_8)
    }

    private fun downloadOne(
        url: String,
        partial: File,
        spec: ModelSpec,
        completed: Long,
        onProgress: (Progress) -> Unit
    ) {
        var existing = partial.length().coerceAtMost(spec.bytes)
        if (partial.length() > spec.bytes) {
            partial.delete()
            existing = 0L
        }
        val builder = Request.Builder().url(url).header("Accept-Encoding", "identity")
        if (existing > 0L) builder.header("Range", "bytes=$existing-")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 416) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            if (response.code == 416 && existing == spec.bytes) return
            val append = response.code == 206 && existing > 0L
            if (!append) existing = 0L
            val body = response.body ?: throw IllegalStateException("下载响应为空")
            RandomAccessFile(partial, "rw").use { out ->
                if (append) out.seek(existing) else out.setLength(0)
                body.byteStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = existing
                    var lastNotify = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        downloaded += count
                        val now = System.currentTimeMillis()
                        if (now - lastNotify >= 150L || downloaded == spec.bytes) {
                            onProgress(
                                Progress(
                                    completed + downloaded.coerceAtMost(spec.bytes),
                                    TOTAL_BYTES,
                                    spec.fileName,
                                    "正在下载"
                                )
                            )
                            lastNotify = now
                        }
                    }
                }
            }
        }
    }

    private fun isValid(file: File, spec: ModelSpec): Boolean {
        if (!file.isFile || file.length() != spec.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex.startsWith(spec.sha256Prefix, ignoreCase = true)
    }
}
