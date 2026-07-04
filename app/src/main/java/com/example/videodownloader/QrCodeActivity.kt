package com.example.videodownloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.videodownloader.databinding.ActivityQrcodeBinding
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.OutputStream

/**
 * 二维码生成工具。
 *
 * 功能：
 * - 输入文本/链接生成二维码
 * - 自定义前景色 / 背景色（预设 + 自定义）
 * - 方块 / 圆点两种点阵样式
 * - 边距调节（0-8）
 * - 容错等级（L/M/Q/H，加 Logo 建议 H）
 * - 中心 Logo（从相册选，自动缩放并加白底）
 * - 保存到相册 Pictures/QrCode/
 *
 * 任意样式参数改动后，会自动重新生成预览（如果输入框有内容）。
 */
class QrCodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrcodeBinding

    /** 当前前景色 ARGB */
    private var foreColor: Int = Color.BLACK
    /** 当前背景色 ARGB */
    private var backColor: Int = Color.WHITE
    /** 中心 Logo Bitmap，null 表示不加 */
    private var logoBitmap: Bitmap? = null
    /** 当前生成的二维码 Bitmap，用于保存 */
    private var currentQrBitmap: Bitmap? = null

    /** 预设颜色列表（前景色用深色，背景色用浅色） */
    private val foreColorPresets = listOf(
        Color.BLACK to "黑",
        0xFF1A73E8.toInt() to "蓝",
        0xFFE53935.toInt() to "红",
        0xFF43A047.toInt() to "绿",
        0xFF8E24AA.toInt() to "紫",
        0xFFFB8C00.toInt() to "橙"
    )
    private val backColorPresets = listOf(
        Color.WHITE to "白",
        Color.TRANSPARENT to "透明",
        0xFFFFF8E1.toInt() to "米黄",
        0xFFE3F2FD.toInt() to "浅蓝",
        0xFFF1F8E9.toInt() to "浅绿",
        0xFFFCE4EC.toInt() to "浅粉"
    )

    /** 从相册选 Logo */
    private val pickLogoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bmp = readBitmapFromUri(uri)
                logoBitmap = bmp
                binding.ivLogoPreview.setImageBitmap(bmp)
                binding.ivLogoPreview.visibility = View.VISIBLE
                toast("Logo 已设置，自动切换到 H 容错等级")
                binding.rbEcH.isChecked = true
                regenerateIfHasInput()
            } catch (e: Exception) {
                toast("读取图片失败")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrcodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupColorPickers()
        setupListeners()
    }

    /** 初始化前景色 / 背景色色块 */
    private fun setupColorPickers() {
        foreColorPresets.forEach { (color, label) ->
            val swatch = createColorSwatch(color, label, isFore = true)
            binding.llForeColors.addView(swatch)
        }
        backColorPresets.forEach { (color, label) ->
            val swatch = createColorSwatch(color, label, isFore = false)
            binding.llBackColors.addView(swatch)
        }
        // 默认选中黑/白
        (binding.llForeColors.getChildAt(0) as? View)?.isSelected = true
        (binding.llBackColors.getChildAt(0) as? View)?.isSelected = true
    }

    /** 创建一个圆形色块 */
    private fun createColorSwatch(color: Int, label: String, isFore: Boolean): View {
        val size = dp(40)
        val view = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (color == Color.TRANSPARENT) {
                    // 透明背景画一个白底带边框，表示"透明"
                    setStroke(dp(2), Color.GRAY)
                    setColor(Color.WHITE)
                }
            }
            contentDescription = label
        }
        view.setOnClickListener {
            // 更新选中态
            val parent = if (isFore) binding.llForeColors else binding.llBackColors
            for (i in 0 until parent.childCount) {
                parent.getChildAt(i).isSelected = false
            }
            view.isSelected = true
            // 选中描边
            (view.background as? GradientDrawable)?.setStroke(dp(3), 0xFF3F51B5.toInt())

            // 清除其他色块的描边
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child !== view) {
                    (child.background as? GradientDrawable)?.setStroke(0, Color.TRANSPARENT)
                }
            }

            if (isFore) foreColor = color else backColor = color
            regenerateIfHasInput()
        }
        return view
    }

    private fun setupListeners() {
        binding.btnQrPaste.setOnClickListener {
            val text = readClipboard()
            if (text.isNullOrBlank()) {
                toast("剪贴板为空")
            } else {
                binding.etQrInput.setText(text)
            }
        }

        binding.btnGenerate.setOnClickListener {
            generate()
        }

        // 样式改动自动重新生成
        binding.rgDotStyle.setOnCheckedChangeListener { _, _ -> regenerateIfHasInput() }
        binding.rgEcLevel.setOnCheckedChangeListener { _, _ -> regenerateIfHasInput() }
        binding.sbMargin.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                regenerateIfHasInput()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.btnPickLogo.setOnClickListener {
            pickLogoLauncher.launch("image/*")
        }

        binding.btnClearLogo.setOnClickListener {
            logoBitmap = null
            binding.ivLogoPreview.setImageDrawable(null)
            binding.ivLogoPreview.visibility = View.GONE
            regenerateIfHasInput()
            toast("已清除 Logo")
        }

        binding.btnSave.setOnClickListener {
            currentQrBitmap?.let { saveToGallery(it) }
        }
    }

    /** 生成二维码并显示到预览区 */
    private fun generate() {
        val content = binding.etQrInput.text.toString().trim()
        if (content.isBlank()) {
            toast(getString(R.string.qr_empty_input))
            return
        }
        val sizePx = dp(280)
        val ecLevel = when (binding.rgEcLevel.checkedRadioButtonId) {
            R.id.rbEcL -> ErrorCorrectionLevel.L
            R.id.rbEcM -> ErrorCorrectionLevel.M
            R.id.rbEcQ -> ErrorCorrectionLevel.Q
            else -> ErrorCorrectionLevel.H
        }
        val roundDot = binding.rbRound.isChecked
        val margin = binding.sbMargin.progress

        val bmp = QrCodeGenerator.generate(
            content = content,
            sizePx = sizePx,
            foreColor = foreColor,
            backColor = backColor,
            margin = margin,
            ecLevel = ecLevel,
            roundDot = roundDot,
            logo = logoBitmap
        )
        if (bmp != null) {
            currentQrBitmap = bmp
            binding.ivQrPreview.setImageBitmap(bmp)
            binding.btnSave.isEnabled = true
        } else {
            toast("生成失败，内容可能过长")
        }
    }

    /** 输入框有内容时自动重新生成 */
    private fun regenerateIfHasInput() {
        if (binding.etQrInput.text.isNotBlank() && currentQrBitmap != null) {
            generate()
        }
    }

    /** 保存二维码到相册 Pictures/QrCode/ */
    private fun saveToGallery(bitmap: Bitmap) {
        val displayName = "qrcode_${System.currentTimeMillis()}.png"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QrCode")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, contentValues)
        if (uri == null) {
            toast(getString(R.string.qr_save_fail))
            return
        }
        var os: OutputStream? = null
        try {
            os = resolver.openOutputStream(uri)
            if (os == null) throw IllegalStateException("无法打开输出流")
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            os.flush()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            // 通知相册刷新
            sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
            toast(getString(R.string.qr_save_success))
        } catch (e: Exception) {
            toast(getString(R.string.qr_save_fail))
            e.printStackTrace()
        } finally {
            try { os?.close() } catch (_: Exception) {}
        }
    }

    /** 从 Uri 读 Bitmap */
    private fun readBitmapFromUri(uri: Uri): Bitmap {
        return contentResolver.openInputStream(uri).use { input ->
            android.graphics.BitmapFactory.decodeStream(input)
            ?: throw IllegalStateException("解码图片失败")
        }
    }

    private fun readClipboard(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this).toString()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
