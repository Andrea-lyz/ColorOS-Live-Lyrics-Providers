/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.scoperequest

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import io.github.andrealtb.coloroslyrics.provider.modulestatus.ModuleStatus
import io.github.libxposed.service.XposedService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Exported entry of management protocol v1 (plan §7.2–§7.3), merged into every Provider that uses
 * this library (the 12 on the shared settings library, and Readify). The service comes from
 * [ModuleStatus], which every one of them feeds from its own Application. Started for a result by the Bridge store (same signing certificate) or by this
 * Provider itself. It never shows its own allow/deny choice: the framework's scope prompt is the
 * only authorization, and this page only says that one is pending.
 */
class ScopeRequestActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private var finished = false
    private var request: ScopeRequest? = null
    private lateinit var message: TextView

    private var controller: ScopeRequestController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        message = TextView(this).apply { textSize = 16f; setTextColor(Color.rgb(30, 30, 30)) }
        setContentView(LinearLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(message)
        })
        // A recreated instance cannot know how the earlier prompt ended; the caller re-reads.
        if (savedInstanceState != null) return reply(ScopeRequestOutcome(ScopeRequestStatus.PENDING))
        val parsed = if (callerTrusted()) parse() else null
        if (parsed == null) return reply(ScopeRequestOutcome(ScopeRequestStatus.INVALID_REQUEST))
        request = parsed
        message.text = "正在向 LSPosed 申请作用域：\n" + parsed.hosts.joinToString("\n") +
            "\n\n如果出现 LSPosed 的“作用域请求”通知，请在通知中选择“允许”。这里不会替你选择。"
        controller = ScopeRequestController(
            request = parsed,
            dispatch = ::submit,
            schedule = { delay, action -> schedule(delay, action) },
            finish = { outcome -> main.post { reply(outcome) } },
        )
        // The framework hands the service over shortly after this process starts.
        submit {
            val service = ModuleStatus.awaitService(ScopeRequestController.SERVICE_TIMEOUT_MS)
            controller?.start(service?.let(::LibxposedScopeClient))
        }
    }

    /** The caller must be this Provider or an app signed with the same certificate (the Bridge). */
    private fun callerTrusted(): Boolean {
        val caller = callingActivity?.packageName ?: return false
        if (caller == packageName) return true
        return packageManager.checkSignatures(packageName, caller) == PackageManager.SIGNATURE_MATCH
    }

    private fun parse(): ScopeRequest? {
        val allowed = javaClass.classLoader?.getResourceAsStream(ScopeRequestProtocol.SCOPE_LIST_RESOURCE)
            ?.use(ScopeRequestProtocol::parseScopeList).orEmpty()
        return ScopeRequestProtocol.validate(
            protocol = intent.getIntExtra(ScopeRequestProtocol.EXTRA_PROTOCOL, 0),
            requestId = intent.getStringExtra(ScopeRequestProtocol.EXTRA_REQUEST_ID),
            hosts = intent.getStringArrayExtra(ScopeRequestProtocol.EXTRA_HOSTS)?.toList(),
            allowed = allowed,
        )
    }

    @Deprecated("Back still answers the caller: the prompt may yet be answered.")
    override fun onBackPressed() {
        reply(ScopeRequestOutcome(ScopeRequestStatus.PENDING, request?.hosts.orEmpty()))
    }

    private fun reply(outcome: ScopeRequestOutcome) {
        if (finished) return
        finished = true
        val data = Intent()
            .putExtra(ScopeRequestProtocol.EXTRA_PROTOCOL, ScopeRequestProtocol.VERSION)
            .putExtra(ScopeRequestProtocol.RESULT_STATUS, outcome.status.name)
            .putExtra(ScopeRequestProtocol.RESULT_REQUESTED, outcome.requested.toTypedArray())
            .putExtra(ScopeRequestProtocol.RESULT_GRANTED, outcome.granted.toTypedArray())
        request?.let { data.putExtra(ScopeRequestProtocol.EXTRA_REQUEST_ID, it.requestId) }
        setResult(RESULT_OK, data)
        finish()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun submit(action: () -> Unit) {
        try {
            worker.execute(action)
        } catch (_: RejectedExecutionException) {
            // Destroyed: late framework callbacks are dropped; the caller re-reads the state.
        }
    }

    private fun schedule(delayMs: Long, action: () -> Unit) {
        try {
            worker.schedule(action, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
        }
    }
}

/** SDK leaf: every call uses the service delivered to THIS module App. */
private class LibxposedScopeClient(private val service: XposedService) : ScopeClient {
    override fun readScope(): Set<String> = service.scope.toSet()

    override fun requestScope(packages: List<String>, callback: (Set<String>?) -> Unit) {
        service.requestScope(packages, object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) = callback(approved.toSet())
            // API 102 gives no typed reject / never-ask reason, and its message is not shown or logged.
            override fun onScopeRequestFailed(message: String) = callback(null)
        })
    }
}
