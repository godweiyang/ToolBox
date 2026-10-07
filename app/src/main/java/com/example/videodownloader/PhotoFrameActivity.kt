package com.example.videodownloader

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

/** Immersive multi-photo frame editor. Selection never writes until Export is pressed. */
class PhotoFrameActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPhotoFrameBinding
    private var processing = false
    private var lastResult: Bitmap? = null
    private val preparedUris = mutableListOf<Uri>()
    private var selectedIndex = -1
    private var options = FrameOptions()
    private var renderGeneration = 0
    private val tabs by lazy { listOf(binding.tabRatio, binding.tabLogo, binding.tabParams,
        binding.tabTheme, binding.tabShadow, binding.tabMargin) }

    private val writePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) doExport() else toast(getString(R.string.pf_save_fail)) }

    private val pickImagesLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_BATCH)
    ) { uris -> if (uris.isNotEmpty()) addPhotos(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoFrameBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = ContextCompat.getColor(this, R.color.pf_editor_bg)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.pf_sheet_bg)
        binding.root.background = ContextCompat.getDrawable(this, R.drawable.frame_editor_empty_bg)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPick.setOnClickListener { if (!processing) launchPicker() }
        binding.tvStatus.setOnClickListener {
            if (preparedUris.isEmpty() && !processing) launchPicker()
        }
        updateStatusPlacement(empty = true)
        binding.btnExport.setOnClickListener {
            if (!processing && ensureLegacyWritePermission()) doExport()
        }
        binding.btnExport.isEnabled = false
        binding.tabRatio.setOnClickListener { showPanel(EditorPanel.RATIO) }
        binding.tabLogo.setOnClickListener { showPanel(EditorPanel.LOGO) }
        binding.tabParams.setOnClickListener { showPanel(EditorPanel.PARAMS) }
        binding.tabTheme.setOnClickListener { showPanel(EditorPanel.THEME) }
        binding.tabShadow.setOnClickListener { showPanel(EditorPanel.SHADOW) }
        binding.tabMargin.setOnClickListener { showPanel(EditorPanel.MARGIN) }
        showPanel(EditorPanel.RATIO)
    }

    private fun launchPicker() = pickImagesLauncher.launch(
        androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    )

    private fun addPhotos(uris: List<Uri>) {
        val room = MAX_BATCH - preparedUris.size
        preparedUris += uris.filterNot(preparedUris::contains).take(room)
        if (selectedIndex !in preparedUris.indices) selectedIndex = 0
        rebuildThumbnails()
        renderSelected()
    }

    private fun removePhoto(index: Int) {
        if (index !in preparedUris.indices || processing) return
        preparedUris.removeAt(index)
        selectedIndex = when {
            preparedUris.isEmpty() -> -1
            selectedIndex > index -> selectedIndex - 1
            selectedIndex >= preparedUris.size -> preparedUris.lastIndex
            else -> selectedIndex
        }
        rebuildThumbnails()
        if (selectedIndex >= 0) renderSelected() else {
            binding.ivPreview.setImageDrawable(null)
            binding.root.background = ContextCompat.getDrawable(this, R.drawable.frame_editor_empty_bg)
            window.statusBarColor = ContextCompat.getColor(this, R.color.pf_editor_bg)
            lastResult?.recycle(); lastResult = null
            binding.tvStatus.text = getString(R.string.pf_idle)
            updateStatusPlacement(empty = true)
            binding.btnExport.isEnabled = false
        }
    }

    private fun selectPhoto(index: Int) {
        if (index !in preparedUris.indices || index == selectedIndex || processing) return
        selectedIndex = index
        rebuildThumbnails()
        renderSelected()
    }

    private fun rebuildThumbnails() {
        binding.thumbnailRow.removeAllViews()
        preparedUris.forEachIndexed { index, uri ->
            val box = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).also { it.marginEnd = dp(5) }
                background = ContextCompat.getDrawable(this@PhotoFrameActivity, R.drawable.frame_editor_option)
                isSelected = index == selectedIndex
                setOnClickListener { selectPhoto(index) }
            }
            val image = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            box.addView(image)
            val close = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END)
                gravity = Gravity.CENTER
                text = "×"
                textSize = 13f
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(this@PhotoFrameActivity, R.drawable.frame_delete_circle)
                contentDescription = getString(R.string.pf_remove_photo)
                setOnClickListener { removePhoto(index) }
            }
            box.addView(close)
            binding.thumbnailRow.addView(box)
            lifecycleScope.launch {
                val thumb = withContext(Dispatchers.IO) { decodeBitmap(uri, 256) }
                if (thumb != null && index < preparedUris.size && preparedUris[index] == uri)
                    image.setImageBitmap(thumb) else thumb?.recycle()
            }
        }
    }

    private fun renderSelected() {
        val uri = preparedUris.getOrNull(selectedIndex) ?: return
        val generation = ++renderGeneration
        processing = true
        setControlsEnabled(false)
        binding.tvStatus.text = getString(R.string.pf_batch_progress, selectedIndex + 1, preparedUris.size)
        lifecycleScope.launch {
            val composed = withContext(Dispatchers.IO) { processOne(uri, options) }
            if (generation != renderGeneration) { composed?.recycle(); return@launch }
            processing = false
            setControlsEnabled(true)
            if (composed == null) {
                binding.tvStatus.text = getString(R.string.pf_render_fail)
            } else {
                val previous = lastResult
                lastResult = composed
                binding.ivPreview.setImageBitmap(composed)
                updateEditorBackground(composed)
                previous?.recycle()
                binding.tvStatus.text = getString(R.string.pf_selected_photo,
                    selectedIndex + 1, preparedUris.size)
                updateStatusPlacement(empty = false)
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.btnPick.isEnabled = enabled && preparedUris.size < MAX_BATCH
        binding.btnExport.isEnabled = enabled && preparedUris.isNotEmpty()
        tabs.forEach { it.isEnabled = enabled }
    }

    private fun showPanel(panel: EditorPanel) {
        tabs.forEachIndexed { index, view -> view.isSelected = index == panel.ordinal }
        binding.optionRow.removeAllViews()
        when (panel) {
            EditorPanel.RATIO -> {
                ratioOption(getString(R.string.pf_original_ratio), options.ratio == FrameRatio.ORIGINAL) { updateOptions(options.copy(ratio=FrameRatio.ORIGINAL)) }
                ratioOption(getString(R.string.pf_ratio_16_9), options.ratio == FrameRatio.LANDSCAPE_16_9) { updateOptions(options.copy(ratio=FrameRatio.LANDSCAPE_16_9)) }
                ratioOption(getString(R.string.pf_ratio_3_4), options.ratio == FrameRatio.PORTRAIT_3_4) { updateOptions(options.copy(ratio=FrameRatio.PORTRAIT_3_4)) }
                ratioOption(getString(R.string.pf_ratio_9_16), options.ratio == FrameRatio.PORTRAIT_9_16) { updateOptions(options.copy(ratio=FrameRatio.PORTRAIT_9_16)) }
            }
            EditorPanel.LOGO -> {
                option(getString(R.string.pf_logo_auto), options.showLogo) { updateOptions(options.copy(showLogo=true)) }
                option(getString(R.string.pf_logo_hide), !options.showLogo) { updateOptions(options.copy(showLogo=false)) }
            }
            EditorPanel.PARAMS -> {
                option(getString(R.string.pf_params_auto), options.showParams) { updateOptions(options.copy(showParams=true)) }
                option(getString(R.string.pf_params_hide), !options.showParams) { updateOptions(options.copy(showParams=false)) }
            }
            EditorPanel.THEME -> {
                option(getString(R.string.pf_theme_photo), options.theme == FrameTheme.PHOTO) { updateOptions(options.copy(theme=FrameTheme.PHOTO)) }
                option(getString(R.string.pf_theme_dark), options.theme == FrameTheme.DARK) { updateOptions(options.copy(theme=FrameTheme.DARK)) }
                option(getString(R.string.pf_theme_light), options.theme == FrameTheme.LIGHT) { updateOptions(options.copy(theme=FrameTheme.LIGHT)) }
            }
            EditorPanel.SHADOW -> {
                option(getString(R.string.pf_shadow_soft), options.shadow == 1f) { updateOptions(options.copy(shadow=1f)) }
                option(getString(R.string.pf_shadow_light), options.shadow == .55f) { updateOptions(options.copy(shadow=.55f)) }
                option(getString(R.string.pf_shadow_none), options.shadow == 0f) { updateOptions(options.copy(shadow=0f)) }
            }
            EditorPanel.MARGIN -> {
                option(getString(R.string.pf_margin_compact), options.margin == .7f) { updateOptions(options.copy(margin=.7f)) }
                option(getString(R.string.pf_margin_standard), options.margin == 1f) { updateOptions(options.copy(margin=1f)) }
                option(getString(R.string.pf_margin_wide), options.margin == 1.3f) { updateOptions(options.copy(margin=1.3f)) }
            }
        }
    }

    private fun option(label: String, selected: Boolean, action: () -> Unit) {
        val view = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
            text = label
            textSize = 14f
            setTextColor(if (selected) Color.WHITE else Color.rgb(205, 205, 210))
            setOnClickListener { action() }
        }
        binding.optionRow.addView(view)
    }

    /** 比例面板：单选圈 + 两行居中文案，四列等分。 */
    private fun ratioOption(label: String, selected: Boolean, action: () -> Unit) {
        val lines = label.split("\n")
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            isClickable = true
            setOnClickListener { action() }
        }
        val radio = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            background = ContextCompat.getDrawable(this@PhotoFrameActivity,
                if (selected) R.drawable.frame_radio_selected else R.drawable.frame_radio_unselected)
        }
        container.addView(radio)
        lines.forEachIndexed { index, line ->
            container.addView(TextView(this).apply {
                text = line
                setTextColor(Color.WHITE)
                textSize = if (index == 0) 14f else 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(if (index == 0) 8 else 2), 0, 0)
            })
        }
        binding.optionRow.addView(container)
    }

    private fun updateOptions(value: FrameOptions) {
        if (options == value) return
        options = value
        val selected = tabs.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        showPanel(EditorPanel.entries[selected])
        if (selectedIndex >= 0) renderSelected()
    }

    private fun doExport() {
        val uris = preparedUris.toList()
        if (uris.isEmpty()) { toast(getString(R.string.pf_no_image)); return }
        processing = true; setControlsEnabled(false)
        lifecycleScope.launch {
            var ok = 0; var fail = 0
            uris.forEachIndexed { index, uri ->
                binding.tvStatus.text = getString(R.string.pf_batch_progress, index + 1, uris.size)
                val composed = withContext(Dispatchers.IO) { processOne(uri, options) }
                if (composed == null) fail++ else {
                    val saved = withContext(Dispatchers.IO) { saveBitmap(composed) }
                    composed.recycle(); if (saved) ok++ else fail++
                }
            }
            processing = false; setControlsEnabled(true)
            binding.tvStatus.text = getString(R.string.pf_batch_done, ok, fail)
        }
    }

    private fun processOne(uri: Uri, frameOptions: FrameOptions): Bitmap? {
        val bmp = decodeBitmap(uri, 2000) ?: return null
        val info = readExif(uri)
        return runCatching { FrameComposer.compose(bmp, info, this, frameOptions) }
            .getOrNull().also { bmp.recycle() }
    }

    private fun decodeBitmap(uri: Uri, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val orientation = readOrientation(uri)
        val swapAxes = orientation.rotationDegrees == 90f || orientation.rotationDegrees == 270f
        val orientedWidth = if (swapAxes) bounds.outHeight else bounds.outWidth
        val orientedHeight = if (swapAxes) bounds.outWidth else bounds.outHeight
        val longEdge = max(orientedWidth, orientedHeight)
        if (longEdge <= 0) return null
        var sample = 1
        while (longEdge / sample > maxEdge) sample *= 2
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        if (!orientation.changesPixels) return decoded
        val matrix = Matrix().apply {
            if (orientation.mirrorHorizontal || orientation.mirrorVertical) {
                postScale(if (orientation.mirrorHorizontal) -1f else 1f,
                    if (orientation.mirrorVertical) -1f else 1f)
            }
            if (orientation.rotationDegrees != 0f) postRotate(orientation.rotationDegrees)
        }
        return runCatching {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }.getOrNull()?.also { transformed ->
            if (transformed !== decoded) decoded.recycle()
        } ?: decoded
    }

    private fun readOrientation(uri: Uri): OrientationTransform = try {
        val value = contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        PhotoOrientation.fromExif(value)
    } catch (_: Exception) {
        OrientationTransform()
    }

    private fun readExif(uri: Uri): PhotoInfo {
        return try {
            val input = contentResolver.openInputStream(uri)
                ?: return PhotoInfo(null,null,null,null,null,null)
            val exif = input.use { ExifInterface(it) }
            val make = CameraBrands.clean(exif.getAttribute(ExifInterface.TAG_MAKE))
            val model = CameraBrands.displayModel(exif.getAttribute(ExifInterface.TAG_MODEL))
            val focal35 = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.toDoubleOrNull()
            val focal = parseRational(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))
            PhotoInfo(make, model, if (focal35 != null && focal35 > 0) focal35 else focal,
                parseRational(exif.getAttribute(ExifInterface.TAG_F_NUMBER)),
                parseRational(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)),
                exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.toIntOrNull())
        } catch (_: Exception) { PhotoInfo(null,null,null,null,null,null) }
    }

    private fun parseRational(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        val parts=value.split('/')
        return if (parts.size==1) parts[0].toDoubleOrNull() else {
            val a=parts[0].toDoubleOrNull(); val b=parts.getOrNull(1)?.toDoubleOrNull()
            if (a!=null && b!=null && b!=0.0) a/b else null
        }
    }

    private fun saveBitmap(bmp: Bitmap): Boolean {
        val cv=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,"photoframe_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/PhotoFrame");put(MediaStore.Images.Media.IS_PENDING,1)}
        }
        val collection=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri=contentResolver.insert(collection,cv)?:return false
        var stream:OutputStream?=null
        return try { stream=contentResolver.openOutputStream(uri)?:return false;bmp.compress(Bitmap.CompressFormat.JPEG,95,stream)
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){cv.clear();cv.put(MediaStore.Images.Media.IS_PENDING,0);contentResolver.update(uri,cv,null,null)};true
        } catch (_:Exception){contentResolver.delete(uri,null,null);false} finally { try{stream?.close()}catch(_:Exception){} }
    }

    private fun ensureLegacyWritePermission():Boolean{
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)return true
        val p=Manifest.permission.WRITE_EXTERNAL_STORAGE
        if(ContextCompat.checkSelfPermission(this,p)==PackageManager.PERMISSION_GRANTED)return true
        writePermissionLauncher.launch(p);return false
    }
    private fun updateStatusPlacement(empty: Boolean) {
        val params = binding.tvStatus.layoutParams as FrameLayout.LayoutParams
        params.gravity = if (empty) Gravity.BOTTOM or Gravity.END
            else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.marginEnd = if (empty) dp(2) else 0
        params.bottomMargin = if (empty) dp(12) else dp(4)
        binding.tvStatus.layoutParams = params
        binding.tvStatus.textSize = if (empty) 12f else 11f
        binding.tvStatus.alpha = if (empty) 0.92f else 0.82f
        binding.tvStatus.isClickable = empty
    }

    private fun updateEditorBackground(bitmap: Bitmap) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val xs = intArrayOf(bitmap.width / 8, bitmap.width / 2, bitmap.width * 7 / 8)
        val ys = intArrayOf(bitmap.height / 14, bitmap.height / 5)
        var red = 0L; var green = 0L; var blue = 0L; var count = 0
        for (y in ys) for (x in xs) {
            val color = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1),
                y.coerceIn(0, bitmap.height - 1))
            red += Color.red(color); green += Color.green(color); blue += Color.blue(color); count++
        }
        if (count == 0) return
        val base = Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
        val top = blend(base, Color.WHITE, 0.08f)
        val bottom = blend(base, ContextCompat.getColor(this, R.color.pf_sheet_bg), 0.48f)
        binding.root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, base, bottom)
        )
        window.statusBarColor = top
    }

    private fun blend(first: Int, second: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(first) * (1f - a) + Color.red(second) * a).toInt(),
            (Color.green(first) * (1f - a) + Color.green(second) * a).toInt(),
            (Color.blue(first) * (1f - a) + Color.blue(second) * a).toInt()
        )
    }

    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun toast(value:String)=Toast.makeText(this,value,Toast.LENGTH_SHORT).show()
    override fun onDestroy(){super.onDestroy();lastResult?.recycle();lastResult=null}
    private enum class EditorPanel { RATIO, LOGO, PARAMS, THEME, SHADOW, MARGIN }
    private companion object { const val MAX_BATCH=30 }
}
