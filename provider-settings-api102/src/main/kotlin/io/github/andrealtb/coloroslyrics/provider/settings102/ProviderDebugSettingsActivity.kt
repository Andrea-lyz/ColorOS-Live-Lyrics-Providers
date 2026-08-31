/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.settings102

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.libxposed.service.XposedService

/**
 * v4.1 Debug settings page. Reads and writes the Provider's Remote Preferences group through the
 * connected Xposed service. When the service is absent, the framework API is below 102, the
 * remote capability is missing, or the group cannot be opened, the switch is disabled with an
 * explicit reason. There is intentionally no MODE_PRIVATE fallback.
 */
abstract class ProviderDebugSettingsActivity : Activity() {

    protected abstract val providerId: ProviderId
    protected abstract val providerDisplayName: String
    protected abstract val targetPackageDescription: String

    private lateinit var debugSwitch: Switch
    private lateinit var stateView: TextView
    private lateinit var warningView: TextView
    private var syncingSwitch = false
    private var remotePrefs: SharedPreferences? = null

    private val serviceListener: (XposedService?) -> Unit = { service ->
        runOnUiThread { onServiceChanged(service) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()

        val horizontalPadding = dp(24)
        val topPadding = dp(32)
        val bottomPadding = dp(28)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 250, 250))
            setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                horizontalPadding + bars.left,
                topPadding + bars.top,
                horizontalPadding + bars.right,
                bottomPadding + bars.bottom
            )
            insets
        }

        root.addView(titleView())
        root.addView(descriptionView())

        debugSwitch = Switch(this).apply {
            text = "启用调试日志"
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            isEnabled = false
        }
        root.addView(
            debugSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(24)
            }
        )

        stateView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(80, 80, 80))
            text = "当前状态：正在连接 Xposed 服务…"
        }
        root.addView(
            stateView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )

        warningView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(176, 0, 32))
        }
        root.addView(
            warningView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(20)
            }
        )

        root.addView(
            TextView(this).apply {
                text = (
                    "开启后，额外的 [CLL] DEBUG 事件会同时写入 Android logcat 和 "
                        + "LSPosed/Xposed framework log，可从 LSPosed 导出的日志包中获取。"
                        + "设置存储在 LSPosed Remote Preferences；从 4.0 升级后需要重新开启一次。"
                        + "切换后必须完全结束并重新启动目标播放器才会生效。"
                    )
                textSize = 14f
                setTextColor(Color.rgb(80, 80, 80))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(20)
            }
        )

        debugSwitch.setOnCheckedChangeListener { _, enabled ->
            if (syncingSwitch) {
                return@setOnCheckedChangeListener
            }
            val prefs = remotePrefs
            if (prefs == null) {
                revertSwitch(enabled)
                return@setOnCheckedChangeListener
            }
            val committed = runCatching {
                prefs.edit()
                    .putBoolean(ProviderDebugConfig.KEY_DEBUG_ENABLED, enabled)
                    .commit()
            }.getOrDefault(false)
            if (committed) {
                updateState(enabled)
            } else {
                revertSwitch(enabled)
                Toast.makeText(this, "无法保存调试开关", Toast.LENGTH_SHORT).show()
            }
        }

        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        ProviderServiceState.addListener(serviceListener)
        onServiceChanged(ProviderServiceState.service)
    }

    override fun onDestroy() {
        ProviderServiceState.removeListener(serviceListener)
        super.onDestroy()
    }

    private fun onServiceChanged(service: XposedService?) {
        remotePrefs = null
        when {
            service == null -> disableSwitch(
                "当前没有连接到 Xposed 服务。请确认 LSPosed 正在运行、本模块已激活，然后重新打开本页面。"
            )
            service.apiVersion < XposedService.API_102 -> disableSwitch(
                "Xposed 框架 API 版本低于 102，无法使用 Remote Preferences，开关暂不可用。"
            )
            (service.frameworkProperties and XposedService.PROP_CAP_REMOTE) == 0L -> disableSwitch(
                "当前 Xposed 框架未提供 Remote Preferences 能力，开关暂不可用。"
            )
            else -> {
                val prefs = runCatching {
                    service.getRemotePreferences(ProviderDebugConfig.prefsName(providerId))
                }.getOrNull()
                if (prefs == null) {
                    disableSwitch("打开 Remote Preferences 失败。请检查框架状态后重新打开本页面。")
                    return
                }
                remotePrefs = prefs
                warningView.text = ""
                debugSwitch.isEnabled = true
                syncingSwitch = true
                debugSwitch.isChecked = prefs.getBoolean(ProviderDebugConfig.KEY_DEBUG_ENABLED, false)
                syncingSwitch = false
                updateState(debugSwitch.isChecked)
            }
        }
    }

    private fun disableSwitch(reason: String) {
        syncingSwitch = true
        debugSwitch.isChecked = false
        syncingSwitch = false
        debugSwitch.isEnabled = false
        warningView.text = reason
        stateView.text = "当前状态：不可用。"
    }

    private fun revertSwitch(attempted: Boolean) {
        syncingSwitch = true
        debugSwitch.isChecked = !attempted
        syncingSwitch = false
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    private fun titleView(): TextView = TextView(this).apply {
        text = providerDisplayName + " 调试日志"
        textSize = 24f
        setTextColor(Color.rgb(25, 25, 25))
    }

    private fun descriptionView(): TextView = TextView(this).apply {
        text = targetPackageDescription
        textSize = 14f
        setTextColor(Color.rgb(95, 95, 95))
    }

    private fun updateState(enabled: Boolean) {
        stateView.text = if (enabled) {
            "当前状态：已开启。请完全结束并重新启动目标播放器后采集日志。"
        } else {
            "当前状态：已关闭。仅保留启动摘要、关键状态、警告和错误日志。"
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
