# 工具百宝箱 ToolBox

一个轻量级 Android 工具集合应用。采用"工具百宝箱"架构,主页是工具卡片列表,点击进入对应工具,后续可逐步扩展新工具。

## 当前工具

| 工具 | 功能 |
|---|---|
| 🎬 视频下载 | 抖音 / 快手 / 小红书 / B站 视频下载(无水印 / B站高清) |
| ▦ 二维码生成 | 输入文本/链接,自定义颜色样式生成二维码,支持 Logo |
| 🎞️ 视频转 GIF | 导入本地视频,选取时间段和区域,生成 GIF 表情包 |
| ▦ 九宫格切图 | 把一张图切成 3×3,发朋友圈九宫格 |
| 🔄 GIF 倒放 | 把 GIF 反过来播,趣味效果 |
| 🔊 分贝仪 | 用麦克风测量环境噪音分贝 |

---

## 功能特性

### 🎬 视频下载

- **一键粘贴**:直接粘贴分享文本,自动用正则提取其中的链接,无需手动挑
- **多平台支持**:
  - 抖音(含西瓜视频)
  - 快手
  - 小红书
  - B站(b23.tv 短链 / bilibili.com 完整链接)
- **无水印下载**:抖音 / 快手 / 小红书均解析原始视频直链,下载的是无水印版本
- **B站高清画质**:支持 DASH 音视频分离流,登录后可下载 1080P+ 画质,自动用 MediaMuxer 合成 mp4
- **B站登录**:内置 WebView 登录页,抓取 SESSDATA cookie 调 playurl 接口拿最高清晰度(未登录仅 480P)
- **自动入库相册**:视频保存到 `Movies/VideoDownloader/`,自动通知 MediaStore 刷新相册
- **实时进度**:解析和下载过程均有日志与进度条展示
- **多级兜底**:每个平台都准备了多条解析路径,应对官方接口变更

### ▦ 二维码生成

- **文本/链接输入**:粘贴或手动输入任意内容
- **前景色 / 背景色**:各 6 种预设色板,一键切换
- **点阵样式**:方块 / 圆点两种风格
- **容错等级**:L(7%) / M(15%) / Q(25%) / H(30%)
- **边距调节**:0-8 模块(SeekBar)
- **中心 Logo**:从相册选图,自动白底圆角缩放,加 Logo 自动切到 H 容错
- **保存相册**:PNG 格式,存到 `Pictures/QrCode/`
- **实时预览**:任意样式改动自动重新生成

### 🎞️ 视频转 GIF

- **视频导入**:从相册选择本地视频
- **视频预览**:VideoView 实时预览,拖动时间滑块时自动 seek 到对应帧
- **时间段选取**:双 SeekBar(起/止)拖动选区间,实时预览对应帧,左上角显示当前时间,最小间隔 3%
- **裁剪区域**:直接在预览图上拖拽裁剪框,8 个手柄(4 角 + 4 边中点)+ 三分线辅助构图 + 暗色遮罩
- **帧率**:5-25 fps 可调,默认 14
- **输出宽度**:120-720 px 可调,默认 480(高度按比例)
- **GIF 画质**:1-30 可调,默认 10(越小画质越好)
- **实时进度**:进度条 + 百分比文字
- **输出位置**:MediaStore → `Pictures/GifOutput/`(API 29+ 无需权限)

---

## 平台解析原理(视频下载)

### 抖音

```
分享文本
  │ 正则提取 https://v.douyin.com/xxx/
  ▼
短链 GET(不跟随重定向)
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

兜底:iteminfo 接口失效时,直接请求 share 页 HTML,正则搜索
`"play_addr":{"url_list":["..."]}` / `_ROUTER_DATA` / mp4 直链。

### 小红书

```
分享文本
  │ 正则提取 http://xhslink.com/o/xxx
  ▼
短链 GET(不跟随重定向)
  │ 读取 Location: https://www.xiaohongshu.com/discovery/item/{noteId}?xsec_token=xxx
  │ ⚠ xsec_token 是访问凭证,必须原样保留
  ▼
GET 跳转后的完整 URL(含 xsec_token)
  │ HTML 里有 window.__INITIAL_STATE__ = {...}
  ▼
