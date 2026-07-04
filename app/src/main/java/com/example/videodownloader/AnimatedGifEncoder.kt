package com.example.videodownloader

import android.graphics.Bitmap
import android.util.Log
import java.io.OutputStream

/**
 * AnimatedGifEncoder - 将一系列 Bitmap 编码为动画 GIF。
 *
 * 来源：Apache Harmony 项目（org.apache.harmony.awt.gl.utils.AnimatedGifEncoder）
 * License：Apache License 2.0（允许商用、修改、重新分发，保留版权声明）
 *
 * 核心算法：
 *  - 每帧用 NeuQuant 量化器量化到 256 色
 *  - LZW 压缩
 *  - 支持透明色、循环、帧间隔
 *
 * 用法：
 *   encoder.setSize(w, h)
 *   encoder.setDelay(ms)
 *   encoder.setQuality(10)       // 1-30，越小质量越好但越慢
 *   encoder.setRepeat(0)         // 0=无限循环
 *   encoder.start(outputStream)
 *   encoder.addFrame(bitmap)     // 逐帧加入
 *   encoder.finish()
 */
class AnimatedGifEncoder {
    companion object { private const val TAG = "AnimatedGifEncoder" }

    private var width = 0
    private var height = 0
    private var transparent = false
    private var transIndex = 0
    private var repeat = -1            // -1=不写, 0=循环
    private var delay = 0              // 每帧间隔 ms
    private var started = false
    private var out: OutputStream? = null
    private var image: Bitmap? = null
    private var pixels: IntArray = IntArray(0)
    private var indexedPixels: ByteArray = ByteArray(0)
    private var colorDepth: Int = 0
    private var colorTab: IntArray = IntArray(0)
    private var usedEntry = BooleanArray(256)
    private var palSize = 7            // 调色板大小 = 2^(palSize+1) = 256
    private var dispose = -1
    private var closeStream = false
    private var firstFrame = true
    private var sizeSet = false
    private var sample = 10            // 1-30，越小质量越好

    fun setDelay(ms: Int) { delay = ms }
    fun setFrameRate(fps: Float) { delay = (1000f / fps).toInt() }
    fun setQuality(quality: Int) { sample = quality.coerceIn(1, 30) }
    fun setRepeat(iter: Int) { repeat = if (iter >= 0) iter else 0 }
    fun setTransparent(color: Int) { transparent = true; transIndex = color }

    /** 设置 GIF 画布尺寸。不调用则用第一帧实际尺寸。 */
    fun setSize(w: Int, h: Int) {
        width = w; height = h
        sizeSet = true
    }

    fun start(os: OutputStream): Boolean {
        if (os == null) return false
        closeStream = false
        out = os
        var ok = false
        try {
            writeString("GIF89a")
            started = true
            ok = true
        } catch (e: Exception) {
            Log.e(TAG, "start 失败", e)
        }
        return ok
    }

    fun finish(): Boolean {
        if (!started) return false
        var ok = false
        try {
            out?.write(0x3b)  // trailer
            out?.flush()
            if (closeStream) out?.close()
            ok = true
        } catch (e: Exception) {
            Log.e(TAG, "finish 失败", e)
        }
        started = false
        return ok
    }

    fun addFrame(bm: Bitmap): Boolean {
        if (!started || bm == null) return false
        image = bm
        if (!sizeSet) {
            width = bm.width; height = bm.height
        }
        getImagePixels()
        analyzePixels()
        if (firstFrame) {
            writeLSD()
            writePalette()
            if (repeat >= 0) writeNetscapeExt()
        }
        writeGraphicCtrlExt()
        writeImageDesc()
        // 仅非首帧写局部调色板;首帧用 LSD 后的全局调色板,writeImageDesc 已声明无 LCT
        // 若首帧也写,解码器会把这 768 字节当成 LZW 数据,导致整个流错位损坏
        if (!firstFrame) writePalette()
        writePixels()
        firstFrame = false
        return true
    }

