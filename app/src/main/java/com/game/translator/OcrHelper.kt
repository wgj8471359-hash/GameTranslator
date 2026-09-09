package com.game.translator

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
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

    // 采用 Google ML Kit 离线韩文（兼容英文与数字）文本识别客户端
    private val recognizer = TextRecognition.getClient(
        KoreanTextRecognizerOptions.Builder().build()
    )

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

    /**
     * 截屏 Bitmap 识别并进行 2D 并查集几何聚类
     * @param lineGapRatio 垂直行距容差倍率（默认 1.2）
     * @param minTextLength 最小文本长度过滤阈值（默认 2，过滤杂质噪点）
     * @param horizontalOverlapToleranceDp 水平投影重叠容差（单位 dp，默认 -20dp）
     * @param density 屏幕密度比例，用于将 dp 转换为 px
     */
    suspend fun recognizeAndCluster(
        bitmap: Bitmap,
        lineGapRatio: Float = 1.2f,
        minTextLength: Int = 2,
        horizontalOverlapToleranceDp: Float = -20f,
        density: Float = 1f
    ): List<ClusteredText> {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val visionText = suspendCancellableCoroutine<Text> { continuation ->
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

        // 提取所有文字行
        val validLines = mutableListOf<Text.Line>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                if (line.text.isNotBlank() && line.boundingBox != null) {
                    validLines.add(line)
                }
            }
        }

        if (validLines.isEmpty()) {
            return emptyList()
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
                    // 上下换行段落：允许垂直间距在倍率内，且允许段落水平偏移容差
                    vDist <= avgLineHeight * lineGapRatio && hOverlap > hTolerancePx
                } else {
                    // 水平同行：仅在实际重叠或微小词距时合并，禁止使用 -20dp 跨度容差合并独立按钮
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

            val mergedContent = lines.joinToString(" ") { it.text.trim() }.trim()

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

        // 最终聚类按屏幕空间自上而下排序，并赋予从 1 开始的编号
        tempClusters.sortWith(compareBy({ it.rect.top }, { it.rect.left }))

        return tempClusters.mapIndexed { index, cluster ->
            ClusteredText(
                id = index + 1,
                originalText = cluster.text,
                translatedText = null,
                boundingBox = cluster.rect
            )
        }
    }

    fun release() {
        recognizer.close()
    }
}
