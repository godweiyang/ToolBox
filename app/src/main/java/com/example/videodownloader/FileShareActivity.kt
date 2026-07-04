package com.example.videodownloader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.videodownloader.databinding.ActivityFileShareBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 局域网文件互传工具。
 *
 * - 启动 [FileShareService] 开 HTTP server
 * - 显示访问地址二维码（方便电脑扫码）
 * - 显示接收到的文件列表
 *
 * 接收文件回调通过 [FileShareService.onFileReceived] 静态字段传入，
 * service 收到文件后调用，Activity 在主线程刷新 UI。
 */
class FileShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileShareBinding
    private val handler = Handler(Looper.getMainLooper())
    private val receivedFiles = ArrayList<FileShareServer.SharedFile>()
    private lateinit var adapter: ReceivedFileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ReceivedFileAdapter(receivedFiles)
        binding.rvReceived.layoutManager = LinearLayoutManager(this)
        binding.rvReceived.adapter = adapter

        binding.btnToggle.setOnClickListener {
            if (FileShareService.isRunning) {
                stopService()
            } else {
                startService()
            }
        }

        // 注册接收文件回调
        FileShareService.onFileReceived = { name, size ->
            handler.post {
                refreshReceivedList()
                toast("已接收:$name")
            }
        }

        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        FileShareService.onFileReceived = null
    }

    override fun onResume() {
        super.onResume()
        refreshReceivedList()
        updateUI()
    }

    private fun startService() {
        val intent = Intent(this, FileShareService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // 等待 service 起来后刷新 UI
        handler.postDelayed({ updateUI() }, 500)
    }

    private fun stopService() {
        stopService(Intent(this, FileShareService::class.java))
        handler.postDelayed({ updateUI() }, 300)
    }

    private fun updateUI() {
        val running = FileShareService.isRunning
        binding.tvStatus.text = if (running) getString(R.string.fs_status_on) else getString(R.string.fs_status_off)
        binding.tvStatus.setTextColor(if (running) 0xFF388E3C.toInt() else 0xFF888888.toInt())
        binding.btnToggle.text = if (running) getString(R.string.fs_btn_stop) else getString(R.string.fs_btn_start)

        if (running) {
            val ip = FileShareService.serverInstance?.getServiceIpAddress()
            val addr = if (ip != null) "http://$ip:${FileShareService.PORT}" else null
            if (addr != null) {
                binding.tvAddress.text = addr
                binding.tvAddress.visibility = View.VISIBLE
                val qr = generateQrCode(addr, 480)
                if (qr != null) {
                    binding.ivQrCode.setImageBitmap(qr)
                    binding.ivQrCode.visibility = View.VISIBLE
                }
            } else {
                binding.tvAddress.text = "无法获取 IP,请检查 WiFi"
                binding.tvAddress.visibility = View.VISIBLE
                binding.ivQrCode.visibility = View.GONE
            }
        } else {
            binding.tvAddress.visibility = View.GONE
            binding.ivQrCode.visibility = View.GONE
        }
    }

    private fun refreshReceivedList() {
        receivedFiles.clear()
        // 直接调 server 的公开方法，不再用反射
        val list = FileShareService.serverInstance?.listSharedFilesPublic()
        if (list != null) receivedFiles.addAll(list)
        adapter.notifyDataSetChanged()
        binding.tvReceivedCount.text = if (receivedFiles.isEmpty()) {
            getString(R.string.fs_no_files)
        } else {
            getString(R.string.fs_received_count, receivedFiles.size)
        }
    }

    /** 生成访问地址二维码 */
    private fun generateQrCode(content: String, sizePx: Int): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1, hints)
            val moduleCount = matrix.width
            val moduleSize = sizePx / moduleCount
            val realSize = moduleSize * moduleCount
            val bmp = Bitmap.createBitmap(realSize, realSize, Bitmap.Config.ARGB_8888)
            for (y in 0 until moduleCount) {
                for (x in 0 until moduleCount) {
                    if (matrix.get(x, y)) {
                        for (i in 0 until moduleSize) {
                            for (j in 0 until moduleSize) {
                                bmp.setPixel(x * moduleSize + i, y * moduleSize + j, Color.BLACK)
                            }
                        }
                    }
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 接收文件列表适配器 */
    private class ReceivedFileAdapter(
        private val items: List<FileShareServer.SharedFile>
    ) : RecyclerView.Adapter<ReceivedFileAdapter.VH>() {

        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                setPadding(12, 12, 12, 12)
                textSize = 13f
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = items[position]
            holder.tv.text = "${f.name}  (${formatSize(f.size)})"
        }

        override fun getItemCount(): Int = items.size

        private fun formatSize(b: Long): String {
            if (b < 1024) return "$b B"
            if (b < 1048576) return "%.1f KB".format(b / 1024.0)
            if (b < 1073741824) return "%.1f MB".format(b / 1048576.0)
            return "%.2f GB".format(b / 1073741824.0)
        }
    }
}
