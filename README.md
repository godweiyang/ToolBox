# 工具百宝箱 ToolBox

一个轻量级 Android 工具集合应用。主页是工具卡片列表,点击进入对应工具,长按可拖动排序。

## 当前工具

| 工具 | 功能 |
|---|---|
| 🎬 视频下载 | 抖音 / 快手 / 小红书 / B站 视频下载(无水印 / B站高清) |
| ▦ 二维码生成 | 输入文本/链接,自定义颜色样式生成二维码,支持 Logo |
| 🎞️ 视频转 GIF | 导入本地视频,选取时间段和区域,生成 GIF 表情包 |
| ▦ 九宫格切图 | 把一张图切成 3×3,发朋友圈九宫格 |
| 🔄 GIF 倒放 | 把 GIF 反过来播,趣味效果 |
| 🔊 分贝仪 | 用麦克风测量环境噪音分贝,带实时曲线 |
| 📶 WiFi 信号探测 | 实时显示 RSSI/频段/速率/距离,室内步行轨迹+热力图 |
| 📤 局域网文件互传 | 手机开 HTTP 服务,电脑浏览器扫码互传文件 |
| 🧭 金属探测器 | 利用磁力计检测磁场异常,带阈值报警与实时曲线 |

## 主页交互

- **点击卡片**进入对应工具
- **长按卡片**进入拖拽,支持上下左右四方向移动(适配 2 列网格)
- 拖拽顺序自动持久化,App 重启后保留
- 后续新增工具会自动追加到列表末尾,不影响已排序

## 编译环境

| 依赖 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.0(由 wrapper 自动下载) |
| Android Gradle Plugin | 8.1.4 |
| Kotlin | 1.9.24 |
| compileSdk / targetSdk | 34(Android 14) |
| minSdk | 26(Android 8.0) |

主要第三方库:OkHttp、Gson、Kotlinx Coroutines、ZXing、NanoHTTPD、AndroidX。

## 编译方法

> 克隆后需准备 Android SDK(platform android-34 + build-tools 34.0.0),
> 并在工程根目录创建 `local.properties` 指向 SDK 路径。

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

产物路径:`app/build/outputs/apk/debug/app-debug.apk`

## 安装到手机

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或把 APK 传到手机点击安装(需在系统设置允许"未知来源应用"安装)。

## 新增工具

只需 3 步:

1. 写 `XxxActivity.kt` + `activity_xxx.xml`
2. `AndroidManifest.xml` 注册 Activity
3. `MainActivity.getTools()` 里加一行 `Tool(id=..., ...)`,id 必须唯一且稳定

## 仅供学习交流

请遵守各平台协议与当地法律,下载内容版权归原作者所有。本应用不用于商业用途,不对任何因使用本应用产生的纠纷负责。
