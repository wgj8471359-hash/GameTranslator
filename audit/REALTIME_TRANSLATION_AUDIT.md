# GameTranslator 实时翻译状态审计

## 修复状态（提交前快照，均未经编译/实机验证）

| 项 | 状态 | 说明 |
|---|---|---|
| F1 静止文字不进队列 | 已修复 | 重写 DiffEngine：静止候选必过稳定窗口进入 needModelTranslation；缓存命中即时 added 上屏 |
| F2 自捕获吞新台词 | 已修复 | 实时截屏前经 OverlayManager.withHiddenForCapture 隐藏浮层等待干净帧，彻底移除光学自捕获启发式 |
| F3 在途请求当有效期 | 已修复 | ID 即内容版本且永不复用；回调经 getActiveCluster 版本校验；离屏即退役，结果仅入缓存 |
| F4 任务清理混乱 | 已修复 | 请求挂 realtimeRequestScope 会话任务树，停止时整树取消；ID 单调不复用 |
| F5 迟到结果旧坐标 | 已修复 | 展示前用 getActiveCluster 最新坐标重建条目；内容相同移动输出 moved |
| F6 失败伪装成功 | 已修复 | Form B 失败置空译文；onSuccess 空译文走 markFailed 指数退避重试 |
| F7 重译无守卫 | 已修复 | 重译捕获 startEpoch + 版本校验 + preShowCheck + finally 清 in-flight |
| F8 触控模式失同步 | 未修 | 属于交互一致性问题，本轮未涉及（低风险，可下轮） |
| F9 配置/缓存一致性 | 部分修复 | 模糊缓存已移除；configureCacheNamespace 已提供但未接线（模型配置不自动清缓存） |

复审（两轮独立静态复审，最终一轮仅剩3项且均已修复确认）：第一轮 BLOCK 5 项全部修复；第二轮复核 3 项误判/遗漏已修。
已知未验证项：整项目编译（无本地工具链，由 GitHub Actions 编译验证）、真机行为（用户自测）。

## 结论与范围

当前实时模式存在核心状态机缺陷，不只是模型速度或 OCR 精度不佳。最严重的是：冷缓存的静止文字不能正常从候选态转入翻译队列；已上屏气泡可能长期遮蔽新台词；在途翻译被错误地当成“画面仍有效”。

本轮没有修改 Android/Kotlin 业务代码、依赖或配置。审阅了 TranslatorService、DiffEngine、OverlayManager、HyMtClient、OcrHelper，以及相关 MainActivity 配置和 Manifest。

验证边界：
- 源码控制流可确定的问题与设备相关风险分开记录。
- `audit\realtime_state_probe.py` 是选定分支的 Python 简化模型，不是对 Kotlin 生产实现的调用。4/4 检查确认旧行为，不能当作修复通过的单元测试。
- 本机 PATH 未发现 kotlinc、gradle、adb，用户 Gradle 缓存目录不存在，ANDROID_HOME 指向位置没有 platform-tools\adb.exe；没有执行 Android 构建、真机 OCR、网络抓包或交互测试，也没有核对用户安装 APK 与本次源码版本是否一致。
- 尝试的独立复核子任务运行失败，以下结论由主审计直接检查源码形成，未声称获得独立复核。

分级：P1 = 核心功能错误，优先修；P2 = 状态/体验/恢复性问题，随后修。没有把普通功能缺陷标成安全漏洞或灾难级 P0。

## F1 · P1 · 静止新文字永远到不了稳定/待翻译态（确定）

证据：`DiffEngine.kt:406–418, 421–443, 522–551, 583–605`。

最小序列：
1. 冷缓存，t=0 首次识别文字 A：阶段 3 只创建 isStable=false、isDisplayed=false 的追踪条目；不输出 added。
2. t=1200ms，完全相同文字、相同框，simOriginal=1；406 行进入 unchanged 并 continue。
3. 此后每帧重复第 2 步，永远不执行 431–443 行的稳定判定。
4. 模型候选只从 added+updated 提取，故始终没有请求。

更严格地说，默认阈值下 430 行 else 要求 originalText==curr.originalText，但相等时早在 406 行就已 continue，所以这个分支不可达。文字真正变化后的状态也会先清空译文、重置计时，下一帧又进入相同的死路。

