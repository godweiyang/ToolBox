package com.example.videodownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface

/**
 * 局域网文件互传服务。
 *
 * - 前台 Service 保活，防止后台被杀
 * - NanoHTTPD 监听 0.0.0.0:8080
 * - 路由：
 *   GET /           → 返回 HTML 页面（拖拽上传 + 文件列表）
 *   POST /upload    → 接收 multipart 文件，写入 Download/ToolBoxShare/
 *   GET /list       → 返回手机 Download/ToolBoxShare/ 下文件 JSON
 *   GET /file?id=x  → 下载指定文件
 *
 * 同一 WiFi 下的电脑/手机/iPad 浏览器均可访问。
 */
class FileShareService : Service() {

    companion object {
        private const val TAG = "FileShareService"
        const val PORT = 8080
        const val CHANNEL_ID = "fileshare_channel"
        const val NOTIF_ID = 1001
        const val SHARE_DIR_NAME = "ToolBoxShare"

        /** 单例 server 实例，Activity 通过它拿地址/状态 */
        @Volatile var serverInstance: FileShareServer? = null
            private set

        @Volatile var isRunning = false
            private set

        /** 接收文件回调（Activity 注册以刷新 UI） */
        @Volatile var onFileReceived: ((fileName: String, size: Long) -> Unit)? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("服务运行中")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        try {
            if (serverInstance == null) {
                serverInstance = FileShareServer(PORT, this)
                serverInstance?.start()
                isRunning = true
                Log.i(TAG, "HTTP server started on port $PORT")
            }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            isRunning = false
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { serverInstance?.stop() } catch (_: Exception) {}
        serverInstance = null
        isRunning = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fs_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.fs_channel_desc) }
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tool_fileshare_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    /** 获取本机局域网 IPv4，无则 null */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                // 只要 wlan/eth 接口
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.displayName.lowercase()
                if (!name.contains("wlan") && !name.contains("eth") && !name.contains("ap")) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a.hostAddress?.contains(':') == false) {
                        return a.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLocalIpAddress", e)
        }
        return null
    }

    /** 拉取当前 Download/ToolBoxShare/ 下的文件列表（供 Activity 调用） */
    fun listSharedFilesFromService(): List<FileShareServer.SharedFile> {
        return serverInstance?.listSharedFilesPublic() ?: emptyList()
    }
}

/**
 * NanoHTTPD 实现：HTTP server。
 * 持有 service 引用以访问 ContentResolver / 资源。
 */
