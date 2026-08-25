/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.salt

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver

@InjectYukiHookWithXposed(modulePackageName = "io.github.andrealtb.coloroslyrics.provider.salt")
class HookEntry : IYukiHookXposedInit {

    override fun onHook() {
        YukiHookAPI.encase {
            loadApp(SaltPlayerConstants.SALT_PACKAGE) {
                if (SaltEntryPolicy.shouldNotifyXposed(BuildConfig.NPATCH_EMBEDDED)) {
                    RuntimeModeResolver.notifyXposedHookActive()
                }
                onAppLifecycle {
                    onCreate {
                        val hostContext = appContext ?: return@onCreate
                        SaltPlayerHooker(
                            hookContext = hostContext,
                            hostVersion = hostContext.packageManager
                                .getPackageInfo(hostContext.packageName, 0).versionName
                        ).onHook()
                    }
                }
            }
        }
    }

    override fun onInit() {
        YukiHookAPI.configs {
            debugLog {
                tag = "SaltPlayerProvider"
            }
        }
    }
}

internal object SaltEntryPolicy {
    fun shouldNotifyXposed(npatchEmbedded: Boolean): Boolean = !npatchEmbedded
}