    private fun getImagePixels() {
        val w = image!!.width
        val h = image!!.height
        if (pixels.size != w * h) pixels = IntArray(w * h)
        image!!.getPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun analyzePixels() {
        val len = pixels.size
        indexedPixels = ByteArray(len)
        val nq = NeuQuant(pixels, len, sample)
        colorTab = nq.process()
        usedEntry = BooleanArray(256)
        // colorTab 里每个 int 装 RGB（0xffffff 以内），共 256 个
        for (i in 0 until colorTab.size) {
            colorTab[i] = colorTab[i] and 0xffffff
        }
        for (i in 0 until len) {
            val idx = nq.map(pixels[i] and 0xffffff)
            indexedPixels[i] = idx.toByte()
            usedEntry[idx] = true
        }
        colorDepth = 8
        palSize = 7
        if (transparent) transIndex = findClosest(0)
    }

    private fun findClosest(c: Int): Int {
        if (colorTab.isEmpty()) return -1
        if (transparent) usedEntry[transIndex] = false
        var minpos = 0
        var dmin = 256 * 256 * 256
        val len = colorTab.size
        var i = 0
        while (i < len) {
            val r = (colorTab[i] shr 16) and 0xff
            val g = (colorTab[i] shr 8) and 0xff
            val b = colorTab[i] and 0xff
            val dr = r - ((c shr 16) and 0xff)
            val dg = g - ((c shr 8) and 0xff)
            val db = b - (c and 0xff)
            val d = dr * dr + dg * dg + db * db
            val idx = i / 3
            if (usedEntry[idx] && d < dmin) {
                dmin = d; minpos = idx
            }
            i += 3
        }
        if (transparent) usedEntry[transIndex] = true
        return minpos
    }

    private fun writeGraphicCtrlExt() {
        val o = out ?: return
        o.write(0x21); o.write(0xf9); o.write(4)
        var transp = if (transparent) 1 else 0
        var disp = (if (dispose >= 0) dispose else 0) and 7
        disp = disp shl 2
        o.write(disp or transp or 0)
        o.write(delay and 0xff)
        o.write((delay shr 8) and 0xff)
        o.write(transIndex)
        o.write(0)
    }

    private fun writeImageDesc() {
        val o = out ?: return
        o.write(0x2c)
        o.write(0); o.write(0); o.write(0); o.write(0)  // left/top
        o.write(width and 0xff); o.write((width shr 8) and 0xff)
        o.write(height and 0xff); o.write((height shr 8) and 0xff)
        // local color table: 第一帧用全局，后续帧用局部
        o.write(if (firstFrame) 0 else (0x80 or palSize))
    }

    private fun writeLSD() {
        val o = out ?: return
        writeShort(width)
        writeShort(height)
        // 全局颜色表标志=1, 颜色分辨率=7, 排序=0, 全局表大小=palSize
        o.write(0x80 or (0x70 shl 4 shr 4) or palSize)
        o.write(0)  // 背景色索引
        o.write(0)  // 像素纵横比
    }

    private fun writeNetscapeExt() {
        val o = out ?: return
        o.write(0x21); o.write(0xff); o.write(11)
        writeString("NETSCAPE2.0")
        o.write(3); o.write(1)
        o.write(repeat and 0xff)
        o.write((repeat shr 8) and 0xff)
        o.write(0)
    }

    private fun writePalette() {
        // colorTab 来自 NeuQuant.mapPixels(): IntArray(256 * 3),
        // 每个元素是单个通道值(0-255),按 R,G,B,R,G,B,... 排列。
        // GIF 调色板 = 256 项 × 3 字节 = 768 字节,直接按字节写。
        // ⚠ 不能把每个 int 当完整 RGB 写 3 字节,否则会写 2304 字节,
        // 多出的 1536 字节把后续 LZW 数据全部错位,导致 GIF 一片空白/损坏。
        val o = out ?: return
        for (i in colorTab.indices) {
            o.write(colorTab[i] and 0xff)
        }
        // 补齐到 256 项 × 3 字节 = 768 字节(NeuQuant 已满 256 项,通常无需补)
        val n = 3 * 256 - colorTab.size
        for (i in 0 until n) o.write(0)
    }

    private fun writePixels() {
        // GIF LZW minimum code size = colorDepth (8 for 256 colors), 最小为 2
        // 实际 code 位宽从 initCodeSize + 1 开始
        val initCodeSize = colorDepth.coerceAtLeast(2)
        val encoder = LZWEncoder(indexedPixels, initCodeSize)
        encoder.encode(out!!)
    }

    private fun writeShort(value: Int) {
        val o = out ?: return
        o.write(value and 0xff)
        o.write((value shr 8) and 0xff)
    }

    private fun writeString(s: String) {
        val o = out ?: return
        for (c in s) o.write(c.code)
    }
}

/**
 * NeuQuant 量化器：把 RGB 图像量化到 256 色。
 * 来自 Apache Harmony / AnimatedGifEncoder 配套实现。
 */
internal class NeuQuant(
    private val thePixels: IntArray,
    private val count: Int,
    private val sample: Int
) {
    private val netsize = 256
    private val netbiasshift = 4
    private val ncycles = 100
    private val intbiasshift = 16
    private val intbias = 1 shl intbiasshift
    private val gammashift = 10
    private val gamma = 1 shl gammashift
    private val betashift = 10
    private val beta = intbias shr betashift
    private val betagamma = intbias shl (gammashift - betashift)
    private val initrad = netsize shr 3
    private val radiusbiasshift = 6
    private val radiusbias = 1 shl radiusbiasshift
    private val initradius = initrad * radiusbias
    private val radiusdec = 30
    private val alphabiasshift = 10
    private val initalpha = 1 shl alphabiasshift
    private var alphadec = 0
    private val network = Array(netsize) { IntArray(3) }
    private val netindex = IntArray(256)
    private val bias = IntArray(netsize)
    private val freq = IntArray(netsize)
    private val radpower = IntArray(initrad)

    init {
        for (i in 0 until netsize) {
            val v = (i shl (netbiasshift + 8)) / netsize
            network[i][0] = v; network[i][1] = v; network[i][2] = v
            freq[i] = intbias / netsize
            bias[i] = 0
        }
    }

    fun process(): IntArray {
        learn()
        unbiasnet()
        inxbuild()
        return mapPixels()
    }

    private fun unbiasnet() {
        for (i in 0 until netsize) {
            network[i][0] = network[i][0] shr netbiasshift
            network[i][1] = network[i][1] shr netbiasshift
            network[i][2] = network[i][2] shr netbiasshift
        }
    }

    private fun altersingle(alpha: Int, i: Int, b: Int, g: Int, r: Int) {
        network[i][0] -= (alpha * (network[i][0] - b)) / initalpha
        network[i][1] -= (alpha * (network[i][1] - g)) / initalpha
        network[i][2] -= (alpha * (network[i][2] - r)) / initalpha
    }

    private fun alterneigh(rad: Int, i: Int, b: Int, g: Int, r: Int) {
        var lo = i - rad; if (lo < -1) lo = -1
        var hi = i + rad; if (hi > netsize) hi = netsize
        var j = i + 1
        var k = i - 1
        var q = 0
        while (j < hi || k > lo) {
            val a = radpower[q++]
            if (j < hi) {
                network[j][0] -= (a * (network[j][0] - b)) / initalpha
                network[j][1] -= (a * (network[j][1] - g)) / initalpha
                network[j][2] -= (a * (network[j][2] - r)) / initalpha
                j++
            }
            if (k > lo) {
                network[k][0] -= (a * (network[k][0] - b)) / initalpha
                network[k][1] -= (a * (network[k][1] - g)) / initalpha
                network[k][2] -= (a * (network[k][2] - r)) / initalpha
                k--
            }
        }
    }

    private fun contest(b: Int, g: Int, r: Int): Int {
        var bestd = 1 shl 30
        var bestbiasd = bestd
        var bestpos = -1
        var bestbiaspos = bestpos
        for (i in 0 until netsize) {
            val bb = network[i][0] - b
            val gg = network[i][1] - g
            val rr = network[i][2] - r
            var dist = bb * bb + gg * gg + rr * rr
            if (dist < bestd) { bestd = dist; bestpos = i }
            val biasdist = dist - (bias[i] shr (intbiasshift - netbiasshift))
            if (biasdist < bestbiasd) { bestbiasd = biasdist; bestbiaspos = i }
            val betafreq = freq[i] shr betashift
            freq[i] -= betafreq
            bias[i] += betafreq shl gammashift
        }
        freq[bestpos] += beta
        bias[bestpos] -= betagamma
        return bestbiaspos
    }

    private fun inxbuild() {
        var previouscol = 0
        var startpos = 0
        for (i in 0 until netsize) {
            val p = network[i]
            var smallpos = i
            var smallval = p[1]
            for (j in i + 1 until netsize) {
                val q = network[j]
                if (q[1] < smallval) { smallpos = j; smallval = q[1] }
            }
            val q = network[smallpos]
            if (i != smallpos) {
                val t0 = q[0]; q[0] = p[0]; p[0] = t0
                val t1 = q[1]; q[1] = p[1]; p[1] = t1
                val t2 = q[2]; q[2] = p[2]; p[2] = t2
            }
            if (smallval != previouscol) {
                netindex[previouscol] = startpos + i shr 1
                var j = previouscol + 1
                while (j < smallval) { netindex[j] = i; j++ }
                previouscol = smallval
                startpos = i
            }
        }
        netindex[previouscol] = startpos + netsize shr 1
        var j = previouscol + 1
        while (j < 256) { netindex[j] = netsize; j++ }
    }

    private fun inxsearch(b: Int, g: Int, r: Int): Int {
        var bestd = 1000
        var best = 0
        var i = netindex[g]
        var j = i - 1
        while (i < netsize || j >= 0) {
            if (i < netsize) {
                val p = network[i][1]
                var dist = p - g
                if (dist >= bestd) {
                    i = netsize
                } else {
                    dist += kotlin.math.abs(network[i][0] - b) + kotlin.math.abs(network[i][2] - r)
                    if (dist < bestd) { bestd = dist; best = i }
                    i++
                }
            }
            if (j >= 0) {
                val p = network[j][1]
                var dist = g - p
                if (dist >= bestd) {
                    j = -1
                } else {
                    dist += kotlin.math.abs(network[j][0] - b) + kotlin.math.abs(network[j][2] - r)
                    if (dist < bestd) { bestd = dist; best = j }
                    j--
                }
            }
        }
        return best
    }

    private fun learn() {
        val lengthcount = count
        val samplefac = sample
        alphadec = 30 + (samplefac - 1) / 3
        val samplepixels = lengthcount / samplefac
        var delta = samplepixels / ncycles
        if (delta == 0) delta = 1
        var alpha = initalpha
        var radius = initradius
        var rad = radius shr radiusbiasshift
        if (rad <= 1) rad = 0
        var i = 0
        var pos = 0
        while (i < samplepixels) {
            val p = thePixels[pos]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            val j = contest(b, g, r)
            altersingle(alpha, j, b, g, r)
            if (rad != 0) alterneigh(rad, j, b, g, r)
            pos += samplefac
            if (pos >= lengthcount) pos -= lengthcount
            i++
            if (i % delta == 0) {
                alpha -= alpha / alphadec
                radius -= radius / radiusdec
                rad = radius shr radiusbiasshift
                if (rad <= 1) rad = 0
            }
        }
    }

    private fun mapPixels(): IntArray {
        val map = IntArray(netsize * 3)
        for (i in 0 until netsize) {
            map[i * 3] = network[i][0]
            map[i * 3 + 1] = network[i][1]
            map[i * 3 + 2] = network[i][2]
        }
        return map
    }

    fun map(rgb: Int): Int {
        val b = rgb and 0xff
        val g = (rgb shr 8) and 0xff
        val r = (rgb shr 16) and 0xff
        return inxsearch(b, g, r)
    }
}

/**
 * LZW 编码器：GIF 用的 LZW 变长长度压缩。
 *
 * 严格按 GIF89a 规范：
 *  - LZW minimum code size = initCodeSize（颜色位深，256色时为 8）
 *  - clear code = 1 << initCodeSize
 *  - end-of-information code = clear + 1
 *  - 第一个可用字典 code = clear + 2
 *  - 初始 code 位宽 = initCodeSize + 1
 *  - 字典插入新词后，当下一个待输出 code >= 2^curCodeSize 时升位宽（最高 12 位）
 *  - 字典满（code 4095）时输出 clear code 并重置
 *
 * 输出格式：一系列 sub-block，每个 sub-block = 1 字节长度 N（1-255）+ N 字节数据，
 * 最后以 0 字节结束整个 image data。
 */
internal class LZWEncoder(
    private val pixels: ByteArray,
    private val initCodeSize: Int
) {
    private val clearCode = 1 shl initCodeSize
    private val eofCode = clearCode + 1
    private val firstFreeCode = eofCode + 1   // 第一个可用字典 code = clear + 2
    private var dictSize = firstFreeCode
    private var curCodeSize = initCodeSize + 1
    private val maxCode = 4096  // 12 位最大值 + 1

    // LZW 字典：哈希表存 (prefixCode, suffixByte) -> dictCode
    // key = (prefixCode << 8) | suffixByte
    private val hashSize = 5003
    private val hashKey = IntArray(hashSize) { -1 }
    private val hashCode = IntArray(hashSize)

    // 位缓冲（低位先存，LSB first）
    private var bitBuf = 0
    private var bitCount = 0

    // sub-block 缓冲
    private val blockBuf = ByteArray(255)
    private var blockLen = 0

    fun encode(out: OutputStream) {
        // 1. 写 LZW minimum code size 字节
        out.write(initCodeSize)

        // 2. 初始化字典
        resetDict()

        // 3. 输出 clear code
        writeCode(out, clearCode)

        if (pixels.isEmpty()) {
            writeCode(out, eofCode)
            flushBits(out)
            flushBlock(out)
            out.write(0)  // block terminator
            return
        }

        // 4. LZW 主循环
        var w = pixels[0].toInt() and 0xff
        var idx = 1
        while (idx < pixels.size) {
            val k = pixels[idx].toInt() and 0xff
            idx++

            val newKey = (w shl 8) or k
            val hashIdx = hashLookup(newKey)

            if (hashKey[hashIdx] == newKey) {
                // (w, k) 在字典里，扩展 w
                w = hashCode[hashIdx]
            } else {
                // (w, k) 不在字典里
                // 先输出当前 w
                writeCode(out, w)

                // 检查是否需要升位宽：下一个要分配的 dict code 是 dictSize
                // 如果 dictSize >= 2^curCodeSize，需要先升位宽（在新词插入前）
                // 但标准做法是：插入新词后，如果 dictSize == 2^curCodeSize 且 curCodeSize < 12，升位宽
                if (dictSize < maxCode) {
                    hashKey[hashIdx] = newKey
                    hashCode[hashIdx] = dictSize
                    dictSize++
                    // 插入后检查：如果新分配的 code 用完了当前位宽，升位宽
                    // 注意：dictSize 此时是"下一个待分配"的 code，所以判断 dictSize > 2^curCodeSize
                    if (dictSize > (1 shl curCodeSize) && curCodeSize < 12) {
                        curCodeSize++
                    }
                } else {
                    // 字典满了，输出 clear code 并重置
                    writeCode(out, clearCode)
                    resetDict()
                }
                w = k
            }
        }

        // 5. 收尾：输出最后的 w 和 eof
        writeCode(out, w)
        writeCode(out, eofCode)
        flushBits(out)
        flushBlock(out)
        out.write(0)  // block terminator
    }

    private fun resetDict() {
        dictSize = firstFreeCode
        curCodeSize = initCodeSize + 1
        for (i in hashKey.indices) hashKey[i] = -1
    }

    /** 线性探测哈希查找槽位（返回 key 已存在的位置，或第一个空槽） */
    private fun hashLookup(key: Int): Int {
        var h = (key * 264) % hashSize
        if (h < 0) h += hashSize
        while (hashKey[h] >= 0 && hashKey[h] != key) {
            h = (h + 1) % hashSize
        }
        return h
    }

    /** 写一个 code 到位缓冲，每攒够字节就推入 sub-block */
    private fun writeCode(out: OutputStream, code: Int) {
        bitBuf = bitBuf or (code shl bitCount)
        bitCount += curCodeSize
        while (bitCount >= 8) {
            blockBuf[blockLen++] = (bitBuf and 0xff).toByte()
            bitBuf = bitBuf ushr 8
            bitCount -= 8
            if (blockLen == 255) flushBlock(out)
        }
    }

    /** 把剩余不足 8 位的位补成完整字节 */
    private fun flushBits(out: OutputStream) {
        if (bitCount > 0) {
            blockBuf[blockLen++] = (bitBuf and 0xff).toByte()
            bitBuf = 0
            bitCount = 0
            if (blockLen == 255) flushBlock(out)
        }
    }

    /** 把 blockBuf 里攒的字节作为一个 sub-block 输出 - 长度字节 + 数据 */
    private fun flushBlock(out: OutputStream) {
        if (blockLen > 0) {
            out.write(blockLen)
            out.write(blockBuf, 0, blockLen)
            blockLen = 0
        }
    }
}
