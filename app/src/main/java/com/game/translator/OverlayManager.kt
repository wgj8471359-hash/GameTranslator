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
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    data class OverlayConfig(
        val minTextLength: Int = 2,
        val bubbleAlphaPercent: Int = 85,
        val minFontSp: Int = 8,
        val maxFontSp: Int = 16
    )

    private var rootOverlayView: FrameLayout? = null
    private var isShowing = false

    /**
     * 展现翻译气泡全屏覆盖层
     * @param clusters 待展示的聚类文本与译文列表
     * @param config 样式与过滤配置（透明度、字号自适应范围、最小字数）
     */
    /**
     * 展现翻译气泡全屏覆盖层
     * @param clusters 待展示的聚类文本与译文列表
     * @param config 样式与过滤配置（透明度、字号自适应范围、最小字数）
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showOverlay(clusters: List<ClusteredText>, config: OverlayConfig = OverlayConfig()) {
        val showAction = Runnable {
            // 同步清除已有浮层，确保在添加新浮层前彻底完成旧视图卸载
            dismissInternal()

            // 过滤有效气泡（文字长度 >= minTextLength）
            val validClusters = clusters.filter {
                val content = it.translatedText ?: it.originalText
                content.trim().length >= config.minTextLength && content.isNotBlank()
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
            // 确保全屏刘海屏物理坐标 1:1 吻合
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

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            val realDm = DisplayMetrics()
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val defaultDisplay = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            if (defaultDisplay != null) {
                @Suppress("DEPRECATION")
                defaultDisplay.getRealMetrics(realDm)
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(realDm)
            }
            val screenWidth = realDm.widthPixels
            val screenHeight = realDm.heightPixels
            val density = realDm.density
            val cornerRadiusPx = 6f * density
            val paddingPx = (4f * density).toInt()

            // 计算气泡动态透明度与颜色（默认 85% -> ARGB: D9, 1E, 1E, 24）
            val alphaInt = ((config.bubbleAlphaPercent.coerceIn(20, 100) / 100f) * 255).toInt()
            val bubbleColor = Color.argb(alphaInt, 0x1E, 0x1E, 0x24)

            val minSp = config.minFontSp.coerceAtLeast(6)
            val maxSp = config.maxFontSp.coerceAtLeast(minSp)

            for (item in validClusters) {
                val box = item.boundingBox
                val rawWidth = box.width().coerceAtLeast((30 * density).toInt())
                val rawHeight = box.height().coerceAtLeast((20 * density).toInt())

                // 边界安全防护：防止气泡超出物理屏幕范围
                val left = box.left.coerceIn(0, (screenWidth - (30 * density).toInt()).coerceAtLeast(0))
                val top = box.top.coerceIn(0, (screenHeight - (20 * density).toInt()).coerceAtLeast(0))
                val width = rawWidth.coerceAtMost(screenWidth - left)
                val height = rawHeight.coerceAtMost(screenHeight - top)

                val bubbleView = TextView(context).apply {
                    // 半透明深色圆角矩形（动态透明度，圆角 6dp）
                    val bgDrawable = GradientDrawable().apply {
                        setColor(bubbleColor)
                        cornerRadius = cornerRadiusPx
                    }
                    background = bgDrawable
                    setTextColor(Color.WHITE)
                    setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START

                    // 文字自适应充满原框（可配置 minSp ~ maxSp）
                    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                        this,
                        minSp,
                        maxSp,
                        1,
                        TypedValue.COMPLEX_UNIT_SP
                    )

                    // 默认优先展示译文，若无则展示原文
                    var isShowingTranslation = true
                    text = item.translatedText ?: item.originalText

                    // 点击单个气泡：在原文与译文之间来回切换
                    setOnClickListener {
                        isShowingTranslation = !isShowingTranslation
                        text = if (isShowingTranslation) {
                            item.translatedText ?: item.originalText
                        } else {
                            item.originalText
                        }
                    }
                }

                val childParams = FrameLayout.LayoutParams(width, height).apply {
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
