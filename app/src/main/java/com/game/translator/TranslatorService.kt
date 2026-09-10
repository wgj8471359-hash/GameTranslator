package com.game.translator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.abs

class TranslatorService : Service() {

    companion object {
        const val ACTION_START_SERVICE = "com.game.translator.action.START"
        const val ACTION_STOP_SERVICE = "com.game.translator.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "translator_service_channel"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null

    private lateinit var ocrHelper: OcrHelper
    private lateinit var hyMtClient: HyMtClient
    private lateinit var overlayManager: OverlayManager

    // 正在运行的翻译协程任务
    private var currentTranslationJob: Job? = null

    // 常驻屏幕捕获管道（规避 Android 14 单次令牌安全限制）
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var currentWidth = 0
    private var currentHeight = 0
    private var currentDpi = 0
    private var displayListener: DisplayManager.DisplayListener? = null

    // 悬浮球视图及布局参数
    private var floatingBallView: ImageView? = null
    private var floatingBallParams: WindowManager.LayoutParams? = null

    // 单击与双击判定
    private var lastClickTime: Long = 0
    private var pendingSingleClickRunnable: Runnable? = null

    // 并发防重入锁
    private val isTranslating = AtomicBoolean(false)
    private val isCancelling = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        ocrHelper = OcrHelper()
        hyMtClient = HyMtClient()
        overlayManager = OverlayManager(this).apply {
            onUserDismissListener = {
                // 仅当用户手动轻触空白区域关闭气泡时，立即终止后台还在进行的流式翻译与网络请求
                cancelTranslation(notifyUser = false)
            }
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START_SERVICE -> {
                startForegroundNotification()

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != 0 && resultData != null) {
                    mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                    mediaProjection?.let { initCaptureSession(it) }
                }

                initFloatingBall()
            }
            ACTION_STOP_SERVICE -> {
                stopServiceInternal()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_notification_text)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TranslatorService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher, getString(R.string.btn_stop_service), stopPendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private data class ScreenMetrics(val width: Int, val height: Int, val densityDpi: Int)

    /**
     * 动态获取当前物理硬件真实屏幕尺寸（严格结合硬件旋转角，保证横竖屏 1:1 精确长宽映射）
     */
    private fun getCurrentScreenMetrics(): ScreenMetrics {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val defaultDisplay = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = defaultDisplay?.rotation ?: Surface.ROTATION_0
        val isLandscape = rotation == Surface.ROTATION_90 ||
                rotation == Surface.ROTATION_270 ||
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        var rawWidth = 0
        var rawHeight = 0

        // 优先尝试 Android 30+ WindowMetrics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val bounds = windowManager.maximumWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    rawWidth = bounds.width()
                    rawHeight = bounds.height()
                }
            } catch (e: Exception) {
                // 忽略
            }
        }

        val dm = DisplayMetrics()
        if (defaultDisplay != null) {
            @Suppress("DEPRECATION")
            defaultDisplay.getRealMetrics(dm)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
        }

        if (rawWidth <= 0 || rawHeight <= 0) {
            rawWidth = dm.widthPixels
            rawHeight = dm.heightPixels
        }
        val densityDpi = if (dm.densityDpi > 0) dm.densityDpi else resources.configuration.densityDpi

        // 【核心数学与旋转保证】：手机硬件面板以长边为自然高，但旋转 90/270 度切入横屏游戏时，
        // 逻辑宽度必然是长边，高度必然是短边；竖屏时宽度必然为短边，高度为长边。
        // 消除底层 panel 物理长宽倒挂导致的 1080px 截断与缩放问题！
        val finalWidth = if (isLandscape) maxOf(rawWidth, rawHeight) else minOf(rawWidth, rawHeight)
        val finalHeight = if (isLandscape) minOf(rawWidth, rawHeight) else maxOf(rawWidth, rawHeight)

        return ScreenMetrics(
            width = finalWidth,
            height = finalHeight,
            densityDpi = densityDpi
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.post {
            checkAndResizeCaptureSession()
            updateFloatingBallForOrientation()
        }
    }

    /**
     * 注册硬件屏幕旋转与分辨率变化监听器，实现横屏游戏即时无缝适配
     */
    private fun registerDisplayListener() {
        if (displayListener != null) return
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    mainHandler.post {
                        checkAndResizeCaptureSession()
                        updateFloatingBallForOrientation()
                    }
                }
            }
        }
        displayManager.registerDisplayListener(listener, mainHandler)
        displayListener = listener
    }

    private fun unregisterDisplayListener() {
        displayListener?.let {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            displayManager?.unregisterDisplayListener(it)
            displayListener = null
        }
    }

    /**
     * 屏幕旋转时同步校准悬浮球坐标，仅限制在可视屏幕范围内，完全保留用户指定位置，绝不强制贴边与回弹
     */
    private fun updateFloatingBallForOrientation() {
        val ballView = floatingBallView ?: return
        val params = floatingBallParams ?: return
        val metrics = getCurrentScreenMetrics()
        val ballSize = params.width

        // 仅在超出新屏幕范围时修正，绝对不强制吸附或隐藏半边
        params.x = params.x.coerceIn(0, (metrics.width - ballSize).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.height - ballSize).coerceAtLeast(0))
        ballView.alpha = 0.85f

        try {
            windowManager.updateViewLayout(ballView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 初始化常驻屏幕流（规避 Android 14 多次调用 createVirtualDisplay 导致的 SecurityException）
     */
    private fun initCaptureSession(proj: MediaProjection) {
        val metrics = getCurrentScreenMetrics()
        currentWidth = metrics.width
        currentHeight = metrics.height
        currentDpi = metrics.densityDpi

        // 注册回调，监听系统级录屏结束或用户取消
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                releaseCaptureSession()
                mediaProjection = null
                stopSelf()
            }
        }
        proj.registerCallback(callback, mainHandler)
        projectionCallback = callback

        // 注册屏幕旋转动态监听
        registerDisplayListener()

        val reader = ImageReader.newInstance(currentWidth, currentHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        try {
            virtualDisplay = proj.createVirtualDisplay(
                "GameTranslatorCapture",
                currentWidth,
                currentHeight,
                currentDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 检查屏幕旋转或分辨率变更（例如切入横屏游戏），动态调整 VirtualDisplay 尺寸而无需重建
     */
    private fun checkAndResizeCaptureSession(): Boolean {
        val metrics = getCurrentScreenMetrics()
        if (metrics.width != currentWidth || metrics.height != currentHeight || metrics.densityDpi != currentDpi) {
            currentWidth = metrics.width
            currentHeight = metrics.height
            currentDpi = metrics.densityDpi

            val oldReader = imageReader
            val newReader = ImageReader.newInstance(currentWidth, currentHeight, PixelFormat.RGBA_8888, 2)
            imageReader = newReader

            virtualDisplay?.resize(currentWidth, currentHeight, currentDpi)
            virtualDisplay?.setSurface(newReader.surface)

            oldReader?.close()
            return true
        }
        return false
    }

    /**
     * 释放屏幕捕获会话
     */
    private fun releaseCaptureSession() {
        unregisterDisplayListener()
        projectionCallback?.let {
            try {
                mediaProjection?.unregisterCallback(it)
            } catch (e: Exception) {
                // 忽略注销异常
            }
            projectionCallback = null
        }
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        imageReader = null
    }

    /**
     * 初始化半透明悬浮球（支持大小自定义与自由拖拽放置，取消贴边隐藏）
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initFloatingBall() {
        if (floatingBallView != null) return

        val prefs = MainActivity.getPrefs(this)
        val ballSizeDp = prefs.getInt(MainActivity.KEY_BALL_SIZE_DP, 44)
        val density = resources.displayMetrics.density
        val ballSize = (ballSizeDp * density).toInt()
        val screenMetrics = getCurrentScreenMetrics()

        val params = WindowManager.LayoutParams().apply {
            width = ballSize
            height = ballSize
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            // 初始放置在屏幕右侧并留出 16dp 边距，完全在可视屏幕内，绝不半隐藏
            x = (screenMetrics.width - ballSize - (16 * density).toInt()).coerceAtLeast(0)
            y = screenMetrics.height / 3
        }
        floatingBallParams = params

        val ballView = ImageView(this).apply {
            setBackgroundResource(R.drawable.bg_floating_ball)
            setImageResource(R.drawable.ic_translate)
            val iconPadding = (ballSizeDp * 0.22f * density).toInt().coerceAtLeast((6 * density).toInt())
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            alpha = 0.85f // 始终保持舒适可见度，取消贴边半隐藏变暗
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        ballView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        ballView.alpha = 1.0f // 按下立刻高亮
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                            isDragging = true
                        }
                        val screenMetrics = getCurrentScreenMetrics()
                        // 允许平滑拖拽至当前屏幕的任何位置，实时边界防护防止移出视野
                        params.x = (initialX + dx).toInt().coerceIn(0, (screenMetrics.width - ballSize).coerceAtLeast(0))
                        params.y = (initialY + dy).toInt().coerceIn(0, (screenMetrics.height - ballSize).coerceAtLeast(0))
                        windowManager.updateViewLayout(ballView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val screenMetrics = getCurrentScreenMetrics()
                        val screenWidth = screenMetrics.width
                        val screenHeight = screenMetrics.height

                        if (!isDragging) {
                            // 单击事件：短暂保持高亮触发翻译，随后恢复
                            ballView.alpha = 1.0f
                            handleFloatingBallClick()
                            mainHandler.postDelayed({
                                ballView.alpha = 0.85f
                            }, 1200)
                        } else {
                            // 【彻底移除贴边隐藏半边与强制吸附】
                            // 无论横屏还是竖屏，松手即停留在当前位置，绝不自动弹跳到边缘，也绝不隐藏半边！
                            params.x = params.x.coerceIn(0, (screenWidth - ballSize).coerceAtLeast(0))
                            params.y = params.y.coerceIn(0, (screenHeight - ballSize).coerceAtLeast(0))
                            ballView.alpha = 0.85f
                            windowManager.updateViewLayout(ballView, params)
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(ballView, params)
            floatingBallView = ballView
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 立即取消正在执行的截屏与流式翻译任务，释放所有网络连接与协程资源
     */
    private fun cancelTranslation(notifyUser: Boolean = false) {
        if (!isCancelling.compareAndSet(false, true)) return
        try {
            currentTranslationJob?.cancel()
            currentTranslationJob = null
            hyMtClient.cancelAll()
            overlayManager.dismiss()
            floatingBallView?.visibility = View.VISIBLE
            isTranslating.set(false)
            if (notifyUser) {
                Toast.makeText(this, R.string.toast_translation_cancelled, Toast.LENGTH_SHORT).show()
            }
        } finally {
            isCancelling.set(false)
        }
    }

    /**
     * 单击与双击分发处理
     */
    private fun handleFloatingBallClick() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime < 300) {
            // 双击：取消待执行的单击事件，调起 MainActivity 配置页
            pendingSingleClickRunnable?.let { mainHandler.removeCallbacks(it) }
            lastClickTime = 0

            val configIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(configIntent)
        } else {
            // 若当前正在翻译/流式吐字中，用户再次点击悬浮球，代表想停止当前翻译：立即中断！
            if (isTranslating.get() || currentTranslationJob?.isActive == true) {
                cancelTranslation(notifyUser = true)
                lastClickTime = 0
                return
            }

            // 单击：延迟 300ms 触发截屏翻译，等待双击判定
            lastClickTime = currentTime
            val clickRunnable = Runnable {
                triggerScreenTranslate()
            }
            pendingSingleClickRunnable = clickRunnable
            mainHandler.postDelayed(clickRunnable, 300)
        }
    }

    /**
     * 触发完整截屏 -> OCR -> 翻译 -> 气泡展示管线
     */
    private fun triggerScreenTranslate() {
        val proj = mediaProjection
        if (proj == null) {
            Toast.makeText(this, "截屏服务未就绪，请重新在主页启动", Toast.LENGTH_SHORT).show()
            return
        }

        // 并发防重入锁：正在翻译时忽略连击
        if (!isTranslating.compareAndSet(false, true)) {
            return
        }

        currentTranslationJob = serviceScope.launch {
            try {
                // 1. 隐藏悬浮球与历史气泡，并留出 60ms 帧同步间隔，防止自身被截图录入
                floatingBallView?.visibility = View.INVISIBLE
                overlayManager.dismiss()
                delay(60)

                // 2. 检测横竖屏旋转动态调整分辨率
                val hasResized = checkAndResizeCaptureSession()
                if (hasResized) {
                    delay(120) // 给系统合成器足够时间刷新至新尺寸 Surface
                }

                val bitmap = captureScreen()
                floatingBallView?.visibility = View.VISIBLE

                if (bitmap == null) {
                    Toast.makeText(this@TranslatorService, R.string.toast_capture_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 交互反馈：截屏完成后立即弹出提示词句，告知用户正在处理
                Toast.makeText(this@TranslatorService, R.string.toast_translating, Toast.LENGTH_SHORT).show()

                // 3. 读取用户最新配置（含 API Key）
                val prefs = MainActivity.getPrefs(this@TranslatorService)
                val lineGapRatio = prefs.getFloat(MainActivity.KEY_LINE_GAP_RATIO, 1.2f)
                val minTextLength = prefs.getInt(MainActivity.KEY_MIN_TEXT_LENGTH, 2)
                val horizontalOverlapToleranceDp = prefs.getFloat(MainActivity.KEY_HORIZONTAL_OVERLAP_TOLERANCE, -20f)
                val bubbleAlpha = prefs.getInt(MainActivity.KEY_BUBBLE_ALPHA, 85)
                val fontMinSp = prefs.getInt(MainActivity.KEY_BUBBLE_FONT_MIN_SP, 8)
                val fontMaxSp = prefs.getInt(MainActivity.KEY_BUBBLE_FONT_MAX_SP, 16)
                val density = resources.displayMetrics.density
                val ocrLanguage = prefs.getString(MainActivity.KEY_OCR_LANGUAGE, OcrHelper.LANG_AUTO) ?: OcrHelper.LANG_AUTO

                // 4. Google ML Kit 本地离线 OCR 识别与并查集几何聚类（支持中日韩英全语种）
                val bmpWidth = bitmap.width
                val bmpHeight = bitmap.height
                val clusters = try {
                    ocrHelper.recognizeAndCluster(
                        bitmap = bitmap,
                        ocrLanguage = ocrLanguage,
                        lineGapRatio = lineGapRatio,
                        minTextLength = minTextLength,
                        horizontalOverlapToleranceDp = horizontalOverlapToleranceDp,
                        density = density
                    )
                } finally {
                    bitmap.recycle()
                }

                if (clusters.isEmpty()) {
                    Toast.makeText(this@TranslatorService, R.string.toast_no_text_detected, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val timeoutSeconds = prefs.getInt(MainActivity.KEY_TIMEOUT_SECONDS, 60)
                val streamMode = prefs.getBoolean(MainActivity.KEY_STREAM_MODE, true)
                val streamType = prefs.getString(MainActivity.KEY_STREAM_TYPE, MainActivity.STREAM_TYPE_FORM_B) ?: MainActivity.STREAM_TYPE_FORM_B

                val config = HyMtClient.TranslationConfig(
                    endpointUrl = prefs.getString(MainActivity.KEY_ENDPOINT, getString(R.string.default_endpoint_url)) ?: "",
                    apiKey = prefs.getString(MainActivity.KEY_API_KEY, null),
                    modelName = prefs.getString(MainActivity.KEY_MODEL, getString(R.string.default_model_name)) ?: "",
                    temperature = prefs.getFloat(MainActivity.KEY_TEMPERATURE, 0.7f),
                    topP = prefs.getFloat(MainActivity.KEY_TOP_P, 0.6f),
                    frequencyPenalty = prefs.getFloat(MainActivity.KEY_FREQUENCY_PENALTY, 1.05f),
                    maxTokens = prefs.getInt(MainActivity.KEY_MAX_TOKENS, 4096),
                    systemPrompt = prefs.getString(MainActivity.KEY_SYSTEM_PROMPT, getString(R.string.default_system_prompt)) ?: "",
                    timeoutSeconds = timeoutSeconds,
                    streamMode = streamMode,
                    streamType = streamType
                )

                val overlayConfig = OverlayManager.OverlayConfig(
                    minTextLength = minTextLength,
                    bubbleAlphaPercent = bubbleAlpha,
                    minFontSp = fontMinSp,
                    maxFontSp = fontMaxSp,
                    sourceImageWidth = bmpWidth,
                    sourceImageHeight = bmpHeight
                )

                // 5. 提前准备透明气泡图层（若开启流式，第一句生成出来时以零等待上屏呈现）
                if (streamMode) {
                    overlayManager.prepareOverlay(overlayConfig)
                }

                // 6. 请求本地/局域网大模型流式或单次翻译
                val result = hyMtClient.translateStream(clusters, config) { clusterId, text, isFinished ->
                    if (streamMode) {
                        val cluster = clusters.find { it.id == clusterId }
                        if (cluster != null) {
                            cluster.translatedText = text
                            overlayManager.showOrUpdateBubble(cluster, overlayConfig, isFinished)
                        }
                    }
                }

                result.onSuccess { translatedClusters ->
                    if (!streamMode) {
                        overlayManager.showOverlay(translatedClusters, overlayConfig)
                    } else {
                        // 确认所有气泡最终状态均已正确落地，无闪烁刷新
                        for (item in translatedClusters) {
                            overlayManager.showOrUpdateBubble(item, overlayConfig, isFinished = true)
                        }
                    }
                }.onFailure { error ->
                    val msg = getString(R.string.toast_translation_failed, error.localizedMessage ?: "未知错误")
                    Toast.makeText(this@TranslatorService, msg, Toast.LENGTH_LONG).show()
                }

            } catch (e: CancellationException) {
                // 用户主动中断或切换页面关闭气泡，安全静默退出
            } catch (e: Exception) {
                Toast.makeText(this@TranslatorService, "处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                floatingBallView?.visibility = View.VISIBLE
                isTranslating.set(false)
                currentTranslationJob = null
            }
        }
    }

    /**
     * 从常驻 ImageReader 中获取最新帧，安全解析 RowStride 并避免 Bitmap 零拷贝导致的回收异常
     */
    private suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.Default) {
        val reader = imageReader ?: return@withContext null

        // 尝试直接获取已到达的最新帧
        var image: Image? = reader.acquireLatestImage()
        if (image == null) {
            // 若暂无新帧到达（画面静止），挂起等待下一帧渲染事件（超时 1000ms 保护）
            image = withTimeoutOrNull(1000) {
                suspendCancellableCoroutine<Image?> { cont ->
                    reader.setOnImageAvailableListener({ r ->
                        reader.setOnImageAvailableListener(null, null)
                        val img = try {
                            r.acquireLatestImage()
                        } catch (e: Exception) {
                            null
                        }
                        if (cont.isActive) cont.resume(img)
                    }, mainHandler)

                    cont.invokeOnCancellation {
                        reader.setOnImageAvailableListener(null, null)
                    }
                }
            }
        }

        if (image == null) return@withContext null

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height

            // 图像行步长填充 (RowStride Padding) 计算，消除花屏与斜向撕裂
            val rowPadding = rowStride - pixelStride * width

            val tempBitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            tempBitmap.copyPixelsFromBuffer(buffer)

            // 【P0 崩溃陷阱修复】：当 rowPadding == 0 时，裁剪宽高与原图一致，
            // Android 官方 Bitmap.createBitmap 会直接返回 tempBitmap 自身。
            // 此时必须避免 recycle，否则 cleanBitmap 的内存被释放导致后续抛出 Cannot use recycled bitmap！
            val cleanBitmap = if (rowPadding == 0) {
                tempBitmap
            } else {
                val cropped = Bitmap.createBitmap(tempBitmap, 0, 0, width, height)
                tempBitmap.recycle()
                cropped
            }

            cleanBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            image.close()
        }
    }

    /**
     * 彻底停止服务并完整释放所有前台通知、常驻屏幕流与媒体投影资源
     */
    private fun stopServiceInternal() {
        if (!isRunning && mediaProjection == null && floatingBallView == null) {
            stopSelf()
            return
        }
        isRunning = false
        pendingSingleClickRunnable?.let { mainHandler.removeCallbacks(it) }

        // 1. 立即从 WindowManager 中移除悬浮球
        if (floatingBallView != null) {
            try {
                windowManager.removeView(floatingBallView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            floatingBallView = null
        }

        // 2. 关键修复：主动解除前台服务状态并彻底移除常驻通知
        // 必须在 stopSelf 之前调用，否则 Android 系统会因为前台标志位而拦截服务的正常销毁！
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. 中断进行中的翻译协程与 OCR 实例
        try {
            cancelTranslation(notifyUser = false)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            ocrHelper.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. 彻底注销屏幕捕获会话（解绑监听、注销 Display、关闭 ImageReader）
        try {
            releaseCaptureSession()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 5. 彻底向系统服务器发送停止媒体投影信号，通知系统关闭投屏状态
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaProjection = null

        serviceScope.cancel()

        // 6. 最终通知系统销毁本 Service 实例
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServiceInternal()
    }
}
