package com.game.translator

import android.graphics.Rect
import android.os.SystemClock
import android.util.LruCache
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DiffResult(
    val unchanged: List<ClusteredText>,
    val moved: List<ClusteredText>,
    val updated: List<ClusteredText>,
    val added: List<ClusteredText>,
    val removedIds: List<Int>,
    val cachedMap: Map<Int, String>,
    val needModelTranslation: List<ClusteredText>
) {
    val hasVisualChanges: Boolean
        get() = moved.isNotEmpty() || updated.isNotEmpty() || added.isNotEmpty() || removedIds.isNotEmpty()
}

private data class TrackedCluster(
    val id: Int,
    val originalText: String,
    var boundingBox: Rect,
    val firstSeenTime: Long,
    var translatedText: String? = null,
    var isInFlight: Boolean = false,
    var failures: Int = 0,
    var retryAt: Long = 0
)

/** IDs identify immutable source revisions, not reusable screen slots. */
class DiffEngine(
    debounceWindowMs: Long = DEBOUNCE_STABLE_WINDOW_MS,
    cacheCapacity: Int = MAX_TRANSLATION_CACHE_SIZE
) {
    companion object {
        const val DEBOUNCE_STABLE_WINDOW_MS = 400L
        const val DEFAULT_SAMPLE_INTERVAL_MS = 1200L
        const val IDLE_BACKOFF_INTERVAL_MS = 2500L
        const val MAX_TRANSLATION_CACHE_SIZE = 500
    }

    private var stableWindowMs = debounceWindowMs.coerceIn(100L, 2000L)

    /** 运行中调整稳定消抖窗口（实时模式每次启动时应用最新配置） */
    @Synchronized
    fun configureDebounce(windowMs: Long) {
        stableWindowMs = windowMs.coerceIn(100L, 2000L)
    }

    // Never reuse an ID, even after reset/rotation. Old finally blocks cannot
    // modify a new track that happens to occupy the same screen coordinates.
    private var nextClusterId = 1
    private val trackedMap = linkedMapOf<Int, TrackedCluster>()
    private val translationCache = LruCache<String, String>(cacheCapacity)
    private var namespace: String? = null

    @Synchronized fun reset() { trackedMap.clear() }
    @Synchronized fun clearCache() { translationCache.evictAll() }

    @Synchronized
    fun configureCacheNamespace(value: String): Boolean {
        if (namespace == value) return false
        namespace = value
        clearCache()
        reset()
        return true
    }

    @Synchronized fun isClusterActive(id: Int): Boolean = trackedMap.containsKey(id)
    @Synchronized fun markInFlight(id: Int, inFlight: Boolean) {
        trackedMap[id]?.isInFlight = inFlight
    }

    @Synchronized
    fun markFailed(id: Int, now: Long = SystemClock.elapsedRealtime()) {
        trackedMap[id]?.let {
            it.isInFlight = false
            it.failures = (it.failures + 1).coerceAtMost(5)
            it.retryAt = now + (2000L shl (it.failures - 1)).coerceAtMost(30000L)
        }
    }

    @Synchronized
    fun getActiveCluster(id: Int, originalText: String): ClusteredText? {
        val t = trackedMap[id] ?: return null
        if (t.originalText != originalText) return null
        return snapshot(t)
    }

    private fun snapshot(t: TrackedCluster) = ClusteredText(
        t.id, t.originalText, t.translatedText, Rect(t.boundingBox)
    )

    // Preserve punctuation, digits, case and negation. Fuzzy OCR similarity is
    // not sufficient evidence that the same translation is still correct.
    fun normalizeForCache(text: String): String = text.trim().replace(Regex("\\s+"), " ")
    private fun key(lang: String, text: String) = "$lang\u0000${normalizeForCache(text)}"

    fun isValidTranslation(original: String, translated: String?): Boolean =
        !translated.isNullOrBlank() &&
            !translated.trim().equals(original.trim(), ignoreCase = true) &&
            !translated.contains("[id]", ignoreCase = true)

    @Synchronized
    fun putCache(ocrLang: String, originalText: String, translatedText: String, clusterId: Int? = null) {
        if (!isValidTranslation(originalText, translatedText)) return
        val clean = translatedText.trim()
        translationCache.put(key(ocrLang, originalText), clean)
        trackedMap.values.filter {
            it.originalText == originalText && (clusterId == null || it.id == clusterId)
        }.forEach {
            it.translatedText = clean
            it.failures = 0
            it.retryAt = 0
        }
    }

    @Synchronized fun getCache(ocrLang: String, originalText: String): String? =
        translationCache.get(key(ocrLang, originalText))

    @Synchronized
    fun removeCache(ocrLang: String, originalText: String) {
        translationCache.remove(key(ocrLang, originalText))
        trackedMap.values.filter { it.originalText == originalText }.forEach {
            it.translatedText = null
            it.retryAt = 0
            it.failures = 0
        }
    }

    fun calculateIoU(a: Rect, b: Rect): Float {
        val intersection = max(0, min(a.right, b.right) - max(a.left, b.left)).toLong() *
            max(0, min(a.bottom, b.bottom) - max(a.top, b.top))
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - intersection
        return if (union > 0) intersection.toFloat() / union else 0f
    }

    /** Input must be a clean screenshot, with our overlay hidden before capture. */
    @Synchronized
    fun processFrame(
        currentClusters: List<ClusteredText>,
        ocrLang: String,
        currentTime: Long = SystemClock.elapsedRealtime()
    ): DiffResult {
        val unchanged = mutableListOf<ClusteredText>()
        val moved = mutableListOf<ClusteredText>()
        val added = mutableListOf<ClusteredText>()
        val need = mutableListOf<ClusteredText>()
        val cached = mutableMapOf<Int, String>()
        val matched = mutableSetOf<Int>()
        val oldIds = trackedMap.keys.toSet()

        for (curr in currentClusters) {
            // Exact content first; nearest geometry disambiguates repeated labels.
            // A changed source never inherits a previous request's identity.
            val existing = trackedMap.values.filter {
                it.id in oldIds && it.id !in matched && it.originalText == curr.originalText
            }.minByOrNull {
                abs(it.boundingBox.centerX().toLong() - curr.boundingBox.centerX()) +
                    abs(it.boundingBox.centerY().toLong() - curr.boundingBox.centerY())
            }
            val t = existing ?: TrackedCluster(
                nextClusterId++, curr.originalText, Rect(curr.boundingBox), currentTime,
                getCache(ocrLang, curr.originalText)
            ).also { trackedMap[it.id] = it }
            val positionChanged = t.boundingBox != curr.boundingBox
            t.boundingBox = Rect(curr.boundingBox)
            matched.add(t.id)
            if (t.translatedText == null) t.translatedText = getCache(ocrLang, t.originalText)
            val item = snapshot(t)
            if (t.translatedText != null) {
                cached[t.id] = t.translatedText!!
                when {
                    existing == null -> added.add(item)
                    positionChanged -> moved.add(item)
                    else -> unchanged.add(item)
                }
            }
            // Stable != displayed. A stationary candidate MUST progress here.
            if (t.translatedText == null && !t.isInFlight &&
                currentTime - t.firstSeenTime >= stableWindowMs && currentTime >= t.retryAt) {
                need.add(item)
            }
        }
        // In-flight work never extends visibility. A missed observation retires
        // the ID; a later reappearance can use cache but not an obsolete callback.
        val removed = oldIds.filter { it !in matched }
        removed.forEach { trackedMap.remove(it) }
        return DiffResult(unchanged, moved, emptyList(), added, removed, cached, need)
    }
}
