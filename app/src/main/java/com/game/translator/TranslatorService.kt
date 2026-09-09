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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null

    private lateinit var ocrHelper: OcrHelper
    private lateinit var hyMtClient: HyMtClient
    private lateinit var overlayManager: OverlayManager

    // 常驻屏幕捕获管道（规避 Android 14 单次令牌安全限制）
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var currentWidth = 0
    private var currentHeight = 0
    private var currentDpi = 0

    // 悬浮球视图及布局参数
    private var floatingBallView: ImageView? = null
    private var floatingBallParams: WindowManager.LayoutParams? = null

    // 单击与双击判定
    private var lastClickTime: Long = 0
    private var pendingSingleClickRunnable: Runnable? = null

    // 并发防重入锁
    private val isTranslating = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        ocrHelper = OcrHelper()
        hyMtClient = HyMtClient()
        overlayManager = OverlayManager(this)

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
                stopSelf()
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

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
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
     * 动态获取当前物理屏幕的方向与像素边界（支持 Android 30+ WindowMetrics）
     */
    private fun getCurrentScreenMetrics(): ScreenMetrics {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            val config = resources.configuration
            ScreenMetrics(
                width = bounds.width(),
                height = bounds.height(),
                densityDpi = config.densityDpi
            )
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            ScreenMetrics(
                width = dm.widthPixels,
                height = dm.heightPixels,
                densityDpi = dm.densityDpi
            )
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
    private fun checkAndResizeCaptureSession() {
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
        }
    }

    /**
     * 释放屏幕捕获会话
     */
    private fun releaseCaptureSession() {
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
     * 初始化半透明圆形悬浮球
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initFloatingBall() {
        if (floatingBallView != null) return

        val density = resources.displayMetrics.density
        val ballSize = (54 * density).toInt()
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
            x = screenMetrics.width - ballSize - (16 * density).toInt()
            y = screenMetrics.height / 3
        }
        floatingBallParams = params

        val ballView = ImageView(this).apply {
            setBackgroundResource(R.drawable.bg_floating_ball)
            setImageResource(R.drawable.ic_translate)
            val iconPadding = (12 * density).toInt()
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
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
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                            isDragging = true
                        }
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        windowManager.updateViewLayout(ballView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            handleFloatingBallClick()
                        } else {
                            // 松手贴边平滑吸附
                            val screenWidth = getCurrentScreenMetrics().width
                            val middleX = screenWidth / 2
                            val edgeMargin = (12 * density).toInt()
                            params.x = if (params.x + ballSize / 2 < middleX) edgeMargin else screenWidth - ballSize - edgeMargin
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

        serviceScope.launch {
            try {
                // 1. 隐藏悬浮球与历史气泡，并留出 60ms 帧同步间隔，防止自身被截图录入
                floatingBallView?.visibility = View.INVISIBLE
                overlayManager.dismiss()
                delay(60)

                // 2. 检测横竖屏旋转动态调整分辨率
                checkAndResizeCaptureSession()

                val bitmap = captureScreen()
                floatingBallView?.visibility = View.VISIBLE

                if (bitmap == null) {
                    Toast.makeText(this@TranslatorService, R.string.toast_capture_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 3. 读取用户最新配置（含 API Key）
                val prefs = MainActivity.getPrefs(this@TranslatorService)
                val lineGapRatio = prefs.getFloat(MainActivity.KEY_LINE_GAP_RATIO, 1.2f)
                val minTextLength = prefs.getInt(MainActivity.KEY_MIN_TEXT_LENGTH, 2)
                val horizontalOverlapToleranceDp = prefs.getFloat(MainActivity.KEY_HORIZONTAL_OVERLAP_TOLERANCE, -20f)
                val bubbleAlpha = prefs.getInt(MainActivity.KEY_BUBBLE_ALPHA, 85)
                val fontMinSp = prefs.getInt(MainActivity.KEY_BUBBLE_FONT_MIN_SP, 8)
                val fontMaxSp = prefs.getInt(MainActivity.KEY_BUBBLE_FONT_MAX_SP, 16)
                val density = resources.displayMetrics.density

                // 4. Google ML Kit 本地离线 OCR 识别与并查集几何聚类
                val clusters = ocrHelper.recognizeAndCluster(
                    bitmap = bitmap,
                    lineGapRatio = lineGapRatio,
                    minTextLength = minTextLength,
                    horizontalOverlapToleranceDp = horizontalOverlapToleranceDp,
                    density = density
                )
                bitmap.recycle()

                if (clusters.isEmpty()) {
                    Toast.makeText(this@TranslatorService, R.string.toast_no_text_detected, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val config = HyMtClient.TranslationConfig(
                    endpointUrl = prefs.getString(MainActivity.KEY_ENDPOINT, getString(R.string.default_endpoint_url)) ?: "",
                    apiKey = prefs.getString(MainActivity.KEY_API_KEY, null),
                    modelName = prefs.getString(MainActivity.KEY_MODEL, getString(R.string.default_model_name)) ?: "",
                    temperature = prefs.getFloat(MainActivity.KEY_TEMPERATURE, 0.1f),
                    topP = prefs.getFloat(MainActivity.KEY_TOP_P, 0.7f),
                    frequencyPenalty = prefs.getFloat(MainActivity.KEY_FREQUENCY_PENALTY, 1.05f),
                    maxTokens = prefs.getInt(MainActivity.KEY_MAX_TOKENS, 1024),
                    systemPrompt = prefs.getString(MainActivity.KEY_SYSTEM_PROMPT, getString(R.string.default_system_prompt)) ?: ""
                )

                // 5. 请求本地/局域网大模型单次结构化批量翻译
                val result = hyMtClient.translate(clusters, config)
                result.onSuccess { translatedClusters ->
                    // 6. 浮层展示半透明圆角气泡
                    val overlayConfig = OverlayManager.OverlayConfig(
                        minTextLength = minTextLength,
                        bubbleAlphaPercent = bubbleAlpha,
                        minFontSp = fontMinSp,
                        maxFontSp = fontMaxSp
                    )
                    overlayManager.showOverlay(translatedClusters, overlayConfig)
                }.onFailure { error ->
                    val msg = getString(R.string.toast_translation_failed, error.localizedMessage ?: "未知错误")
                    Toast.makeText(this@TranslatorService, msg, Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                Toast.makeText(this@TranslatorService, "处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                floatingBallView?.visibility = View.VISIBLE
                isTranslating.set(false)
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

    override fun onDestroy() {
        super.onDestroy()
        pendingSingleClickRunnable?.let { mainHandler.removeCallbacks(it) }

        if (floatingBallView != null) {
            try {
                windowManager.removeView(floatingBallView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            floatingBallView = null
        }

        overlayManager.dismiss()
        ocrHelper.release()
        releaseCaptureSession()
        mediaProjection?.stop()
        mediaProjection = null
        serviceScope.cancel()
    }
}
