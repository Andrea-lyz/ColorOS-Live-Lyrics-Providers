/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver

@InjectYukiHookWithXposed(modulePackageName = ApplePlayerConstants.MODULE_PACKAGE)
class HookEntry : IYukiHookXposedInit {

    override fun onHook() {
        YukiHookAPI.encase {
            ApplePlayerConstants.QUALIFIED_HOST_PACKAGES.forEach { packageName ->
                loadApp(packageName) {
                    initApple(packageName)
                }
            }
        }
    }

    private fun PackageParam.initApple(packageName: String) {
        RuntimeModeResolver.notifyXposedHookActive()
        onAppLifecycle {
            onCreate {
                val hostContext = appContext ?: return@onCreate
                ApplePlayerHooker(
                    hookContext = hostContext,
                    hostPackage = packageName,
                    hostVersion = runCatching {
                        hostContext.packageManager.getPackageInfo(hostContext.packageName, 0)
                            .versionName
                    }.getOrNull()
                ).onHook()
            }
        }
    }

    override fun onInit() {
        if (ProviderDebugConfig.readXposedSwitch(
                ApplePlayerConstants.MODULE_PACKAGE,
                ProviderId.APPLE
            )
        ) {
            YukiHookAPI.configs {
                debugLog {
                    tag = "AppleMusicProvider"
                }
            }
        }
    }
}
