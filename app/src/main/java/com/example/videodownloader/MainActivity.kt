package com.example.videodownloader

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.videodownloader.databinding.ActivityMainBinding
import com.example.videodownloader.databinding.DialogAppUpdateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        binding.btnCheckUpdate.setOnClickListener { checkForUpdates(manual = true) }
        checkForUpdates(manual = false)
    }

    private fun checkForUpdates(manual: Boolean) {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = getString(R.string.update_checking)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AppUpdater.fetchLatest() } }
            binding.btnCheckUpdate.isEnabled = true
            binding.btnCheckUpdate.text = getString(R.string.update_check)
            result.onSuccess { info ->
                getSharedPreferences("updates", MODE_PRIVATE).edit()
                    .putLong("last_check", System.currentTimeMillis()).apply()
                if (AppVersions.isNewer(info.tag, getVersionName())) {
                    binding.btnCheckUpdate.text = info.tag
                    showUpdateDialog(info)
                } else {
                    binding.btnCheckUpdate.text = getString(R.string.update_latest)
                    if (manual) showLatestDialog(info)
                }
            }.onFailure {
                if (manual) Toast.makeText(this@MainActivity,
                    R.string.update_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUpdateDialog(info: ReleaseInfo) {
        val content = DialogAppUpdateBinding.inflate(layoutInflater)
        content.tvUpdateTitle.text = getString(R.string.update_available, info.tag)
        content.tvUpdateSubtitle.text = getString(R.string.update_current, "v${getVersionName()}")
        content.tvUpdateNotes.text = info.notes.ifBlank { getString(R.string.update_notes_empty) }
        val dialog = AlertDialog.Builder(this).setView(content.root)
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_open_browser) { _, _ -> AppUpdater.openBrowser(this, info.pageUrl) }
            .setPositiveButton(R.string.update_download_install, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!canRequestPackageInstalls()) {
                    requestInstallPermission()
                    Toast.makeText(this, R.string.update_install_permission, Toast.LENGTH_LONG).show()
                } else downloadAndInstall(info, dialog, content)
            }
        }
        dialog.show()
    }

    private fun showLatestDialog(info: ReleaseInfo) {
        val content = DialogAppUpdateBinding.inflate(layoutInflater)
        content.tvUpdateIcon.text = "✓"
        content.tvUpdateTitle.text = getString(R.string.update_latest)
        content.tvUpdateSubtitle.text = getString(R.string.update_latest_detail, "v${getVersionName()}")
        content.tvUpdateNotes.text = info.notes.ifBlank { getString(R.string.update_notes_empty) }
        val dialog = AlertDialog.Builder(this).setView(content.root)
            .setPositiveButton(R.string.update_close, null).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
        dialog.show()
    }

    private fun downloadAndInstall(info: ReleaseInfo, dialog: AlertDialog,
                                   content: DialogAppUpdateBinding) {
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        button.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppUpdater.downloadApk(this@MainActivity, info) { progress ->
                    runOnUiThread {
                        button.text = getString(R.string.update_downloading, progress)
                        content.tvUpdateSubtitle.text = getString(R.string.update_downloading, progress)
                    }
                } }
            }
            result.onSuccess { apk ->
                val archive = runCatching { packageManager.getPackageArchiveInfo(apk.absolutePath, 0) }.getOrNull()
                if (archive?.packageName != packageName) {
                    apk.delete()
                    Toast.makeText(this@MainActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                    AppUpdater.openBrowser(this@MainActivity, info.pageUrl)
                    button.isEnabled = true
                    button.text = getString(R.string.update_download_install)
                    return@onSuccess
                }
                runCatching { AppUpdater.install(this@MainActivity, apk) }.onFailure {
                    AppUpdater.openBrowser(this@MainActivity, info.pageUrl)
                }
                dialog.dismiss()
            }.onFailure {
                button.isEnabled = true
                button.text = getString(R.string.update_download_install)
                Toast.makeText(this@MainActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                AppUpdater.openBrowser(this@MainActivity, info.pageUrl)
            }
        }
    }

    private fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")))
        }
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
                    viewHolder.itemView.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
                    viewHolder.itemView.elevation = 18f
                    viewHolder.itemView.z = 18f
                }
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                // 松手：还原
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                viewHolder.itemView.elevation = 0f
                viewHolder.itemView.z = 0f
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
            iconBgRes = R.drawable.icon_grad_video,
            launcher = { ctx -> Intent(ctx, VideoDownloaderActivity::class.java) }
        ),
        Tool(
            id = "qrcode",
            title = getString(R.string.tool_qrcode_title),
            desc = getString(R.string.tool_qrcode_desc),
            iconRes = android.R.drawable.ic_menu_camera,
            iconBgRes = R.drawable.icon_grad_qr,
            launcher = { ctx -> Intent(ctx, QrCodeActivity::class.java) }
        ),
        Tool(
            id = "video_to_gif",
            title = getString(R.string.tool_video_to_gif_title),
            desc = getString(R.string.tool_video_to_gif_desc),
            iconRes = android.R.drawable.ic_menu_gallery,
            iconBgRes = R.drawable.icon_grad_gif,
            launcher = { ctx -> Intent(ctx, VideoToGifActivity::class.java) }
        ),
        Tool(
            id = "ninegrid",
            title = getString(R.string.tool_ninegrid_title),
            desc = getString(R.string.tool_ninegrid_desc),
            iconRes = android.R.drawable.ic_menu_crop,
            iconBgRes = R.drawable.icon_grad_grid,
            launcher = { ctx -> Intent(ctx, NineGridActivity::class.java) }
        ),
        Tool(
            id = "gifreverse",
            title = getString(R.string.tool_gifreverse_title),
            desc = getString(R.string.tool_gifreverse_desc),
            iconRes = android.R.drawable.ic_menu_rotate,
            iconBgRes = R.drawable.icon_grad_reverse,
            launcher = { ctx -> Intent(ctx, GifReverseActivity::class.java) }
        ),
        Tool(
            id = "decibel",
            title = getString(R.string.tool_decibel_title),
            desc = getString(R.string.tool_decibel_desc),
            iconRes = android.R.drawable.ic_btn_speak_now,
            iconBgRes = R.drawable.icon_grad_decibel,
            launcher = { ctx -> Intent(ctx, DecibelMeterActivity::class.java) }
        ),
        Tool(
            id = "wifi_signal",
            title = getString(R.string.tool_wifi_title),
            desc = getString(R.string.tool_wifi_desc),
            iconRes = android.R.drawable.ic_menu_compass,
            iconBgRes = R.drawable.icon_grad_wifi,
            launcher = { ctx -> Intent(ctx, WifiSignalActivity::class.java) }
        ),
        Tool(
            id = "fileshare",
            title = getString(R.string.tool_fileshare_title),
            desc = getString(R.string.tool_fileshare_desc),
            iconRes = android.R.drawable.stat_sys_upload,
            iconBgRes = R.drawable.icon_grad_share,
            launcher = { ctx -> Intent(ctx, FileShareActivity::class.java) }
        ),
        Tool(
            id = "metal_detector",
            title = getString(R.string.tool_metal_title),
            desc = getString(R.string.tool_metal_desc),
            iconRes = android.R.drawable.ic_menu_compass,
            iconBgRes = R.drawable.icon_grad_metal,
            launcher = { ctx -> Intent(ctx, MetalDetectorActivity::class.java) }
        ),
        Tool(
            id = "battery_info",
            title = getString(R.string.tool_battery_title),
            desc = getString(R.string.tool_battery_desc),
            iconRes = android.R.drawable.ic_lock_idle_charging,
            iconBgRes = R.drawable.icon_grad_battery,
            launcher = { ctx -> Intent(ctx, BatteryInfoActivity::class.java) }
        ),
        Tool(
            id = "gnss_sky",
            title = getString(R.string.tool_gnss_title),
            desc = getString(R.string.tool_gnss_desc),
            iconRes = android.R.drawable.ic_menu_mylocation,
            iconBgRes = R.drawable.icon_grad_gnss,
            launcher = { ctx -> Intent(ctx, GnssSkyActivity::class.java) }
        ),
        Tool(
            id = "photo_frame",
            title = getString(R.string.tool_photoframe_title),
            desc = getString(R.string.tool_photoframe_desc),
            iconRes = android.R.drawable.ic_menu_gallery,
            iconBgRes = R.drawable.icon_grad_frame,
            launcher = { ctx -> Intent(ctx, PhotoFrameActivity::class.java) }
        )
    )
}
