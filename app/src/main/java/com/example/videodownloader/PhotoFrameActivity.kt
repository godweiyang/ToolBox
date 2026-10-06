package com.example.videodownloader

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.example.videodownloader.databinding.ActivityPhotoFrameBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlin.math.max

/**
 * 光影边框工具。
 *
 *  - 从相册选择一张或多张照片（最多 30 张）
 *  - 读取 EXIF：品牌 / 型号 / 焦距 / 光圈 / 快门 / ISO
 *  - 照片放大模糊形成柔和光晕，照片以圆角卡片悬浮
 *  - 底部自动叠加拍摄参数
 *  - 选图后仅生成预览，点击「导出到相册」才保存到 Pictures/PhotoFrame/
 */
class PhotoFrameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoFrameBinding

    private var processing = false
    private var lastResult: Bitmap? = null
    private val preparedUris = mutableListOf<Uri>()

    private val writePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) doExport()
        else toast(getString(R.string.pf_save_fail))
    }

    private val pickImagesLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_BATCH)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) prepare(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoFrameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPick.setOnClickListener {
            if (!processing) launchPicker()
        }
        binding.btnExport.setOnClickListener {
            if (!processing && ensureLegacyWritePermission()) doExport()
        }
        binding.btnExport.isEnabled = false
    }

    private fun launchPicker() = pickImagesLauncher.launch(
        androidx.activity.result.PickVisualMediaRequest(
            ActivityResultContracts.PickVisualMedia.ImageOnly
        )
    )

    /** 选图后：逐张解码 → 合成，仅用于预览，不保存。 */
    private fun prepare(uris: List<Uri>) {
        processing = true
        binding.btnPick.isEnabled = false
        binding.btnExport.isEnabled = false
        preparedUris.clear()
        lastResult?.recycle()
        lastResult = null

        lifecycleScope.launch {
            var fail = 0
            uris.forEachIndexed { index, uri ->
                binding.tvStatus.text =
                    getString(R.string.pf_batch_progress, index + 1, uris.size)
                val composed = withContext(Dispatchers.IO) { processOne(uri) }
                if (composed == null) {
                    fail++
                    return@forEachIndexed
                }
                preparedUris.add(uri)
                lastResult?.recycle()
                lastResult = composed
                binding.ivPreview.setImageBitmap(composed)
            }

            processing = false
            binding.btnPick.isEnabled = true
            if (preparedUris.isNotEmpty()) {
                binding.tvStatus.text = getString(R.string.pf_ready, preparedUris.size)
                binding.btnExport.isEnabled = true
            } else {
                binding.tvStatus.text = getString(R.string.pf_render_fail)
            }
        }
    }

    /** 点击导出：逐张重新合成 → 保存相册。 */
    private fun doExport() {
        val uris = preparedUris.toList()
        if (uris.isEmpty()) {
            toast(getString(R.string.pf_no_image)); return
        }
        processing = true
        binding.btnPick.isEnabled = false
        binding.btnExport.isEnabled = false

        lifecycleScope.launch {
            var ok = 0
            var fail = 0
            uris.forEachIndexed { index, uri ->
                binding.tvStatus.text =
                    getString(R.string.pf_batch_progress, index + 1, uris.size)
                val composed = withContext(Dispatchers.IO) { processOne(uri) }
                if (composed == null) {
                    fail++
                    return@forEachIndexed
                }
                val saved = withContext(Dispatchers.IO) { saveBitmap(composed) }
                composed.recycle()
                if (saved) ok++ else fail++
            }

            binding.tvStatus.text = getString(R.string.pf_batch_done, ok, fail)
            binding.btnPick.isEnabled = true
            // 导出完成后停用导出按钮，避免重复保存；重新选图后才再次可用
            binding.btnExport.isEnabled = false
            preparedUris.clear()
            lastResult?.recycle()
            lastResult = null
            processing = false
        }
    }

    /** 解码 → 读 EXIF → 合成，任何一步失败返回 null。 */
    private fun processOne(uri: Uri): Bitmap? {
        val bmp = decodeBitmap(uri) ?: return null
        val info = readExif(uri)
        return runCatching { FrameComposer.compose(bmp, info, this) }
            .getOrNull()
            .also { if (it !== bmp) bmp.recycle() }
    }

    /** 降采样解码，长边不超过 2000，避免 OOM。 */
    private fun decodeBitmap(uri: Uri): Bitmap? {
        val resolver = contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null
        var sample = 1
        while (longEdge / sample > 2000) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    /** 读取 EXIF 拍摄信息。 */
    private fun readExif(uri: Uri): PhotoInfo {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return PhotoInfo(
                null, null, null, null, null, null
            )
            val exif = input.use { ExifInterface(it) }

            val rawMake = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
            val rawModel = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()

            val brand = rawMake?.split(" ")?.firstOrNull()?.let { capitalizeWord(it) }
            // 型号原样展示（参考效果为 "NIKON Z 30"）
            val model = rawModel?.uppercase()

            val focal35 = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
                ?.toDoubleOrNull()
            val focal = parseRational(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))
            val focalFinal = when {
                focal35 != null && focal35 > 0 -> focal35
                else -> focal
            }
            val fNumber = parseRational(exif.getAttribute(ExifInterface.TAG_F_NUMBER))
            val exposure = parseRational(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
            val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                ?.toIntOrNull()

            PhotoInfo(brand, model, focalFinal, fNumber, exposure, iso)
        } catch (e: Exception) {
            PhotoInfo(null, null, null, null, null, null)
        }
    }

    private fun parseRational(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val parts = s.split("/")
        return when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            else -> {
                val a = parts[0].toDoubleOrNull()
                val b = parts[1].toDoubleOrNull()
                if (a != null && b != null && b != 0.0) a / b else null
            }
        }
    }

    private fun capitalizeWord(s: String): String =
        s.lowercase().replaceFirstChar { it.uppercase() }

    /** 保存到相册 Pictures/PhotoFrame/。 */
    private fun saveBitmap(bmp: Bitmap): Boolean {
        val name = "photoframe_${System.currentTimeMillis()}.jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoFrame")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = contentResolver.insert(collection, cv) ?: return false
        var os: OutputStream? = null
        return try {
            os = contentResolver.openOutputStream(uri) ?: return false
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, os)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            try { os?.close() } catch (_: Exception) {}
        }
    }

    private fun ensureLegacyWritePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) return true
        writePermissionLauncher.launch(permission)
        return false
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private companion object {
        const val MAX_BATCH = 30
    }
}
