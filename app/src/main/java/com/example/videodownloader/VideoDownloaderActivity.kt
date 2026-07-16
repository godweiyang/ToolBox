package com.example.videodownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.videodownloader.databinding.ActivityVideoDownloaderBinding
import kotlinx.coroutines.launch

/**
 * 视频下载工具：粘贴分享文本 → 解析视频直链 → 下载到相册。
 *
 * 支持平台：抖音 / 快手 / 小红书 / B站。
 * B站高清画质需先点"B站登录"。
 */
class VideoDownloaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoDownloaderBinding

    /** Android 10+ 通过 MediaStore 写入无需存储权限；旧版本用 WRITE_EXTERNAL_STORAGE */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            doDownload()
        } else {
            log("缺少存储权限，无法保存视频。请在系统设置里授予存储权限。")
            toast("缺少存储权限")
        }
    }

    /** B站登录回调 */
    private val biliLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            log("B站登录成功")
            toast("B站登录成功")
        } else {
            log("B站登录取消")
        }
        refreshBiliStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoDownloaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvLog.movementMethod = ScrollingMovementMethod()

        binding.btnPaste.setOnClickListener {
            val text = readClipboard()
            if (text.isNullOrBlank()) {
                toast("剪贴板为空")
            } else {
                binding.etShareText.setText(text)
                log("已粘贴剪贴板内容（长度 ${text.length}）")
            }
        }

        binding.btnClear.setOnClickListener {
            binding.etShareText.text.clear()
            binding.progressBar.progress = 0
            binding.tvProgress.text = ""
            log("已清空输入")
        }

        binding.btnDownload.setOnClickListener {
            val input = binding.etShareText.text.toString().trim()
            if (input.isBlank()) {
                toast("请先粘贴分享文本")
                return@setOnClickListener
            }
            ensurePermissionsThenDownload()
        }

        binding.btnBiliLogin.setOnClickListener {
            if (BiliCookieStore.isLoggedIn(this)) {
                toast("已登录，长按按钮可登出")
            } else {
                val intent = Intent(this, BiliLoginActivity::class.java)
                biliLoginLauncher.launch(intent)
            }
        }

        binding.btnBiliLogin.setOnLongClickListener {
            if (BiliCookieStore.isLoggedIn(this)) {
                BiliCookieStore.clear(this)
                log("已登出 B站")
                toast("已登出")
                refreshBiliStatus()
            } else {
                val intent = Intent(this, BiliLoginActivity::class.java)
                biliLoginLauncher.launch(intent)
            }
            true
        }

        refreshBiliStatus()
    }

    /** 刷新 B站登录状态显示 */
    private fun refreshBiliStatus() {
        binding.tvBiliStatus.text = if (BiliCookieStore.isLoggedIn(this)) {
            "B站：已登录（1080P+）"
        } else {
            "B站：未登录（480P）"
        }
    }

    private fun ensurePermissionsThenDownload() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            doDownload()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            emptyList()
        } else {
            listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun doDownload() {
        val input = binding.etShareText.text.toString().trim()
        lifecycleScope.launch {
            setUiBusy(true)
            binding.progressBar.progress = 0
            binding.tvProgress.text = "解析中…"
            log("====================")

            val parseResult = VideoParser.parse(input, applicationContext)
            val video = parseResult.getOrElse { e ->
                log("解析失败：${e.message}")
                setUiBusy(false)
                binding.tvProgress.text = ""
                toast("解析失败")
                return@launch
            }
            log("标题：${video.title}")
            log("作者：${video.author}")
            log("平台：${video.platform}")
            if (video.isImage) {
                log("类型：图文笔记（${video.imageUrls.size} 张图片）")
                if (video.musicUrl.isNotBlank()) {
                    log("背景音乐：${video.musicDuration}秒")
                }
                // 图文笔记：弹出选择对话框
                showImageDownloadDialog(video)
                return@launch
            } else {
                if (video.qualityLabel.isNotBlank()) {
                    log("画质：${video.qualityLabel}")
                }
                log("视频直链：${video.videoUrl}")
                if (video.isDash) {
                    log("音频直链：${video.audioUrl}")
                    log("B站高清 dash 模式：下载视频+音频 → 合成 mp4")
                }
            }

            val result = if (video.isDash) {
                binding.tvProgress.text = "下载视频流 0%"
                log("下载视频流…")
                DownloadManager.downloadDash(
                    context = applicationContext,
                    videoUrl = video.videoUrl,
                    audioUrl = video.audioUrl,
                    displayName = video.title
                ) { percent ->
                    runOnUiIfAlive {
                        binding.progressBar.progress = percent
                        val stage = when {
                            percent < 40 -> "下载视频流"
                            percent < 70 -> "下载音频流"
                            percent < 100 -> "合成 mp4"
                            else -> "完成"
                        }
                        binding.tvProgress.text = "$stage $percent%"
                    }
                }
            } else {
                log("开始下载…")
                binding.tvProgress.text = "下载中 0%"
                DownloadManager.download(
                    context = applicationContext,
                    videoUrl = video.videoUrl,
                    displayName = video.title
                ) { percent ->
                    runOnUiIfAlive {
                        binding.progressBar.progress = percent
                        binding.tvProgress.text = "下载中 $percent%"
                    }
                }
            }

            if (!isActivityAlive()) return@launch
            when (result) {
                is DownloadManager.Result.Success -> {
                    DownloadManager.notifyGallery(applicationContext, result.uri)
                    log("下载完成：${result.filePath}")
                    log("已保存到相册：Movies/VideoDownloader/")
                    binding.tvProgress.text = "完成"
                    toast("已保存到相册")
                }
                is DownloadManager.Result.Failure -> {
                    log("下载失败：${result.message}")
                    binding.tvProgress.text = "失败"
                    toast("下载失败")
                }
            }
            setUiBusy(false)
        }
    }

    /** 图文笔记下载方式选择对话框 */
    private fun showImageDownloadDialog(video: VideoInfo) {
        setUiBusy(false)
        val items = mutableListOf<String>()
        // 选项索引映射
        val idxImages = items.size; items.add("保存图片（${video.imageUrls.size} 张）")
        val idxLivePhoto = items.size; items.add("保存实况照片（图片+背景音乐）")

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("图文笔记下载方式")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    idxImages -> startDownloadImages(video)
                    idxLivePhoto -> startDownloadLivePhoto(video)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startDownloadImages(video: VideoInfo) {
        lifecycleScope.launch {
            setUiBusy(true)
            binding.progressBar.progress = 0
            binding.tvProgress.text = "下载图片 0%"
            log("下载图片…")
            val result = DownloadManager.downloadImages(
                context = applicationContext,
                imageUrls = video.imageUrls,
                displayName = video.title
            ) { percent ->
                runOnUiIfAlive {
                    binding.progressBar.progress = percent
                    binding.tvProgress.text = "下载图片 $percent%"
                }
            }
            handleDownloadResult(result, "Pictures/VideoDownloader/")
        }
    }

    private fun startDownloadLivePhoto(video: VideoInfo) {
        lifecycleScope.launch {
            setUiBusy(true)
            binding.progressBar.progress = 0
            binding.tvProgress.text = "保存实况照片 0%"
            log("保存实况照片（图片+背景音乐）…")
            val result = DownloadManager.downloadLivePhoto(
                context = applicationContext,
                imageUrls = video.imageUrls,
                musicUrl = video.musicUrl,
                displayName = video.title,
                musicDurationSec = video.musicDuration
            ) { percent ->
                runOnUiIfAlive {
                    binding.progressBar.progress = percent
                    binding.tvProgress.text = "保存实况照片 $percent%"
                }
            }
            handleDownloadResult(result, "Pictures/VideoDownloader/")
        }
    }

    private fun handleDownloadResult(result: DownloadManager.Result, folder: String) {
        if (!isActivityAlive()) return
        when (result) {
            is DownloadManager.Result.Success -> {
                DownloadManager.notifyGallery(applicationContext, result.uri)
                log("下载完成：${result.filePath}")
                log("已保存到相册：$folder")
                binding.tvProgress.text = "完成"
                toast("已保存到相册")
            }
            is DownloadManager.Result.Failure -> {
                log("下载失败：${result.message}")
                binding.tvProgress.text = "失败"
                toast("下载失败")
            }
        }
        setUiBusy(false)
    }

    private fun isActivityAlive(): Boolean = !isFinishing && !isDestroyed

    private fun runOnUiIfAlive(action: () -> Unit) {
        if (!isActivityAlive()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            runOnUiThread {
                if (isActivityAlive()) action()
            }
        }
    }

    private fun setUiBusy(busy: Boolean) {
        binding.btnDownload.isEnabled = !busy
        binding.btnPaste.isEnabled = !busy
        binding.btnClear.isEnabled = !busy
        binding.progressBar.visibility =
            if (busy) android.view.View.VISIBLE else android.view.View.GONE
        binding.tvProgress.visibility =
            if (busy) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun readClipboard(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this).toString()
    }

    private fun log(msg: String) {
        val cur = binding.tvLog.text.toString()
        binding.tvLog.text = if (cur.isBlank() || cur.endsWith("\n")) cur + msg else cur + "\n" + msg
        binding.tvLog.append("\n")
        binding.scrollLog.post {
            binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
