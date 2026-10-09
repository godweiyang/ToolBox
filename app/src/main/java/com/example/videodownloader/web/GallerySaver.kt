package com.example.videodownloader.web

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

/**
 * 把图片保存进系统相册（图库立即可见），替代原先写入 app 私有目录
 * （getExternalFilesDir 不会被媒体库扫描，用户在相册里看不到图）。
 *
 *  - API 29+：MediaStore.Images + RELATIVE_PATH=Pictures/ToolBox，无需任何存储权限；
 *  - API 26–28：直接写公共 Pictures 目录（需要已授予 WRITE_EXTERNAL_STORAGE），
 *    再用 MediaScanner 入库。
 */
object GallerySaver {

    const val ALBUM_DIR = "ToolBox"

    /**
     * @param fileName 期望文件名（可不含扩展名）
     * @param bytes 图片字节
     * @param mime 如 "image/png"
     * @return 相册中图片的 content:// uri；失败为 null
     */
    fun save(context: Context, fileName: String, bytes: ByteArray, mime: String): Uri? {
        val uniqueName = uniqueFileName(fileName, mime)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, uniqueName, bytes, mime)
        } else {
            saveLegacy(context, uniqueName, bytes, mime)
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        name: String,
        bytes: ByteArray,
        mime: String
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + ALBUM_DIR
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun saveLegacy(
        context: Context,
        name: String,
        bytes: ByteArray,
        mime: String
    ): Uri? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM_DIR
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, name)
        file.outputStream().use { it.write(bytes) }
        // 通知媒体库扫描入库，相册随即可见
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        android.media.MediaScannerConnection.scanFile(
            context, arrayOf(file.absolutePath),
            arrayOf(ext?.let { "$mime" } ?: mime), null
        )
        return Uri.fromFile(file)
    }

    /** 文件名规范化并加时间戳，避免相册里重名覆盖。 */
    private fun uniqueFileName(raw: String, mime: String): String {
        val base = raw.substringBeforeLast('.', raw).ifBlank { "image" }
        val safe = base.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_')
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        return "${safe}_$stamp.$ext"
    }
}
