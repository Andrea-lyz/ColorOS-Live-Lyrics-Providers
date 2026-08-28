/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.lx

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver

@InjectYukiHookWithXposed(modulePackageName = "io.github.andrealtb.coloroslyrics.provider.lx")
class HookEntry : IYukiHookXposedInit {

    override fun onHook() {
        YukiHookAPI.encase {
            LxPlayerConstants.QUALIFIED_HOST_PACKAGES.forEach { packageName ->
                loadApp(packageName) {
                    initLx(packageName)
                }
            }
        }
    }

    private fun PackageParam.initLx(packageName: String) {
        RuntimeModeResolver.notifyXposedHookActive()
        onAppLifecycle {
            onCreate {
                val hostContext = appContext ?: return@onCreate
                LxPlayerHooker(
                    hookContext = hostContext,
                    hostPackage = packageName,
                    hostVersion = runCatching {
                        hostContext.packageManager.getPackageInfo(hostContext.packageName, 0).versionName
                    }.getOrNull()
                ).onHook()
            }
        }
    }

    override fun onInit() {
        if (ProviderDebugConfig.readXposedSwitch(LxPlayerConstants.MODULE_PACKAGE, ProviderId.LX)) {
            YukiHookAPI.configs {
                debugLog {
                    tag = "LxMusicProvider"
                }
            }
        }
    }
}
