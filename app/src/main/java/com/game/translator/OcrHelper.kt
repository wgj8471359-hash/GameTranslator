package com.game.translator

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

data class ClusteredText(
    val id: Int,
    val originalText: String,
    var translatedText: String? = null,
    val boundingBox: Rect
)

class OcrHelper {

    companion object {
        const val LANG_AUTO = "auto"
        const val LANG_KOREAN = "korean"
        const val LANG_JAPANESE = "japanese"
        const val LANG_CHINESE = "chinese"
        const val LANG_LATIN = "latin"
    }

    // 按需惰性初始化的多语言离线识别引擎（零网络、零审查）
    private val koreanRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    private val japaneseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val chineseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * 并查集 (Disjoint-Set / Union-Find) 实现
     */
    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size) { 0 }

        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) {
                root = parent[root]
            }
            // 路径压缩
            var curr = i
            while (curr != root) {
                val nxt = parent[curr]
                parent[curr] = root
                curr = nxt
            }
            return root
        }

        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI != rootJ) {
                if (rank[rootI] < rank[rootJ]) {
                    parent[rootI] = rootJ
                } else if (rank[rootI] > rank[rootJ]) {
                    parent[rootJ] = rootI
                } else {
                    parent[rootJ] = rootI
                    rank[rootI]++
                }
            }
        }
    }

    private suspend fun processRecognizer(recognizer: TextRecognizer, inputImage: InputImage): Text {
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { text ->
                    if (continuation.isActive) {
                        continuation.resume(text)
                    }
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(exception)
                    }
                }
        }
    }

    /**
     * 截屏 Bitmap 识别并进行 2D 并查集几何聚类
     * @param ocrLanguage 源语言类型（auto / korean / japanese / chinese / latin）
     * @param lineGapRatio 垂直行距容差倍率（默认 1.2）
     * @param minTextLength 最小文本长度过滤阈值（默认 2，过滤杂质噪点）
     * @param horizontalOverlapToleranceDp 水平投影重叠容差（单位 dp，默认 -20dp）
     * @param density 屏幕密度比例，用于将 dp 转换为 px
     */
    suspend fun recognizeAndCluster(
        bitmap: Bitmap,
        ocrLanguage: String = LANG_AUTO,
        lineGapRatio: Float = 1.2f,
        minTextLength: Int = 2,
        horizontalOverlapToleranceDp: Float = -20f,
        density: Float = 1f
    ): List<ClusteredText> = coroutineScope {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        // 根据用户指定的语言或自动多引擎并发识别
        val visionTexts: List<Text> = when (ocrLanguage) {
            LANG_KOREAN -> listOf(processRecognizer(koreanRecognizer, inputImage))
            LANG_JAPANESE -> listOf(processRecognizer(japaneseRecognizer, inputImage))
            LANG_CHINESE -> listOf(processRecognizer(chineseRecognizer, inputImage))
            LANG_LATIN -> listOf(processRecognizer(latinRecognizer, inputImage))
            else -> {
                // LANG_AUTO: 日语引擎与韩语引擎并行运行（分别覆盖日汉字/假名与韩文字母，且两者均兼容英文数字）
                val jpDeferred = async { processRecognizer(japaneseRecognizer, inputImage) }
                val krDeferred = async { processRecognizer(koreanRecognizer, inputImage) }
                listOf(jpDeferred.await(), krDeferred.await())
            }
        }

        // 提取候选文字行
        val rawLines = mutableListOf<Text.Line>()
        for (vt in visionTexts) {
            for (block in vt.textBlocks) {
                for (line in block.lines) {
                    if (line.text.isNotBlank() && line.boundingBox != null) {
                        rawLines.add(line)
                    }
                }
            }
        }

        if (rawLines.isEmpty()) {
            return@coroutineScope emptyList()
        }

        // 多引擎与候选行去重：消除空间重叠行 (IoU > 0.3 或包含度 > 0.4)
        val validLines = mutableListOf<Text.Line>()
        for (candidate in rawLines) {
            val cBox = candidate.boundingBox ?: continue
            val cArea = cBox.width().toLong() * cBox.height()
            if (cArea <= 0) continue

            val duplicateIndex = validLines.indexOfFirst { existing ->
                val eBox = existing.boundingBox ?: return@indexOfFirst false
                val iLeft = max(cBox.left, eBox.left)
                val iTop = max(cBox.top, eBox.top)
                val iRight = min(cBox.right, eBox.right)
                val iBottom = min(cBox.bottom, eBox.bottom)
                if (iLeft < iRight && iTop < iBottom) {
                    val interArea = (iRight - iLeft).toLong() * (iBottom - iTop)
                    val eArea = eBox.width().toLong() * eBox.height()
                    val minArea = min(cArea, eArea)
                    val unionArea = cArea + eArea - interArea
                    val iou = if (unionArea > 0) interArea.toFloat() / unionArea else 0f
                    val iom = if (minArea > 0) interArea.toFloat() / minArea else 0f
                    iou > 0.3f || iom > 0.4f
                } else {
                    false
                }
            }

            if (duplicateIndex >= 0) {
                // 空间重叠时，保留文本更完整或长度更长的有效行
                if (candidate.text.trim().length > validLines[duplicateIndex].text.trim().length) {
                    validLines[duplicateIndex] = candidate
                }
            } else {
                validLines.add(candidate)
            }
        }

        if (validLines.isEmpty()) {
            return@coroutineScope emptyList()
        }

        val n = validLines.size
        val uf = UnionFind(n)
        val hTolerancePx = horizontalOverlapToleranceDp * density
        // 同行并列微距容差（约 4dp，仅容许微小字距，避免横向并列的不同按钮/列粘连）
        val inlineWordGapTolerancePx = -4f * density

        // 几何关系判定：
        // 1. 垂直换行段落 (vDist > 0)：垂直间距 <= lineGapRatio * avgLineHeight 且 水平投影满足容差 (hOverlap > hTolerancePx)
        // 2. 水平同行元素 (vDist == 0)：严格要求水平实际重叠或间距极小 (hOverlap > inlineWordGapTolerancePx)，防止粘连并排独立控件
        for (i in 0 until n) {
            val boxA = validLines[i].boundingBox ?: continue
            val hA = max(1, boxA.height())
            for (j in i + 1 until n) {
                val boxB = validLines[j].boundingBox ?: continue
                val hB = max(1, boxB.height())
                val avgLineHeight = (hA + hB) / 2f

                // 垂直净距离
                val isVerticallyStacked = boxA.bottom < boxB.top || boxB.bottom < boxA.top
                val vDist = when {
                    boxA.bottom < boxB.top -> (boxB.top - boxA.bottom).toFloat()
                    boxB.bottom < boxA.top -> (boxA.top - boxB.bottom).toFloat()
                    else -> 0f // 垂直方向存在重叠（同行或有高度交叉）
                }

                // 水平投影重叠量（小于0表示横向有间距）
                val hOverlap = (min(boxA.right, boxB.right) - max(boxA.left, boxB.left)).toFloat()

                val shouldUnion = if (isVerticallyStacked) {
                    // 上下换行段落：允许垂直间距在倍率内，且允许段落水平偏移容差（保证多行台词完整合并为单气泡）
                    vDist <= avgLineHeight * lineGapRatio.coerceAtLeast(1.2f) && hOverlap > hTolerancePx
                } else {
                    // 水平同行：仅在实际重叠或微小词距时合并，禁止使用跨度容差合并独立按钮
                    hOverlap > inlineWordGapTolerancePx
                }

                if (shouldUnion) {
                    uf.union(i, j)
                }
            }
        }

        // 按连通分量汇聚分词
        val clustersMap = mutableMapOf<Int, MutableList<Text.Line>>()
        for (i in 0 until n) {
            val root = uf.find(i)
            clustersMap.getOrPut(root) { mutableListOf() }.add(validLines[i])
        }

        // 构建聚类文本块
        data class TempCluster(val text: String, val rect: Rect)
        val tempClusters = mutableListOf<TempCluster>()

        for ((_, lines) in clustersMap) {
            // 同一对话框内行按从上到下、从左到右排序拼接
            lines.sortWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))

            // 智能语言拼接：东亚汉字/日文假名间不强行插入空格，避免破坏词法结构
            val mergedContent = buildString {
                for (l in lines) {
                    val t = l.text.trim()
                    if (t.isEmpty()) continue
                    if (isNotEmpty()) {
                        val lastChar = last()
                        val firstChar = t.first()
                        if (isEastAsianChar(lastChar) && isEastAsianChar(firstChar)) {
                            // CJK 无缝连接
                        } else {
                            append(" ")
                        }
                    }
                    append(t)
                }
            }.trim()

            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = Int.MIN_VALUE
            var bottom = Int.MIN_VALUE

            for (l in lines) {
                val b = l.boundingBox ?: continue
                left = min(left, b.left)
                top = min(top, b.top)
                right = max(right, b.right)
                bottom = max(bottom, b.bottom)
            }

            // 过滤噪点：包围盒有效且文字长度达到 minTextLength
            if (left < right && top < bottom && mergedContent.length >= minTextLength && mergedContent.isNotBlank()) {
                tempClusters.add(TempCluster(mergedContent, Rect(left, top, right, bottom)))
            }
        }

        // 聚类层级 2D 防重叠碰撞抑制 (Cluster-level NMS)
        // 彻底根除多引擎冲突或行切分差异导致的“两个译文气泡重叠覆盖”问题
        tempClusters.sortByDescending { it.rect.width() * it.rect.height() }
        val finalClusters = mutableListOf<TempCluster>()

        for (curr in tempClusters) {
            val cRect = curr.rect
            val cArea = cRect.width().toLong() * cRect.height()
            var isDuplicate = false

            for (idx in finalClusters.indices) {
                val exist = finalClusters[idx]
                val eRect = exist.rect
                val iLeft = max(cRect.left, eRect.left)
                val iTop = max(cRect.top, eRect.top)
                val iRight = min(cRect.right, eRect.right)
                val iBottom = min(cRect.bottom, eRect.bottom)
                if (iLeft < iRight && iTop < iBottom) {
                    val interArea = (iRight - iLeft).toLong() * (iBottom - iTop)
                    val eArea = eRect.width().toLong() * eRect.height()
                    val minArea = min(cArea, eArea)
                    val unionArea = cArea + eArea - interArea
                    val iou = if (unionArea > 0) interArea.toFloat() / unionArea else 0f
                    val iom = if (minArea > 0) interArea.toFloat() / minArea else 0f

                    if (iou > 0.3f || iom > 0.45f) {
                        isDuplicate = true
                        // 若候选气泡文本更长更完整，替换已有气泡
                        if (curr.text.length > exist.text.length) {
                            finalClusters[idx] = curr
                        }
                        break
                    }
                }
            }

            if (!isDuplicate) {
                finalClusters.add(curr)
            }
        }

        // 最终聚类按屏幕空间自上而下排序，并赋予从 1 开始的稳定编号
        finalClusters.sortWith(compareBy({ it.rect.top }, { it.rect.left }))

        finalClusters.mapIndexed { index, cluster ->
            ClusteredText(
                id = index + 1,
                originalText = cluster.text,
                translatedText = null,
                boundingBox = cluster.rect
            )
        }
    }

    private fun isEastAsianChar(c: Char): Boolean {
        val ub = Character.UnicodeBlock.of(c)
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.HIRAGANA ||
                ub == Character.UnicodeBlock.KATAKANA ||
                ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
    }

    fun release() {
        try { koreanRecognizer.close() } catch (e: Throwable) {}
        try { japaneseRecognizer.close() } catch (e: Throwable) {}
        try { chineseRecognizer.close() } catch (e: Throwable) {}
        try { latinRecognizer.close() } catch (e: Throwable) {}
    }
}