体验：紫色实时球看起来已开启，但没译文；先手动翻译再开实时可能“突然好了”（缓存命中走另一条路径）；移动文字有时触发阶段 2，也会让表现变得不稳定。

建议：把候选稳定化与“已完成结果无需刷新”分开。内容相同且候选未稳定时必须推进稳定计时；稳定且没有有效译文、没有有效请求时可排队。不要只往变化分支里塞一个请求，也不要对每个 unchanged 无条件重译。

## F2 · P1 · 自捕获规避把真正的新台词也屏蔽了（确定逻辑；光学表现待实机）

证据：`TranslatorService.kt:683–712`；`DiffEngine.kt:306–329, 393–418`。

实时截图没有像手动模式（915–918 行）那样隐藏浮层。引擎用气泡矩形覆盖率和文字相似度“猜测”识别内容来自自己的气泡。

只要历史条目已显示/在途，且当前框落在可见气泡里（coverage>=0.5 或 IoU>=0.4），就无条件归为自捕获；不要求当前原文仍相同。即使 OCR 真的认到了不同台词 B，也会保留 A 的原文和译文。匹配还会把 missingFrames 清零，所以气泡读到自身译文时可能一直不消失。与译文仅共用一个 CJK 字也可触发另一条豁免路径。

体验：对话已经推进，翻译停在上一句；页面切换后残留旧气泡；点击原文会暂时改变 OCR 输入。

建议：优先解决截图输入中包含浮层的问题。可评估短暂隐藏浮层并等待新合成帧的实现与闪烁成本；恢复必须放在 finally。不能仅遮罩/忽略气泡区域，因为那仍看不到被遮住的新台词。对捕获能力的选型应遵循系统支持的接口，不使用隐藏接口或规避系统保护。

## F3 · P1 · 在途请求被当成内容/可见性不变，旧结果覆盖新画面（确定）

证据：`DiffEngine.kt:406, 560–579, 94–96`；`TranslatorService.kt:745–775`。

两条独立路径：
- 同一位置由 A 变 B，isInFlight=true 时直接 unchanged，A 仍是追踪原文。
- A 从屏幕消失，在途条目享有绝对不移除豁免；连续丢失十帧也仍 active。

回调只检查 epoch、实时模式开启、ID 存在。普通换台词/换页面没有推进 epoch；ID 存在也不代表这个请求对应的内容仍在当前画面。于是 A 迟到后可上屏到 B 或空白页面。

建议：请求使用不可变键 `(sessionGeneration, trackId, contentRevision, requestId)`；内容变化立即使旧 revision 失效。可见性过期必须独立于网络任务；允许取消请求或只保存历史缓存，但不得显示过期结果。入缓存与写追踪状态也需要区分：历史结果可入独立缓存，不应凭 ID 覆盖已变内容。

## F4 · P1 · 停止/旋转后任务未成组取消，旧 finally 能污染新会话（确定结构；时序可构造）

证据：`TranslatorService.kt:409–412, 631–641, 749, 779–784`；`DiffEngine.kt:77–80, 103–105`；`HyMtClient.kt:32–39, 287, 324–328`。

模型任务用 serviceScope.launch 创建，与 realtimeJob 是同级任务。stopRealtimeMode 仅取消采样循环，并未取消这批 Job/HTTP 请求（完整停服务另有取消逻辑，不要混淆）。reset 又把 ID 从 1 开始。

最小序列：旧会话 ID=1 请求未完 → 关闭再开启/旋转 reset → 新会话也有 ID=1 → 旧任务终于完成 → finally 不检查 epoch，removeAll 并 markInFlight(false)，清掉新请求的在途标记。回调的 epoch 检查不能保护 finally。

另外 Form B 的 Semaphore 每次调用新建，只限制单批请求；多个采样批次/重译并发时，用户设置的并发数不是全局上限。

建议：按实时会话持有父 Job、按请求拥有 Call 和唯一令牌；停止时取消整个会话任务树，并把协程取消绑定到 Call.cancel。清理必须按 requestId 比较后删除。并发限制放在共享调度器，而不是每批重新创建。

## F5 · P2 · 小幅移动不刷新，迟到请求使用旧坐标（确定）

