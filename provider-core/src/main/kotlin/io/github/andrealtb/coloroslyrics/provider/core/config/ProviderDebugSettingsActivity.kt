/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.core.config

import android.app.Activity
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

abstract class ProviderDebugSettingsActivity : Activity() {

    protected abstract val providerId: ProviderId
    protected abstract val providerDisplayName: String
    protected abstract val targetPackageDescription: String

    private lateinit var debugSwitch: Switch
    private lateinit var stateView: TextView
    private var syncingSwitch = false
    private var usingLsposedSharedPrefs = false

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

        usingLsposedSharedPrefs = ProviderDebugConfig.openModulePrefs(
            applicationContext,
            providerId
        ).usingLsposedSharedPrefs

        root.addView(titleView())
        root.addView(descriptionView())

        debugSwitch = Switch(this).apply {
            text = "启用调试日志"
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            isChecked = currentDebugEnabled()
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
        updateState(debugSwitch.isChecked)

        if (!usingLsposedSharedPrefs) {
            root.addView(
                TextView(this).apply {
                    text = "当前没有进入 LSPosed 共享存储模式。开关只会写在本模块私有目录，目标播放器读不到，日志里会一直是 reason=disabled。"
                    textSize = 14f
                    setTextColor(Color.rgb(176, 0, 32))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(20)
                }
            )
        }

        root.addView(
            TextView(this).apply {
                text = (
                    "开启后，额外的 [CLL] DEBUG 事件会同时写入 Android logcat 和 "
                        + "LSPosed/Xposed framework log，可从 LSPosed 导出的日志包中获取。"
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
            if (ProviderDebugConfig.setRootDebugEnabled(applicationContext, providerId, enabled)) {
                updateState(enabled)
            } else {
                syncingSwitch = true
                debugSwitch.isChecked = !enabled
                syncingSwitch = false
                Toast.makeText(this, "无法保存调试开关", Toast.LENGTH_SHORT).show()
            }
        }

        setContentView(root)
        ViewCompat.requestApplyInsets(root)
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

    private fun currentDebugEnabled(): Boolean =
        ProviderDebugConfig.sharedPreferencesSource(applicationContext).read(providerId) == true

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
