// 顶层构建文件
plugins {
    id("com.android.application") version "8.1.4" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Chaquopy：内嵌 CPython，用于在 app 进程内直接运行 lol 本地服务（server.py）
    id("com.chaquo.python") version "15.0.1" apply false
}
