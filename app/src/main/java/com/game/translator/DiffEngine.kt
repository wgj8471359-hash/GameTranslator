package com.game.translator

import android.graphics.Rect
import android.util.LruCache
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 差分计算结果
 */
data class DiffResult(
    val unchanged: List<ClusteredText>,               // 几何与文本均无显著变化的簇（保持当前气泡）
    val moved: List<ClusteredText>,                   // 内容未变但发生了空间平移（如网页/漫画滚动）的簇（原地平移更新坐标）
    val updated: List<ClusteredText>,                 // 几何重合但文本发生变化的簇（更新气泡内容）
    val added: List<ClusteredText>,                   // 当前帧新出现的簇（创建新气泡）
    val removedIds: List<Int>,                        // 上一帧存在但当前帧消失的簇 ID（移除气泡）
    val cachedMap: Map<Int, String>,                  // 命中 LRU 翻译缓存的簇 ID -> 已有译文映射
    val needModelTranslation: List<ClusteredText>     // 剔除命中缓存后，真正需要请求大模型翻译的簇
) {
    val hasVisualChanges: Boolean
        get() = moved.isNotEmpty() || updated.isNotEmpty() || added.isNotEmpty() || removedIds.isNotEmpty()
}

/**
 * 内部追踪条目：保存几何包围盒、内容、持久化 ID 及稳定化消抖状态
 */
private data class TrackedCluster(
    val id: Int,
    var originalText: String,
    var translatedText: String? = null,
    var boundingBox: Rect,
    var firstSeenTime: Long,
    var isStable: Boolean = false,
    var isDisplayed: Boolean = false
)

/**
 * 实时截屏差分引擎：
 * 负责两帧之间的空间包围盒 IoU 匹配、文本编辑距离相似度计算、
 * 打字机出字防抖动（Debounce）以及本地 LRU 翻译记忆缓存。
 */
