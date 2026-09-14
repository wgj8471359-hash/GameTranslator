package com.game.translator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private lateinit var diffEngine: DiffEngine

    // 正在运行的翻译协程任务
    private var currentTranslationJob: Job? = null

    // 实时差异化翻译状态与协程循环
    private val isRealtimeActive = AtomicBoolean(false)
    private var isRealtimePassthrough = true
    private var realtimeJob: Job? = null
    private var longPressRunnable: Runnable? = null
    private var isLongPressTriggered = false

    // 零内存抖动复用缓冲池（消除高频截屏 GC 掉帧）
    private var cachedRawBitmap: Bitmap? = null
    private var cachedCroppedBitmap: Bitmap? = null
    private val bitmapBufferLock = Any()

    // 世代时序令牌（防范大模型网络延迟导致的过期翻译覆盖新视口）
    private val currentEpoch = AtomicLong(0)

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
    private val isStopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        diffEngine = DiffEngine()
        ocrHelper = OcrHelper()
        hyMtClient = HyMtClient()
        overlayManager = OverlayManager(this).apply {
            onUserDismissListener = {
                // 仅当用户手动轻触空白区域关闭气泡时，立即终止后台还在进行的流式翻译与网络请求
                cancelTranslation(notifyUser = false)
            }
            onBubbleLongClickListener = { cluster ->
                handleBubbleRetranslate(cluster)
            }
            onPassthroughModeChangedListener = { isPassthrough ->
                isRealtimePassthrough = isPassthrough
                if (isRealtimeActive.get()) {
                    floatingBallView?.setColorFilter(
                        if (isPassthrough) android.graphics.Color.parseColor("#6366F1")
                        else android.graphics.Color.parseColor("#F59E0B")
                    )
                }
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
                mainHandler.post {
                    stopServiceInternal()
                }
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

            currentEpoch.incrementAndGet()
            diffEngine.reset()
            overlayManager.dismiss()

            synchronized(bitmapBufferLock) {
                cachedRawBitmap?.recycle()
                cachedRawBitmap = null
                cachedCroppedBitmap?.recycle()
                cachedCroppedBitmap = null
            }

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

        synchronized(bitmapBufferLock) {
            cachedRawBitmap?.recycle()
            cachedRawBitmap = null
            cachedCroppedBitmap?.recycle()
            cachedCroppedBitmap = null
        }
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
                        isLongPressTriggered = false
                        ballView.alpha = 1.0f // 按下立刻高亮

                        // 长按 600ms 判定：触发「实时差异化翻译模式」切换
                        val longPressTask = Runnable {
                            if (!isDragging) {
                                isLongPressTriggered = true
                                toggleRealtimeMode()
                            }
                        }
                        longPressRunnable = longPressTask
                        mainHandler.postDelayed(longPressTask, 600)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                            isDragging = true
                            longPressRunnable?.let {
                                mainHandler.removeCallbacks(it)
                                longPressRunnable = null
                            }
                        }
                        val screenMetrics = getCurrentScreenMetrics()
                        // 允许平滑拖拽至当前屏幕的任何位置，实时边界防护防止移出视野
                        params.x = (initialX + dx).toInt().coerceIn(0, (screenMetrics.width - ballSize).coerceAtLeast(0))
                        params.y = (initialY + dy).toInt().coerceIn(0, (screenMetrics.height - ballSize).coerceAtLeast(0))
                        windowManager.updateViewLayout(ballView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        longPressRunnable?.let {
                            mainHandler.removeCallbacks(it)
                            longPressRunnable = null
                        }
                        val screenMetrics = getCurrentScreenMetrics()
                        val screenWidth = screenMetrics.width
                        val screenHeight = screenMetrics.height

                        if (!isDragging && !isLongPressTriggered) {
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
     * 切换实时差异化翻译模式开启/关闭状态
     */
    private fun toggleRealtimeMode() {
        if (isRealtimeActive.get()) {
            stopRealtimeMode()
        } else {
            startRealtimeMode()
        }
    }

    /**
     * 开启实时差异化截屏翻译循环
     */
    private fun startRealtimeMode() {
        val proj = mediaProjection
        if (proj == null) {
            Toast.makeText(this, "截屏服务未就绪，请重新在主页启动", Toast.LENGTH_SHORT).show()
            return
        }

        if (isRealtimeActive.compareAndSet(false, true)) {
            isRealtimePassthrough = true
            // 视觉反馈：悬浮球着色标明进入实时监听态（默认沉浸穿透）
            floatingBallView?.setColorFilter(android.graphics.Color.parseColor("#6366F1"))
            Toast.makeText(this, "已开启实时翻译【沉浸穿透态】：可正常滑动网页与操作游戏", Toast.LENGTH_SHORT).show()
            runRealtimeTranslationLoop()
        }
    }

    /**
     * 关闭实时差异化截屏翻译循环
     */
    private fun stopRealtimeMode() {
        if (isRealtimeActive.compareAndSet(true, false)) {
            currentEpoch.incrementAndGet()
            realtimeJob?.cancel()
            realtimeJob = null
            diffEngine.reset()
            overlayManager.dismiss()
            isRealtimePassthrough = true
            floatingBallView?.clearColorFilter()
            Toast.makeText(this, "已关闭实时翻译模式", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 实时差异化翻译主循环：
     * 1. 动态自适应休眠与帧捕获（闲置降频至 2.5s，活跃 1.2s）；
     * 2. 内存零抖动复用 Bitmap 缓冲；
     * 3. 空间与文本两级差分比对，仅翻译新增或变动文本；
     * 4. 增量渲染 Overlay（未变动气泡零重绘零闪烁，失效气泡平滑淡出）。
     */
    private fun runRealtimeTranslationLoop() {
        realtimeJob?.cancel()
        realtimeJob = serviceScope.launch {
            var idleRounds = 0
            diffEngine.reset()

            val prefs = MainActivity.getPrefs(this@TranslatorService)
            val baseInterval = try {
                prefs.getLong(MainActivity.KEY_SAMPLE_INTERVAL_MS, DiffEngine.DEFAULT_SAMPLE_INTERVAL_MS)
            } catch (e: Exception) {
                try {
                    prefs.getInt(MainActivity.KEY_SAMPLE_INTERVAL_MS, DiffEngine.DEFAULT_SAMPLE_INTERVAL_MS.toInt()).toLong()
                } catch (e2: Exception) {
                    DiffEngine.DEFAULT_SAMPLE_INTERVAL_MS
                }
            }

            while (isActive && isRealtimeActive.get()) {
                try {
                    // 自适应降频：连续无变化时步长放宽至 2.5s，降低芯片功耗与电池发热
                    val currentInterval = if (idleRounds >= 5) {
                        DiffEngine.IDLE_BACKOFF_INTERVAL_MS
                    } else {
                        baseInterval
                    }
                    delay(currentInterval)
                    if (!isActive || !isRealtimeActive.get()) break

                    // 检测横竖屏旋转动态调整分辨率
                    checkAndResizeCaptureSession()

                    // 静默复用缓冲区抓帧
                    val bitmap = captureScreen(reuse = true) ?: continue

                    val lineGapRatio = prefs.getFloat(MainActivity.KEY_LINE_GAP_RATIO, 1.2f)
                    val minTextLength = prefs.getInt(MainActivity.KEY_MIN_TEXT_LENGTH, 2)
                    val horizontalOverlapToleranceDp = prefs.getFloat(MainActivity.KEY_HORIZONTAL_OVERLAP_TOLERANCE, -20f)
                    val bubbleAlpha = prefs.getInt(MainActivity.KEY_BUBBLE_ALPHA, 85)
                    val fontMinSp = prefs.getInt(MainActivity.KEY_BUBBLE_FONT_MIN_SP, 8)
                    val fontMaxSp = prefs.getInt(MainActivity.KEY_BUBBLE_FONT_MAX_SP, 16)
                    val density = resources.displayMetrics.density
                    val ocrLanguage = prefs.getString(MainActivity.KEY_OCR_LANGUAGE, OcrHelper.LANG_AUTO) ?: OcrHelper.LANG_AUTO

                    // 本地离线 OCR 识别与并查集聚类
                    val clusters = ocrHelper.recognizeAndCluster(
                        bitmap = bitmap,
                        ocrLanguage = ocrLanguage,
                        lineGapRatio = lineGapRatio,
                        minTextLength = minTextLength,
                        horizontalOverlapToleranceDp = horizontalOverlapToleranceDp,
                        density = density
                    )

                    // 核心差分计算：空间 IoU 匹配、相似度比对、打字机消抖与 LRU 缓存匹配
                    val diffResult = diffEngine.processFrame(clusters, ocrLanguage)

                    val overlayConfig = OverlayManager.OverlayConfig(
                        minTextLength = minTextLength,
                        bubbleAlphaPercent = bubbleAlpha,
                        minFontSp = fontMinSp,
                        maxFontSp = fontMaxSp,
                        sourceImageWidth = bitmap.width,
                        sourceImageHeight = bitmap.height,
                        isRealtimeMode = true
                    )

                    // 增量上屏渲染（未变动气泡零闪烁，消失的气泡平滑淡出，缓存命中的即时呈现）
                    if (diffResult.hasVisualChanges) {
                        overlayManager.applyDiffResult(diffResult, overlayConfig)
                    }

                    // 增量请求大模型（仅处理未命中缓存且已稳定的文本）
                    if (diffResult.needModelTranslation.isNotEmpty()) {
                        idleRounds = 0
                        val translationConfig = getTranslationConfig(prefs)
                        val streamMode = translationConfig.streamMode
                        val requestEpoch = currentEpoch.get()

                        val result = hyMtClient.translateStream(diffResult.needModelTranslation, translationConfig) { clusterId, text, isFinished ->
                            if (requestEpoch != currentEpoch.get() || !isRealtimeActive.get()) return@translateStream
                            val target = diffResult.needModelTranslation.find { it.id == clusterId }
                            if (target != null) {
                                target.translatedText = text
                                if (streamMode) {
                                    overlayManager.showOrUpdateBubble(target, overlayConfig, isFinished)
                                }
                                if (isFinished) {
                                    diffEngine.putCache(ocrLanguage, target.originalText, text)
                                }
                            }
                        }

                        result.onSuccess { translatedList ->
                            if (requestEpoch != currentEpoch.get() || !isRealtimeActive.get()) return@onSuccess
                            for (item in translatedList) {
                                if (item.translatedText != null) {
                                    diffEngine.putCache(ocrLanguage, item.originalText, item.translatedText!!)
                                    overlayManager.showOrUpdateBubble(item, overlayConfig, isFinished = true)
                                }
                            }
                        }
                    } else {
                        if (!diffResult.hasVisualChanges) {
                            idleRounds++
                        } else {
                            idleRounds = 0
                        }
                    }

                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    // 实时监控单次网络波动或异常静默降级，继续下一轮监听
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * 统一构造大模型调用配置
     */
    private fun getTranslationConfig(prefs: SharedPreferences): HyMtClient.TranslationConfig {
        return HyMtClient.TranslationConfig(
            endpointUrl = prefs.getString(MainActivity.KEY_ENDPOINT, getString(R.string.default_endpoint_url)) ?: "",
            apiKey = prefs.getString(MainActivity.KEY_API_KEY, null),
            modelName = prefs.getString(MainActivity.KEY_MODEL, getString(R.string.default_model_name)) ?: "",
            temperature = prefs.getFloat(MainActivity.KEY_TEMPERATURE, 0.7f),
            topP = prefs.getFloat(MainActivity.KEY_TOP_P, 0.6f),
            frequencyPenalty = prefs.getFloat(MainActivity.KEY_FREQUENCY_PENALTY, 1.05f),
            maxTokens = prefs.getInt(MainActivity.KEY_MAX_TOKENS, 4096),
            systemPrompt = prefs.getString(MainActivity.KEY_SYSTEM_PROMPT, getString(R.string.default_system_prompt)) ?: "",
            timeoutSeconds = prefs.getInt(MainActivity.KEY_TIMEOUT_SECONDS, 60),
            streamMode = prefs.getBoolean(MainActivity.KEY_STREAM_MODE, true),
            streamType = prefs.getString(MainActivity.KEY_STREAM_TYPE, MainActivity.STREAM_TYPE_FORM_B) ?: MainActivity.STREAM_TYPE_FORM_B
        )
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
                if (isRealtimeActive.get()) {
                    // 实时监听模式下单次点击：在“沉浸触控穿透（滑网页/玩游戏）”与“气泡交互（点击原文/长按重译）”之间切换
                    toggleRealtimeTouchMode()
                } else {
                    triggerScreenTranslate()
                }
            }
            pendingSingleClickRunnable = clickRunnable
            mainHandler.postDelayed(clickRunnable, 300)
        }
    }

    /**
     * 实时模式下动态切换触控穿透态与气泡交互态：
     * 1. 穿透态（默认/紫色）：触控 100% 穿透到底层，保证手指正常滑动网页、AVG 推进对话、动作摇杆操作且绝不误触关闭气泡；
     * 2. 交互态（亮橙色）：触控临时被浮层接管，允许用户点击气泡切换原文/译文，长按气泡强制重新翻译该句。轻触空白处自动切回穿透态。
     */
    private fun toggleRealtimeTouchMode() {
        isRealtimePassthrough = !isRealtimePassthrough
        overlayManager.setTouchPassthrough(isRealtimePassthrough)
        if (isRealtimePassthrough) {
            floatingBallView?.setColorFilter(android.graphics.Color.parseColor("#6366F1"))
            Toast.makeText(this, "已恢复【沉浸穿透】：可正常滑动网页与操作游戏", Toast.LENGTH_SHORT).show()
        } else {
            floatingBallView?.setColorFilter(android.graphics.Color.parseColor("#F59E0B"))
            Toast.makeText(this, "已切入【气泡交互】：可点击切换原文，长按重译该句", Toast.LENGTH_SHORT).show()
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

                val bitmap = captureScreen(reuse = false)
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

                val config = getTranslationConfig(prefs)
                val streamMode = config.streamMode

                val overlayConfig = OverlayManager.OverlayConfig(
                    minTextLength = minTextLength,
                    bubbleAlphaPercent = bubbleAlpha,
                    minFontSp = fontMinSp,
                    maxFontSp = fontMaxSp,
                    sourceImageWidth = bmpWidth,
                    sourceImageHeight = bmpHeight,
                    isRealtimeMode = false
                )

                // 5. 检查本地 LRU 缓存（支持全模式缓存秒级直出）
                val needModelClusters = mutableListOf<ClusteredText>()
                for (cluster in clusters) {
                    val cached = diffEngine.getCache(ocrLanguage, cluster.originalText)
                    if (cached != null) {
                        cluster.translatedText = cached
                    } else {
                        needModelClusters.add(cluster)
                    }
                }

                // 提前准备透明气泡图层，并将所有命中缓存的条目即时挂载上屏（零模型等待、零延迟呈现）
                overlayManager.prepareOverlay(overlayConfig)
                for (cluster in clusters) {
                    if (cluster.translatedText != null) {
                        overlayManager.showOrUpdateBubble(cluster, overlayConfig, isFinished = true)
                    }
                }

                if (needModelClusters.isEmpty()) {
                    // 全屏文本全部命中缓存，无需请求大模型
                    Toast.makeText(this@TranslatorService, "已从缓存秒级呈现译文", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 6. 仅对未命中缓存的增量文本请求本地/局域网大模型
                val result = hyMtClient.translateStream(needModelClusters, config) { clusterId, text, isFinished ->
                    val cluster = needModelClusters.find { it.id == clusterId }
                    if (cluster != null) {
                        cluster.translatedText = text
                        if (streamMode) {
                            overlayManager.showOrUpdateBubble(cluster, overlayConfig, isFinished)
                        }
                        if (isFinished) {
                            diffEngine.putCache(ocrLanguage, cluster.originalText, text)
                        }
                    }
                }

                result.onSuccess { translatedClusters ->
                    for (item in translatedClusters) {
                        if (item.translatedText != null) {
                            diffEngine.putCache(ocrLanguage, item.originalText, item.translatedText!!)
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
     * 单句强制重新翻译：当用户发现某句译文有误时长按气泡触发
     * 1. 彻底清空该句本地 LRU 缓存；
     * 2. 气泡即时进入“正在重译...”占位态；
     * 3. 独立调用大模型单句翻译并流式写回更新气泡与缓存。
     */
    private fun handleBubbleRetranslate(cluster: ClusteredText) {
        serviceScope.launch {
            val prefs = MainActivity.getPrefs(this@TranslatorService)
            val ocrLanguage = prefs.getString(MainActivity.KEY_OCR_LANGUAGE, OcrHelper.LANG_AUTO) ?: OcrHelper.LANG_AUTO

            // 1. 彻底从 LRU 缓存中剔除该句译文
            diffEngine.removeCache(ocrLanguage, cluster.originalText)
            Toast.makeText(this@TranslatorService, "正在重新翻译本句...", Toast.LENGTH_SHORT).show()

            // 2. 占位刷新
            val config = getTranslationConfig(prefs)
            val overlayConfig = overlayManager.currentOverlayConfig

            cluster.translatedText = "正在重新翻译..."
            overlayManager.showOrUpdateBubble(cluster, overlayConfig, isFinished = false)

            // 3. 单句重新调用大模型
            val singleResult = hyMtClient.translateStream(listOf(cluster), config) { _, partialText, isFinished ->
                cluster.translatedText = partialText
                overlayManager.showOrUpdateBubble(cluster, overlayConfig, isFinished)
                if (isFinished) {
                    diffEngine.putCache(ocrLanguage, cluster.originalText, partialText)
                }
            }

            singleResult.onSuccess { list ->
                for (item in list) {
                    if (item.translatedText != null) {
                        diffEngine.putCache(ocrLanguage, item.originalText, item.translatedText!!)
                        overlayManager.showOrUpdateBubble(item, overlayConfig, isFinished = true)
                    }
                }
            }.onFailure { error ->
                Toast.makeText(this@TranslatorService, "重译失败: ${error.localizedMessage ?: "网络异常"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 从常驻 ImageReader 中获取最新帧，安全解析 RowStride 并避免 Bitmap 零拷贝导致的回收异常。
     * @param reuse 为 true 时启用单例复用缓冲，不重复分配内存，消除高频 GC 掉帧。
     */
    private suspend fun captureScreen(reuse: Boolean = false): Bitmap? = withContext(Dispatchers.Default) {
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

            synchronized(bitmapBufferLock) {
                if (reuse) {
                    val rawW = width + rowPadding / pixelStride
                    val rawH = height
                    val tempBitmap = if (cachedRawBitmap != null &&
                        cachedRawBitmap!!.width == rawW &&
                        cachedRawBitmap!!.height == rawH &&
                        !cachedRawBitmap!!.isRecycled
                    ) {
                        cachedRawBitmap!!
                    } else {
                        cachedRawBitmap?.recycle()
                        val newRaw = Bitmap.createBitmap(rawW, rawH, Bitmap.Config.ARGB_8888)
                        cachedRawBitmap = newRaw
                        newRaw
                    }

                    buffer.rewind()
                    tempBitmap.copyPixelsFromBuffer(buffer)

                    if (rowPadding == 0) {
                        tempBitmap
                    } else {
                        val cleanBitmap = if (cachedCroppedBitmap != null &&
                            cachedCroppedBitmap!!.width == width &&
                            cachedCroppedBitmap!!.height == height &&
                            !cachedCroppedBitmap!!.isRecycled
                        ) {
                            cachedCroppedBitmap!!
                        } else {
                            cachedCroppedBitmap?.recycle()
                            val newCrop = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            cachedCroppedBitmap = newCrop
                            newCrop
                        }
                        val canvas = android.graphics.Canvas(cleanBitmap)
                        val srcRect = android.graphics.Rect(0, 0, width, height)
                        val dstRect = android.graphics.Rect(0, 0, width, height)
                        canvas.drawBitmap(tempBitmap, srcRect, dstRect, null)
                        cleanBitmap
                    }
                } else {
                    val tempBitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    tempBitmap.copyPixelsFromBuffer(buffer)

                    val cleanBitmap = if (rowPadding == 0) {
                        tempBitmap
                    } else {
                        val cropped = Bitmap.createBitmap(tempBitmap, 0, 0, width, height)
                        tempBitmap.recycle()
                        cropped
                    }

                    cleanBitmap
                }
            }
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
        if (!isStopping.compareAndSet(false, true)) {
            return
        }
        isRunning = false
        pendingSingleClickRunnable?.let { mainHandler.removeCallbacks(it) }

        // 停止实时模式与协程循环
        try {
            stopRealtimeMode()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 1. 立即从 WindowManager 中移除悬浮球
        if (floatingBallView != null) {
            try {
                windowManager.removeView(floatingBallView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            floatingBallView = null
        }

        // 2. 中断进行中的翻译协程与 OCR 实例
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

        // 3. 彻底注销屏幕捕获会话（解绑监听、注销 Display、关闭 ImageReader）
        try {
            releaseCaptureSession()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. 【Android 14 关键时序修复】：必须在前台服务解除之前向系统服务器发送停止媒体投影信号！
        // 保证 MediaProjectionManagerService 能在前台服务合法存续期内注销 Token，及时通知 SystemUI 清除状态栏共享胶囊
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaProjection = null

        // 5. 投影正式关闭后，再解除前台服务状态并彻底移除通知栏
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

        serviceScope.cancel()

        // 6. 最终通知系统销毁本 Service 实例
        stopSelf()
    }

    /**
     * 当用户在多任务列表（Recents）上滑划掉主程序卡片时触发：
     * 联动彻底销毁截屏常驻服务与悬浮球，绝不在后台留下悬挂的录屏流与系统胶囊
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopServiceInternal()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServiceInternal()
    }
}
