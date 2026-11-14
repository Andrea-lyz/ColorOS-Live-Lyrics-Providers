/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.scopeprobe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Diagnostic harness, NOT the planned Compose management UI or a simulated permission dialog. */
class ScopeProbeActivity : Activity() {
    private lateinit var session: ScopeProbeSession
    private lateinit var status: TextView
    private lateinit var request: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG) { "The scope probe must never be a Release application" }
        session = (lastNonConfigurationInstance as? ScopeProbeSession) ?: ScopeProbeSession(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        content.addView(TextView(this).apply {
            text = "M0 · LSP 原生作用域请求实验"
            textSize = 22f
            setTextColor(Color.BLACK)
        })
        content.addView(TextView(this).apply {
            text = "独立 Debug 测试包，不是正式 Provider；不安装歌词 Hook。\n" +
                "仅下方按钮会请求框架授权，打开/返回/旋转不会自动请求。\n" +
                "允许、拒绝、不再询问均由 LSP 原生通知处理，本页不仿制通知。\n" +
                "每份测试数据最多提交一次；回调缺失或进程重建也不重试。"
            textSize = 15f
            setTextColor(Color.DKGRAY)
        })
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.BLACK)
            setTextIsSelectable(true)
        }
        content.addView(status)
        request = Button(this).apply {
            text = "请求 LSP 原生作用域通知（一次）"
            isEnabled = false
            setOnClickListener { session.request() }
        }
        content.addView(request)
        content.addView(Button(this).apply {
            text = "只回读作用域 · 不请求"
            setOnClickListener { session.refresh() }
        })
        content.addView(Button(this).apply {
            text = "复制本页实验摘要"
            setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("LSP Scope PoC", status.text))
                Toast.makeText(this@ScopeProbeActivity, "已复制；不含原始错误文案或播放器数据", Toast.LENGTH_SHORT).show()
            }
        })
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })
        session.start()
    }

    override fun onStart() {
        super.onStart()
        session.attach(::render)
        session.refresh() // Reconcile after the user operates the framework. Never sends a request.
    }

    override fun onStop() {
        session.detach()
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any = session

    override fun onDestroy() {
        if (!isChangingConfigurations) session.close()
        super.onDestroy()
    }

    private fun render(state: ScopeProbeSnapshot) {
        val readback = state.scope
        val scopeStatus = when {
            readback == null -> "unknown（未读到；不能判为未启用）"
            state.scopeConfirmed -> "已回读确认目标子集；不代表宿主已加载或歌词有效"
            else -> "缺少目标：" + (state.allowedHosts - readback).joinToString()
        }
        val observed = readback?.intersect(state.allowedHosts)?.joinToString()?.ifEmpty { "无" } ?: "unknown"
        status.text = "\n包名：" + BuildConfig.APPLICATION_ID +
            "\n探针版本：" + BuildConfig.VERSION_NAME + " / " + BuildConfig.VERSION_CODE +
            "\nAndroid SDK：" + Build.VERSION.SDK_INT +
            "\nstaticScope：" + BuildConfig.STATIC_SCOPE +
            "\n矩阵样本：player-salt；允许宿主：" + state.allowedHosts.joinToString() +
            "\n框架：" + state.framework +
            "\n服务连接：" + state.connected +
            "\n作用域：" + scopeStatus +
            "\n回读中的目标：" + observed +
            "\n额外已有 scope 数：" + (readback?.count { it !in state.allowedHosts } ?: "unknown") +
            "\n本地提交记录：" + state.attempted +
            "\n请求/回调状态：" + state.requestStatus.name +
            "\n回调批准的目标：" + state.callbackApprovedHosts.joinToString().ifEmpty { "无/未知" } +
            "\n注意：失败回调不能区分拒绝/不再询问；60 秒仅为回调观察窗，不是 Binder 硬取消。" +
            "\n保留的事件（最多 32 条）：\n" + state.events.joinToString("\n") + "\n"
        request.isEnabled = state.canRequest
    }
}
