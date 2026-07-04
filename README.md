# VideoDownload 视频下载器

一个 Android 应用，粘贴抖音 / 快手 / 小红书的分享文本即可自动提取无水印视频并下载到手机相册。

## 功能特性

- **一键粘贴**：直接粘贴分享文本，自动用正则提取其中的链接，无需手动挑
- **多平台支持**：
  - 抖音（含西瓜视频）
  - 快手
  - 小红书
- **无水印下载**：各平台均解析原始视频直链，下载的是无水印版本
- **自动入库相册**：视频保存到 `Movies/VideoDownloader/`，自动通知 MediaStore 刷新相册
- **实时进度**：解析和下载过程均有日志与进度条展示
- **多级兜底**：每个平台都准备了多条解析路径，应对官方接口变更

## 平台解析原理

### 抖音

```
分享文本
  │ 正则提取 https://v.douyin.com/xxx/
  ▼
短链 GET（不跟随重定向）
  │ 读取 Location: .../share/video/{video_id}/
  ▼
GET https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids={video_id}
  │ JSON: item_list[0].video.play_addr.url_list[0]
  ▼
将 URL 里的 "playwm" 替换为 "play"  →  无水印地址
  │ 跟随一次 302 拿最终直链
  ▼
下载并写入 MediaStore(Movies/VideoDownloader/)
```

兜底：iteminfo 接口失效时，直接请求 share 页 HTML，正则搜索
`"play_addr":{"url_list":["..."]}` / `_ROUTER_DATA` / mp4 直链。

### 小红书

```
分享文本
  │ 正则提取 http://xhslink.com/o/xxx
  ▼
短链 GET（不跟随重定向）
  │ 读取 Location: https://www.xiaohongshu.com/discovery/item/{noteId}?xsec_token=xxx
  │ ⚠ xsec_token 是访问凭证，必须原样保留
  ▼
GET 跳转后的完整 URL（含 xsec_token）
  │ HTML 里有 window.__INITIAL_STATE__ = {...}
  ▼
用花括号平衡计数提取完整 JSON
  │ 递归查找字段：
  │   - masterUrl（完整直链，优先）
  │   - originVideoKey（拼接 https://sns-video-bd.xhscdn.com/{key}）
  ▼
下载并写入相册
```

### 快手

短链 302 → share 页 → 正则搜索 `"photoUrl":"xxx.mp4"` 或 mp4 直链。

## 文件结构

```
VideoDownload/
├── settings.gradle.kts          # 工程设置
├── build.gradle.kts             # 顶层构建（AGP 8.1.4 + Kotlin 1.9.24）
├── gradle.properties            # Gradle / AndroidX 配置
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties   # Gradle 8.0
├── gradlew / gradlew.bat        # Gradle Wrapper 脚本
├── .gitignore
├── README.md
└── app/
    ├── build.gradle.kts         # 模块构建（compileSdk 34, minSdk 26）
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/videodownloader/
        │   ├── MainActivity.kt       # UI + 流程编排（粘贴→解析→下载→入库）
        │   ├── VideoInfo.kt          # 数据类
        │   ├── VideoParser.kt        # 链接提取 + 多平台多策略解析
        │   └── DownloadManager.kt    # 下载 + MediaStore 入库
        └── res/
            ├── layout/activity_main.xml
            ├── drawable/ic_launcher_foreground.xml
            ├── mipmap-anydpi-v26/ic_launcher.xml
            ├── mipmap-anydpi-v26/ic_launcher_round.xml
            └── values/{strings,colors,themes}.xml
```

## 编译环境

| 依赖 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.0（由 wrapper 自动下载） |
| Android Gradle Plugin | 8.1.4 |
| Kotlin | 1.9.24 |
| compileSdk | 34 |
| minSdk | 26（Android 8.0） |
| targetSdk | 34（Android 14） |

主要第三方库：
- OkHttp 4.12.0（网络请求）
- Gson 2.11.0（JSON 解析）
- Kotlinx Coroutines 1.8.1（协程）
- AndroidX / Material Components

## 编译方法

> 克隆后需要先准备 Android SDK（含 platform android-34 和 build-tools 34.0.0），
> 并在工程根目录创建 `local.properties` 指向 SDK 路径。

### 1. 准备 SDK

如果已装 Android Studio，SDK 通常在：
- Windows: `C:\Users\<用户名>\AppData\Local\Android\Sdk`
- macOS: `~/Library/Android/sdk`
- Linux: `~/Android/Sdk`

通过 SDK Manager 安装：
- SDK Platform 34（Android 14）
- Android SDK Build-Tools 34.0.0
- Android SDK Platform-Tools
- Android SDK Command-line Tools

### 2. 配置 local.properties

在工程根目录创建 `local.properties`，内容（路径换成你本机的）：

```properties
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

> `local.properties` 已在 `.gitignore` 中，不会提交。

### 3. 接受 SDK License

```bash
# 用命令行工具接受所有 license
yes | sdkmanager --licenses
```

### 4. 编译

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

产物路径：`app/build/outputs/apk/debug/app-debug.apk`

## 安装到手机

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或把 APK 文件传到手机，点击安装（需在系统设置允许“未知来源应用”安装）。

## 使用方法

1. 在抖音 / 快手 / 小红书 App 里点视频右下角“分享” → “复制链接”
2. 打开本应用，点“粘贴剪贴板”（或手动粘贴到输入框）
3. 点“解析并下载”
4. 日志区会显示标题 / 作者 / 平台 / 视频直链 / 下载进度
5. 下载完成后到相册的 `Movies/VideoDownloader/` 目录查看

### 示例输入

抖音：
```
1.02 复制打开抖音，看看【我就是老盖的作品】这是私房照吗？ # 摄影师老盖 # 人像摄影  https://v.douyin.com/D68hRKDKps8/  XMJ:/ :7pm 01/25 o@d.aN
```

小红书：
```
黑 是真黑 但 不得不说，这个颜值导播可以的！ http://xhslink.com/o/7H6YzcRALw8 复制后直接打开【小红书】，笔记即刻可见。
```

## 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` | 访问短链、解析接口、下载视频 |
| `WRITE_EXTERNAL_STORAGE`（≤Android 9） | 旧版本写入相册 |
| `READ_MEDIA_VIDEO`（Android 13+） | 媒体细粒度权限 |

Android 10/11/12 通过 MediaStore 写入，无需存储权限；
Android 13+ 仅申请 `READ_MEDIA_VIDEO`。

## 已知限制

- 各平台官方接口会不定期变更。本应用为每个平台都准备了多级兜底解析策略，
  但仍可能因官方接口彻底改版而失效。失效时优先更新 `VideoParser.kt` 中
  对应平台的接口地址或正则。
- 解析出的“无水印”地址在服务端就已是原始视频流，本应用不做本地去水印处理。
- 直播流、图集类作品（纯图片）暂不支持。
- 小红书的 `xsec_token` 有时效性，分享文本需尽快使用。
- **仅供学习交流使用**，请遵守各平台协议与当地法律，下载内容版权归原作者所有。
