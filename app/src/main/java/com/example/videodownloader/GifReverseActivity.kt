@file:Suppress("DEPRECATION")

package com.example.videodownloader

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.videodownloader.databinding.ActivityGifReverseBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * GIF 倒放工具。
 *
 * 流程：
 *  1. 选 GIF → 读 byte[]
 *  2. Movie.decodeByteArray 解码
 *  3. 按帧数等间隔抽帧（Movie.setTime + draw 到 Canvas/Bitmap）
 *  4. 反转帧序列
 *  5. 用 AnimatedGifEncoder 重新编码
 *  6. 保存到 Pictures/GifOutput/
 *
 * 注意：android.graphics.Movie 在高版本 API 标记 deprecated 但仍可用，
 * 且无需引入第三方 GIF 解码库，保持工程零依赖。
 */
class GifReverseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGifReverseBinding
    private var gifBytes: ByteArray? = null
    /** 倒放后的 GIF 字节 */
    private var reversedBytes: ByteArray? = null

    private val writePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) saveReversed() else toast(getString(R.string.gr_fail))
    }

    private val pickGifLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = readBytes(uri)
                if (bytes != null && bytes.size > 0) {
                    gifBytes = bytes
                    reversedBytes = null
                    binding.btnSave.isEnabled = false
                    // 显示原图首帧
                    showOriginalFirstFrame(bytes)
                    binding.ivReversed.setImageDrawable(null)
                    val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
                    val info = if (movie != null) {
                        "${movie.width()}×${movie.height()}，${movie.duration()}ms，${bytes.size / 1024}KB"
                    } else {
                        "${bytes.size / 1024}KB"
                    }
                    binding.tvGifInfo.text = "已加载：$info"
                    binding.btnGenerate.isEnabled = movie != null
                } else {
                    toast("读取 GIF 失败")
                }
            } catch (e: Exception) {
                toast("读取 GIF 失败")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGifReverseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPick.setOnClickListener { pickGifLauncher.launch("image/gif") }

        binding.btnClear.setOnClickListener {
            gifBytes = null
            reversedBytes = null
            binding.ivOriginal.setImageDrawable(null)
            binding.ivReversed.setImageDrawable(null)
            binding.tvGifInfo.text = getString(R.string.gr_input_label)
            binding.btnGenerate.isEnabled = false
            binding.btnSave.isEnabled = false
        }

        binding.sbFrames.max = 48
        binding.sbFrames.progress = 20
        binding.sbFrames.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                refreshFrameLabel()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        refreshFrameLabel()

        binding.btnGenerate.setOnClickListener { doGenerate() }
        binding.btnSave.setOnClickListener { saveReversed() }
    }

    private fun refreshFrameLabel() {
        // progress 范围 0-48，加 6 保证至少 6 帧
        val n = binding.sbFrames.progress + 6
        binding.tvFrames.text = getString(R.string.gr_frames, n)
    }

    private fun getFrameCount(): Int = binding.sbFrames.progress + 6

    /** 显示 GIF 首帧 */
    private fun showOriginalFirstFrame(bytes: ByteArray) {
        try {
            val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return
            val w = movie.width()
            val h = movie.height()
            movie.setTime(0)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            movie.draw(canvas, 0f, 0f)
            binding.ivOriginal.setImageBitmap(bmp)
        } catch (_: Exception) {}
    }

    /** 生成倒放 GIF */
    private fun doGenerate() {
        val bytes = gifBytes ?: run {
            toast(getString(R.string.gr_no_gif))
            return
        }
        val frameCount = getFrameCount()
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.tvProgress.visibility = android.view.View.VISIBLE
        binding.btnGenerate.isEnabled = false

        lifecycleScope.launch {
            val result: ByteArray? = withContext(Dispatchers.Default) {
                try {
                    val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
                    val duration = movie.duration().toLong().coerceAtLeast(1)
                    val w = movie.width()
                    val h = movie.height()

                    // 抽 frameCount 帧
                    val frames = ArrayList<Bitmap>(frameCount)
                    for (i in 0 until frameCount) {
                        val t = (i.toLong() * duration / frameCount).toInt()
                        movie.setTime(t)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        canvas.drawColor(Color.WHITE)
                        movie.draw(canvas, 0f, 0f)
                        frames.add(bmp)
                        // 进度（抽帧阶段占 60%）
                        val prog = ((i + 1) * 60 / frameCount)
                        withContext(Dispatchers.Main) {
                            binding.progressBar.progress = prog
                            binding.tvProgress.text = getString(R.string.gr_progress, prog)
                        }
                    }

                    // 反转
                    frames.reverse()

                    // 编码（编码阶段占 60%-100%）
                    val baos = ByteArrayOutputStream()
                    val encoder = AnimatedGifEncoder()
                    encoder.setSize(w, h)
                    // delay 单位是 1/100 秒，duration 是 ms，需 /10
                    val delayCenti = (duration / frameCount / 10L).toInt().coerceAtLeast(2)
                    encoder.setDelay(delayCenti)
                    encoder.setQuality(10)
                    encoder.setRepeat(0)
                    encoder.start(baos)
                    for (i in frames.indices) {
                        encoder.addFrame(frames[i])
                        val prog = 60 + (i + 1) * 40 / frames.size
                        withContext(Dispatchers.Main) {
                            binding.progressBar.progress = prog
                            binding.tvProgress.text = getString(R.string.gr_progress, prog)
                        }
                    }
                    encoder.finish()
                    // 回收帧
                    frames.forEach { it.recycle() }
                    baos.toByteArray()
                } catch (e: Exception) {
                    null
                }
            }

            binding.progressBar.visibility = android.view.View.GONE
            binding.tvProgress.visibility = android.view.View.GONE
            binding.btnGenerate.isEnabled = true

            if (result != null) {
                reversedBytes = result
                // 显示倒放首帧（即原图最后一帧）
                showReversedFirstFrame(result)
                binding.btnSave.isEnabled = true
                toast("倒放 GIF 生成完成，${result.size / 1024}KB")
            } else {
                toast(getString(R.string.gr_fail))
            }
        }
    }

    private fun showReversedFirstFrame(bytes: ByteArray) {
        try {
            val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return
            val w = movie.width()
            val h = movie.height()
            movie.setTime(0)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            movie.draw(canvas, 0f, 0f)
            binding.ivReversed.setImageBitmap(bmp)
        } catch (_: Exception) {}
    }

    private fun saveReversed() {
        val bytes = reversedBytes ?: return
        if (!ensureLegacyWritePermission()) return
        val name = "gifreverse_${System.currentTimeMillis()}.gif"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GifOutput")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = contentResolver.insert(collection, cv)
        if (uri == null) {
            toast(getString(R.string.gr_fail))
            return
        }
        var os: OutputStream? = null
        try {
            os = contentResolver.openOutputStream(uri)
            os?.write(bytes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
            }
            toast(getString(R.string.gr_success))
        } catch (e: Exception) {
            toast(getString(R.string.gr_fail))
        } finally {
            try { os?.close() } catch (_: Exception) {}
        }
    }

    private fun ensureLegacyWritePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        writePermissionLauncher.launch(permission)
        return false
    }

    private fun readBytes(uri: Uri): ByteArray? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } > 0) baos.write(buf, 0, n)
            input.close()
            baos.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
