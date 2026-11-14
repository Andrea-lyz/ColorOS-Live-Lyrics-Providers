package io.github.andrealtb.coloroslyrics.provider.hook102

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.DiagnosticSink
import io.github.andrealtb.coloroslyrics.provider.core.diagnostics.StructuredDiagnostics
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Executable
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ProviderApplicationBootstrapTest {
    private class RecordingRuntime : ProviderHookRuntime {
        lateinit var target: Executable
        lateinit var specification: ProviderHookSpec
        override val installedHookCount get() = if (::target.isInitialized) 1 else 0
        override fun hook(executable: Executable, id: String, spec: ProviderHookSpec.() -> Unit): Boolean {
            target = executable
            specification = ProviderHookSpec().apply(spec)
            return true
        }
        fun attached(app: Application) {
            specification.afterCallback!!(ProviderAfterHook(target, app, arrayOf<Any?>(app), null, null))
        }
    }
    private class Host : Application() {
        override fun getPackageName() = "com.salt.music"
        override fun getApplicationContext(): Context = throw NullPointerException("host not initialized")
    }
    private val sink = object : DiagnosticSink {
        override fun log(level: Int, tag: String, message: String, throwable: Throwable?) = Unit
    }
    private fun ready(): XposedModuleInterface.PackageReadyParam = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(XposedModuleInterface.PackageReadyParam::class.java)
    ) { _, method, _ -> when (method.name) {
        "getPackageName" -> "com.salt.music"
        "getClassLoader", "getDefaultClassLoader" -> javaClass.classLoader
        "getApplicationInfo" -> ApplicationInfo()
        "isFirstPackage" -> true
        else -> null
    } } as XposedModuleInterface.PackageReadyParam

    @Test fun diagnosticExceptionCannotStopOrDuplicateTheBusinessInstaller() {
        val runtime = RecordingRuntime()
        val host = Host()
        var installed = 0
        var observed: Context? = null
        val bootstrap = ProviderApplicationBootstrap(
            entry = object : XposedModule() {}, runtime = runtime, providerId = ProviderId.SALT,
            diagnosticsComponent = "provider/salt", frameworkSink = sink,
            installer = ProviderHookInstaller { installed++; observed = it.application },
            registerDiagnostics = { throw NullPointerException("synthetic optional diagnostic failure") }
        )
        bootstrap.schedule(ready(), "com.salt.music")
        runtime.attached(host)
        runtime.attached(host)
        assertEquals(1, installed)
        assertSame(host, observed)
    }
    @Test fun diagnosticLinkageFailureAlsoLeavesNormalInstallerReachable() {
        val runtime = RecordingRuntime()
        var installed = 0
        val bootstrap = ProviderApplicationBootstrap(
            entry = object : XposedModule() {}, runtime = runtime, providerId = ProviderId.SALT,
            diagnosticsComponent = "provider/salt", frameworkSink = sink,
            installer = ProviderHookInstaller { installed++ },
            registerDiagnostics = { throw NoClassDefFoundError("synthetic optional diagnostic linkage") }
        )
        bootstrap.schedule(ready(), "com.salt.music")
        runtime.attached(Host())
        assertEquals(1, installed)
    }
    @Test fun installerReturnedFailureKeepsInstallerSingleAndSummaryRecorded() {
        listOf<Throwable>(
            IllegalStateException("synthetic installerReturned failure"),
            NoClassDefFoundError("synthetic installerReturned linkage")
        ).forEach { failure ->
            val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
            StructuredDiagnostics.configure(false, listOf(object : DiagnosticSink {
                override fun log(level: Int, tag: String, message: String, throwable: Throwable?) {
                    messages += message
                }
            }))
            try {
                val runtime = RecordingRuntime()
                val host = Host()
                var installed = 0
                var recorded = 0
                val bootstrap = ProviderApplicationBootstrap(
                    entry = object : XposedModule() {}, runtime = runtime, providerId = ProviderId.SALT,
                    diagnosticsComponent = "provider/salt", frameworkSink = sink,
                    installer = ProviderHookInstaller { installed++ },
                    registerDiagnostics = { },
                    recordInstallerReturned = { recorded++; throw failure }
                )
                bootstrap.schedule(ready(), "com.salt.music")
                runtime.attached(host)
                runtime.attached(host)
                assertEquals(1, installed)
                assertEquals(1, recorded)
                assertEquals(1, messages.count { "HOOK_INSTALL_SUMMARY" in it })
                assertTrue(messages.any { "RUNTIME_DIAGNOSTIC_FAILED" in it })
                assertFalse(messages.any { "HOOK_INSTALL_FAILED" in it })
            } finally {
                StructuredDiagnostics.configure(false)
            }
        }
    }
}
