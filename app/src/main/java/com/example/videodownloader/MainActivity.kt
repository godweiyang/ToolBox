package com.example.videodownloader

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.videodownloader.databinding.ActivityMainBinding

/**
 * 工具百宝箱入口：展示所有可用工具的卡片列表。
 * 点击卡片跳转到对应工具的 Activity。
 *
 * 新增工具只需在 [getTools] 里加一项 [Tool]，
 * 再写对应的 Activity 即可，无需改动本类逻辑。
 *
 * 长按卡片可拖拽排序，顺序持久化到 SharedPreferences。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ToolAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 取默认工具列表
        val defaultTools = getTools().toMutableList()
        // 2. 按持久化顺序重排（用户拖拽过的顺序）
        val orderedTools = applyPersistedOrder(defaultTools)

        adapter = ToolAdapter(orderedTools) { tool ->
            startActivity(tool.launcher(this))
        }
        binding.rvTools.layoutManager = GridLayoutManager(this, 2)
        binding.rvTools.adapter = adapter

        // 3. 配置长按拖拽
        setupDragSort()

        // 底部显示版本号，方便用户确认当前安装的版本
        binding.tvVersion.text = "v${getVersionName()}"
    }

    /**
     * 配置 ItemTouchHelper 实现长按拖拽排序。
     * - 支持上下左右四个方向移动（适配 2 列网格）
     * - 拖拽中放大 + 提升阴影
     * - 松手时持久化新顺序
     */
    private fun setupDragSort() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0  // 不支持滑动删除
        ) {
            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // no-op：不支持滑动
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    // 拖拽中：放大 + 提升阴影
                    viewHolder.itemView.animate().scaleX(1.08f).scaleY(1.08f).setDuration(100).start()
                    viewHolder.itemView.elevation = 16f
                }
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                // 松手：还原
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                viewHolder.itemView.elevation = 0f
                // 持久化新顺序
                saveOrder(adapter.getOrderIds())
            }

            override fun isLongPressDragEnabled(): Boolean = false  // 我们自己处理长按

            override fun isItemViewSwipeEnabled(): Boolean = false
        }

        val itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(binding.rvTools)

        // 把长按事件路由到 ItemTouchHelper
        adapter.setDragListener { vh ->
            itemTouchHelper.startDrag(vh)
        }
    }

    /** 读取持久化的工具顺序，应用到默认列表上 */
    private fun applyPersistedOrder(defaultTools: MutableList<Tool>): MutableList<Tool> {
        val prefs = getSharedPreferences("tool_order", MODE_PRIVATE)
        val saved = prefs.getString("order", null) ?: return defaultTools
        val savedIds = saved.split(",").filter { it.isNotBlank() }
        if (savedIds.isEmpty()) return defaultTools

        // 按 savedIds 顺序排，未在列表中的工具追加到末尾
        val byId = defaultTools.associateBy { it.id }.toMutableMap()
        val result = mutableListOf<Tool>()
        for (id in savedIds) {
            byId.remove(id)?.let { result.add(it) }
        }
        // 新增的工具（用户更新 App 后可能多出来）追加到末尾
        result.addAll(byId.values)
        return result
    }

    /** 保存工具顺序到 SharedPreferences */
    private fun saveOrder(ids: List<String>) {
        getSharedPreferences("tool_order", MODE_PRIVATE)
            .edit()
            .putString("order", ids.joinToString(","))
            .apply()
    }

    /** 从 PackageInfo 读取 versionName，避免硬编码 */
    private fun getVersionName(): String = try {
        val pm = packageManager
        val pkgInfo = pm.getPackageInfo(packageName, 0)
        pkgInfo.versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    /** 工具清单：新增工具在这里加一行即可（id 必须唯一且稳定） */
    private fun getTools(): List<Tool> = listOf(
        Tool(
            id = "video_downloader",
            title = getString(R.string.tool_video_downloader_title),
            desc = getString(R.string.tool_video_downloader_desc),
            iconRes = android.R.drawable.ic_media_play,
            bgColorRes = R.color.douyin_red,
            launcher = { ctx -> Intent(ctx, VideoDownloaderActivity::class.java) }
        ),
        Tool(
            id = "qrcode",
            title = getString(R.string.tool_qrcode_title),
            desc = getString(R.string.tool_qrcode_desc),
            iconRes = android.R.drawable.ic_menu_camera,
            bgColorRes = R.color.tool_qrcode_bg,
            launcher = { ctx -> Intent(ctx, QrCodeActivity::class.java) }
        ),
        Tool(
            id = "video_to_gif",
            title = getString(R.string.tool_video_to_gif_title),
            desc = getString(R.string.tool_video_to_gif_desc),
            iconRes = android.R.drawable.ic_menu_gallery,
            bgColorRes = R.color.tool_qrcode_bg,
            launcher = { ctx -> Intent(ctx, VideoToGifActivity::class.java) }
        ),
        Tool(
            id = "ninegrid",
            title = getString(R.string.tool_ninegrid_title),
            desc = getString(R.string.tool_ninegrid_desc),
            iconRes = android.R.drawable.ic_menu_crop,
            bgColorRes = R.color.tool_ninegrid_bg,
            launcher = { ctx -> Intent(ctx, NineGridActivity::class.java) }
        ),
        Tool(
            id = "gifreverse",
            title = getString(R.string.tool_gifreverse_title),
            desc = getString(R.string.tool_gifreverse_desc),
            iconRes = android.R.drawable.ic_menu_rotate,
            bgColorRes = R.color.tool_gifreverse_bg,
            launcher = { ctx -> Intent(ctx, GifReverseActivity::class.java) }
        ),
        Tool(
            id = "decibel",
            title = getString(R.string.tool_decibel_title),
            desc = getString(R.string.tool_decibel_desc),
            iconRes = android.R.drawable.ic_btn_speak_now,
            bgColorRes = R.color.tool_decibel_bg,
            launcher = { ctx -> Intent(ctx, DecibelMeterActivity::class.java) }
        ),
        Tool(
            id = "wifi_signal",
            title = getString(R.string.tool_wifi_title),
            desc = getString(R.string.tool_wifi_desc),
            iconRes = android.R.drawable.ic_menu_compass,
            bgColorRes = R.color.tool_wifi_bg,
            launcher = { ctx -> Intent(ctx, WifiSignalActivity::class.java) }
        ),
        Tool(
            id = "fileshare",
            title = getString(R.string.tool_fileshare_title),
            desc = getString(R.string.tool_fileshare_desc),
            iconRes = android.R.drawable.stat_sys_upload,
            bgColorRes = R.color.tool_fileshare_bg,
            launcher = { ctx -> Intent(ctx, FileShareActivity::class.java) }
        ),
        Tool(
            id = "metal_detector",
            title = getString(R.string.tool_metal_title),
            desc = getString(R.string.tool_metal_desc),
            iconRes = android.R.drawable.ic_menu_compass,
            bgColorRes = R.color.tool_metal_bg,
            launcher = { ctx -> Intent(ctx, MetalDetectorActivity::class.java) }
        )
    )
}