证据：`DiffEngine.kt:406–418, 483–515`；`TranslatorService.kt:728–730, 754–775`；`OverlayManager.kt:568–595, 625–629`。

相同文字若移动后仍满足 IoU 匹配，会更新追踪 boundingBox，却只输出 unchanged。hasVisualChanges 不包括 unchanged；即使执行 apply，已有 unchanged 气泡也不更新坐标。因此慢速滚动可能“黏住”，移动足够远进入第二阶段才跳动。

异步回调又拿发请求时的 toTranslate 对象渲染，而不是读取最新追踪坐标。跟踪器替换 Rect，不会更新旧请求对象，因此返回时可退回旧位置。整批 onSuccess 还会把已单句完成的条目再渲染一遍，进一步放大晚到回跳。

建议：内容状态与几何状态独立；相同内容只要有超出抖动阈值的移动就发 geometry 更新。网络结果只携带翻译文本，展示时按版本读取当前坐标，不把截图时坐标绑定到迟到结果。

## F6 · P1 · 网络失败被包装成成功，实时模式无错误/重试态（确定）

证据：`HyMtClient.kt:330–334, 381–398`；`TranslatorService.kt:768–784`；`OverlayManager.kt:291–299`；`DiffEngine.kt:587–605`。

默认 Form B 遇到非 2xx 或大多数异常，回调原文、isFinished=true，最后返回 Result.success。气泡层又会删除“译文等于原文”的气泡。实时调用没有 onFailure 展示或重试调度；一旦条目走到已显示但无译文的状态，也没有从 unchanged 中再次排队的通路。

体验：接口错误、超时或 API Key 不对时，没有清楚的失败提示，只像“没识别到”“一直等着”；网络恢复后原地停留不一定恢复。

建议：逐条建模 queued / translating / translated / failed / cancelled，保持失败原因；有界指数退避，仅重试当前有效版本。无译文不等同于成功；界面至少展示最近一次成功时间、错误摘要和待处理数。失败不能伪造为原文翻译成功。

## F7 · P1 · 强制重译没有会话/内容校验，交互绑定可能过期（确定）

证据：`TranslatorService.kt:1054–1089`；`OverlayManager.kt:301–329, 436–459`；`DiffEngine.kt:110–124`。

重译任务直接挂 serviceScope，不保存任务、不检查 epoch、不检查 track 是否仍活跃，也不校验当前文本版本。长按 A 后滚动/旋转/停止再开，只要新的浮层已存在并解除 dismissed，迟到回调就能创建旧 A 气泡或更新复用 ID。putCache 的 ID 命中条件还可能把结果写到别的原文追踪项。

气泡的点击/长按监听捕获的是首次创建时的 item；后续同 ID 更新文本/位置只刷新 View，不替换这个绑定。故显示新内容不保证点击切换和重译针对的也是新内容。

建议：重译走同一版本化调度器；使用最新 ID/revision 查询交互目标或显式更新绑定。重译失败应恢复已确认译文并显示错误，而不是留下占位或半截文本。

## F8 · P2 · 紫/橙指示不等于浮层实际触控模式（确定）

证据：`TranslatorService.kt:620–623, 886–895, 409–412`；`OverlayManager.kt:168–199, 691–720`。

浮层尚未建立时点击悬浮球，Service 已翻转 isRealtimePassthrough、球变橙色；setTouchPassthrough 因 root=null 直接返回。之后新建浮层一律以实时默认穿透 flags 创建，所以橙色也可能无法点气泡。旋转导致 dismiss/recreate 时也会丢失浮层交互态，但 Service 仍保留旧值。

建议：只有一个持久化的期望触控模式，浮层创建/重建都应用它；UI 颜色根据成功应用后的实际模式更新，而非在调用前乐观宣告成功。

## F9 · P2 · 配置、缓存和采样节奏不一致（确定）

证据：`TranslatorService.kt:658–677, 807–820`；`DiffEngine.kt:70–71, 229–232`；`HyMtClient.kt:297`；`MainActivity.kt:226–255`。