class FileShareServer(
    port: Int,
    private val service: FileShareService
) : NanoHTTPD(port) {

    companion object { private const val TAG = "FileShareServer" }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        return try {
            when {
                uri == "/" && method == Method.GET -> serveIndexPage()
                uri == "/list" && method == Method.GET -> serveFileList()
                uri.startsWith("/file") && method == Method.GET -> serveFileDownload(session)
                uri == "/upload" && method == Method.POST -> handleUpload(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve error: $uri", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    /** GET / → 返回 HTML 页面 */
    private fun serveIndexPage(): Response {
        val html = HTML_PAGE
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    /** GET /list → 返回 Download/ToolBoxShare/ 下文件 JSON */
    private fun serveFileList(): Response {
        val files = listSharedFiles()
        val arr = JSONArray()
        for (f in files) {
            val o = JSONObject()
            o.put("id", f.id)
            o.put("name", f.name)
            o.put("size", f.size)
            o.put("time", f.time)
            arr.put(o)
        }
        val result = JSONObject()
        result.put("files", arr)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", result.toString())
    }

    /** GET /file?id=x → 下载指定文件 */
    private fun serveFileDownload(session: IHTTPSession): Response {
        val params = session.parameters
        val id = params["id"]?.firstOrNull()?.toLongOrNull() ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing id"
        )
        val files = listSharedFiles()
        val target = files.find { it.id == id } ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND, MIME_PLAINTEXT, "file not found"
        )
        val input = openSharedFileInput(target) ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND, MIME_PLAINTEXT, "file missing"
        )
        val mime = guessMime(target.name)
        val resp = newChunkedResponse(Response.Status.OK, mime, input)
        resp.addHeader("Content-Disposition", "attachment; filename=\"${target.name.replace("\"", "_")}\"")
        if (target.size > 0) {
            resp.addHeader("Content-Length", target.size.toString())
        }
        return resp
    }

    private fun openSharedFileInput(target: SharedFile): java.io.InputStream? {
        return try {
            if (target.path.startsWith("content://")) {
                service.contentResolver.openInputStream(Uri.parse(target.path))
            } else {
                val file = File(target.path)
                if (file.exists()) file.inputStream() else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "open shared file failed: ${target.name}", e)
            null
        }
    }

    /** POST /upload → 接收 multipart 文件 */
    private fun handleUpload(session: IHTTPSession): Response {
        // NanoHTTPD 要求先 parse body 才能拿到 files
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            Log.e(TAG, "parseBody failed", e)
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "parse error")
        }
        // NanoHTTPD 把上传文件存到临时路径，key 是表单字段名
        val uploadedTempPath = files["file"] ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "no file"
        )
        // 优先从前端显式传递的 filename 字段拿原始文件名（含后缀）
        // NanoHTTPD 不会自动解析 multipart 的 filename 字段，必须前端额外带
        val originalName = sanitizeDisplayName(session.parameters["filename"]?.firstOrNull()
            ?: extractFilenameFromKey(files.keys, "file")
            ?: "uploaded_${System.currentTimeMillis()}")
        val tempFile = File(uploadedTempPath)
        val saved = saveToDownloads(tempFile, originalName)
        return if (saved != null) {
            FileShareService.onFileReceived?.invoke(originalName, saved)
            val o = JSONObject()
            o.put("ok", true)
            o.put("name", originalName)
            o.put("size", saved)
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "save failed")
        }
    }

    private fun sanitizeDisplayName(name: String): String {
        val baseName = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\r\\n]"), "_")
            .trim()
        return baseName.ifBlank { "uploaded_${System.currentTimeMillis()}" }
    }

    /**
     * 兜底：从 NanoHTTPD files map 的 key 里提取原始文件名。
     * NanoHTTPD 在某些版本会把 key 格式化为 "fieldName;filename=xxx.png"。
     * 找不到返回 null。
     */
    private fun extractFilenameFromKey(keys: Set<String>, fieldName: String): String? {
        for (k in keys) {
            if (k.startsWith(fieldName) && k.contains("filename=", ignoreCase = true)) {
                val idx = k.indexOf("filename=", ignoreCase = true)
                return k.substring(idx + "filename=".length).trim().trim('"').ifBlank { null }
            }
        }
        return null
    }

    /** 把上传的文件写入 Download/ToolBoxShare/ */
    private fun saveToDownloads(tempFile: File, displayName: String): Long? {
        val mime = guessMime(displayName)
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/${FileShareService.SHARE_DIR_NAME}")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val resolver = service.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val uri = resolver.insert(collection, cv) ?: run {
            // 回退：写入应用专属外部存储
            return saveToExternalLegacy(tempFile, displayName)
        }
        return try {
            resolver.openOutputStream(uri)?.use { os ->
                tempFile.inputStream().use { it.copyTo(os) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cv.clear()
                cv.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, cv, null, null)
            }
            tempFile.length()
        } catch (e: Exception) {
            Log.e(TAG, "saveToDownloads failed", e)
            null
        }
    }

    /** 旧版本回退：写到外部存储公共 Download 目录 */
    private fun saveToExternalLegacy(tempFile: File, displayName: String): Long? {
        return try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FileShareService.SHARE_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, displayName)
            tempFile.inputStream().use { it.copyTo(target.outputStream()) }
            target.length()
        } catch (e: Exception) {
            Log.e(TAG, "saveToExternalLegacy failed", e)
            null
        }
    }

    /** 公开接口：供 Activity 直接调用拿文件列表（无需反射） */
    fun listSharedFilesPublic(): List<SharedFile> = listSharedFiles()

    /** 公开接口：供 Activity 拿到本机局域网 IP（无需反射） */
    fun getServiceIpAddress(): String? = service.getLocalIpAddress()

    /** 列出 Download/ToolBoxShare/ 下的文件 */
    private fun listSharedFiles(): List<SharedFile> {
        val result = ArrayList<SharedFile>()
        // 优先用 MediaStore 查 Downloads
        try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Files.getContentUri("external")
            }
            val proj = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.DATE_ADDED
                )
            } else {
                arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.DATE_ADDED,
                    MediaStore.Downloads.DATA
                )
            }
            val sel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            } else {
                "${MediaStore.Downloads.DATA} LIKE ?"
            }
            val selArgs = arrayOf("%${FileShareService.SHARE_DIR_NAME}%")
            service.contentResolver.query(collection, proj, sel, selArgs, "${MediaStore.Downloads.DATE_ADDED} DESC")?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.Downloads._ID)
                val nameIdx = c.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(MediaStore.Downloads.SIZE)
                val timeIdx = c.getColumnIndex(MediaStore.Downloads.DATE_ADDED)
                val dataIdx = c.getColumnIndex(MediaStore.Downloads.DATA)
                while (c.moveToNext()) {
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                    if (name == null) continue
                    val id = if (idIdx >= 0) c.getLong(idIdx) else 0L
                    val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                    val time = if (timeIdx >= 0) c.getLong(timeIdx) * 1000 else 0L
                    val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContentUris.withAppendedId(collection, id).toString()
                    } else if (dataIdx >= 0) {
                        c.getString(dataIdx) ?: ""
                    } else {
                        ""
                    }
                    result.add(SharedFile(id, name, size, time, path))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listSharedFiles MediaStore query failed", e)
        }
        // MediaStore 查不到则回退扫描文件路径
        if (result.isEmpty()) {
            try {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FileShareService.SHARE_DIR_NAME)
                if (dir.exists()) {
                    dir.listFiles()?.forEach { f ->
                        result.add(SharedFile(f.lastModified(), f.name, f.length(), f.lastModified(), f.absolutePath))
                    }
                    result.sortByDescending { it.time }
                }
            } catch (e: Exception) {
                Log.e(TAG, "listSharedFiles file scan failed", e)
            }
        }
        return result
    }

    private fun guessMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".txt") -> "text/plain"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".apk") -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    data class SharedFile(
        val id: Long,
        val name: String,
        val size: Long,
        val time: Long,
        val path: String
    )
}

