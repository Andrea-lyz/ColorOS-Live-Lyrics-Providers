/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.qishui

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver

@InjectYukiHookWithXposed(modulePackageName = QishuiPlayerConstants.MODULE_PACKAGE)
class HookEntry : IYukiHookXposedInit {
    override fun onHook() {
        YukiHookAPI.encase {
            QishuiPlayerConstants.QUALIFIED_HOST_PACKAGES.forEach { packageName ->
                loadApp(packageName) {
                    initQishui(packageName)
                }
            }
        }
    }

    private fun PackageParam.initQishui(packageName: String) {
        RuntimeModeResolver.notifyXposedHookActive()
        onAppLifecycle {
            onCreate {
                val context = appContext ?: return@onCreate
                QishuiPlayerHooker(
                    hookContext = context,
                    hostPackage = packageName,
                    hostVersion = runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull()
                ).onHook()
            }
        }
    }

    override fun onInit() {
        if (ProviderDebugConfig.readXposedSwitch(
                QishuiPlayerConstants.MODULE_PACKAGE,
                ProviderId.QISHUI
            )
        ) {
            YukiHookAPI.configs {
                debugLog {
                    tag = "QishuiMusicProvider"
                }
            }
        }
    }
}
