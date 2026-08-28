/*
 * Copyright 2026 Proify, Tomakino, Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.kugou

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderDebugConfig
import io.github.andrealtb.coloroslyrics.provider.core.config.ProviderId
import io.github.andrealtb.coloroslyrics.provider.core.mode.RuntimeModeResolver

@InjectYukiHookWithXposed(modulePackageName = KuGouPlayerConstants.MODULE_PACKAGE)
class HookEntry : IYukiHookXposedInit {

    override fun onHook() {
        YukiHookAPI.encase {
            KuGouPlayerConstants.QUALIFIED_HOST_PACKAGES.forEach { packageName ->
                loadApp(packageName) {
                    initKuGou(packageName)
                }
            }
        }
    }

    private fun PackageParam.initKuGou(packageName: String) {
        RuntimeModeResolver.notifyXposedHookActive()
        loadHooker(KuGouPlayerHooker(packageName))
    }

    override fun onInit() {
        if (ProviderDebugConfig.readXposedSwitch(
                KuGouPlayerConstants.MODULE_PACKAGE,
                ProviderId.KUGOU
            )
        ) {
            YukiHookAPI.configs {
                debugLog {
                    tag = "KuGouMusicProvider"
                }
            }
        }
    }
}