class DiffEngine(
    private val iouThreshold: Float = IOU_MATCH_THRESHOLD,
    private val similarityThreshold: Float = TEXT_SIMILARITY_THRESHOLD,
    private val debounceWindowMs: Long = DEBOUNCE_STABLE_WINDOW_MS,
    cacheCapacity: Int = MAX_TRANSLATION_CACHE_SIZE
) {

    companion object {
        const val IOU_MATCH_THRESHOLD = 0.65f
        const val TEXT_SIMILARITY_THRESHOLD = 0.90f
        const val DEBOUNCE_STABLE_WINDOW_MS = 400L
        const val DEFAULT_SAMPLE_INTERVAL_MS = 1200L
        const val IDLE_BACKOFF_INTERVAL_MS = 2500L
        const val MAX_TRANSLATION_CACHE_SIZE = 500
    }

    // 单调递增的持久化跟踪 ID 生成器（彻底杜绝跨帧 ID 碰撞与错位）
    private var nextClusterId = 1

    // 空间状态追踪表：Key 为持久化跟踪 ID
    private val trackedMap = mutableMapOf<Int, TrackedCluster>()

    // 本地内存 LRU 翻译缓存：Key = "${ocrLang}_${normalizedText}", Value = translatedText
    private val translationCache = object : LruCache<String, String>(cacheCapacity) {}

    /**
     * 重置差分引擎状态（例如切入新界面、重新启动服务或用户主动清屏）
     */
    @Synchronized
    fun reset() {
        trackedMap.clear()
        nextClusterId = 1
    }

    /**
     * 清空翻译内存缓存
     */
    @Synchronized
    fun clearCache() {
        translationCache.evictAll()
    }

    /**
     * 将翻译结果存入 LRU 缓存并同步更新当前追踪项
     */
    @Synchronized
    fun putCache(ocrLang: String, originalText: String, translatedText: String) {
        val key = buildCacheKey(ocrLang, originalText)
        if (key.isNotEmpty() && translatedText.isNotBlank()) {
            val clean = translatedText.trim()
            translationCache.put(key, clean)
            for ((_, tracked) in trackedMap) {
                if (tracked.originalText.trim() == originalText.trim()) {
                    tracked.translatedText = clean
                }
            }
        }
    }

    /**
     * 查询 LRU 缓存中是否已存在该句译文
     */
    @Synchronized
    fun getCache(ocrLang: String, originalText: String): String? {
        val key = buildCacheKey(ocrLang, originalText)
        return if (key.isNotEmpty()) translationCache.get(key) else null
    }

    private fun buildCacheKey(ocrLang: String, text: String): String {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        return if (normalized.isEmpty()) "" else "${ocrLang}_$normalized"
    }

    /**
     * 计算两个矩形的交并比 (Intersection over Union, IoU)
     */
    fun calculateIoU(rectA: Rect, rectB: Rect): Float {
        val intersectLeft = max(rectA.left, rectB.left)
        val intersectTop = max(rectA.top, rectB.top)
        val intersectRight = min(rectA.right, rectB.right)
        val intersectBottom = min(rectA.bottom, rectB.bottom)

        val intersectWidth = max(0, intersectRight - intersectLeft)
        val intersectHeight = max(0, intersectBottom - intersectTop)
        val intersectArea = intersectWidth * intersectHeight

        if (intersectArea <= 0) return 0f

        val areaA = rectA.width() * rectA.height()
        val areaB = rectB.width() * rectB.height()
        val unionArea = areaA + areaB - intersectArea

        return if (unionArea > 0) intersectArea.toFloat() / unionArea else 0f
    }

    /**
     * 计算两段文本的归一化相似度 (基于 Levenshtein 动态规划编辑距离)
     */
    fun calculateSimilarity(s1: String, s2: String): Float {
        val clean1 = s1.trim()
        val clean2 = s2.trim()
        if (clean1 == clean2) return 1.0f
        if (clean1.isEmpty() || clean2.isEmpty()) return 0.0f

        val len1 = clean1.length
        val len2 = clean2.length
        val maxLen = max(len1, len2)

        var prevRow = IntArray(len2 + 1) { it }
        var currRow = IntArray(len2 + 1)

        for (i in 1..len1) {
            currRow[0] = i
            val char1 = clean1[i - 1]
            for (j in 1..len2) {
                val cost = if (char1 == clean2[j - 1]) 0 else 1
                currRow[j] = min(
                    min(currRow[j - 1] + 1, prevRow[j] + 1),
                    prevRow[j - 1] + cost
                )
            }
            val temp = prevRow
            prevRow = currRow
            currRow = temp
        }

        val distance = prevRow[len2]
        return 1.0f - (distance.toFloat() / maxLen.toFloat())
    }

    /**
     * 核心差分调度：将当前帧识别出的文本簇与历史追踪表对比，
     * 执行几何匹配、文本匹配、消抖与缓存预取。
     */
    @Synchronized
    fun processFrame(
        currentClusters: List<ClusteredText>,
        ocrLang: String,
        currentTime: Long = System.currentTimeMillis()
    ): DiffResult {
        val unchanged = mutableListOf<ClusteredText>()
        val moved = mutableListOf<ClusteredText>()
        val updated = mutableListOf<ClusteredText>()
        val added = mutableListOf<ClusteredText>()
        val matchedTrackedIds = mutableSetOf<Int>()
        val unmatchedCurrent = mutableListOf<ClusteredText>()

        // 阶段 1：静态高 IoU 空间匹配与自截屏二次识别阻断
        for (curr in currentClusters) {
            var bestMatch: TrackedCluster? = null
            var bestIoU = 0f

            for ((_, tracked) in trackedMap) {
                if (matchedTrackedIds.contains(tracked.id)) continue
                val iou = calculateIoU(curr.boundingBox, tracked.boundingBox)
                if (iou > bestIoU && iou >= iouThreshold) {
                    bestIoU = iou
                    bestMatch = tracked
                }
            }

            if (bestMatch != null) {
                matchedTrackedIds.add(bestMatch.id)

                // 核心防线 1：自截屏污染阻断 (Self-Capture OCR Poisoning Filter)
                // 若当前 OCR 识别到的文本，与该气泡已展示的译文高度吻合，
                // 说明是截屏捕获到了自身悬浮气泡中的中文译文，绝不是新的外文输入！
                val isSelfBubbleCaptured = bestMatch.translatedText != null &&
                        (calculateSimilarity(curr.originalText, bestMatch.translatedText!!) >= 0.60f ||
                         (curr.originalText.trim().length >= 3 && bestMatch.translatedText!!.contains(curr.originalText.trim())))

                if (isSelfBubbleCaptured) {
                    bestMatch.boundingBox = curr.boundingBox
                    unchanged.add(
                        ClusteredText(
                            id = bestMatch.id,
                            originalText = bestMatch.originalText,
                            translatedText = bestMatch.translatedText,
                            boundingBox = curr.boundingBox
                        )
                    )
                    continue
                }

                val similarity = calculateSimilarity(curr.originalText, bestMatch.originalText)
                if (similarity >= similarityThreshold) {
                    // 内容几何与文本高度吻合
                    bestMatch.boundingBox = curr.boundingBox
                    if (bestMatch.isDisplayed) {
                        unchanged.add(
                            ClusteredText(
                                id = bestMatch.id,
                                originalText = bestMatch.originalText,
                                translatedText = bestMatch.translatedText,
                                boundingBox = curr.boundingBox
                            )
                        )
                    } else {
                        // 处于新增消抖窗口中，检查是否已经稳定
                        val elapsed = currentTime - bestMatch.firstSeenTime
                        if (elapsed >= debounceWindowMs) {
                            bestMatch.isStable = true
                            bestMatch.isDisplayed = true
                            added.add(
                                ClusteredText(
                                    id = bestMatch.id,
                                    originalText = curr.originalText,
                                    translatedText = bestMatch.translatedText,
                                    boundingBox = curr.boundingBox
                                )
                            )
                        }
                    }
                } else {
                    // 几何位置重合但文本发生变动（台词推进或打字机出字）
                    if (bestMatch.originalText != curr.originalText) {
                        bestMatch.originalText = curr.originalText
                        bestMatch.boundingBox = curr.boundingBox
                        bestMatch.firstSeenTime = currentTime
                        bestMatch.isStable = false
                    } else {
                        val elapsed = currentTime - bestMatch.firstSeenTime
                        if (elapsed >= debounceWindowMs) {
                            bestMatch.isStable = true
                            bestMatch.isDisplayed = true
                            updated.add(
                                ClusteredText(
                                    id = bestMatch.id,
                                    originalText = curr.originalText,
                                    translatedText = null,
                                    boundingBox = curr.boundingBox
                                )
                            )
                        }
                    }
                }
            } else {
                unmatchedCurrent.add(curr)
            }
        }

        // 阶段 2：运动学平移跟踪 (Kinematic Translation Tracker for Webpage/Manga Scrolling)
        // 针对阶段 1 未通过静态 IoU 匹配的文本，按文本高相似度与水平对齐寻找发生垂直平移的存量气泡
        val stillUnmatchedCurrent = mutableListOf<ClusteredText>()
        for (curr in unmatchedCurrent) {
            var kinematicMatch: TrackedCluster? = null
            var bestSim = 0f

            for ((_, tracked) in trackedMap) {
                if (matchedTrackedIds.contains(tracked.id)) continue

                // 检查是否文本一致（原外文一致，或者截屏读出的是已知译文）
                val simOriginal = calculateSimilarity(curr.originalText, tracked.originalText)
                val simTrans = if (tracked.translatedText != null) {
                    calculateSimilarity(curr.originalText, tracked.translatedText!!)
                } else 0f
                val effectiveSim = max(simOriginal, simTrans)

                // 水平 X 坐标偏差与宽度变化在合理阈值内（页面主要发生 Y 轴上下滚动）
                val hDist = abs(curr.boundingBox.left - tracked.boundingBox.left)
                val wDist = abs(curr.boundingBox.width() - tracked.boundingBox.width())
                val isHorizontallyAligned = hDist <= max(40, (tracked.boundingBox.width() * 0.35f).toInt()) &&
                        wDist <= max(50, (tracked.boundingBox.width() * 0.40f).toInt())

                if (effectiveSim >= 0.85f && isHorizontallyAligned && effectiveSim > bestSim) {
                    bestSim = effectiveSim
                    kinematicMatch = tracked
                }
            }

            if (kinematicMatch != null) {
                matchedTrackedIds.add(kinematicMatch.id)
                kinematicMatch.boundingBox = curr.boundingBox

                if (kinematicMatch.isDisplayed) {
                    // 已在屏幕上：分发 moved 状态，指令 Overlay 原地平移，绝不销毁重建，零延迟零闪烁
                    moved.add(
                        ClusteredText(
                            id = kinematicMatch.id,
                            originalText = kinematicMatch.originalText,
                            translatedText = kinematicMatch.translatedText,
                            boundingBox = curr.boundingBox
                        )
                    )
                } else {
                    val elapsed = currentTime - kinematicMatch.firstSeenTime
                    if (elapsed >= debounceWindowMs) {
                        kinematicMatch.isStable = true
                        kinematicMatch.isDisplayed = true
                        added.add(
                            ClusteredText(
                                id = kinematicMatch.id,
                                originalText = kinematicMatch.originalText,
                                translatedText = kinematicMatch.translatedText,
                                boundingBox = curr.boundingBox
                            )
                        )
                    }
                }
            } else {
                stillUnmatchedCurrent.add(curr)
            }
        }

        // 阶段 3：真正全新的文本簇（分配新 ID）
        for (curr in stillUnmatchedCurrent) {
            val newId = nextClusterId++
            val newTracked = TrackedCluster(
                id = newId,
                originalText = curr.originalText,
                translatedText = null,
                boundingBox = curr.boundingBox,
                firstSeenTime = currentTime,
                isStable = false,
                isDisplayed = false
            )
            trackedMap[newId] = newTracked
            matchedTrackedIds.add(newId)
        }

        // 阶段 4：判定真正消失的气泡（完全划出屏幕或被覆盖）
        val removedIds = mutableListOf<Int>()
        val iterator = trackedMap.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val tracked = entry.value
            if (!matchedTrackedIds.contains(tracked.id)) {
                if (tracked.isDisplayed) {
                    removedIds.add(tracked.id)
                }
                iterator.remove()
            }
        }

        // 阶段 5：查询 LRU 翻译缓存与提取待翻译项
        val cachedMap = mutableMapOf<Int, String>()
        val needModel = mutableListOf<ClusteredText>()

        for (item in (added + updated)) {
            val cachedTranslation = getCache(ocrLang, item.originalText)
            if (cachedTranslation != null) {
                item.translatedText = cachedTranslation
                cachedMap[item.id] = cachedTranslation
                trackedMap[item.id]?.translatedText = cachedTranslation
            } else {
                needModel.add(item)
            }
        }

        return DiffResult(
            unchanged = unchanged,
            moved = moved,
            updated = updated,
            added = added,
            removedIds = removedIds,
            cachedMap = cachedMap,
            needModelTranslation = needModel
        )
    }
}
