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

    private data class TextSegment(
        val text: String,
        val boundingBox: Rect
    )

    /**
     * 文本行内分列与词元切分：
     * 针对 Google ML Kit 将同一水平基线上并排的两列（如左侧属性名、右侧数值，或双栏对话/多列按钮）
     * 强行打包合并为单条 Text.Line 的底层缺陷进行前置解耦拆分。
     * 按照物理水平 X 坐标递增排序，若相邻 Text.Element 间距大于阈值（>= 14dp 或 1.3 倍字高），
     * 坚决判定为并列分栏/列间距，切分为独立的 TextSegment，从源头消灭跨列“桥接包围盒”。
     */
    private fun splitLineIntoSegments(line: Text.Line, density: Float): List<TextSegment> {
        val lineBox = line.boundingBox ?: return emptyList()
        val elements = line.elements
        if (elements.isEmpty()) {
            return if (line.text.isNotBlank()) listOf(TextSegment(line.text.trim(), lineBox)) else emptyList()
        }

        val validElements = elements
            .filter { it.text.isNotBlank() && it.boundingBox != null }
            .sortedBy { it.boundingBox!!.left }

        if (validElements.isEmpty()) {
            return if (line.text.isNotBlank()) listOf(TextSegment(line.text.trim(), lineBox)) else emptyList()
        }

        val segments = mutableListOf<TextSegment>()
        val currentElements = mutableListOf<Text.Element>()

        fun flushCurrentSegment() {
            if (currentElements.isEmpty()) return
            var sLeft = Int.MAX_VALUE
            var sTop = Int.MAX_VALUE
            var sRight = Int.MIN_VALUE
            var sBottom = Int.MIN_VALUE

            val segText = buildString {
                for (elem in currentElements) {
                    val b = elem.boundingBox ?: continue
                    sLeft = min(sLeft, b.left)
                    sTop = min(sTop, b.top)
                    sRight = max(sRight, b.right)
                    sBottom = max(sBottom, b.bottom)

                    val t = elem.text.trim()
                    if (t.isNotEmpty()) {
                        if (isNotEmpty()) {
                            val lastChar = last()
                            val firstChar = t.first()
                            if (isEastAsianChar(lastChar) && isEastAsianChar(firstChar)) {
                                // CJK 字符无缝拼接
                            } else {
                                append(" ")
                            }
                        }
                        append(t)
                    }
                }
            }.trim()

            if (segText.isNotEmpty() && sLeft < sRight && sTop < sBottom) {
                segments.add(TextSegment(segText, Rect(sLeft, sTop, sRight, sBottom)))
            }
            currentElements.clear()
        }

        for (elem in validElements) {
            val currBox = elem.boundingBox ?: continue
            if (currentElements.isEmpty()) {
                currentElements.add(elem)
            } else {
                val prevBox = currentElements.last().boundingBox ?: continue
                val gapX = currBox.left - prevBox.right
                val elemHeight = maxOf(currBox.height(), prevBox.height(), 1)
                val splitGapThreshold = maxOf(14f * density, elemHeight * 1.3f)

                if (gapX > splitGapThreshold) {
                    flushCurrentSegment()
                }
                currentElements.add(elem)
            }
        }
        flushCurrentSegment()

        return if (segments.isNotEmpty()) segments else listOf(TextSegment(line.text.trim(), lineBox))
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

        // 提取候选文字行并进行行内分列解耦拆分（切断 ML Kit 同行基线强行拼接的两列）
        val rawSegments = mutableListOf<TextSegment>()
        for (vt in visionTexts) {
            for (block in vt.textBlocks) {
                for (line in block.lines) {
                    if (line.text.isNotBlank() && line.boundingBox != null) {
                        val segs = splitLineIntoSegments(line, density)
                        rawSegments.addAll(segs)
                    }
                }
            }
        }

        if (rawSegments.isEmpty()) {
            return@coroutineScope emptyList()
        }

        // 多引擎与候选分段去重：消除空间重叠分段 (IoU > 0.3 或包含度 > 0.4)
        val validSegments = mutableListOf<TextSegment>()
        for (candidate in rawSegments) {
            val cBox = candidate.boundingBox
            val cArea = cBox.width().toLong() * cBox.height()
            if (cArea <= 0) continue

            val duplicateIndex = validSegments.indexOfFirst { existing ->
                val eBox = existing.boundingBox
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
                // 空间重叠时，保留文本更完整或长度更长的有效分段
                if (candidate.text.trim().length > validSegments[duplicateIndex].text.trim().length) {
                    validSegments[duplicateIndex] = candidate
                }
            } else {
                validSegments.add(candidate)
            }
        }

        if (validSegments.isEmpty()) {
            return@coroutineScope emptyList()
        }

        val n = validSegments.size
        val uf = UnionFind(n)

        // 几何关系聚类判定（核心双列/多列物理隔离数学模型）：
        // 1. 垂直换行段落 (isVerticallyStacked)：
        //    垂直净间距 <= avgLineHeight * lineGapRatio，且水平投影必须严格实质重叠 (hOverlap > 0 且 hOverlapRatio >= 0.35f)。
        //    坚决彻底废弃原本的负重叠容差 (-20dp)，杜绝横向无重叠的两列通过垂直距离在并查集中产生传递性串联坍缩！
        // 2. 水平同行元素 (!isVerticallyStacked)：
        //    垂直方向必须基准对齐 (垂直重叠高度 / minHeight >= 0.5f)，且水平微间距 <= 0.6 * avgLineHeight，
        //    仅允许正常微小词距拼合，严禁跨列横向桥接并排的两列内容。
        for (i in 0 until n) {
            val boxA = validSegments[i].boundingBox
            val hA = max(1, boxA.height())
            val wA = max(1, boxA.width())
            for (j in i + 1 until n) {
                val boxB = validSegments[j].boundingBox
                val hB = max(1, boxB.height())
                val wB = max(1, boxB.width())
                val avgLineHeight = (hA + hB) / 2f
                val minWidth = min(wA, wB).toFloat()
                val minHeight = min(hA, hB).toFloat()

                // 垂直净距离与重叠判定
                val isVerticallyStacked = boxA.bottom <= boxB.top || boxB.bottom <= boxA.top
                val vDist = when {
                    boxA.bottom < boxB.top -> (boxB.top - boxA.bottom).toFloat()
                    boxB.bottom < boxA.top -> (boxA.top - boxB.bottom).toFloat()
                    else -> 0f
                }

                // 水平投影重叠量与重叠比（以较窄行宽度为基准计算覆盖率）
                val hOverlap = (min(boxA.right, boxB.right) - max(boxA.left, boxB.left)).toFloat()
                val hOverlapRatio = if (minWidth > 0 && hOverlap > 0) hOverlap / minWidth else 0f

                val shouldUnion = if (isVerticallyStacked) {
                    // 上下换行段落：垂直距离在行距倍率内，且水平投影严格重叠并达到 35% 覆盖率
                    val maxAllowedVDist = avgLineHeight * lineGapRatio.coerceAtLeast(1.2f)
                    vDist <= maxAllowedVDist && hOverlap > 0 && hOverlapRatio >= 0.35f
                } else {
                    // 水平同行并列：垂直基准对齐（高度重叠 >= 50%），且水平间隙处于正常词距内
                    val vOverlap = (min(boxA.bottom, boxB.bottom) - max(boxA.top, boxB.top)).toFloat()
                    val isVerticallyAligned = (vOverlap / minHeight) >= 0.5f
                    val hGap = (max(boxA.left, boxB.left) - min(boxA.right, boxB.right)).toFloat()
                    isVerticallyAligned && (hGap <= avgLineHeight * 0.6f)
                }

                if (shouldUnion) {
                    uf.union(i, j)
                }
            }
        }

        // 按连通分量汇聚分词
        val clustersMap = mutableMapOf<Int, MutableList<TextSegment>>()
        for (i in 0 until n) {
            val root = uf.find(i)
            clustersMap.getOrPut(root) { mutableListOf() }.add(validSegments[i])
        }

        // 构建聚类文本块
        data class TempCluster(val text: String, val rect: Rect)
        val tempClusters = mutableListOf<TempCluster>()

        for ((_, segments) in clustersMap) {
            // 同一聚类内分段按从上到下、从左到右排序拼接
            segments.sortWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))

            // 智能语言与多行结构拼接：
            // 同行并列词元保持空格或 CJK 无缝连接；换行行严格保留 \n 换行符，
            // 确保大模型能天然感知多行条目并生成对应分行，彻底根绝列表符号窜入上一行行尾的问题
            val mergedContent = buildString {
                var prevBox: Rect? = null
                for (seg in segments) {
                    val t = seg.text.trim()
                    if (t.isEmpty()) continue
                    val currBox = seg.boundingBox

                    if (isNotEmpty()) {
                        val minH = if (prevBox != null) min(max(1, prevBox!!.height()), max(1, currBox.height())) else max(1, currBox.height())
                        val verticalShift = if (prevBox != null) currBox.top - prevBox!!.top else 0
                        val verticalOverlap = if (prevBox != null) min(prevBox!!.bottom, currBox.bottom) - max(prevBox!!.top, currBox.top) else 0

                        // 换行判定：垂直重叠高度较低（< 45% 字高）或垂直基线明显下移（> 50% 字高）
                        val isNewLine = prevBox != null && (verticalOverlap < 0.45f * minH || verticalShift > 0.5f * minH)

                        if (isNewLine) {
                            append("\n")
                        } else {
                            val lastChar = last()
                            val firstChar = t.first()
                            if (isEastAsianChar(lastChar) && isEastAsianChar(firstChar)) {
                                // CJK 同行无缝连接
                            } else {
                                append(" ")
                            }
                        }
                    }
                    append(t)
                    prevBox = currBox
                }
            }.trim()

            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = Int.MIN_VALUE
            var bottom = Int.MIN_VALUE

            for (seg in segments) {
                val b = seg.boundingBox
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
