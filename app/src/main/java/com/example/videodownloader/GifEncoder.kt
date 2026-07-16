package com.example.videodownloader

import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 把多张 Bitmap 合成为 GIF89a 动图。
 *
 * - 颜色量化：3-3-2 均匀量化（R 取高 3 位 / G 取高 3 位 / B 取高 2 位 = 256 色），
 *   调色板固定，全局调色板与各帧局部调色板相同。
 * - 压缩：标准 GIF LZW（变长码，最小码尺寸 8，码宽从 9 位起逐级递增，最大 12 位）。
 * - 仅依赖 Android SDK 与 Java 标准库。
 *
 * 风格参考项目中的 [BiliMuxer]：object 单例 + try/finally 释放资源。
 */
object GifEncoder {

    private const val TAG = "GifEncoder"

    /**
     * 把 [bitmaps] 合成为 GIF 写入 [output]。
     *
     * @param bitmaps    要合成的图片列表（尺寸不一致时统一缩放到第一张尺寸）
     * @param output     输出 GIF 文件
     * @param delayMs    每帧延迟（毫秒，GIF 内部按 1/100 秒存储）
     * @param onProgress 进度回调 0..100
     * @return true 表示成功
     */
    fun encode(
        bitmaps: List<Bitmap>,
        output: File,
        delayMs: Int = 1000,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        if (bitmaps.isEmpty()) {
            Log.w(TAG, "bitmaps 为空，无法编码")
            return false
        }
        var out: BufferedOutputStream? = null
        var lastReported = -1
        try {
            val first = bitmaps[0]
            val width = first.width
            val height = first.height

            // 3-3-2 固定调色板（256 色），作为全局及各帧局部调色板
            val palette = buildPalette332()
            // 像素与索引缓冲在帧间复用，避免大图反复分配导致内存抖动
            val pixels = IntArray(width * height)
            val indices = ByteArray(width * height)

            out = BufferedOutputStream(FileOutputStream(output))
            // 1. 文件头 GIF89a
            writeHeader(out)
            // 2. 逻辑屏幕描述符 + 3. 全局调色板（由首帧量化得到的 3-3-2 调色板）
            writeLogicalScreenDescriptor(out, width, height)
            writeColorTable(out, palette)

            val frameCount = bitmaps.size
            for (i in 0 until frameCount) {
                // 尺寸不一致时统一缩放到第一帧尺寸
                val frame = scaleIfNeeded(bitmaps[i], width, height)
                frame.getPixels(pixels, 0, width, 0, 0, width, height)
                quantize332(pixels, indices)

                // 每帧：图形控制扩展（延迟）+ 图像描述符 + 局部调色板 + LZW 数据
                writeGraphicControlExtension(out, delayMs)
                writeImageDescriptor(out, width, height)
                writeColorTable(out, palette)
                writeImageData(out, indices)

                if (frame !== bitmaps[i]) frame.recycle()

                val pct = ((i + 1) * 100 / frameCount).coerceIn(0, 100)
                if (pct != lastReported) {
                    lastReported = pct
                    onProgress(pct)
                }
            }
            // 结束标记
            out.write(0x3B)
            out.flush()
            Log.i(TAG, "GIF 编码完成: ${output.absolutePath} ($frameCount 帧, ${width}x${height})")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "GIF 编码失败", e)
            return false
        } finally {
            try { out?.close() } catch (_: Exception) {}
        }
    }

    // ---------------- GIF 结构 ----------------

    /** 文件头：GIF89a */
    private fun writeHeader(out: OutputStream) {
        out.write(byteArrayOf(
            'G'.toByte(), 'I'.toByte(), 'F'.toByte(),
            '8'.toByte(), '9'.toByte(), 'a'.toByte()
        ))
    }

    /**
     * 逻辑屏幕描述符（7 字节）。
     * packed: GCT=1 | 颜色分辨率=8(存 7) | 不排序 | GCT 大小=7(2^(7+1)=256)
     */
    private fun writeLogicalScreenDescriptor(out: OutputStream, width: Int, height: Int) {
        writeShort(out, width)
        writeShort(out, height)
        out.write(0xF7) // 1_111_0_111
        out.write(0)    // 背景色索引
        out.write(0)    // 像素宽高比
    }

    /** 写 256 色调色板（768 字节）。全局与局部调色板格式一致。 */
    private fun writeColorTable(out: OutputStream, palette: ByteArray) {
        out.write(palette)
    }

    /** 图形控制扩展：设置每帧延迟（GIF 内部单位为 1/100 秒）。 */
    private fun writeGraphicControlExtension(out: OutputStream, delayMs: Int) {
        out.write(0x21) // 扩展引导
        out.write(0xF9) // 图形控制标签
        out.write(0x04) // 块大小
        out.write(0x00) // packed：处置方法 0（未指定），无透明
        writeShort(out, (delayMs / 10).coerceAtLeast(1))
        out.write(0x00) // 透明色索引
        out.write(0x00) // 块结束
    }

    /**
     * 图像描述符（10 字节，含分隔符 0x2C）。
     * packed: LCT=1 | 不交错 | 不排序 | LCT 大小=7(256 色)
     */
    private fun writeImageDescriptor(out: OutputStream, width: Int, height: Int) {
        out.write(0x2C) // 图像分隔符
        writeShort(out, 0) // left
        writeShort(out, 0) // top
        writeShort(out, width)
        writeShort(out, height)
        out.write(0x87) // 1_0_0_00_111
    }

    /** 写 16 位小端整数 */
    private fun writeShort(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    // ---------------- 颜色量化 (3-3-2) ----------------

    /** 构建 3-3-2 固定调色板：256 色 * 3 字节 = 768 字节。各通道展开到全范围。 */
    private fun buildPalette332(): ByteArray {
        val palette = ByteArray(256 * 3)
        for (i in 0 until 256) {
            val r3 = (i shr 5) and 0x07
            val g3 = (i shr 2) and 0x07
            val b2 = i and 0x03
            palette[i * 3] = (r3 * 255 / 7).toByte()
            palette[i * 3 + 1] = (g3 * 255 / 7).toByte()
            palette[i * 3 + 2] = (b2 * 255 / 3).toByte()
        }
        return palette
    }

    /** 把 ARGB 像素按 3-3-2 量化为 0..255 的颜色索引，结果写入 [out]。 */
    private fun quantize332(pixels: IntArray, out: ByteArray) {
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // R 高 3 位放高位、G 高 3 位放中位、B 高 2 位放低位
            out[i] = (((r and 0xE0) or ((g and 0xE0) shr 3) or ((b and 0xC0) shr 6)) and 0xFF).toByte()
        }
    }

    /** 尺寸不一致时缩放到目标尺寸（返回新 Bitmap；相同尺寸返回原对象）。 */
    private fun scaleIfNeeded(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    // ---------------- LZW 压缩 ----------------

    /**
     * 写一帧图像数据：LZW 最小码尺寸 + 压缩后的子块序列 + 块结束符。
     * 256 色使用最小码尺寸 8（GIF LZW 最小码尺寸下限为 2，对应最小码表 4；按颜色数取值）。
     */
    private fun writeImageData(out: OutputStream, indices: ByteArray) {
        val minCodeSize = 8
        out.write(minCodeSize)
        val compressed = lzwCompress(indices, minCodeSize)
        var pos = 0
        while (pos < compressed.size) {
            val len = minOf(255, compressed.size - pos)
            out.write(len)
            out.write(compressed, pos, len)
            pos += len
        }
        out.write(0x00) // 子块结束
    }

    /**
     * 标准 GIF LZW 压缩。
     * - 清除码 = 2^minCodeSize，结束码 = 清除码+1，首个可用码 = 结束码+1。
     * - 码宽从 minCodeSize+1 起，达到边界后逐级递增，最大 12 位；字典满时输出清除码并重置。
     * - 输出按 LSB 优先打包成字节流。
     */
    private fun lzwCompress(indices: ByteArray, minCodeSize: Int): ByteArray {
        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        val dict = HashMap<Int, Int>(4096)

        var nextCode = eoiCode + 1
        var codeSize = minCodeSize + 1
        val sink = ByteArrayOutputStream()
        var bitBuf = 0
        var bitCount = 0

        fun emit(code: Int) {
            bitBuf = bitBuf or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                sink.write(bitBuf and 0xFF)
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
        }

        fun resetDict() {
            dict.clear()
            nextCode = eoiCode + 1
            codeSize = minCodeSize + 1
        }

        emit(clearCode)
        if (indices.isEmpty()) {
            emit(eoiCode)
            if (bitCount > 0) sink.write(bitBuf and 0xFF)
            return sink.toByteArray()
        }

        // 当前串对应的码：首像素的隐式码即其像素值（0..clearCode-1）
        var w = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = (w shl 8) or k
            val found = dict[key]
            if (found != null) {
                w = found
            } else {
                emit(w)
                if (nextCode < 0x1000) { // 4096：字典未满，加入新串
                    dict[key] = nextCode
                    nextCode++
                    if (nextCode > (1 shl codeSize) - 1 && codeSize < 12) {
                        codeSize++
                    }
                } else { // 字典满：输出清除码并重置
                    emit(clearCode)
                    resetDict()
                }
                w = k
            }
        }
        emit(w)
        emit(eoiCode)
        if (bitCount > 0) sink.write(bitBuf and 0xFF)
        return sink.toByteArray()
    }
}
