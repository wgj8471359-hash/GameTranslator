package com.game.translator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        /** 截屏前等待合成器刷新干净帧的时间（毫秒）——事件驱动等待的超时兜底 */
        const val FRAME_DRAIN_MS = 120L
        /** 隐藏事务生效沉降：ViewRootImpl traversal + 一个合成器 vsync 的保守值 */
        const val POST_HIDE_SETTLE_MS = 32L
    }

    data class OverlayConfig(
        val minTextLength: Int = 2,
        val bubbleAlphaPercent: Int = 85,
        val minFontSp: Int = 8,
        val maxFontSp: Int = 16,
        val sourceImageWidth: Int = 0,
        val sourceImageHeight: Int = 0,
        val isRealtimeMode: Boolean = false,
        /** 译文布局：cover=遮盖原位（默认），note_below=下一行旁注，note_right=右侧旁注 */
        val layoutMode: String = LAYOUT_COVER
    ) {
        companion object {
            const val LAYOUT_COVER = "cover"
            const val LAYOUT_NOTE_BELOW = "note_below"
            const val LAYOUT_NOTE_RIGHT = "note_right"
        }
    }

    private var rootOverlayView: FrameLayout? = null
    private var isShowing = false
    private var isDismissed = false
    private val bubbleViews = mutableMapOf<Int, TextView>()
    // cover 占位态：已挂载但译文未完成的气泡 ID（灰显原文，译文完成后恢复常规样式）
    private val placeholderIds = mutableSetOf<Int>()

    private data class BubbleLayoutInfo(
        val id: Int,
        @Volatile var left: Int,
        @Volatile var top: Int,
        @Volatile var width: Int,
        @Volatile var height: Int
    )
    private val bubbleDataMap = java.util.concurrent.ConcurrentHashMap<Int, BubbleLayoutInfo>()
    // 全景文本框包围盒映射（包含尚未翻译的原文几何框，用于全局间距防护）
    private val clusterBoundsMap = mutableMapOf<Int, Rect>()

    /**
     * 获取当前屏幕上所有已呈现译文气泡映射回截屏原始图像坐标系的几何包围盒列表。
     * 用于实时差分引擎 (DiffEngine) 实施光学隔离，识别并屏蔽气泡自捕获产生的光学污染。
     */
    fun getVisibleBubbleImageRects(): List<Rect> {
        if (!isShowing || isDismissed) return emptyList()
        val sx = if (currentScaleX > 0f) currentScaleX else 1.0f
        val sy = if (currentScaleY > 0f) currentScaleY else 1.0f
        return bubbleDataMap.values.mapNotNull { info ->
            if (info.width > 0 && info.height > 0) {
                val imgLeft = (info.left / sx).toInt()
                val imgTop = (info.top / sy).toInt()
                val imgRight = ((info.left + info.width) / sx).toInt()
                val imgBottom = ((info.top + info.height) / sy).toInt()
                Rect(imgLeft, imgTop, imgRight, imgBottom)
            } else null
        }
    }

    /**
     * 注册全量 OCR 识别到的文本簇包围盒，用于行间距几何避让
     */
    fun registerClusterBounds(clusters: List<ClusteredText>) {
        synchronized(clusterBoundsMap) {
            clusterBoundsMap.clear()
            for (c in clusters) {
                clusterBoundsMap[c.id] = c.boundingBox
            }
        }
    }

    /**
     * 仅当用户手动轻触全屏空白背景清除气泡时触发（用于终止后台仍在进行的网络请求）
     * 严禁在内部调用 dismiss() 时触发，防止陷入递归循环与误取消。
     */
    var onUserDismissListener: (() -> Unit)? = null

    /**
     * 用户长按单个气泡触发重新翻译该句的回调
     */
    var onBubbleLongClickListener: ((ClusteredText) -> Unit)? = null

    /**
     * 当处于交互态且用户轻触空白区域自动切回穿透态时的监听
     */
    var onPassthroughModeChangedListener: ((isPassthrough: Boolean) -> Unit)? = null

    private var currentConfig: OverlayConfig = OverlayConfig()
    val currentOverlayConfig: OverlayConfig get() = currentConfig
    private var currentScreenWidth = 0
    private var currentScreenHeight = 0
    private var currentDensity = 0f
    private var currentScaleX = 1f
    private var currentScaleY = 1f

    /**
     * 提前准备全屏透明覆盖根视图（用于流式逐句上屏呈现）
     */
    fun prepareOverlay(config: OverlayConfig, allClusters: List<ClusteredText>? = null) {
        allClusters?.let { registerClusterBounds(it) }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            prepareOverlayInternal(config)
        } else {
            mainHandler.post { prepareOverlayInternal(config) }
        }
    }

    private fun prepareOverlayInternal(config: OverlayConfig) {
        isDismissed = false
        if (isShowing && rootOverlayView != null) {
            // 如果模式发生变化（例如从单次截屏模式切换到实时监听穿透模式，或反之），动态更新 WindowManager Flags 与点击监听
            if (currentConfig.isRealtimeMode != config.isRealtimeMode) {
                currentConfig = config
                val lp = rootOverlayView?.layoutParams as? WindowManager.LayoutParams
                if (lp != null) {
                    val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    lp.flags = if (config.isRealtimeMode) {
                        baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    } else {
                        baseFlags
                    }
                    try {
                        windowManager.updateViewLayout(rootOverlayView, lp)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                if (config.isRealtimeMode) {
                    rootOverlayView?.setOnClickListener(null)
                    rootOverlayView?.isClickable = false
                } else {
                    rootOverlayView?.isClickable = true
                    rootOverlayView?.setOnClickListener {
                        dismiss()
                        onUserDismissListener?.invoke()
                    }
                }
            }
            if (config.sourceImageWidth > 0 && currentScreenWidth > 0) {
                currentScaleX = currentScreenWidth.toFloat() / config.sourceImageWidth
            }
            if (config.sourceImageHeight > 0 && currentScreenHeight > 0) {
                currentScaleY = currentScreenHeight.toFloat() / config.sourceImageHeight
            }
            currentConfig = config
            return
        }

        val isRealtime = config.isRealtimeMode
        val rootView = FrameLayout(context).apply {
            if (!isRealtime) {
                // 仅在单次手动截屏翻译模式下，轻触屏幕空白背景关闭气泡；
                // 实时监听模式下严禁拦截手势，保证用户正常滑动网页或操作游戏！
                setOnClickListener {
                    dismiss()
                    onUserDismissListener?.invoke()
                }
            } else {
                isClickable = false
            }
        }

        // WindowManager 参数配置：
        // 实时模式增加 FLAG_NOT_TOUCHABLE：全屏触控直接穿透到底层网页/游戏，用户滑动页面毫无阻碍且绝对不会误触关闭气泡！
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val layoutParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = if (isRealtime) {
                baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                baseFlags
            }
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val defaultDisplay = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
        val isLandscape = rotation == android.view.Surface.ROTATION_90 ||
                rotation == android.view.Surface.ROTATION_270 ||
                context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        var rawWidth = 0
        var rawHeight = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val bounds = windowManager.maximumWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    rawWidth = bounds.width()
                    rawHeight = bounds.height()
                }
            } catch (e: Throwable) {}
        }

        val realDm = DisplayMetrics()
        if (defaultDisplay != null) {
            @Suppress("DEPRECATION")
            defaultDisplay.getRealMetrics(realDm)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(realDm)
        }

        if (rawWidth <= 0 || rawHeight <= 0) {
            rawWidth = realDm.widthPixels
            rawHeight = realDm.heightPixels
        }

        currentScreenWidth = if (isLandscape) maxOf(rawWidth, rawHeight) else minOf(rawWidth, rawHeight)
        currentScreenHeight = if (isLandscape) minOf(rawWidth, rawHeight) else maxOf(rawWidth, rawHeight)
        currentDensity = realDm.density
        currentScaleX = if (config.sourceImageWidth > 0) currentScreenWidth.toFloat() / config.sourceImageWidth else 1.0f
        currentScaleY = if (config.sourceImageHeight > 0) currentScreenHeight.toFloat() / config.sourceImageHeight else 1.0f
        currentConfig = config

        try {
            windowManager.addView(rootView, layoutParams)
            rootOverlayView = rootView
            isShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 单个气泡实时呈现或流式增量刷新（逐句呈现模式）。
     * @param preShowCheck 在 UI 线程实际执行渲染前再次校验；返回 false 时丢弃本次展示，
     *        用于防止异步回调排队期间轨迹失效/会话切换后旧结果重新上屏。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showOrUpdateBubble(
        item: ClusteredText,
        config: OverlayConfig = currentConfig,
        isFinished: Boolean = false,
        preShowCheck: (() -> Boolean)? = null
    ) {
        val action = Runnable {
            // preShowCheck 仅在显式传入时生效；null 表示调用方未要求执行时守卫
            if (preShowCheck != null && preShowCheck.invoke() != true) return@Runnable
            showOrUpdateBubbleInternal(item, config, isFinished)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    /**
     * cover 模式占位：译文未到时先以灰显原文占位（布局锚定与译文完全一致，零抖动）。
     * 旁注模式原文常显，无需占位，直接忽略。
     */
    fun showPlaceholder(item: ClusteredText, config: OverlayConfig = currentConfig) {
        if (config.layoutMode != OverlayConfig.LAYOUT_COVER) return
        placeholderIds.add(item.id)
        showOrUpdateBubble(item, config, isFinished = false)
    }

    /** 占位 ↔ 常规样式的视觉切换（仅调整颜色，不改几何，避免布局抖动） */
    private fun applyPlaceholderStyle(view: TextView, config: OverlayConfig, isPlaceholder: Boolean) {
        val alphaInt = ((config.bubbleAlphaPercent.coerceIn(20, 100) / 100f) * 255).toInt()
        val bg = view.background as? GradientDrawable ?: return
        if (isPlaceholder) {
            bg.setColor(Color.argb((alphaInt * 0.45f).toInt(), 0x1E, 0x1E, 0x24))
            view.setTextColor(Color.argb(210, 0x9E, 0x9E, 0x9E))
        } else {
            bg.setColor(Color.argb(alphaInt, 0x1E, 0x1E, 0x24))
            view.setTextColor(Color.WHITE)
        }
    }

    /** 单条端到端几何入口：cover 与 note_below/note_right 三种布局的统一计算。 */
    private data class BubbleGeometry(
        val left: Int,
        val top: Int,
        val width: Int,
        val minHeight: Int,
        val maxHeight: Int,
        val effectiveMode: String
    )

    private fun computeBubbleGeometry(item: ClusteredText, config: OverlayConfig): BubbleGeometry? {
        val screenWidth = currentScreenWidth
        val screenHeight = currentScreenHeight
        val density = currentDensity
        val scaleX = currentScaleX
        val scaleY = currentScaleY
        if (screenWidth <= 0 || screenHeight <= 0) return null

        val box = item.boundingBox
        val scaledLeft = (box.left * scaleX).toInt()
        val scaledTop = (box.top * scaleY).toInt()
        val scaledWidth = (box.width() * scaleX).toInt().coerceAtLeast((30 * density).toInt())
        val scaledHeight = (box.height() * scaleY).toInt().coerceAtLeast((20 * density).toInt())

        val left = scaledLeft.coerceIn(0, (screenWidth - (30 * density).toInt()).coerceAtLeast(0))
        val top = scaledTop.coerceIn(0, (screenHeight - (20 * density).toInt()).coerceAtLeast(0))
        val width = scaledWidth.coerceAtMost(screenWidth - left)
        val originalHeight = scaledHeight.coerceAtMost(screenHeight - top)

        val maxAllowedHeight = maxOf(originalHeight, (originalHeight * 2.4f).toInt(), (48 * density).toInt())
            .coerceAtMost((screenHeight - top).coerceAtLeast(originalHeight))

        val gapPx = (4f * density).toInt()
        val minNoteHeight = (20 * density).toInt()
        val minNoteWidth = (30 * density).toInt()

        // 正下方同一竖向投影重叠的下一个文本簇顶部（cover 高度屏障 / note_below 可用净空）
        var nextLineTop = screenHeight
        synchronized(clusterBoundsMap) {
            for ((_, otherBox) in clusterBoundsMap) {
                val otherScaledLeft = (otherBox.left * scaleX).toInt()
                val otherScaledRight = (otherBox.right * scaleX).toInt()
                val otherScaledTop = (otherBox.top * scaleY).toInt()
                val hOverlap = maxOf(left, otherScaledLeft) < minOf(left + width, otherScaledRight)
                if (hOverlap && otherScaledTop > top + (originalHeight / 2) && otherScaledTop < nextLineTop) {
                    nextLineTop = otherScaledTop
                }
            }
        }

        val requestedMode = when (config.layoutMode) {
            OverlayConfig.LAYOUT_NOTE_BELOW, OverlayConfig.LAYOUT_NOTE_RIGHT -> config.layoutMode
            else -> OverlayConfig.LAYOUT_COVER
        }
        var effectiveMode = requestedMode

        var geomLeft = left
        var geomTop = top
        var geomWidth = width
        var geomMinHeight = originalHeight
        var geomMaxHeight = minOf(maxAllowedHeight, (nextLineTop - top - gapPx).coerceAtLeast(originalHeight))

        // 回退链第一级：右侧旁注（右侧净空不足则退到下一行）
        if (effectiveMode == OverlayConfig.LAYOUT_NOTE_RIGHT) {
            val noteLeft = (left + width + gapPx).coerceAtMost((screenWidth - minNoteWidth).coerceAtLeast(0))
            val availRight = screenWidth - noteLeft - (8 * density).toInt()
            if (availRight >= minNoteWidth) {
                geomLeft = noteLeft
                geomWidth = minOf(availRight, (screenWidth * 0.4f).toInt().coerceAtLeast(minNoteWidth))
            } else {
                effectiveMode = OverlayConfig.LAYOUT_NOTE_BELOW
            }
        }
        // 回退链第二级：下一行旁注（下方净空不足则退回 cover 遮盖原位）
        if (effectiveMode == OverlayConfig.LAYOUT_NOTE_BELOW) {
            val noteTop = top + originalHeight + gapPx
            val availBelow = nextLineTop - noteTop - gapPx
            if (availBelow >= minNoteHeight && noteTop + minNoteHeight <= screenHeight) {
                geomLeft = left
                geomTop = noteTop
                geomMinHeight = minOf(originalHeight, minNoteHeight)
                geomMaxHeight = availBelow
            } else {
                effectiveMode = OverlayConfig.LAYOUT_COVER
            }
        }

        // 与已存在气泡的碰撞避让（旁注同样受约；跳过自身防止更新路径自推自挤）
        for ((_, exist) in bubbleDataMap) {
            if (exist.id == item.id) continue
            val hOverlap = maxOf(geomLeft, exist.left) < minOf(geomLeft + geomWidth, exist.left + exist.width)
            if (hOverlap) {
                if (geomTop >= exist.top) {
                    val minAllowedTop = exist.top + exist.height + gapPx
                    if (geomTop < minAllowedTop) {
                        geomTop = minAllowedTop.coerceAtMost((screenHeight - (20 * density).toInt()).coerceAtLeast(0))
                    }
                } else {
                    val spaceAbove = exist.top - geomTop - gapPx
                    if (spaceAbove >= geomMinHeight) {
                        geomMaxHeight = minOf(geomMaxHeight, spaceAbove)
                    }
                }
            }
        }
        geomMaxHeight = minOf(geomMaxHeight, (screenHeight - geomTop).coerceAtLeast((20 * density).toInt()))
        geomMaxHeight = maxOf(geomMaxHeight, geomMinHeight)
        return BubbleGeometry(geomLeft, geomTop, geomWidth, geomMinHeight, geomMaxHeight, effectiveMode)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOrUpdateBubbleInternal(item: ClusteredText, config: OverlayConfig, isFinished: Boolean) {
        if (isDismissed) {
            // 实时模式：dismiss 只来自服务侧生命周期（切换/旋转/清屏），干净帧管线会随后重建浮层，
            // 因此不允许 dismissed 状态永久吞掉首个译文；手动模式仍保持"用户关闭后不再弹出"
            if (!config.isRealtimeMode) return
            isDismissed = false
        }
        if (!isShowing || rootOverlayView == null) {
            prepareOverlayInternal(config)
        }
        val rootView = rootOverlayView ?: return

        val content = item.translatedText ?: item.originalText
        val isSameAsOriginal = if (item.translatedText != null) {
            item.translatedText!!.trim().equals(item.originalText.trim(), ignoreCase = true)
        } else {
            false
        }

        // 若已完成生成且内容与原文完全一致或字数不足，则剔除该气泡避免遮挡游戏原文
        if (isFinished && (isSameAsOriginal || content.trim().length < config.minTextLength)) {
            val existing = bubbleViews.remove(item.id)
            bubbleDataMap.remove(item.id)
            if (existing != null) {
                rootView.removeView(existing)
            }
            return
        }

        val existingView = bubbleViews[item.id]
        if (existingView != null) {
            // 已存在对应气泡，实时刷新文本内容（流式文字打字效果）
            if (existingView.text != content) {
                existingView.text = content
                existingView.scrollTo(0, 0)
            }
            // 统一几何入口：更新路径同样重算，旁注模式跟随原文重锚定；占位态解除时恢复常规样式
            val isPlaceholder = placeholderIds.contains(item.id) && !isFinished
            applyPlaceholderStyle(existingView, config, isPlaceholder = isPlaceholder)
            if (!isPlaceholder) placeholderIds.remove(item.id)
            computeBubbleGeometry(item, config)?.let { geom ->
                val lp = existingView.layoutParams as? FrameLayout.LayoutParams
                if (lp != null && (lp.leftMargin != geom.left || lp.topMargin != geom.top || lp.width != geom.width)) {
                    lp.leftMargin = geom.left
                    lp.topMargin = geom.top
                    lp.width = geom.width
                    existingView.layoutParams = lp
                    existingView.requestLayout()
                    bubbleDataMap[item.id]?.let {
                        it.left = geom.left
                        it.top = geom.top
                        it.width = geom.width
                    }
                }
            }
            return
        }

        // 新建气泡要求有有效内容
        if (content.isBlank()) return

        val density = currentDensity
        val cornerRadiusPx = 6f * density
        val paddingH = (5f * density).toInt()
        val paddingV = (2.5f * density).toInt()

        val alphaInt = ((config.bubbleAlphaPercent.coerceIn(20, 100) / 100f) * 255).toInt()
        val bubbleColor = Color.argb(alphaInt, 0x1E, 0x1E, 0x24)

        val minSp = config.minFontSp.coerceAtLeast(6)
        val maxSp = config.maxFontSp.coerceAtLeast(minSp)
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        // 统一几何入口：cover 与旁注布局在此分叉，含回退链与碰撞避让
        val geometry = computeBubbleGeometry(item, config) ?: return
        val finalLeft = geometry.left
        val finalTop = geometry.top
        val finalWidth = geometry.width

        val bubbleView = TextView(context).apply {
            val bgDrawable = GradientDrawable().apply {
                setColor(bubbleColor)
                cornerRadius = cornerRadiusPx
            }
            background = bgDrawable
            setTextColor(Color.WHITE)
            setPadding(paddingH, paddingV, paddingH, paddingV)
            includeFontPadding = false
            setLineSpacing(0f, 1.05f)
            gravity = Gravity.TOP or Gravity.START

            minHeight = geometry.minHeight
            maxHeight = geometry.maxHeight

            // 开启垂直平滑滚动
            movementMethod = ScrollingMovementMethod.getInstance()
            isVerticalScrollBarEnabled = false

            // 文字自适应充满原框
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                minSp,
                maxSp,
                1,
                TypedValue.COMPLEX_UNIT_SP
            )

            var isShowingTranslation = true
            text = content

            // 点击气泡：在原文与译文之间切换
            setOnClickListener {
                isShowingTranslation = !isShowingTranslation
                text = if (isShowingTranslation) {
                    item.translatedText ?: item.originalText
                } else {
                    item.originalText
                }
                scrollTo(0, 0)
            }

            // 区分轻触点击、长按重译与上下拖拽滚动
            var downX = 0f
            var downY = 0f
            var isScrolling = false
            var hasPerformedLongPress = false

            val longPressRunnable = Runnable {
                if (!isScrolling) {
                    hasPerformedLongPress = true
                    onBubbleLongClickListener?.invoke(item)
                }
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        isScrolling = false
                        hasPerformedLongPress = false
                        mainHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                        v.onTouchEvent(event)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(event.rawX - downX)
                        val dy = abs(event.rawY - downY)
                        if (dy > touchSlop || dx > touchSlop) {
                            isScrolling = true
                            mainHandler.removeCallbacks(longPressRunnable)
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        v.onTouchEvent(event)
                    }
                    MotionEvent.ACTION_UP -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        if (!isScrolling && !hasPerformedLongPress) {
                            v.performClick()
                        } else {
                            v.onTouchEvent(event)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        v.onTouchEvent(event)
                    }
                    else -> v.onTouchEvent(event)
                }
            }
        }

        val childParams = FrameLayout.LayoutParams(finalWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = finalLeft
            topMargin = finalTop
        }

        rootView.addView(bubbleView, childParams)
        bubbleViews[item.id] = bubbleView
        bubbleDataMap[item.id] = BubbleLayoutInfo(item.id, finalLeft, finalTop, finalWidth, geometry.minHeight)
        // cover 占位态：新建时即以灰显样式挂载（旁注模式与缓存命中路径不在占位集合中）
        if (placeholderIds.contains(item.id)) {
            applyPlaceholderStyle(bubbleView, config, isPlaceholder = true)
        }
        // 动态监听实际测量渲染高度，实时回填真实占位高度，确保后排气泡获得真实物理上边界
        bubbleView.addOnLayoutChangeListener { _, _, topPos, _, bottomPos, _, _, _, _ ->
            val actualHeight = bottomPos - topPos
            if (actualHeight > 0) {
                bubbleDataMap[item.id]?.let {
                    it.height = actualHeight
                }
            }
        }
    }

    /**
     * 应用实时差分结果 (增量渲染管线)：
     * 1. 维持未变动气泡 (unchanged) 绝对不动，零闪烁、零动画；
     * 2. 平滑淡出并移除已消失的气泡 (removedIds)；
     * 3. 原地更新文本发生变更的气泡 (updated)；
     * 4. 呈现新出现的气泡 (added)，支持命中 LRU 缓存的瞬间上屏。
     */
    fun applyDiffResult(diffResult: DiffResult, config: OverlayConfig = currentConfig) {
        val action = Runnable {
            applyDiffResultInternal(diffResult, config)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    private fun applyDiffResultInternal(diffResult: DiffResult, config: OverlayConfig) {
        if (isDismissed) {
            isDismissed = false
        }
        if (!isShowing || rootOverlayView == null) {
            prepareOverlayInternal(config)
        }
        val rootView = rootOverlayView ?: return

        // 1. 平滑淡出并移除消失的气泡 (100ms 纯透明度衰减，避免突兀空白)
        for (removedId in diffResult.removedIds) {
            val view = bubbleViews.remove(removedId)
            bubbleDataMap.remove(removedId)
            if (view != null) {
                view.animate()
                    .alpha(0f)
                    .setDuration(100)
                    .withEndAction {
                        try {
                            rootView.removeView(view)
                        } catch (e: Exception) {
                            // 忽略并发移除异常
                        }
                    }
                    .start()
            }
        }

        // 2. 原地平移滚动气泡 (moved)：统一几何入口重算（旁注模式随原文重锚定），平滑跟随滚动
        for (item in diffResult.moved) {
            val existing = bubbleViews[item.id]
            val geom = computeBubbleGeometry(item, config)
            if (existing != null && geom != null) {
                val lp = existing.layoutParams as? FrameLayout.LayoutParams
                if (lp != null && (lp.leftMargin != geom.left || lp.topMargin != geom.top || lp.width != geom.width)) {
                    lp.leftMargin = geom.left
                    lp.topMargin = geom.top
                    lp.width = geom.width
                    existing.layoutParams = lp
                    existing.requestLayout()
                }
                bubbleDataMap[item.id]?.let {
                    it.left = geom.left
                    it.top = geom.top
                    it.width = geom.width
                }
            } else if (geom != null) {
                showOrUpdateBubbleInternal(item, config, isFinished = true)
            }
        }

        // 3. 原地更新内容发生变动的已有气泡（若模型尚在生成，保持现有内容避免闪现原文）
        for (item in diffResult.updated) {
            val cachedText = diffResult.cachedMap[item.id]
            if (cachedText != null) {
                item.translatedText = cachedText
            }
            val existing = bubbleViews[item.id]
            if (existing != null) {
                if (item.translatedText != null && existing.text != item.translatedText) {
                    existing.text = item.translatedText
                }
                // 译文到达：解除 cover 占位灰显
                if (item.translatedText != null && placeholderIds.remove(item.id)) {
                    applyPlaceholderStyle(existing, config, isPlaceholder = false)
                }
            } else {
                // 若此前气泡尚未创建，走新建路径
                showOrUpdateBubbleInternal(item, config, isFinished = item.translatedText != null)
            }
        }

        // 3. 渲染新出现的文本簇
        for (item in diffResult.added) {
            val cachedText = diffResult.cachedMap[item.id]
            if (cachedText != null) {
                item.translatedText = cachedText
            }
            showOrUpdateBubbleInternal(item, config, isFinished = item.translatedText != null)
        }

        // 4. 若此前视图被清空或重建，确保有译文的存量气泡均被挂载上屏
        for (item in diffResult.unchanged) {
            if (!bubbleViews.containsKey(item.id) && item.translatedText != null) {
                showOrUpdateBubbleInternal(item, config, isFinished = true)
            }
        }

        // cover 占位：新挂载且尚无译文的簇以灰显原文占位（旁注模式无需占位）
        if (config.layoutMode == OverlayConfig.LAYOUT_COVER) {
            for (item in diffResult.needModelTranslation) {
                if (!bubbleViews.containsKey(item.id) && !placeholderIds.contains(item.id)) {
                    placeholderIds.add(item.id)
                    showOrUpdateBubbleInternal(item.copy(translatedText = null), config, isFinished = false)
                }
            }
        }
    }

    /**
     * 批量展现翻译气泡全屏覆盖层
     * @param clusters 待展示的聚类文本与译文列表
     * @param config 样式与过滤配置（透明度、字号自适应范围、最小字数、截屏源图尺寸）
     */
    fun showOverlay(clusters: List<ClusteredText>, config: OverlayConfig = OverlayConfig()) {
        val showAction = Runnable {
            dismissInternal()
            registerClusterBounds(clusters)
            prepareOverlayInternal(config)
            for (item in clusters) {
                showOrUpdateBubbleInternal(item, config, isFinished = true)
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            showAction.run()
        } else {
            mainHandler.post(showAction)
        }
    }

    /**
     * 内部同步卸载悬浮气泡
     */
    private fun dismissInternal() {
        isDismissed = true
        placeholderIds.clear()
        if (isShowing && rootOverlayView != null) {
            try {
                windowManager.removeView(rootOverlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        rootOverlayView = null
        bubbleViews.clear()
        bubbleDataMap.clear()
        synchronized(clusterBoundsMap) {
            clusterBoundsMap.clear()
        }
        isShowing = false
    }

    /**
     * 截屏期间临时隐藏译文浮层：隐藏 → 排空积压帧 → 等待隐藏后的新干净帧 → 执行截图 → 恢复。
     * 恢复位于 finally，任何异常/取消路径都不会留下永久隐藏的浮层。
     *
     * 相比固定延时，事件驱动只等"合成器重合成后的第一帧"，浮层不可见时长从 ~120ms+ 压缩到
     * 通常一两帧（约 16-40ms），周期性闪烁感知大幅降低。
     * @param awaitFreshFrame 截图前由服务注入的"等待干净帧"挂起函数（返回 false 表示超时，用兜底延时结果）
     */
    suspend fun withHiddenForCapture(
        awaitFreshFrame: suspend () -> Boolean = { delay(FRAME_DRAIN_MS); true },
        block: suspend () -> Bitmap?
    ): Bitmap? {
        val root = rootOverlayView
        if (root != null && isShowing) {
            withContext(Dispatchers.Main) { root.visibility = View.INVISIBLE }
            try {
                // 给 ViewRootImpl traversal + SurfaceFlinger 事务生效留一帧时间，
                // 否则紧随其后的排空可能漏掉隐藏事务生效前的最后一帧
                delay(POST_HIDE_SETTLE_MS)
                awaitFreshFrame()
                return block()
            } finally {
                // NonCancellable：协程取消时也必须恢复浮层可见，否则永久黑屏
                withContext(NonCancellable + Dispatchers.Main) { root.visibility = View.VISIBLE }
            }
        }
        return block()
    }

    /**
     * 关闭并清除悬浮气泡（对外暴露，支持任意线程调用）
     */
    fun dismiss() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissInternal()
        } else {
            mainHandler.post { dismissInternal() }
        }
    }

    /**
     * 动态切换全屏悬浮窗的触控穿透状态：
     * @param enabled 为 true 时开启 FLAG_NOT_TOUCHABLE（HUD 沉浸穿透态，手指滑网页/玩游戏毫无阻碍且绝不误触）；
     *                为 false 时移除 FLAG_NOT_TOUCHABLE（气泡交互态，可点击切换原文或长按重译）。
     */
    fun setTouchPassthrough(enabled: Boolean) {
        val action = Runnable {
            val root = rootOverlayView ?: return@Runnable
            val lp = root.layoutParams as? WindowManager.LayoutParams ?: return@Runnable
            val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            val targetFlags = if (enabled) {
                baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                baseFlags
            }
            if (lp.flags != targetFlags) {
                lp.flags = targetFlags
                try {
                    windowManager.updateViewLayout(root, lp)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (enabled) {
                root.setOnClickListener(null)
                root.isClickable = false
            } else {
                root.isClickable = true
                root.setOnClickListener {
                    // 交互态下若点击了空白处，自动切回穿透态，不阻碍后续游戏与滑动
                    setTouchPassthrough(true)
                    onPassthroughModeChangedListener?.invoke(true)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }
}