用花括号平衡计数提取完整 JSON
  │ 递归查找字段:
  │   - masterUrl(完整直链,优先)
  │   - originVideoKey(拼接 https://sns-video-bd.xhscdn.com/{key})
  ▼
下载并写入相册
```

### 快手

短链 302 → share 页 → 正则搜索 `"photoUrl":"xxx.mp4"` 或 mp4 直链。

### B站

```
分享文本
  │ 正则提取 https://b23.tv/xxx 或 https://www.bilibili.com/video/BVxxx
  ▼
短链 GET(不跟随重定向)
  │ 读取 Location: https://www.bilibili.com/video/BVxxx
  │ 提取 BV 号
  ▼
GET https://api.bilibili.com/x/web-interface/view?bvid={BV}
  │ 拿到 cid(视频 cid)
  ▼
GET https://api.bilibili.com/x/player/playurl?bvid=&cid=&qn=127&fnval=16&fourk=1
  │   qn=127  请求 4K(最高画质)
  │   fnval=16  请求 DASH 格式(音视频分离)
  │   带 SESSDATA cookie(登录态)→ 解锁 1080P+
  ▼
DASH 流:dash.video[] / dash.audio[] 各选 id 最大的轨道
  │ 视频优先 avc1 编码(MediaMuxer 兼容性最好)
  ▼
下载视频 m4s (0-40%) + 音频 m4s (40-70%)
  ▼
BiliMuxer:MediaExtractor 读两条 m4s 轨道 → MediaMuxer 按时间戳交错写入 → mp4 (70-100%)
  ▼
写入相册
```

**清晰度说明**:
- 未登录:仅 480P(B站服务端限制)
- 普通账号登录:最高 1080P
- 大会员账号登录:最高 4K / HDR

**登录方式**:视频下载页"B站登录"按钮 → 内置 WebView 加载官方登录页 →
登录成功后从 CookieManager 提取 SESSDATA 持久化到 SharedPreferences。
长按"B站登录"按钮可登出。

---

## 视频转 GIF 原理

```
用户选视频 + 时间段 + 裁剪区域 + 帧率/画质参数
  ▼
MediaMetadataRetriever.getFrameAtTime(ts, OPTION_CLOSEST_SYNC)
  │ 按时间戳逐帧抽 Bitmap(微秒精度,返回 ARGB_8888)
  │ 返回 null 时重试 1 次(部分国产 ROM 偶发)
  ▼
Bitmap.createBitmap(src, x, y, w, h)  裁剪到用户选的区域
  ▼
Bitmap.createScaledBitmap  缩放到目标输出宽度(高度按比例)
  ▼
AnimatedGifEncoder.addFrame(bitmap)
  │ NeuQuant 量化到 256 色 → LZW 压缩 → 写入 OutputStream
  ▼
bitmap.recycle()  逐帧回收防 OOM
  ▼
写入 MediaStore(Pictures/GifOutput/)
```

**技术栈**:
- `AnimatedGifEncoder`:Apache 2.0 开源 GIF 编码器单文件(NeuQuant 256 色量化 + LZW 压缩)
- `GifConverter`:协程 `Dispatchers.Default` 后台执行,主线程进度回调
- `MediaMetadataRetriever`:Android 系统组件,无需 native 库

**性能**:10 秒视频 15fps = 150 帧,总耗时约 7-22 秒(取决于设备),全程不阻塞 UI。

---

## 九宫格切图原理

```
用户选图片
  ▼
BitmapFactory.decodeStream  解码原图(可能很大)
  ▼
居中裁剪为正方形  (取宽高较短边,居中 crop)
  ▼
尺寸 > 1080px 时降采样缩放到 1080×1080(防 OOM)
  ▼
按 3×3 切成 9 张子 Bitmap
  │ Bitmap.createBitmap(square, col*pieceSize, row*pieceSize, pieceSize, pieceSize)
  │ 最后一行/列吃掉余数,避免缝隙
  ▼
GridLayout 预览 9 张图(每格 110dp)
  ▼
一键全部保存到 Pictures/NineGrid/  (PNG,MediaStore)
```

**用途**:发朋友圈九宫格——按顺序选 9 张发出去,拼起来是一张完整的图。

---

## GIF 倒放原理

```
用户选 GIF → 读 byte[]
  ▼
Movie.decodeByteArray  (android.graphics.Movie,系统 API,零依赖)
  │ 解析 GIF 元数据:width / height / duration
  ▼
按帧数等间隔抽帧(默认 20 帧,可调 6-54)
  │ for i in 0 until N:
  │   t = i * duration / N
  │   movie.setTime(t)
  │   movie.draw(canvas, 0, 0)  → Bitmap
  ▼
frames.reverse()  反转帧序列
  ▼
AnimatedGifEncoder  重新编码
  │ delay = duration / N / 10  (单位 1/100 秒)
  │ NeuQuant 256 色量化 + LZW 压缩
  ▼
ByteArrayOutputStream  →  byte[]
  ▼
写入 MediaStore(Pictures/GifOutput/,image/gif)
```

**技术栈**:`android.graphics.Movie`(系统 GIF 解码,高版本 deprecated 但仍可用)+ 复用 `AnimatedGifEncoder` 编码。

**效果**:正向播完 → 立即反向播 → 循环,适合做"鬼畜"表情包。

---

## 分贝仪原理

```
用户点"开始测量" → 申请 RECORD_AUDIO 权限
  ▼
AudioRecord(MIC, 44100Hz, MONO, PCM_16BIT)
  │ 后台线程循环读取 PCM 数据
  ▼
计算 RMS(均方根)
  │ rms = sqrt(Σ(sample²) / N)
  ▼
dBFS = 20 · log10(rms / 32767)   (范围 -∞ ~ 0)
  ▼
近似 SPL = dBFS + 90              (粗略校准,0 dBFS ≈ 90 dB SPL)
  │ 结果限制在 0-120 dB
  ▼
主线程 Handler 更新 UI(每 100ms 一次)
  │ - 当前 dB 大数字
  │ - 等级标签:< 30 安静 / < 60 正常 / < 85 较吵 / ≥ 85 很吵
  │ - 最小 / 最大 / 平均统计
  ▼
用户点"停止" → release AudioRecord
```

**校准说明**:手机麦克风无标准 SPL 校准,数值为**近似值**,仅用于相对比较环境噪音。不同手机麦克风灵敏度差异较大,绝对值可能有 ±10 dB 偏差。

---

## 架构:工具百宝箱

```
MainActivity (工具列表入口,RecyclerView 网格)
  │ 点击卡片 → startActivity(tool.launcher(this))
  ├── VideoDownloaderActivity (视频下载工具)
  │   ├── VideoParser.kt       多平台解析
  │   ├── DownloadManager.kt   下载 + 入库
  │   ├── BiliLoginActivity    B站登录
  │   ├── BiliCookieStore      cookie 持久化
  │   └── BiliMuxer            DASH 合成
  ├── QrCodeActivity (二维码工具)
  │   └── QrCodeGenerator.kt   ZXing 生成 + 美化
  ├── VideoToGifActivity (视频转 GIF 工具)
  │   ├── GifConverter.kt       抽帧 + 裁剪 + 缩放 + 编码
  │   ├── CropOverlayView.kt   可拖拽裁剪框自定义 View(8 手柄 + 三分线)
  │   └── AnimatedGifEncoder.kt GIF 编码器(Apache 2.0)
  ├── NineGridActivity (九宫格切图工具)
  ├── GifReverseActivity (GIF 倒放工具)
  │   └── 复用 AnimatedGifEncoder + android.graphics.Movie
  └── DecibelMeterActivity (分贝仪工具)
      └── AudioRecord + RMS → dBFS → 近似 SPL
```

**新增工具只需 3 步**:
1. 写 `XxxActivity.kt` + `activity_xxx.xml`
2. AndroidManifest 注册
3. `MainActivity.getTools()` 里加一行 `Tool(...)`

---

## 应用图标

自适应图标(Adaptive Icon),两部分:
- **背景**:`ic_launcher_background.xml` — 抖音红 `#FE2C55` → 青色 `#25F4EE` 斜向 45° 渐变
- **前景**:`ic_launcher_foreground.xml` — 白色工具箱轮廓(把手 + 主体 + 锁扣横条 + 锁孔)

寓意:工具箱/百宝箱容器,承载各种工具。

---

## 文件结构

```
ToolBox/
├── settings.gradle.kts          # 工程设置
├── build.gradle.kts             # 顶层构建(AGP 8.1.4 + Kotlin 1.9.24)
├── gradle.properties            # Gradle / AndroidX 配置
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties   # Gradle 8.0
├── gradlew / gradlew.bat        # Gradle Wrapper 脚本
├── .gitignore
├── README.md
└── app/
    ├── build.gradle.kts         # 模块构建(compileSdk 34, minSdk 26)
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/videodownloader/
        │   ├── MainActivity.kt             # 工具百宝箱入口(工具列表)
        │   ├── Tool.kt                     # 工具数据类
        │   ├── ToolAdapter.kt              # 工具列表适配器
        │   │
        │   ├── VideoDownloaderActivity.kt  # 视频下载工具 UI
        │   ├── VideoInfo.kt                # 视频数据类(含 DASH 字段)
        │   ├── VideoParser.kt              # 链接提取 + 多平台多策略解析
        │   ├── DownloadManager.kt          # 下载 + MediaStore 入库(含 DASH 合成流程)
        │   ├── BiliCookieStore.kt          # B站 SESSDATA cookie 持久化
        │   ├── BiliLoginActivity.kt        # B站 WebView 登录页
        │   ├── BiliMuxer.kt                # DASH m4s 音视频合成(MediaExtractor + MediaMuxer)
        │   │
        │   ├── QrCodeActivity.kt           # 二维码工具 UI
        │   └── QrCodeGenerator.kt          # ZXing 二维码生成 + 美化
        │   │
        │   ├── VideoToGifActivity.kt       # 视频转 GIF 工具 UI
        │   ├── GifConverter.kt             # 抽帧 + 裁剪 + 缩放 + GIF 编码(协程异步)
        │   ├── CropOverlayView.kt          # 可拖拽裁剪框自定义 View(8 手柄 + 三分线 + 尺寸标签)
        │   ├── AnimatedGifEncoder.kt       # GIF 编码器(Apache 2.0,NeuQuant + LZW)
        │   │
        │   ├── NineGridActivity.kt         # 九宫格切图工具 UI
        │   ├── GifReverseActivity.kt       # GIF 倒放工具(Movie 解码 + 反转 + 重新编码)
        │   └── DecibelMeterActivity.kt     # 分贝仪(AudioRecord + RMS → dBFS → SPL)
        └── res/
            ├── layout/
            │   ├── activity_main.xml             # 工具列表页
            │   ├── item_tool.xml                 # 工具卡片
            │   ├── activity_video_downloader.xml # 视频下载页
            │   ├── activity_qrcode.xml           # 二维码页
            │   ├── activity_video_to_gif.xml     # 视频转 GIF 页
            │   ├── activity_nine_grid.xml        # 九宫格切图页
            │   ├── activity_gif_reverse.xml      # GIF 倒放页
            │   └── activity_decibel_meter.xml    # 分贝仪页
            ├── drawable/ic_launcher_background.xml  # 图标渐变背景
            ├── drawable/ic_launcher_foreground.xml  # 图标工具箱前景
            ├── mipmap-anydpi-v26/ic_launcher.xml
            ├── mipmap-anydpi-v26/ic_launcher_round.xml
            └── values/{strings,colors,themes}.xml
```

## 编译环境

| 依赖 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.0(由 wrapper 自动下载) |
| Android Gradle Plugin | 8.1.4 |
| Kotlin | 1.9.24 |
| compileSdk | 34 |
| minSdk | 26(Android 8.0) |
| targetSdk | 34(Android 14) |

主要第三方库:
- OkHttp 4.12.0(网络请求)
- Gson 2.11.0(JSON 解析)
- Kotlinx Coroutines 1.8.1(协程)
- ZXing 3.5.3(二维码生成)
- AndroidX / Material Components
- AnimatedGifEncoder(Apache 2.0,源码内嵌,非外部依赖)

## 编译方法

> 克隆后需要先准备 Android SDK(含 platform android-34 和 build-tools 34.0.0),
> 并在工程根目录创建 `local.properties` 指向 SDK 路径。

### 1. 准备 SDK

如果已装 Android Studio,SDK 通常在:
- Windows: `C:\Users\<用户名>\AppData\Local\Android\Sdk`
- macOS: `~/Library/Android/sdk`
- Linux: `~/Android/Sdk`

通过 SDK Manager 安装:
- SDK Platform 34(Android 14)
- Android SDK Build-Tools 34.0.0
- Android SDK Platform-Tools
- Android SDK Command-line Tools

### 2. 配置 local.properties

在工程根目录创建 `local.properties`,内容(路径换成你本机的):

```properties
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

> `local.properties` 已在 `.gitignore` 中,不会提交。

### 3. 接受 SDK License

```bash
yes | sdkmanager --licenses
```

### 4. 编译

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

或把 APK 文件传到手机,点击安装(需在系统设置允许"未知来源应用"安装)。

## 使用方法

### 视频下载

1. 在抖音 / 快手 / 小红书 / B站 App 里点视频右下角"分享" → "复制链接"
2. 打开本应用,点击"视频下载"工具卡片
3. 点"粘贴剪贴板"(或手动粘贴到输入框)
4. 点"解析并下载"
5. 日志区会显示标题 / 作者 / 平台 / 视频直链 / 下载进度
6. 下载完成后到相册的 `Movies/VideoDownloader/` 目录查看

> B站下载 1080P+ 画质需先点"B站登录"完成登录;未登录仅 480P。

#### 示例输入

抖音:
```
1.02 复制打开抖音,看看【我就是老盖的作品】这是私房照吗? # 摄影师老盖 # 人像摄影  https://v.douyin.com/D68hRKDKps8/  XMJ:/ :7pm 01/25 o@d.aN
```

小红书:
```
黑 是真黑 但 不得不说,这个颜值导播可以的! http://xhslink.com/o/7H6YzcRALw8 复制后直接打开【小红书】,笔记即刻可见。
```

B站:
```
【【全面评测】你想知道的 Steam Machine 的一切!体验+性能+拆机!-哔哩哔哩】 https://b23.tv/6t989Gh
```

### 二维码生成

1. 打开本应用,点击"二维码生成"工具卡片
2. 在输入框输入文本或链接(可点"粘贴剪贴板"按钮)
3. 在下方样式区选择前景色、背景色、点阵样式、容错等级、边距
4. 二维码会实时显示在预览区(改样式自动刷新)
5. 可选:点"从相册选择"添加中心 Logo(自动切 H 容错)
6. 点"保存到相册",PNG 文件保存到 `Pictures/QrCode/`

### 视频转 GIF

1. 打开本应用,点击"视频转 GIF"工具卡片
2. 点"选择视频"从相册导入本地视频
3. 拖动"起""止"两个 SeekBar 选时间段,拖动时视频自动跳到对应帧预览(左上角显示当前时间)
4. 在预览图上拖拽白色裁剪框的边或角调整裁剪区域(内部拖动可整体移动,带三分线辅助构图,左上角实时显示裁剪尺寸)
5. 调整帧率(默认 14 fps)、输出宽度(默认 480 px)、GIF 画质(默认 10,越小越好)
6. 点"生成 GIF",等进度条到 100%
7. GIF 保存到相册的 `Pictures/GifOutput/` 目录

### 九宫格切图

1. 打开本应用,点击"九宫格切图"工具卡片
2. 点"选择图片"从相册导入图片
3. 自动居中裁剪为正方形并切成 3×3 共 9 张,在 GridLayout 预览
4. 点"全部保存到相册",9 张 PNG 保存到 `Pictures/NineGrid/`
5. 在朋友圈按顺序选这 9 张发出去,拼起来是一张完整的图

### GIF 倒放

1. 打开本应用,点击"GIF 倒放"工具卡片
2. 点"选择 GIF"从相册导入 GIF(左侧显示原图首帧)
3. 调整"抽取帧数"(默认 20,越多越流畅,范围 6-54)
4. 点"生成倒放 GIF",等进度条到 100%(右侧显示倒放首帧)
5. 点"保存到相册",GIF 保存到 `Pictures/GifOutput/`

### 分贝仪

1. 打开本应用,点击"分贝仪"工具卡片
2. 点"开始测量",首次会请求麦克风权限,允许即可
3. 对着麦克风说话或放到噪音源旁,实时显示当前分贝 + 等级 + 最小/最大/平均
4. 点"停止测量"结束

> 手机麦克风无标准 SPL 校准,数值为近似值,仅用于相对比较环境噪音。

## 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` | 视频下载:访问短链、解析接口、下载视频 |
| `WRITE_EXTERNAL_STORAGE`(≤Android 9) | 旧版本写入相册 |
| `READ_MEDIA_VIDEO`(Android 13+) | 视频转 GIF:从相册选视频 / 视频媒体细粒度权限 |
| `READ_MEDIA_IMAGES`(Android 13+) | 二维码:从相册选 Logo;九宫格:从相册选图;GIF 倒放:从相册选 GIF |
| `RECORD_AUDIO` | 分贝仪:麦克风录音测量噪音 |

Android 10/11/12 通过 MediaStore 写入,无需存储权限;
Android 13+ 申请细粒度媒体权限。

## 已知限制

### 视频下载
- 各平台官方接口会不定期变更。本应用为每个平台都准备了多级兜底解析策略,
  但仍可能因官方接口彻底改版而失效。失效时优先更新 `VideoParser.kt` 中
  对应平台的接口地址或正则。
- 解析出的"无水印"地址在服务端就已是原始视频流,本应用不做本地去水印处理。
- 直播流、图集类作品(纯图片)暂不支持。
- 小红书的 `xsec_token` 有时效性,分享文本需尽快使用。
- B站未登录仅 480P;登录后清晰度取决于账号等级,大会员才能拿 4K/HDR。
- B站 DASH 流需下载视频+音频两条 m4s 再本地合成,耗时与体积约为单流的两倍。
- B站直链偶尔会返回非 443 端口(如 `:8082`),部分手机网络会拦截高端口,
  表现为"视频流下载失败"。切换 Wi-Fi / 移动数据通常可解决。
- 微信视频号因链接封闭、视频流加密鉴权,**技术上无法支持**。

### 二维码生成
- 内容过长(超过 ZXing 编码上限,通常 2000+ 字符)会生成失败。
- 圆点样式在低分辨率屏幕上可能比方块样式略显模糊。
- 透明背景保存为 PNG 时透明区域在相册缩略图中显示为黑色。

### 视频转 GIF
- GIF 只有 256 色,颜色丰富的视频会有明显色块,这是格式限制无法避免。
- 帧率越高 / 时间越长 / 输出宽度越大,GIF 文件体积越大(10s 480px 15fps 约 3-8MB)。
- `MediaMetadataRetriever.getFrameAtTime` 在部分国产 ROM 上偶发返回 null,
  已加重试但仍可能丢个别帧(表现为 GIF 略卡)。
- 超长视频(>30s)建议截取片段再转,否则耗时和内存压力较大。

### 九宫格切图
- 自动居中裁剪为正方形,非正方形原图的边缘会被裁掉。
- 切图尺寸基于原图(降采样到 1080px),发朋友圈时建议选高清原图。

### GIF 倒放
- 用 `android.graphics.Movie` 解码,部分超大 GIF 可能解码失败。
- 帧数抽太少会卡顿,抽太多会增大文件体积,建议默认 20 帧。
- 倒放后 GIF 颜色可能略有损失(重新编码时 256 色量化)。

### 分贝仪
- 手机麦克风无标准 SPL 校准,数值为近似值,不同手机灵敏度差异可能 ±10 dB。
- 后台运行或锁屏时测量会停止(AudioRecord 在后台受限)。
- 麦克风被其他应用占用时会初始化失败。

## 仅供学习交流

请遵守各平台协议与当地法律,下载内容版权归原作者所有。本应用不用于商业用途,不对任何因使用本应用产生的纠纷负责。
