package com.game.translator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "game_translator_config"
        const val KEY_ENDPOINT = "endpoint_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model_name"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "top_p"
        const val KEY_FREQUENCY_PENALTY = "frequency_penalty"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
        const val KEY_STREAM_MODE = "stream_mode"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_LINE_GAP_RATIO = "line_gap_ratio"
        const val KEY_MIN_TEXT_LENGTH = "min_text_length"
        const val KEY_HORIZONTAL_OVERLAP_TOLERANCE = "horizontal_overlap_tolerance"
        const val KEY_BUBBLE_ALPHA = "bubble_alpha"
        const val KEY_BUBBLE_FONT_MIN_SP = "bubble_font_min_sp"
        const val KEY_BUBBLE_FONT_MAX_SP = "bubble_font_max_sp"
        const val KEY_BALL_SIZE_DP = "ball_size_dp"

        fun getPrefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private lateinit var etEndpoint: TextInputEditText
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etModelName: TextInputEditText
    private lateinit var etTemperature: TextInputEditText
    private lateinit var etTopP: TextInputEditText
    private lateinit var etFrequencyPenalty: TextInputEditText
    private lateinit var etMaxTokens: TextInputEditText
    private lateinit var etTimeoutSeconds: TextInputEditText
    private lateinit var switchStreamMode: SwitchMaterial
    private lateinit var etSystemPrompt: TextInputEditText
    private lateinit var etBallSize: TextInputEditText
    private lateinit var etLineGapRatio: TextInputEditText
    private lateinit var etMinTextLength: TextInputEditText
    private lateinit var etHorizontalOverlapTolerance: TextInputEditText
    private lateinit var etBubbleAlpha: TextInputEditText
    private lateinit var etBubbleFontMinSp: TextInputEditText
    private lateinit var etBubbleFontMaxSp: TextInputEditText

    private lateinit var btnSaveConfig: MaterialButton
    private lateinit var btnResetRecommend: MaterialButton
    private lateinit var btnStartService: MaterialButton
    private lateinit var btnStopService: MaterialButton
    private lateinit var btnBatteryOptimization: MaterialButton

    // 截屏授权回调
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startTranslatorService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "未能获取截屏授权，悬浮球无法启动", Toast.LENGTH_SHORT).show()
        }
    }

    // 悬浮窗权限回调
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            requestScreenCapture()
        } else {
            Toast.makeText(this, R.string.toast_overlay_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    // Android 13+ 通知权限回调
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        checkOverlayAndStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadConfig()
        setupListeners()
    }

    private fun initViews() {
        etEndpoint = findViewById(R.id.etEndpoint)
        etApiKey = findViewById(R.id.etApiKey)
        etModelName = findViewById(R.id.etModelName)
        etTemperature = findViewById(R.id.etTemperature)
        etTopP = findViewById(R.id.etTopP)
        etFrequencyPenalty = findViewById(R.id.etFrequencyPenalty)
        etMaxTokens = findViewById(R.id.etMaxTokens)
        etTimeoutSeconds = findViewById(R.id.etTimeoutSeconds)
        switchStreamMode = findViewById(R.id.switchStreamMode)
        etSystemPrompt = findViewById(R.id.etSystemPrompt)
        etBallSize = findViewById(R.id.etBallSize)
        etLineGapRatio = findViewById(R.id.etLineGapRatio)
        etMinTextLength = findViewById(R.id.etMinTextLength)
        etHorizontalOverlapTolerance = findViewById(R.id.etHorizontalOverlapTolerance)
        etBubbleAlpha = findViewById(R.id.etBubbleAlpha)
        etBubbleFontMinSp = findViewById(R.id.etBubbleFontMinSp)
        etBubbleFontMaxSp = findViewById(R.id.etBubbleFontMaxSp)

        btnSaveConfig = findViewById(R.id.btnSaveConfig)
        btnResetRecommend = findViewById(R.id.btnResetRecommend)
        btnStartService = findViewById(R.id.btnStartService)
        btnStopService = findViewById(R.id.btnStopService)
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization)
    }

    private fun loadConfig() {
        val prefs = getPrefs(this)
        etEndpoint.setText(prefs.getString(KEY_ENDPOINT, getString(R.string.default_endpoint_url)))
        etApiKey.setText(prefs.getString(KEY_API_KEY, ""))
        etModelName.setText(prefs.getString(KEY_MODEL, getString(R.string.default_model_name)))
        etTemperature.setText(prefs.getFloat(KEY_TEMPERATURE, 0.7f).toString())
        etTopP.setText(prefs.getFloat(KEY_TOP_P, 0.6f).toString())
        etFrequencyPenalty.setText(prefs.getFloat(KEY_FREQUENCY_PENALTY, 1.05f).toString())
        etMaxTokens.setText(prefs.getInt(KEY_MAX_TOKENS, 4096).toString())
        etTimeoutSeconds.setText(prefs.getInt(KEY_TIMEOUT_SECONDS, 60).toString())
        switchStreamMode.isChecked = prefs.getBoolean(KEY_STREAM_MODE, true)
        etSystemPrompt.setText(prefs.getString(KEY_SYSTEM_PROMPT, getString(R.string.default_system_prompt)))
        etBallSize.setText(prefs.getInt(KEY_BALL_SIZE_DP, 44).toString())
        etLineGapRatio.setText(prefs.getFloat(KEY_LINE_GAP_RATIO, 1.2f).toString())
        etMinTextLength.setText(prefs.getInt(KEY_MIN_TEXT_LENGTH, 2).toString())
        etHorizontalOverlapTolerance.setText(prefs.getFloat(KEY_HORIZONTAL_OVERLAP_TOLERANCE, -20f).toString())
        etBubbleAlpha.setText(prefs.getInt(KEY_BUBBLE_ALPHA, 85).toString())
        etBubbleFontMinSp.setText(prefs.getInt(KEY_BUBBLE_FONT_MIN_SP, 8).toString())
        etBubbleFontMaxSp.setText(prefs.getInt(KEY_BUBBLE_FONT_MAX_SP, 16).toString())
    }

    private fun saveConfig() {
        val prefs = getPrefs(this)
        prefs.edit().apply {
            putString(KEY_ENDPOINT, etEndpoint.text.toString().trim())
            putString(KEY_API_KEY, etApiKey.text.toString().trim())
            putString(KEY_MODEL, etModelName.text.toString().trim())
            putFloat(KEY_TEMPERATURE, etTemperature.text.toString().toFloatOrNull() ?: 0.7f)
            putFloat(KEY_TOP_P, etTopP.text.toString().toFloatOrNull() ?: 0.6f)
            putFloat(KEY_FREQUENCY_PENALTY, etFrequencyPenalty.text.toString().toFloatOrNull() ?: 1.05f)
            putInt(KEY_MAX_TOKENS, etMaxTokens.text.toString().toIntOrNull() ?: 4096)
            putInt(KEY_TIMEOUT_SECONDS, (etTimeoutSeconds.text.toString().toIntOrNull() ?: 60).coerceIn(5, 600))
            putBoolean(KEY_STREAM_MODE, switchStreamMode.isChecked)
            putString(KEY_SYSTEM_PROMPT, etSystemPrompt.text.toString().trim())
            putInt(KEY_BALL_SIZE_DP, (etBallSize.text.toString().toIntOrNull() ?: 44).coerceIn(32, 72))
            putFloat(KEY_LINE_GAP_RATIO, etLineGapRatio.text.toString().toFloatOrNull() ?: 1.2f)
            putInt(KEY_MIN_TEXT_LENGTH, etMinTextLength.text.toString().toIntOrNull() ?: 2)
            putFloat(KEY_HORIZONTAL_OVERLAP_TOLERANCE, etHorizontalOverlapTolerance.text.toString().toFloatOrNull() ?: -20f)
            putInt(KEY_BUBBLE_ALPHA, (etBubbleAlpha.text.toString().toIntOrNull() ?: 85).coerceIn(20, 100))
            putInt(KEY_BUBBLE_FONT_MIN_SP, etBubbleFontMinSp.text.toString().toIntOrNull() ?: 8)
            putInt(KEY_BUBBLE_FONT_MAX_SP, etBubbleFontMaxSp.text.toString().toIntOrNull() ?: 16)
            apply()
        }
        Toast.makeText(this, R.string.toast_config_saved, Toast.LENGTH_SHORT).show()
    }

    private fun setupListeners() {
        btnSaveConfig.setOnClickListener {
            saveConfig()
        }

        btnResetRecommend.setOnClickListener {
            etTemperature.setText("0.7")
            etTopP.setText("0.6")
            etFrequencyPenalty.setText("1.05")
            etMaxTokens.setText("4096")
            etTimeoutSeconds.setText("60")
            switchStreamMode.isChecked = true
            etBallSize.setText("44")
            etSystemPrompt.setText(getString(R.string.default_system_prompt))
            saveConfig()
            Toast.makeText(this, R.string.toast_reset_recommend, Toast.LENGTH_SHORT).show()
        }

        btnStartService.setOnClickListener {
            saveConfig()
            if (TranslatorService.isRunning) {
                Toast.makeText(this, R.string.toast_service_already_running, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkPermissionsAndStart()
        }

        btnStopService.setOnClickListener {
            if (!TranslatorService.isRunning) {
                Toast.makeText(this, R.string.toast_service_not_running, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val stopIntent = Intent(this, TranslatorService::class.java).apply {
                action = TranslatorService.ACTION_STOP_SERVICE
            }
            startService(stopIntent)
            Toast.makeText(this, R.string.toast_service_stopped, Toast.LENGTH_SHORT).show()
        }

        btnBatteryOptimization.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }
    }

    private fun checkPermissionsAndStart() {
        // 1. 检查 Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        checkOverlayAndStart()
    }

    private fun checkOverlayAndStart() {
        // 2. 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        // 3. 申请 MediaProjection 截屏授权
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startTranslatorService(resultCode: Int, resultData: Intent) {
        val serviceIntent = Intent(this, TranslatorService::class.java).apply {
            action = TranslatorService.ACTION_START_SERVICE
            putExtra(TranslatorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(TranslatorService.EXTRA_RESULT_DATA, resultData)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(this, R.string.toast_service_started, Toast.LENGTH_SHORT).show()
    }

    private fun requestIgnoreBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // 部分定制系统可能限制直接调用，引导至设置页
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            }
        } else {
            Toast.makeText(this, "已处于忽略电池优化名单中", Toast.LENGTH_SHORT).show()
        }
    }
}