/**
 * 内嵌 HTML 页面：拖拽上传 + 文件列表 + 下载。
 * 纯 JS，不依赖任何 CDN/框架，离线可用。
 */
private const val HTML_PAGE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>ToolBox 文件互传</title>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; background: #f5f7fa; color: #222; padding: 20px; max-width: 900px; margin: 0 auto; }
h1 { color: #0288D1; margin-bottom: 8px; font-size: 24px; }
.subtitle { color: #888; font-size: 13px; margin-bottom: 24px; }
.section { background: #fff; border-radius: 12px; padding: 20px; margin-bottom: 20px; box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.section h2 { font-size: 16px; margin-bottom: 12px; color: #333; }
#drop { border: 2px dashed #0288D1; border-radius: 10px; padding: 40px 20px; text-align: center; color: #0288D1; cursor: pointer; transition: all 0.2s; background: #f0f8ff; }
#drop:hover, #drop.over { background: #e1f5fe; border-color: #01579b; }
#drop p { font-size: 16px; margin-bottom: 6px; }
#drop .hint { font-size: 12px; color: #999; }
#fileInput { display: none; }
#progress { margin-top: 14px; font-size: 13px; color: #555; }
.upload-item { padding: 6px 10px; margin-top: 6px; background: #f1f8e9; border-radius: 6px; font-size: 13px; }
.upload-item.fail { background: #fbe9e7; }
.file-list { list-style: none; }
.file-item { display: flex; align-items: center; padding: 12px; border-bottom: 1px solid #eee; }
.file-item:last-child { border-bottom: none; }
.file-icon { font-size: 22px; margin-right: 12px; }
.file-name { flex: 1; font-size: 14px; word-break: break-all; }
.file-size { color: #999; font-size: 12px; margin: 0 12px; white-space: nowrap; }
.btn-dl { background: #0288D1; color: #fff; border: none; padding: 6px 14px; border-radius: 6px; cursor: pointer; font-size: 13px; }
.btn-dl:hover { background: #01579b; }
.empty { color: #aaa; text-align: center; padding: 20px; font-size: 13px; }
.time { color: #bbb; font-size: 11px; }
</style>
</head>
<body>
<h1>📦 ToolBox 文件互传</h1>
<div class="subtitle">同一 WiFi 下，手机 ↔ 电脑互传文件，不耗流量</div>

<div class="section">
  <h2>📤 上传文件到手机</h2>
  <div id="drop">
    <p>📂 拖文件到这里 或 点击选择</p>
    <div class="hint">支持任意类型文件</div>
  </div>
  <input type="file" id="fileInput" multiple>
  <div id="progress"></div>
</div>

<div class="section">
  <h2>📥 手机上的文件（点下载到电脑）</h2>
  <ul class="file-list" id="list">
    <li class="empty">加载中…</li>
  </ul>
</div>

<script>
const drop = document.getElementById('drop');
const fileInput = document.getElementById('fileInput');
const progress = document.getElementById('progress');
const listEl = document.getElementById('list');

drop.addEventListener('click', () => fileInput.click());
drop.addEventListener('dragover', e => { e.preventDefault(); drop.classList.add('over'); });
drop.addEventListener('dragleave', () => drop.classList.remove('over'));
drop.addEventListener('drop', e => {
  e.preventDefault();
  drop.classList.remove('over');
  uploadFiles(e.dataTransfer.files);
});
fileInput.addEventListener('change', () => uploadFiles(fileInput.files));

function uploadFiles(files) {
  if (!files.length) return;
  Array.from(files).forEach(f => uploadOne(f));
}

function uploadOne(file) {
  const fd = new FormData();
  fd.append('file', file, file.name);
  // 显式带上原始文件名，NanoHTTPD 不会自动解析 multipart filename 字段
  fd.append('filename', file.name);
  const item = document.createElement('div');
  item.className = 'upload-item';
  item.textContent = '⏳ ' + file.name + ' (' + formatSize(file.size) + ')';
  progress.appendChild(item);
  fetch('/upload', { method: 'POST', body: fd })
    .then(r => r.json())
    .then(d => {
      item.textContent = '✓ ' + file.name + ' (' + formatSize(file.size) + ')';
      refreshList();
    })
    .catch(e => {
      item.className = 'upload-item fail';
      item.textContent = '✗ ' + file.name + ' 失败';
    });
}

function formatSize(b) {
  if (b < 1024) return b + ' B';
  if (b < 1048576) return (b/1024).toFixed(1) + ' KB';
  if (b < 1073741824) return (b/1048576).toFixed(1) + ' MB';
  return (b/1073741824).toFixed(2) + ' GB';
}

function formatTime(t) {
  if (!t) return '';
  const d = new Date(t);
  return d.getMonth()+1 + '/' + d.getDate() + ' ' + d.getHours() + ':' + String(d.getMinutes()).padStart(2,'0');
}

function iconFor(name) {
  const n = name.toLowerCase();
  if (/\.(png|jpg|jpeg|gif|webp|bmp)$/.test(n)) return '🖼️';
  if (/\.(mp4|mov|avi|mkv)$/.test(n)) return '🎬';
  if (/\.(mp3|wav|flac|aac|m4a)$/.test(n)) return '🎵';
  if (/\.(pdf)$/.test(n)) return '📕';
  if (/\.(doc|docx)$/.test(n)) return '📘';
  if (/\.(xls|xlsx)$/.test(n)) return '📗';
  if (/\.(zip|rar|7z|tar|gz)$/.test(n)) return '🗜️';
  if (/\.(apk)$/.test(n)) return '🤖';
  if (/\.(txt|md|log)$/.test(n)) return '📝';
  return '📄';
}

function refreshList() {
  listEl.innerHTML = '<li class="empty">加载中…</li>';
  fetch('/list').then(r => r.json()).then(d => {
    if (!d.files || !d.files.length) {
      listEl.innerHTML = '<li class="empty">手机 Download/ToolBoxShare/ 下暂无文件</li>';
      return;
    }
    listEl.innerHTML = '';
    d.files.forEach(f => {
      const li = document.createElement('li');
      li.className = 'file-item';
      const icon = document.createElement('span');
      icon.className = 'file-icon';
      icon.textContent = iconFor(f.name);
      const name = document.createElement('span');
      name.className = 'file-name';
      name.appendChild(document.createTextNode(f.name));
      name.appendChild(document.createElement('br'));
      const time = document.createElement('span');
      time.className = 'time';
      time.textContent = formatTime(f.time);
      name.appendChild(time);
      const size = document.createElement('span');
      size.className = 'file-size';
      size.textContent = formatSize(f.size);
      const btn = document.createElement('button');
      btn.className = 'btn-dl';
      btn.textContent = '下载';
      btn.onclick = () => { window.location.href = '/file?id=' + f.id; };
      li.appendChild(icon);
      li.appendChild(name);
      li.appendChild(size);
      li.appendChild(btn);
      listEl.appendChild(li);
    });
  }).catch(e => {
    listEl.innerHTML = '<li class="empty">加载失败</li>';
  });
}

refreshList();
setInterval(refreshList, 5000);
</script>
</body>
</html>"""
