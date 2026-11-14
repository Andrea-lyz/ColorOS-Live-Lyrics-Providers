/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.modulestatus

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Whether the LSPosed framework has connected its service to this module's own App process,
 * which it does for modules the user enabled in the manager. Apps that already own the
 * libxposed listener (ProviderModuleApplication) forward to [onServiceBind]/[onServiceDied];
 * others call [register] once from Application.onCreate.
 */
object ModuleStatus : XposedServiceHelper.OnServiceListener {
    private val lock = Object()

    @Volatile
    private var service: XposedService? = null

    @JvmStatic
    fun register() {
        runCatching { XposedServiceHelper.registerListener(this) }
    }

    override fun onServiceBind(service: XposedService) {
        synchronized(lock) {
            this.service = service
            lock.notifyAll()
        }
    }

    override fun onServiceDied(service: XposedService) {
        synchronized(lock) {
            if (this.service === service) this.service = null
        }
    }

    /**
     * The framework hands the service over shortly after the process starts, so a status
     * request that started this process waits briefly before answering "not connected".
     */
    fun awaitService(timeoutMs: Long): XposedService? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(lock) {
            while (service == null) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                lock.wait(left)
            }
            return service
        }
    }
}

/** Wire format of the status reply (protocol 1). Pure so it can be tested on the JVM. */
object ModuleStatusReply {
    const val PROTOCOL = 1
    const val METHOD = "status"
    const val KEY_PROTOCOL = "protocol"
    const val KEY_CONNECTED = "connected"
    const val KEY_API_VERSION = "apiVersion"
    const val KEY_SCOPE = "scope"
    const val WAIT_MS = 1_500L

    /** Values for the reply; scope is only included when the framework is connected. */
    fun values(service: ServiceFacts?): Map<String, Any> = buildMap {
        put(KEY_PROTOCOL, PROTOCOL)
        put(KEY_CONNECTED, service != null)
        if (service != null) {
            put(KEY_API_VERSION, service.apiVersion)
            put(KEY_SCOPE, ArrayList(service.scope.distinct().sorted()))
        }
    }

    /** Only apps signed like this module (the official Bridge) may read the status. */
    fun allowed(signatureCheck: Int): Boolean = signatureCheck == PackageManager.SIGNATURE_MATCH

    data class ServiceFacts(val apiVersion: Int, val scope: List<String>)
}

/**
 * Read-only endpoint: `call("status")` returns [ModuleStatusReply] values. It never changes the
 * module's configuration or scope and does not log the caller or the scope.
 */
class ModuleStatusProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != ModuleStatusReply.METHOD) return null
        val pm = context?.packageManager ?: return null
        if (!ModuleStatusReply.allowed(pm.checkSignatures(Binder.getCallingUid(), Process.myUid()))) {
            throw SecurityException("module status is only available to same-signer callers")
        }
        val facts = ModuleStatus.awaitService(ModuleStatusReply.WAIT_MS)?.let { service ->
            runCatching { ModuleStatusReply.ServiceFacts(service.apiVersion, service.scope) }.getOrNull()
        }
        return Bundle().apply {
            for ((key, value) in ModuleStatusReply.values(facts)) {
                when (value) {
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                    is ArrayList<*> -> putStringArrayList(key, ArrayList(value.map { it.toString() }))
                }
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
