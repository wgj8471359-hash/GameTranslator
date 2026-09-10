package com.game.translator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
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
import kotlin.math.abs

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    data class OverlayConfig(
        val minTextLength: Int = 2,
        val bubbleAlphaPercent: Int = 85,
        val minFontSp: Int = 8,
        val maxFontSp: Int = 16,
        val sourceImageWidth: Int = 0,
        val sourceImageHeight: Int = 0
    )

    private var rootOverlayView: FrameLayout? = null
    private var isShowing = false
    private var isDismissed = false
    private val bubbleViews = mutableMapOf<Int, TextView>()

    private data class BubbleLayoutInfo(
        val id: Int,
        var left: Int,
        var top: Int,
        var width: Int,
        var height: Int
    )
    private val bubbleDataMap = mutableMapOf<Int, BubbleLayoutInfo>()

    /**
     * 仅当用户手动轻触全屏空白背景清除气泡时触发（用于终止后台仍在进行的网络请求）
     * 严禁在内部调用 dismiss() 时触发，防止陷入递归循环与误取消。
     */
    var onUserDismissListener: (() -> Unit)? = null

    private var currentConfig: OverlayConfig = OverlayConfig()
    private var currentScreenWidth = 0
    private var currentScreenHeight = 0
    private var currentDensity = 0f
    private var currentScaleX = 1f
    private var currentScaleY = 1f

    /**
     * 提前准备全屏透明覆盖根视图（用于流式逐句上屏呈现）
     */
    fun prepareOverlay(config: OverlayConfig) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            prepareOverlayInternal(config)
        } else {
            mainHandler.post { prepareOverlayInternal(config) }
        }
    }

    private fun prepareOverlayInternal(config: OverlayConfig) {
        isDismissed = false
        if (isShowing && rootOverlayView != null) return

        val rootView = FrameLayout(context).apply {
            // 用户点击屏幕空白任意区域清除气泡，同时触发用户主动关闭回调
            setOnClickListener {
                dismiss()
                onUserDismissListener?.invoke()
            }
        }

        // WindowManager 参数配置：
        // FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS or FLAG_LAYOUT_IN_SCREEN
        // 确保全屏刘海屏物理坐标 1:1 吻合，并强制定位在左上角对齐
        val layoutParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
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
     * 单个气泡实时呈现或流式增量刷新（逐句呈现模式）
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showOrUpdateBubble(item: ClusteredText, config: OverlayConfig = currentConfig, isFinished: Boolean = false) {
        val action = Runnable {
            showOrUpdateBubbleInternal(item, config, isFinished)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOrUpdateBubbleInternal(item: ClusteredText, config: OverlayConfig, isFinished: Boolean) {
        if (isDismissed) {
            // 用户已关闭气泡，直接丢弃后续流式吐字，绝不重新弹出遮挡新画面
            return
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
            }
            return
        }

        // 新建气泡要求有有效内容
        if (content.isBlank()) return

        val screenWidth = currentScreenWidth
        val screenHeight = currentScreenHeight
        val density = currentDensity
        val scaleX = currentScaleX
        val scaleY = currentScaleY

        val cornerRadiusPx = 6f * density
        val paddingH = (5f * density).toInt()
        val paddingV = (2.5f * density).toInt()

        val alphaInt = ((config.bubbleAlphaPercent.coerceIn(20, 100) / 100f) * 255).toInt()
        val bubbleColor = Color.argb(alphaInt, 0x1E, 0x1E, 0x24)

        val minSp = config.minFontSp.coerceAtLeast(6)
        val maxSp = config.maxFontSp.coerceAtLeast(minSp)
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

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

        // 动态防重叠碰撞布局调整：若水平投影重叠，根据已有气泡限制向下膨胀上限或推移垂直起始点，防止气泡上下重叠覆盖
        var finalTop = top
        var finalMaxHeight = maxAllowedHeight
        val gapPx = (4f * density).toInt()

        for ((_, exist) in bubbleDataMap) {
            val hOverlap = maxOf(left, exist.left) < minOf(left + width, exist.left + exist.width)
            if (hOverlap) {
                if (finalTop >= exist.top) {
                    val minAllowedTop = exist.top + exist.height + gapPx
                    if (finalTop < minAllowedTop) {
                        finalTop = minAllowedTop.coerceAtMost((screenHeight - (20 * density).toInt()).coerceAtLeast(0))
                    }
                } else {
                    val spaceAbove = exist.top - finalTop - gapPx
                    if (spaceAbove >= originalHeight) {
                        finalMaxHeight = minOf(finalMaxHeight, spaceAbove)
                    }
                }
            }
        }

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

            minHeight = originalHeight
            maxHeight = finalMaxHeight

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

            // 区分轻触点击与上下拖拽滚动
            var downX = 0f
            var downY = 0f
            var isScrolling = false

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        isScrolling = false
                        v.onTouchEvent(event)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(event.rawX - downX)
                        val dy = abs(event.rawY - downY)
                        if (dy > touchSlop || dx > touchSlop) {
                            isScrolling = true
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        v.onTouchEvent(event)
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isScrolling) {
                            v.performClick()
                        } else {
                            v.onTouchEvent(event)
                        }
                        true
                    }
                    else -> v.onTouchEvent(event)
                }
            }
        }

        val childParams = FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = left
            topMargin = finalTop
        }

        rootView.addView(bubbleView, childParams)
        bubbleViews[item.id] = bubbleView
        bubbleDataMap[item.id] = BubbleLayoutInfo(item.id, left, finalTop, width, originalHeight)
    }

    /**
     * 批量展现翻译气泡全屏覆盖层
     * @param clusters 待展示的聚类文本与译文列表
     * @param config 样式与过滤配置（透明度、字号自适应范围、最小字数、截屏源图尺寸）
     */
    fun showOverlay(clusters: List<ClusteredText>, config: OverlayConfig = OverlayConfig()) {
        val showAction = Runnable {
            dismissInternal()
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
        if (isShowing && rootOverlayView != null) {
            try {
                windowManager.removeView(rootOverlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                rootOverlayView = null
                bubbleViews.clear()
                bubbleDataMap.clear()
                isShowing = false
            }
        } else {
            bubbleViews.clear()
            bubbleDataMap.clear()
            isShowing = false
        }
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
}
