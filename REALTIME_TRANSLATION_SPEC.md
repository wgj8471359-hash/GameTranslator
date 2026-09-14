# 实时差异化翻译模块技术规约 (REALTIME_TRANSLATION_SPEC.md)

本文件作为 **GameTranslator** 实时差异化翻译引擎在项目本地的**上下文锚定文件（Context Anchor）**，用于跨长任务周期固化技术指标、接口契约、算法状态机与防劣化约束，避免上下文漂移与幻觉。

---

## 1. 架构目标与系统不变量 (System Invariants)

1. **零闪烁策略 (Zero-Flicker Policy)**：
   * 空间 IoU $\ge 0.65$ 且文本相似度 $\ge 0.90$ 的未变动文本簇，生命周期内绝对保持原有 Overlay 气泡不动，严禁触发重绘与重排。
2. **零内存抖动策略 (Zero-Allocation Loop)**：
   * 在持续捕获帧（1.0Hz ~ 1.5Hz）的轮询过程中，严禁每秒反复通过 `Bitmap.createBitmap` 分配临时大图像。必须采用可复用的 Bitmap 缓冲区或按需双缓冲，严防频繁 GC 导致游戏掉帧。
3. **Android 14+ 管道绝对安全**：
   * 必须维持常驻 `VirtualDisplay`，严禁在实时轮询中销毁并重建 `VirtualDisplay`（规避 `SecurityException`）。
   * 必须严格处理 `rowPadding = rowStride - pixelStride * width`。
4. **两级消抖与缓存 (Debounce & LRU Cache)**：
   * 对打字机/动态文本采用 $400\text{ms}$ 迟滞消抖窗口。
   * 本地内存维护容量为 500 条的 `LruCache<String, String>`，命中缓存耗时 $0\text{ms}$，直接上屏。

---

## 2. 核心模块与文件拓扑

```
app/src/main/java/com/game/translator/
├── DiffEngine.kt               [NEW] 差异化计算引擎、几何/文本匹配器、消抖器与翻译缓存
├── OverlayManager.kt          [MODIFY] 新增差异化增量渲染管道 (applyDiffResult)
├── TranslatorService.kt       [MODIFY] 新增实时轮询调度协程、复用缓冲池、自适应休眠
└── MainActivity.kt            [MODIFY] 增加实时模式相关的配置 Key 与默认值 (暂不改动 UI 视图)
```

---

## 3. 详细接口契约与数据结构

### 3.1 DiffEngine.kt

```kotlin
// 差分结果集
data class DiffResult(
    val unchanged: List<ClusteredText>,       // 维持原样（已有气泡保持不动）
    val updated: List<ClusteredText>,         // 文本有变动（需更新气泡并请求翻译）
    val added: List<ClusteredText>,           // 新增区域（需创建新气泡并请求翻译）
    val removedIds: List<Int>,                // 已消失区域 ID（需平滑移除气泡）
    val needModelTranslation: List<ClusteredText> // 剔除命中 LRU 缓存后，真正需要请求 LLM 的条目
)

// 稳定化追踪条目（用于应对打字机特效）
data class StabilizingEntry(
    val cluster: ClusteredText,
    var firstSeenTime: Long,
    var lastContent: String,
    var isStable: Boolean = false
)
```

### 3.2 算法参数常量定义

| 参数名 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `IOU_MATCH_THRESHOLD` | `0.65f` | 判定为同一文本框的几何重合阈值 |
| `TEXT_SIMILARITY_THRESHOLD` | `0.90f` | 判定为同一台词内容的 Levenshtein 相似度阈值 |
| `DEBOUNCE_STABLE_WINDOW_MS` | `400L` | 文本稳定消抖窗口（毫秒） |
| `DEFAULT_SAMPLE_INTERVAL_MS` | `1200L`| 默认实时采样间隔（毫秒） |
| `IDLE_BACKOFF_INTERVAL_MS` | `2500L`| 连续无变化时的自适应降频采样间隔 |
| `MAX_TRANSLATION_CACHE_SIZE` | `500` | 内存 LRU 翻译缓存最大容量 |

---

## 4. 实施追踪状态清单 (Implementation Checklist)

- [x] **Phase 1: 核心引擎实现**
  - [x] 编写 `DiffEngine.kt`：实现空间 IoU 计算、Levenshtein 编辑距离、LRU 缓存管理与 Debounce 消抖追踪。
- [x] **Phase 2: 气泡层增量渲染改造**
  - [x] 改造 `OverlayManager.kt`：新增 `applyDiffResult` 方法，实现不重绘复用、平滑淡出失效气泡、流式增量注入。
- [x] **Phase 3: 调度管道与内存优化**
  - [x] 改造 `TranslatorService.kt`：增加实时翻译循环 `runRealtimeTranslationLoop()`，实现单例复用 Bitmap 缓冲池与自适应休眠。
  - [x] 改造交互逻辑：支持双击调配置/长按悬浮球无缝切换「单次截屏模式」与「实时差分模式」。
- [x] **Phase 4: 静态检查与配置声明**
  - [x] 在 `MainActivity.kt` 中补齐 `KEY_REALTIME_MODE`、`KEY_SAMPLE_INTERVAL_MS` 等持久化键名。
- [x] **Phase 5: 现代 UI 设计体系重构与暗/白双主题无泄露适配**
  - [x] 建立 `values/colors.xml`（浅色 Notion 白昼）与 `values-night/colors.xml`（深色 Linear 纯暗），彻底消除硬编码色值。
  - [x] 重构 `themes.xml` 实现 `android:windowLightStatusBar = ?attr/isLightTheme`，根治系统状态栏/导航栏颜色泄露。
  - [x] 重构 `activity_main.xml` 严格落实 4/6/8 圆角、24/32 留白与分组排版，100% 保留全部 27 个视图 ID 与现有业务绑定。
