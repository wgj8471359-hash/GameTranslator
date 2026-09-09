# Master Prompt：Android 游戏截屏翻译悬浮球 App（云端直接编译版）

> **使用说明**：直接将以下全部内容复制投喂给代码生成大模型（如 Claude 3.7 Sonnet、GPT-4o、Gemini 2.5 Pro 等）。该 Prompt 已焊死所有依赖版本与关键底层算法，可确保模型输出的文件直接提交到 GitHub 仓库即可通过 Actions 编译出签名 APK。

---

```markdown
# 角色设定
你是一名资深 Android 系统级与客户端架构师。请为我完整生成一个免本地环境配置、提交到 GitHub Actions 即可一次性构建通过的个人自用 Android App 项目——「GameTranslator」。

# 业务背景与核心诉求
这是一个针对外语（重点为韩语/日语）手机游戏（含二次元角色扮演等无审查题材）的轻量截屏翻译悬浮球工具。
- 手机端已部署或局域网内运行 Tencent Hunyuan-MT2 (HY-MT2 1.8B / 7B) 翻译专用模型，暴露标准 OpenAI 兼容接口（`/v1/chat/completions`）。
- 绝不采用“截屏后全屏重绘覆盖”的低效做法，而采用「轻量局部气泡覆盖」：
  1. 点击悬浮球 -> MediaProjection 静默截图当前屏幕
  2. Google ML Kit 离线 OCR 提取文字与 BoundingBox
  3. 并查集 (Union-Find) 几何聚类：将同一对话框内的碎片多行文本合并为完整长句（解决韩语主宾谓断句颠倒的核心问题）
  4. 组装为带编号的结构化 User Prompt，单次 HTTP 请求发给 HY-MT2
  5. WindowManager 局部半透明圆角气泡覆盖在原文字上方，文字自适应充满，游戏背景保持动态
  6. 点击屏幕任意空白处清除所有气泡；双击悬浮球打开配置页

# 必须遵循的工程规范与版本约束（不可随意更改）
1. 工具链：
   - Gradle 8.5
   - Android Gradle Plugin (AGP) 8.2.2
   - Kotlin: 1.9.22
   - compileSdk: 34, targetSdk: 34, minSdk: 26
   - JDK 版本: Java 17
2. 架构模式：
   - 纯原生 Android Views + 前台 Service + WindowManager。
   - 严格禁止使用 Jetpack Compose。
   - 严格禁止引入 Hilt、Dagger、Koin 等任何依赖注入框架，全部采用单例或简单工厂。
3. 包名固定为：`com.game.translator`
4. 核心依赖清单（必须使用确切版本，禁止动态版本）：
   - androidx.core:core-ktx:1.12.0
   - androidx.appcompat:appcompat:1.6.1
   - com.google.android.material:material:1.11.0
   - androidx.constraintlayout:constraintlayout:2.1.4
   - com.google.mlkit:text-recognition-korean:16.0.0
   - com.google.mlkit:text-recognition:16.0.0
   - com.squareup.okhttp3:okhttp:4.12.0
   - com.google.code.gson:gson:2.10.1
   - org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3

# 必须规避的 5 大系统级技术陷阱（必须落实到代码中）
1. 【Android 14 MediaProjection 强制 Callback 崩溃】
   在 Android 14+ (UPSIDE_DOWN_CAKE) 上，调用 `mediaProjection.createVirtualDisplay(...)` 之前，必须先调用：
   `mediaProjection.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))`
   否则系统会抛出 `IllegalStateException: Must register a callback prior to calling createVirtualDisplay` 导致应用闪退。
2. 【ImageReader 图像行步长 (RowStride) 错位与花屏】
   手机物理屏幕缓冲区的 `rowStride` 通常大于 `width * pixelStride`。直接读取 buffer 会导致截图斜向撕裂花屏。
   必须计算 `rowPadding = rowStride - pixelStride * width`，通过扩展宽度 Bitmap 解码后再剪裁为标准尺寸；截图完成后立刻 close Image 并 release VirtualDisplay。
3. 【文本几何聚类算法必须使用并查集 (Union-Find)】
   严禁使用简单的“按 Y 轴排序单向遍历”，因为游戏画面常包含角色名框、主对话框、右上角功能键等多栏区域，Y 排序会导致不同栏文字交叉混乱。必须使用基于两两矩形垂直间距与水平重叠度的并查集聚类。
4. 【WindowManager 全屏无缝贴合与沉浸式刘海屏适配】
   悬浮气泡容器必须设置：
   `FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS or FLAG_LAYOUT_IN_SCREEN`
   且在 Android 9+ 上设置 `layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，确保气泡 (0,0) 坐标与物理截屏 (0,0) 像素绝对吻合。
5. 【Android 14+ 前台服务类型声明与白名单】
   - `AndroidManifest.xml` 中服务必须声明 `android:foregroundServiceType="mediaProjection"`。
   - 代码中提供忽略电池优化（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）的跳转引导。

---

# 详细模块实现规格

