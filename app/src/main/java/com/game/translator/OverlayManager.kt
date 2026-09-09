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

    /**
     * 展现翻译气泡全屏覆盖层
     * @param clusters 待展示的聚类文本与译文列表
     * @param config 样式与过滤配置（透明度、字号自适应范围、最小字数、截屏源图尺寸）
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showOverlay(clusters: List<ClusteredText>, config: OverlayConfig = OverlayConfig()) {
        val showAction = Runnable {
            // 同步清除已有浮层，确保在添加新浮层前彻底完成旧视图卸载
            dismissInternal()

            // 过滤有效气泡：
            // 1. 文字长度满足设定门槛（默认 >= 2）
            // 2. 智能剔除译文与原文完全相同的项（例如纯中文角色名/菜单、或已本地化的中文句子、或无需变动的标记），
            //    避免无意义的黑框遮挡游戏原本正常的中文显示
            val validClusters = clusters.filter {
                val content = it.translatedText ?: it.originalText
                val hasValidLength = content.trim().length >= config.minTextLength && content.isNotBlank()
                val isTextChanged = if (it.translatedText != null) {
                    !it.translatedText!!.trim().equals(it.originalText.trim(), ignoreCase = true)
                } else {
                    true
                }
                hasValidLength && isTextChanged
            }

            if (validClusters.isEmpty()) return@Runnable

            val rootView = FrameLayout(context).apply {
                // 点击屏幕空白任意区域清除气泡，不阻挡游戏后续操作
                setOnClickListener {
                    dismiss()
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
                } catch (_: Throwable) {}
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

            // 严格保证横屏下长边为宽、短边为高，消除 panel 原生方向倒挂
            val screenWidth = if (isLandscape) maxOf(rawWidth, rawHeight) else minOf(rawWidth, rawHeight)
            val screenHeight = if (isLandscape) minOf(rawWidth, rawHeight) else maxOf(rawWidth, rawHeight)
            val density = realDm.density
            val cornerRadiusPx = 6f * density
            val paddingH = (5f * density).toInt()
            val paddingV = (2.5f * density).toInt()

            // 动态计算源图与屏幕物理分辨率的映射缩放比（防畸变与错位）
            val scaleX = if (config.sourceImageWidth > 0) screenWidth.toFloat() / config.sourceImageWidth else 1.0f
            val scaleY = if (config.sourceImageHeight > 0) screenHeight.toFloat() / config.sourceImageHeight else 1.0f

            // 计算气泡动态透明度与颜色（默认 85% -> ARGB: D9, 1E, 1E, 24）
            val alphaInt = ((config.bubbleAlphaPercent.coerceIn(20, 100) / 100f) * 255).toInt()
            val bubbleColor = Color.argb(alphaInt, 0x1E, 0x1E, 0x24)

            val minSp = config.minFontSp.coerceAtLeast(6)
            val maxSp = config.maxFontSp.coerceAtLeast(minSp)
            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

            for (item in validClusters) {
                val box = item.boundingBox
                val scaledLeft = (box.left * scaleX).toInt()
                val scaledTop = (box.top * scaleY).toInt()
                val scaledWidth = (box.width() * scaleX).toInt().coerceAtLeast((30 * density).toInt())
                val scaledHeight = (box.height() * scaleY).toInt().coerceAtLeast((20 * density).toInt())

                // 边界安全防护：防止气泡超出物理屏幕范围
                val left = scaledLeft.coerceIn(0, (screenWidth - (30 * density).toInt()).coerceAtLeast(0))
                val top = scaledTop.coerceIn(0, (screenHeight - (20 * density).toInt()).coerceAtLeast(0))
                val width = scaledWidth.coerceAtMost(screenWidth - left)
                val originalHeight = scaledHeight.coerceAtMost(screenHeight - top)

                // 【解决两行截断遮挡与滚动查看的核心设计】：
                // 1. 下向自适应高度扩展：当单行译文因中文排版变两行时，允许气泡从原框高向下弹性延伸，
                //    最大可扩展至 2.4 倍原高（上限 48dp 或屏幕底边），彻底消除第二行被底框半遮挡的问题；
                // 2. 内部垂直平滑滚动（Vertical Scroll）：若超长文本达到最大高度依然装不下，
                //    自动启用原生内部滚动机制，用手指上下轻滑即可完整阅读全文。
                val maxAllowedHeight = maxOf(originalHeight, (originalHeight * 2.4f).toInt(), (48 * density).toInt())
                    .coerceAtMost((screenHeight - top).coerceAtLeast(originalHeight))

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
                    maxHeight = maxAllowedHeight

                    // 开启垂直平滑滚动
                    movementMethod = ScrollingMovementMethod.getInstance()
                    isVerticalScrollBarEnabled = false

                    // 文字自适应充满原框（可配置 minSp ~ maxSp）
                    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                        this,
                        minSp,
                        maxSp,
                        1,
                        TypedValue.COMPLEX_UNIT_SP
                    )

                    var isShowingTranslation = true
                    text = item.translatedText ?: item.originalText

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

                    // 区分轻触点击（切换原文/译文）与上下拖动（平滑滚动查看全文）
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
                    topMargin = top
                }

                rootView.addView(bubbleView, childParams)
            }

            try {
                windowManager.addView(rootView, layoutParams)
                rootOverlayView = rootView
                isShowing = true
            } catch (e: Exception) {
                e.printStackTrace()
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
        if (isShowing && rootOverlayView != null) {
            try {
                windowManager.removeView(rootOverlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                rootOverlayView = null
                isShowing = false
            }
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
