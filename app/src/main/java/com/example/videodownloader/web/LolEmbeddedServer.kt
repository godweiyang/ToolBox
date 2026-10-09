package com.example.videodownloader.web

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlin.concurrent.thread

/**
 * LOL 本地服务的内嵌运行器（替代独立的「LOL本地服务」APK / 桌面端单独进程）。
 *
 * 通过 Chaquopy 在本 app 进程内启动 CPython，直接运行与桌面端同源的 server.py，
 * 在 127.0.0.1:17530 提供回环服务。生命周期与 app 进程一致：
 *  - 打开 lol 页面（[WebViewShellActivity]）时自动 [start]，无需用户手动启动；
 *  - 服务只绑定 loopback，进程结束（划掉 app / 系统回收）即随之消失，无需手动停止；
 *  - 账号数据落在 app 私有 filesDir 下，卸载即清。
 *
 * 启动耗时数秒（首次需解压 Python 运行时），页面侧会轮询健康接口，服务就绪后自动连上。
 */
object LolEmbeddedServer {

    private const val TAG = "LolEmbeddedServer"

    @Volatile
    private var started = false

    @Volatile
    private var starting = false

    /** 进程内只启动一次；重复调用直接忽略。 */
    @Synchronized
    fun start(appContext: Context) {
        if (started || starting) return
        starting = true
        thread(name = "lol-python-server", isDaemon = true) {
            try {
                boot(appContext.applicationContext)
                started = true
            } catch (t: Throwable) {
                Log.e(TAG, "lol 内嵌服务启动失败", t)
            } finally {
                starting = false
            }
        }
    }

    private fun boot(context: Context) {
        // 1) 启动内嵌 CPython
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        val py = Python.getInstance()
        Log.i(TAG, "CPython ready: " +
            py.getModule("sys").get("version").toString().lineSequence().first())

        // 2) 指定可写数据目录（APK 内 Python 路径只读），并声明 Android 平台：
        //    server.py 据此关闭“离开网页自动停”的空闲看门狗，避免切后台时误杀
        val environ = py.getModule("os").get("environ")!!
        environ.callAttr("__setitem__", "LOLZJCX_DATA_DIR", context.filesDir.absolutePath)
        environ.callAttr("__setitem__", "LOLZJCX_PLATFORM", "android")
        Log.i(TAG, "data dir = ${context.filesDir.absolutePath}")

        // 3) 导入业务模块并运行 main()（绑定端口后阻塞在服务循环里）
        val server = py.getModule("server")
        Log.i(TAG, "server module loaded, VERSION=" + server.get("VERSION"))
        server.callAttr("main")
        Log.i(TAG, "server main() returned")
    }
}