- 采样间隔只在循环启动时读取：运行中保存不会改变现有循环。
- 实际周期是 delay + 截图等待 + OCR + 差分，不是准确的 1.2 秒。未变化五轮后变 2.5 秒，所以“跟手/实时”注释过强；用户若设置 5 秒，idle 反而变成 2.5 秒。
- 缓存只包含 OCR 语言和归一化原文，不含模型/提示词配置；切模型后继续使用旧译文。
- 默认 Form B 使用写死的单句 prompt，没有消费用户配置的 systemPrompt；调提示词可能完全不生效。
- 模糊缓存 0.90 与追踪 0.85 的门槛会把长句中数字、否定词等少量但关键变化视为相同，不能拿字符相似度当语义等价。

建议：配置变更明确即时生效范围；翻译相关配置形成 cache namespace/revision；按源内容身份严格失效，用模糊匹配只做候选关联而不是直接宣告结果正确。分离采样频率、稳定等待、接口延迟和显示延迟指标。

## R1 · 设备相关风险 · Android 12+ 穿透兼容性需要实测

`OverlayManager.kt:187–207` 设置全屏 TYPE_APPLICATION_OVERLAY + FLAG_NOT_TOUCHABLE，没有显式设置 WindowManager.LayoutParams.alpha；345–346 行只改气泡背景 alpha，二者不同。

Android 官方说明跨 UID 穿透受窗口 opacity 和多个浮窗组合 opacity 限制。但部分 AOSP 版本有自动降低这类窗口 alpha 的兼容补丁。因此不能静态断言所有 Android 12+ 设备必然无法穿透；可以确认“加 FLAG_NOT_TOUCHABLE 就 100% 穿透”的注释不构成保证。

验证时在实际游戏（不同 UID）测滑动、摇杆和空白点击，检查 Logcat 的 `Untrusted touch due to occlusion`，不要只在 App 自己的页面测试。应使用公开接口获取阈值并正确配置窗口，不能以关闭系统安全保护作为发布修复。

来源：
- https://developer.android.com/about/versions/12/behavior-changes-all
- https://developer.android.com/reference/android/view/WindowManager.LayoutParams
- https://github.com/aosp-mirror/platform_frameworks_base/commit/05ebe2894fdaade99666ddf6acf8f550b3924821

## 建议修复顺序

1. F1：修状态转换，给固定文字/固定坐标添加生产实现的回归用例。
2. F2：定义无自捕获污染的观察策略；否则状态机修好仍可能看不到真实台词。
3. F3/F4/F7：统一请求版本、会话任务树和交互数据绑定；必须同时保护回调、入缓存、UI 队列执行和 finally。
4. F6：明确失败状态和重试/取消语义，补可观测性。
5. F5/F8/F9：几何跟踪、触控重建和配置一致性。
6. 真机覆盖 Android 12/14+、不同游戏、横竖屏及慢网场景后再宣布稳定。

不建议先调低采样间隔、增大并发、放宽相似度或继续增加“绝不删除”的豁免；这些措施无法修复状态转换，还可能放大卡顿和旧译文残留。

## 必须补齐的回归验收

| 场景 | 应有结果 |
|---|---|
| 冷缓存，静止文字 A 连续三帧 | 稳定窗口后只排队一次，不要求先移动/手动翻译 |
| 同框 A→B（含仅一处数字/否定变化） | A 结果失效，B 稳定后重译 |
| 气泡显示 A 时游戏已换到 B | 能观察到 B；不能只读自己并无限续命 |
| A 请求慢，A 已离屏/替换 | A 返回不覆盖当前画面 |
| 翻译中缓慢移动文本 | 使用最新坐标，不回跳请求时坐标 |
| 关闭重开/旋转，旧请求最后结束 | 不清理新请求标记、不污染新轨迹 |
| 长按重译后立即切页/关闭重开 | 不弹回旧气泡、不写入新 ID |
| 401、超时、SSE 错误、恢复联网 | 显示失败，有限重试，无假成功 |
| 第一批气泡前点橙色/交互态旋转 | 实际触控状态与球颜色一致 |
| 保存间隔/切模型/改提示词 | 生效范围明确，缓存不会冒充新配置结果 |

建议调试事件记录：会话代次、轨迹 ID、内容版本、请求 ID、状态转换原因、采样/模型耗时、当前在途数、丢弃旧结果原因。避免记录 API Key；原文/截图可能敏感，应默认不落日志。
