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
        if (!isValidTranslation(originalText, translatedText)) return
        val key = buildCacheKey(ocrLang, originalText)
        if (key.isNotEmpty()) {
            val clean = translatedText.trim()
            translationCache.put(key, clean)
            val normInput = normalizeForCache(originalText)
            for ((_, tracked) in trackedMap) {
                val normTracked = normalizeForCache(tracked.originalText)
                if (normTracked == normInput || calculateSimilarity(tracked.originalText, originalText) >= 0.90f) {
                    tracked.translatedText = clean
                }
            }
        }
    }

    /**
     * 查询 LRU 缓存中是否已存在该句译文（支持标点归一化与 OCR 抖动模糊容错）
     */
    @Synchronized
    fun getCache(ocrLang: String, originalText: String): String? {
        val key = buildCacheKey(ocrLang, originalText)
        if (key.isNotEmpty()) {
            val exact = translationCache.get(key)
            if (exact != null) {
                if (isValidTranslation(originalText, exact)) {
                    return exact
                } else {
                    translationCache.remove(key)
                }
            }
        }

        // 模糊容错检索：防止 OCR 因标点噪点或单个字符抖动导致缓存击穿
        val normInput = normalizeForCache(originalText)
        if (normInput.length >= 3) {
            val snapshot = translationCache.snapshot()
            val langPrefix = "${ocrLang}_"
            var bestMatch: String? = null
            var bestSim = 0f

            for ((cachedKey, cachedTrans) in snapshot) {
                if (!cachedKey.startsWith(langPrefix)) continue
                val cachedNorm = cachedKey.removePrefix(langPrefix)
                val sim = calculateSimilarity(normInput, cachedNorm)
                if (sim >= 0.90f && sim > bestSim) {
                    if (isValidTranslation(originalText, cachedTrans)) {
                        bestSim = sim
                        bestMatch = cachedTrans
                    }
                }
            }
            if (bestMatch != null) {
                // 命中模糊缓存后写回精确键，加速后续帧匹配
                if (key.isNotEmpty()) {
                    translationCache.put(key, bestMatch)
                }
                return bestMatch
            }
        }
        return null
    }

    /**
     * 从 LRU 缓存与活动追踪表中彻底剔除某条有瑕疵/错误的译文（支持单句强制重译）
     */
    @Synchronized
    fun removeCache(ocrLang: String, originalText: String) {
        val key = buildCacheKey(ocrLang, originalText)
        if (key.isNotEmpty()) {
            translationCache.remove(key)
        }
        val normInput = normalizeForCache(originalText)
        val snapshot = translationCache.snapshot()
        val langPrefix = "${ocrLang}_"
        for ((cachedKey, _) in snapshot) {
            if (cachedKey.startsWith(langPrefix)) {
                val cachedNorm = cachedKey.removePrefix(langPrefix)
                if (cachedNorm == normInput || calculateSimilarity(normInput, cachedNorm) >= 0.90f) {
                    translationCache.remove(cachedKey)
                }
            }
        }
        for ((_, tracked) in trackedMap) {
            val normTracked = normalizeForCache(tracked.originalText)
            if (normTracked == normInput || calculateSimilarity(tracked.originalText, originalText) >= 0.90f) {
                tracked.translatedText = null
            }
        }
    }

    /**
     * 文本标点与控制字符归一化：消除 OCR 在首尾标点、空格、大小写上的微小抖动差异
     */
    fun normalizeForCache(text: String): String {
        return text.trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\p{Punct}\\p{IsPunctuation}，。！？；：、“”‘’·~—…（）\\[\\]{}《》\\-_]"), "")
            .lowercase()
            .trim()
    }

    /**
     * 校验大模型译文的合法性（自动防御坏译文入库或命中脏缓存）
     */
    fun isValidTranslation(original: String, translated: String?): Boolean {
        if (translated.isNullOrBlank()) return false
        val origNorm = normalizeForCache(original)
        val transNorm = normalizeForCache(translated)
        if (origNorm.isNotEmpty() && origNorm == transNorm) return false
        if (translated.trim().equals(original.trim(), ignoreCase = true)) return false
        if (translated.trim().startsWith("```") && !translated.contains("\n")) return false
        if (translated.contains("[id]") || translated.contains("[ID]")) return false
        return true
    }

    private fun buildCacheKey(ocrLang: String, text: String): String {
        val normalized = normalizeForCache(text)
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
        val selfCapturedTrackedIds = mutableSetOf<Int>()
        val unmatchedCurrent = mutableListOf<ClusteredText>()

        // 阶段 1：静态高 IoU 空间匹配与自截屏二次识别临时标记
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

                // 核心防线 1：自截屏污染识别 (Self-Capture OCR Poisoning Filter)
                // 若当前 OCR 识别到的文本，与该气泡已展示的译文高度吻合，
                // 说明是截屏捕获到了自身悬浮气泡中的中文译文。
                // 标记入 selfCapturedTrackedIds，允许阶段 2 真实发生位移的外文原文接管该条目！
                val isSelfBubbleCaptured = bestMatch.translatedText != null &&
                        (calculateSimilarity(curr.originalText, bestMatch.translatedText!!) >= 0.60f ||
                         (curr.originalText.trim().length >= 3 && bestMatch.translatedText!!.contains(curr.originalText.trim())))

                if (isSelfBubbleCaptured) {
                    selfCapturedTrackedIds.add(bestMatch.id)
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
        // 针对阶段 1 未通过静态 IoU 匹配的文本，按文本高相似度寻找发生平移的存量气泡
        val stillUnmatchedCurrent = mutableListOf<ClusteredText>()
        for (curr in unmatchedCurrent) {
            var kinematicMatch: TrackedCluster? = null
            var bestSim = 0f
            var isOverridingSelfCapture = false

            val normCurr = normalizeForCache(curr.originalText)

            for ((_, tracked) in trackedMap) {
                // 若已被普通条目匹配则跳过；但若此前仅被自截屏占用，允许真实发生位移的源外文接管！
                val isSelfCapturedOnly = selfCapturedTrackedIds.contains(tracked.id)
                if (matchedTrackedIds.contains(tracked.id) && !isSelfCapturedOnly) continue

                val normTracked = normalizeForCache(tracked.originalText)
                val simOriginal = calculateSimilarity(curr.originalText, tracked.originalText)
                val simTrans = if (tracked.translatedText != null) {
                    calculateSimilarity(curr.originalText, tracked.translatedText!!)
                } else 0f
                val effectiveSim = max(simOriginal, simTrans)

                val isExactNormMatch = normCurr.isNotEmpty() && normCurr == normTracked
                val isHighSim = effectiveSim >= 0.88f

                // 网页滚动/重排可能伴随横向微调，只要文本高度一致（>=0.88 或规范化相等）即可直接判定为同一气泡位移
                if (isExactNormMatch || isHighSim) {
                    if (effectiveSim > bestSim || isExactNormMatch) {
                        bestSim = if (isExactNormMatch) 1.0f else effectiveSim
                        kinematicMatch = tracked
                        isOverridingSelfCapture = isSelfCapturedOnly
                        if (isExactNormMatch) break
                    }
                }
            }

            if (kinematicMatch != null) {
                if (isOverridingSelfCapture) {
                    // 真实外文在新坐标出现，剔除旧坐标自截屏产生的假 unchanged
                    unchanged.removeAll { it.id == kinematicMatch.id }
                    selfCapturedTrackedIds.remove(kinematicMatch.id)
                }
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

        // 阶段 3：全新文本簇处理（前置检索本地 LRU 缓存，命中即时赋予并上屏，消除 400ms 冗余消抖）
        for (curr in stillUnmatchedCurrent) {
            val cachedText = getCache(ocrLang, curr.originalText)
            val newId = nextClusterId++
            val isCached = cachedText != null
            val newTracked = TrackedCluster(
                id = newId,
                originalText = curr.originalText,
                translatedText = cachedText,
                boundingBox = curr.boundingBox,
                firstSeenTime = currentTime,
                isStable = isCached,    // 命中缓存直接视为稳定，无需消抖延迟
                isDisplayed = isCached  // 命中缓存本帧直接上屏呈现
            )
            trackedMap[newId] = newTracked
            matchedTrackedIds.add(newId)

            if (isCached) {
                added.add(
                    ClusteredText(
                        id = newId,
                        originalText = curr.originalText,
                        translatedText = cachedText,
                        boundingBox = curr.boundingBox
                    )
                )
            }
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

        // 阶段 5：查询 LRU 翻译缓存与提取真正需要请求大模型的待翻译项
        val cachedMap = mutableMapOf<Int, String>()
        val needModel = mutableListOf<ClusteredText>()

        for (item in (added + updated)) {
            // 若 item 已经拥有有效译文（如 Phase 3 缓存即时赋予）
            if (item.translatedText != null && isValidTranslation(item.originalText, item.translatedText)) {
                cachedMap[item.id] = item.translatedText!!
                continue
            }
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
