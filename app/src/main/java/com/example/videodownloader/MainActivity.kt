package com.example.videodownloader

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Typeface
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.videodownloader.databinding.ActivityMainBinding
import com.example.videodownloader.databinding.DialogAppUpdateBinding
import com.example.videodownloader.web.WebToolRegistry
import com.example.videodownloader.web.WebViewShellActivity
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
    private lateinit var gridLayoutManager: GridLayoutManager
    private var gridColumns: Int = GridMetrics.DEFAULT_COLUMNS

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
        val prefs = getSharedPreferences(HOME_PREFS, MODE_PRIVATE)
        gridColumns = if (prefs.contains(KEY_GRID_COLUMNS))
            GridMetrics.normalizeColumns(prefs.getInt(KEY_GRID_COLUMNS, GridMetrics.DEFAULT_COLUMNS))
        else GridMetrics.DEFAULT_COLUMNS
        gridLayoutManager = GridLayoutManager(this, gridColumns)
        binding.rvTools.layoutManager = gridLayoutManager
        binding.rvTools.adapter = adapter
        adapter.setPresentation(GridMetrics.presentation(gridColumns))

        // 顶部汉堡按钮 + 左侧抽屉（Gmail 风格），抽屉宽度为屏宽 82%、最大 320dp
        binding.btnOpenDrawer.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        val dm = resources.displayMetrics
        val drawerWidth = minOf((dm.widthPixels * 0.82).toInt(), (320 * dm.density).toInt())
        binding.drawerHome.root.layoutParams =
            binding.drawerHome.root.layoutParams.apply { width = drawerWidth }
        setupDrawer()

        // 长按拖拽排序
        setupDragSort()

        // 抽屉底部显示当前版本
        binding.drawerHome.tvDrawerVersion.text = "v${getVersionName()}"
        checkForUpdates(manual = false)
    }

    private fun setupDrawer() {
        val drawer = binding.drawerHome
        val chips = mapOf(
            2 to drawer.chipD2, 3 to drawer.chipD3,
            4 to drawer.chipD4, 5 to drawer.chipD5
        )
        chips.forEach { (columns, view) ->
            view.contentDescription = getString(R.string.home_layout_columns, columns)
            view.setOnClickListener { applyGridColumns(columns, persist = true) }
        }
        applyGridColumns(gridColumns, persist = false)

        // 首页布局：展开/收起列数选择
        drawer.rowDrawerLayoutHead.setOnClickListener {
            val expanded = drawer.panelDrawerColumns.visibility == View.VISIBLE
            drawer.panelDrawerColumns.visibility = if (expanded) View.GONE else View.VISIBLE
            drawer.ivDrawerLayoutChevron.animate()
                .rotation(if (expanded) 0f else 180f).setDuration(180).start()
        }
        drawer.rowDrawerUpdate.setOnClickListener { checkForUpdates(manual = true) }
    }

    private fun applyGridColumns(columnsInput: Int, persist: Boolean) {
        val columns = GridMetrics.normalizeColumns(columnsInput)
        gridColumns = columns
        gridLayoutManager.spanCount = columns
        adapter.setPresentation(GridMetrics.presentation(columns))
        val drawer = binding.drawerHome
        listOf(drawer.chipD2, drawer.chipD3, drawer.chipD4, drawer.chipD5)
            .forEachIndexed { index, chip -> chip.isSelected = index + 2 == columns }
        binding.rvTools.itemAnimator = null
        if (persist) getSharedPreferences(HOME_PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_GRID_COLUMNS, columns).apply()
    }

    private fun checkForUpdates(manual: Boolean) {
        val drawer = binding.drawerHome
        drawer.rowDrawerUpdate.isEnabled = false
        drawer.tvDrawerUpdateStatus.apply {
            setText(R.string.update_checking)
            setTextColor(0xFF9AA0AD.toInt())
            setTypeface(typeface, Typeface.NORMAL)
        }
        drawer.ivDrawerAboutIcon.setColorFilter(0xFF8A8E99.toInt())
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AppUpdater.fetchLatest() } }
            drawer.rowDrawerUpdate.isEnabled = true
            result.onSuccess { info ->
                getSharedPreferences("updates", MODE_PRIVATE).edit()
                    .putLong("last_check", System.currentTimeMillis()).apply()
                if (AppVersions.isNewer(info.tag, getVersionName())) {
                    // 有新版本：整行高亮（紫色图标 + 紫色加粗状态）
                    drawer.ivDrawerAboutIcon.setColorFilter(0xFF5856D6.toInt())
                    drawer.tvDrawerUpdateStatus.apply {
                        text = getString(R.string.drawer_status_newer, info.tag)
                        setTextColor(0xFF5856D6.toInt())
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    showUpdateDialog(info)
                } else {
                    drawer.ivDrawerAboutIcon.setColorFilter(0xFF8A8E99.toInt())
                    drawer.tvDrawerUpdateStatus.apply {
                        setText(R.string.drawer_status_latest)
                        setTextColor(0xFF9AA0AD.toInt())
                        setTypeface(typeface, Typeface.NORMAL)
                    }
                    if (manual) showLatestDialog()
                }
            }.onFailure {
                drawer.ivDrawerAboutIcon.setColorFilter(0xFF8A8E99.toInt())
                drawer.tvDrawerUpdateStatus.apply {
                    setText(R.string.drawer_status_failed)
                    setTextColor(0xFF9AA0AD.toInt())
                    setTypeface(typeface, Typeface.NORMAL)
                }
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
        val dialog = AlertDialog.Builder(this).setView(content.root).create()
        dialog.setOnShowListener { sizeUpdateDialog(dialog) }
        content.btnUpdateGo.setOnClickListener {
            if (!canRequestPackageInstalls()) {
                requestInstallPermission()
                Toast.makeText(this, R.string.update_install_permission, Toast.LENGTH_LONG).show()
            } else downloadAndInstall(info, dialog, content)
        }
        content.btnUpdateBrowser.setOnClickListener {
            AppUpdater.openBrowser(this, info.pageUrl); dialog.dismiss()
        }
        content.btnUpdateLater.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showLatestDialog() {
        // 已是最新版：极简提示，不展示更新日志
        AlertDialog.Builder(this)
            .setMessage(R.string.update_latest)
            .setPositiveButton(R.string.update_close, null)
            .show()
    }

    /** 弹窗尺寸：手机上不超过屏宽 88%、最大 340dp，背景透明以显示自定义圆角卡片。 */
    private fun sizeUpdateDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val dm = resources.displayMetrics
        val maxWidthPx = (340 * dm.density).toInt()
        val width = minOf((dm.widthPixels * 0.88).toInt(), maxWidthPx)
        dialog.window?.setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun downloadAndInstall(info: ReleaseInfo, dialog: AlertDialog,
                                   content: DialogAppUpdateBinding) {
        val go = content.btnUpdateGo
        go.isEnabled = false
        content.btnUpdateBrowser.isEnabled = false
        content.btnUpdateLater.isEnabled = false
        content.pbUpdate.apply { visibility = View.VISIBLE; isIndeterminate = true }
        content.tvUpdateProgress.visibility = View.VISIBLE
        content.tvUpdateSubtitle.setText(R.string.update_preparing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AppUpdater.downloadApk(this@MainActivity, info) { copied, total, speed ->
                        runOnUiThread { renderDownloadProgress(content, copied, total, speed) }
                    }
                }
            }
            result.onSuccess { apk ->
                val archive = runCatching {
                    packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                }.getOrNull()
                if (archive?.packageName != packageName) {
                    apk.delete()
                    resetDownloadUi(content)
                    Toast.makeText(this@MainActivity,
                        R.string.update_download_failed, Toast.LENGTH_LONG).show()
                    return@onSuccess
                }
                val installOk = runCatching {
                    AppUpdater.install(this@MainActivity, apk)
                }.isSuccess
                if (installOk) dialog.dismiss() else {
                    resetDownloadUi(content)
                    Toast.makeText(this@MainActivity,
                        R.string.update_download_failed, Toast.LENGTH_LONG).show()
                }
            }.onFailure {
                resetDownloadUi(content)
                Toast.makeText(this@MainActivity,
                    R.string.update_download_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 刷新下载进度：已知总大小走百分比进度条，未知则走不确定动画 + 已下载 MB。 */
    private fun renderDownloadProgress(
        content: DialogAppUpdateBinding, copied: Long, total: Long, speed: Long
    ) {
        val mb = copied / 1048576.0
        val speedMb = speed / 1048576.0
        if (total > 0) {
            content.pbUpdate.isIndeterminate = false
            content.pbUpdate.progress = (copied * 100 / total).toInt().coerceIn(0, 100)
            content.tvUpdateProgress.text = getString(
                R.string.update_progress_format, mb, total / 1048576.0, speedMb
            )
            content.btnUpdateGo.text = getString(
                R.string.update_downloading, (copied * 100 / total).toInt()
            )
        } else {
            content.pbUpdate.isIndeterminate = true
            content.tvUpdateProgress.text = getString(
                R.string.update_progress_format_nototal, mb, speedMb
            )
            content.btnUpdateGo.setText(R.string.update_downloading_nototal)
        }
    }

    /** 下载失败后恢复按钮状态（主按钮变为重试），不自动跳转浏览器。 */
    private fun resetDownloadUi(content: DialogAppUpdateBinding) {
        content.btnUpdateGo.isEnabled = true
        content.btnUpdateGo.setText(R.string.update_retry)
        content.btnUpdateBrowser.isEnabled = true
        content.btnUpdateLater.isEnabled = true
        content.pbUpdate.visibility = View.GONE
        content.tvUpdateProgress.visibility = View.GONE
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

        // 顺序合并抽成纯逻辑（ToolOrder.reconcile，有单测），再映射回 Tool 对象
        val orderedIds = ToolOrder.reconcile(defaultTools.map { it.id }, savedIds)
        val byId = defaultTools.associateBy { it.id }
        return orderedIds.mapNotNull { byId[it] }.toMutableList()
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
    ).let { base ->
        // 三个离线 Web 工具（lol / pubg / fangdai），共用 WebView 外壳
        base + WebToolRegistry.tools.map { spec ->
            Tool(
                id = spec.id,
                title = getString(spec.titleRes),
                desc = getString(spec.descRes),
                iconRes = spec.iconRes,
                iconBgRes = spec.iconBgRes,
                launcher = { ctx -> WebViewShellActivity.newIntent(ctx, spec.id) }
            )
        }
    }

    private companion object {
        const val HOME_PREFS = "home_preferences"
        const val KEY_GRID_COLUMNS = "grid_columns"
    }
}
