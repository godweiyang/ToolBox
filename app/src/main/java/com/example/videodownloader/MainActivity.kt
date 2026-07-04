package com.example.videodownloader

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.example.videodownloader.databinding.ActivityMainBinding

/**
 * 工具百宝箱入口：展示所有可用工具的卡片列表。
 * 点击卡片跳转到对应工具的 Activity。
 *
 * 新增工具只需在 [getTools] 里加一项 [Tool]，
 * 再写对应的 Activity 即可，无需改动本类逻辑。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val tools = getTools()
        val adapter = ToolAdapter(tools) { tool ->
            startActivity(tool.launcher(this))
        }
        binding.rvTools.layoutManager = GridLayoutManager(this, 2)
        binding.rvTools.adapter = adapter
    }

    /** 工具清单：新增工具在这里加一行即可 */
    private fun getTools(): List<Tool> = listOf(
        Tool(
            title = getString(R.string.tool_video_downloader_title),
            desc = getString(R.string.tool_video_downloader_desc),
            iconRes = android.R.drawable.ic_media_play,
            bgColorRes = R.color.douyin_red,
            launcher = { ctx -> Intent(ctx, VideoDownloaderActivity::class.java) }
        ),
        Tool(
            title = getString(R.string.tool_qrcode_title),
            desc = getString(R.string.tool_qrcode_desc),
            iconRes = android.R.drawable.ic_menu_camera,
            bgColorRes = R.color.tool_qrcode_bg,
            launcher = { ctx -> Intent(ctx, QrCodeActivity::class.java) }
        )
    )
}
