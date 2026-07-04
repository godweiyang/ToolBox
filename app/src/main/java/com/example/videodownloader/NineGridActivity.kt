package com.example.videodownloader

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.videodownloader.databinding.ActivityNineGridBinding
import java.io.OutputStream

/**
 * 九宫格切图工具。
 *
 * 功能：
 *  - 从相册选择图片
 *  - 自动居中裁剪为正方形（朋友圈九宫格标准）
 *  - 切成 3×3 共 9 张，预览
 *  - 一键全部保存到相册 Pictures/NineGrid/
 */
class NineGridActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNineGridBinding
    /** 切好的 9 张图，null 表示无 */
    private val pieces = ArrayList<Bitmap>(9)

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val src = readBitmapFromUri(uri)
                if (src != null) {
                    splitToNine(src)
                    showPreview()
                    binding.tvImageInfo.text = "原图 ${src.width}×${src.height} → 已切成 9 张"
                    binding.btnSaveAll.isEnabled = true
                } else {
                    toast("读取图片失败")
                }
            } catch (e: Exception) {
                toast("读取图片失败")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNineGridBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPick.setOnClickListener { pickImageLauncher.launch("image/*") }

        binding.btnClear.setOnClickListener {
            pieces.forEach { it.recycle() }
            pieces.clear()
            binding.gridPreview.removeAllViews()
            binding.tvImageInfo.text = getString(R.string.ng_input_label)
            binding.btnSaveAll.isEnabled = false
        }

        binding.btnSaveAll.setOnClickListener { saveAll() }
    }

    /** 居中裁剪为正方形，再切成 3×3 共 9 张 */
    private fun splitToNine(src: Bitmap) {
        // 回收旧的
        pieces.forEach { it.recycle() }
        pieces.clear()

        val w = src.width
        val h = src.height
        val size = minOf(w, h)
        val x = (w - size) / 2
        val y = (h - size) / 2

        // 居中裁正方形（尺寸过大时降采样避免 OOM）
        val maxSize = 1080
        val square: Bitmap = if (size > maxSize) {
            val scale = maxSize.toFloat() / size
            Bitmap.createBitmap(src, x, y, size, size).let {
                val scaled = Bitmap.createScaledBitmap(it, maxSize, maxSize, true)
                if (scaled !== it) it.recycle()
                scaled
            }
        } else {
            Bitmap.createBitmap(src, x, y, size, size)
        }

        val pieceSize = square.width / 3
        for (row in 0..2) {
            for (col in 0..2) {
                val px = col * pieceSize
                val py = row * pieceSize
                // 最后一行/列吃掉余数，避免缝隙
                val pw = if (col == 2) square.width - 2 * pieceSize else pieceSize
                val ph = if (row == 2) square.height - 2 * pieceSize else pieceSize
                pieces.add(Bitmap.createBitmap(square, px, py, pw, ph))
            }
        }
        // square 不再需要（pieces 是它的子 bitmap，不持有 square 引用，但为安全不回收 square
        // 因为 createBitmap 子 bitmap 共享像素缓冲，回收 square 会导致子图损坏）
    }

    /** 在 GridLayout 里显示 9 张图 */
    private fun showPreview() {
        binding.gridPreview.removeAllViews()
        val cellSide = dp(110)
        for (i in 0 until 9) {
            val iv = ImageView(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cellSide
                    height = cellSide
                    setMargins(2, 2, 2, 2)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#EEEEEE"))
                setImageBitmap(pieces[i])
            }
            binding.gridPreview.addView(iv)
        }
    }

    /** 9 张图全部保存到相册 */
    private fun saveAll() {
        if (pieces.isEmpty()) {
            toast(getString(R.string.ng_no_image))
            return
        }
        val ts = System.currentTimeMillis()
        var okCount = 0
        for (i in pieces.indices) {
            val name = "ninegrid_${ts}_${i + 1}.png"
            if (saveBitmapToGallery(pieces[i], name, "NineGrid")) okCount++
        }
        if (okCount == 9) {
            toast(getString(R.string.ng_success))
        } else {
            toast("保存了 $okCount/9 张")
        }
    }

    /** 把 Bitmap 存到 MediaStore Pictures/relativePath/ */
    private fun saveBitmapToGallery(bmp: Bitmap, displayName: String, relativePath: String): Boolean {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$relativePath")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, cv) ?: return false
        var os: OutputStream? = null
        return try {
            os = resolver.openOutputStream(uri)
            if (os == null) return false
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, cv, null, null)
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            try { os?.close() } catch (_: Exception) {}
        }
    }

    private fun readBitmapFromUri(uri: Uri): Bitmap? {
        val input = contentResolver.openInputStream(uri) ?: return null
        return input.use { android.graphics.BitmapFactory.decodeStream(it) }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
