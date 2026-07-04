package com.example.videodownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.videodownloader.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Android 13+ 用细粒度媒体权限；旧版本用 WRITE_EXTERNAL_STORAGE */
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 用细粒度媒体权限
            listOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 10/11/12 写入 Movies 通过 MediaStore 不需要权限，留空即可
            emptyList()
        } else {
            listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun doDownload() {
        val input = binding.etShareText.text.toString().trim()
        // 一键流程：解析 → 下载 → 入库
        lifecycleScope.launch {
            setUiBusy(true)
            binding.progressBar.progress = 0
            binding.tvProgress.text = "解析中…"
            log("====================")

            val parseResult = VideoParser.parse(input)
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
            log("视频直链：${video.videoUrl}")
            log("开始下载…")
            binding.tvProgress.text = "下载中 0%"

            val result = DownloadManager.download(
                context = applicationContext,
                videoUrl = video.videoUrl,
                displayName = video.title
            ) { percent ->
                runOnUiThread {
                    binding.progressBar.progress = percent
                    binding.tvProgress.text = "下载中 $percent%"
                }
            }

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