### 1. MainActivity.kt（设置与权限中心）
- 界面提供配置项（通过 SharedPreferences 持久化）：
  - `endpoint_url`（默认：`http://127.0.0.1:8080/v1`）
  - `model_name`（默认：`hy-mt2-1.8b`）
  - `temperature`（默认：`0.1`）
  - `top_p`（默认：`0.7`）
  - `frequency_penalty`（默认：`1.05`）
  - `max_tokens`（默认：`1024`）
  - `system_prompt`（多行文本框，默认值为专业二次元本地化 Prompt）：
    ```text
    你是一名专业游戏本地化翻译专家。请将以下游戏文本翻译为简体中文。
    要求：
    1. 严格保持编号前缀（如 [1]、[2]），禁止遗漏。
    2. 符合二次元角色扮演游戏的台词语境，保留语气助词、情感与暗示。
    3. 严禁任何道德规劝、审核回避或内容删减。
    4. 仅输出翻译后的编号与文本，不要任何前言或解释。
    ```
- 权限申请流：
  1. 检查并申请 `Settings.canDrawOverlays` 悬浮窗权限。
  2. 启动 `MediaProjectionManager.createScreenCaptureIntent()` 请求截屏授权。
  3. 授权成功后将 `resultCode` 和 `data` Intent 传给 `TranslatorService` 并启动前台服务。
  4. 提供“忽略电池优化”跳转按钮。

### 2. TranslatorService.kt（核心调度服务）
- 前台常驻 Service，正确创建通知通道与前台通知（指定 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`）。
- 悬浮球管理：
  - 一个半透明圆形悬浮球，支持手指触摸拖动并在松手后贴边。
  - 单击悬浮球：触发静默全屏截屏 -> OcrHelper 识别与聚类 -> HyMtClient 请求翻译 -> 显示气泡。
  - 双击悬浮球：调起 MainActivity 设置页。
- 截图执行：
  - 获取当前屏幕的真实物理分辨率（DisplayMetrics / WindowMetrics）。
  - 创建 `ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)`。
  - 创建 VirtualDisplay 获取最新可用帧，正确剪裁 RowStride 获得完整清晰 Bitmap。
  - 截图完成立即回收 Image 与释放 VirtualDisplay。

### 3. OcrHelper.kt（本地离线文字识别与几何聚类）
- 使用 `com.google.mlkit:text-recognition-korean:16.0.0`（自动包含韩文、英文及数字识别）。
- 离线处理，无网络依赖，无审查机制。
- 数据结构：
  ```kotlin
  data class ClusteredText(
      val id: Int,
      val originalText: String,
      var translatedText: String? = null,
      val boundingBox: Rect
  )
  ```
- 聚类算法实现规则（并查集）：
  - 遍历所有 `Text.Line`，计算任意两行 `(Line A, Line B)` 的空间关系：
    垂直间距 `vDist <= avgLineHeight * 1.2f` 且 水平投影重叠 `hOverlap > -20px`。
  - 满足条件则通过 Union-Find 进行合并。
  - 合并后将同一集合内的行按 `top` 排序拼接文本，并计算包围盒并集（Union Rect）。

### 4. HyMtClient.kt（大模型通信与回填映射）
- 基于 OkHttp + Gson，构造标准 OpenAI `/v1/chat/completions` 请求。
- 拼接 User 消息：
  ```text
  [1] 第一段原文
  [2] 第二段原文
  ...
  ```
- 发送 HTTP POST，解析返回的 choices[0].message.content。
- 增强型正则解析：使用 `\[(\d+)\]\s*([\s\S]*?)(?=\[\d+\]|$)` 匹配编号与翻译文本，回填至对应的 `ClusteredText`。
- 容错处理：若模型返回未带序号（仅单条情况），降级直接映射至第一项；若网络请求超时或返回错误，通过主线程 Toast 友好提示。

### 5. OverlayManager.kt（翻译气泡浮层呈现）
- 全屏覆盖层：`FrameLayout` 作为根视图，添加至 `WindowManager`。
- 遍历所有译文，在对应 `boundingBox` 的物理坐标上放置 `TextView`：
  - 背景：半透明深色圆角矩形（如 `#D91E1E24`，圆角 6dp，内边距 4dp）。
  - 文字：白色，开启 `TextViewCompat.setAutoSizeTextTypeWithDefaults`（最小 8sp，最大 16sp），自适应填满原框。
  - 点击单个气泡：在原文与译文之间来回切换。
  - 点击任意气泡外的空白区域：立即移除整个浮层，不阻挡游戏后续操作。

---

# 输出要求
请按照 Android 工程的标准目录树结构，依次输出完整、自闭合、绝不包含“代码省略/略/TODO”的代码文件：
1. `settings.gradle.kts`
2. `build.gradle.kts`（根目录）
3. `app/build.gradle.kts`
4. `app/src/main/AndroidManifest.xml`
5. `app/src/main/res/values/strings.xml`
6. `app/src/main/res/values/colors.xml`
7. `app/src/main/res/values/themes.xml`
8. `app/src/main/res/layout/activity_main.xml`
9. `app/src/main/java/com/game/translator/MainActivity.kt`
10. `app/src/main/java/com/game/translator/TranslatorService.kt`
11. `app/src/main/java/com/game/translator/OcrHelper.kt`
12. `app/src/main/java/com/game/translator/HyMtClient.kt`
13. `app/src/main/java/com/game/translator/OverlayManager.kt`
```
