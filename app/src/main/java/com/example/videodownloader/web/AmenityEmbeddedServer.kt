package com.example.videodownloader.web

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.Executors

/** Serialized leases avoid close→reopen races. Only imports amenity.server. */
object AmenityEmbeddedServer {
    private var users = 0
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "amenity-runtime").apply { isDaemon = true }
    }
    @Volatile var failure: String? = null
        private set

    @Synchronized fun acquire(context: Context) {
        users++
        val app = context.applicationContext
        executor.execute {
            synchronized(this) { if (users == 0) return@execute }
            try {
                synchronized(Python::class.java) {
                    if (!Python.isStarted()) Python.start(AndroidPlatform(app))
                }
                val py = Python.getInstance()
                val bridge = File(app.filesDir, "amenity/beike_bridge.js")
                bridge.parentFile?.mkdirs()
                app.assets.open("amenity/beike_bridge.js").use { input ->
                    bridge.outputStream().use { output -> input.copyTo(output) }
                }
                val env = py.getModule("os").get("environ")!!
                env.callAttr("__setitem__", "AMENITY_PLATFORM", "android")
                env.callAttr("__setitem__", "AMENITY_BRIDGE_PATH", bridge.absolutePath)
                py.getModule("amenity.server").callAttr("start_embedded")
                failure = null
            } catch (error: Throwable) {
                failure = "内嵌服务启动失败，请退出后重试"
                Log.e("AmenityEmbeddedServer", "Community service stopped", error)
            }
        }
    }

    @Synchronized fun release() {
        if (users > 0) users--
        executor.execute {
            synchronized(this) { if (users != 0) return@execute }
            if (Python.isStarted()) runCatching {
                Python.getInstance().getModule("amenity.server").callAttr("stop_embedded")
            }.onFailure { Log.e("AmenityEmbeddedServer", "Community stop failed", it) }
        }
    }
}
