# v4.1.0 Phase 0：基线冻结与迁移台账

> 状态：Phase 0 基线记录完成；Phase 1 共享层与 Wave A 首个 Provider（Salt）已在
> 本地构建/单测层级完成，**尚未真机验证**。
> 上游计划：工作区根目录 `todo.md`（ColorOS Live Lyrics Bridge v4.1.0 TODO）。

## 1. 冻结基线（2026-08-31）

| 仓库 | 分支 | HEAD | 说明 |
|---|---|---|---|
| ColorOS-Live-Lyrics-Providers | `4.1`（自 `4.0` 创建） | `950c3b1` Document Provider adaptation workflow | v4.1 迁移工作分支 |
| ColorOS-Live-Lyrics-Providers | `4.0`（= `origin/4.0`） | `950c3b1` | 4.0 基线，不再前进 |
| ColorOS-Live-Lyrics-Bridge | `4.0` | `674d1ec` Document translation action persistence | v4.1 期间 Bridge 业务源码应保持 diff=0 |

4.0 发布基线：Bridge tag `v4.0.0`，Provider source tag `providers-v1.0.0`，
矩阵契约 `release/v5-provider-matrix.json`（schema 1，suiteVersion 4.0.0，12 Provider）。

用户未提交修改（属于用户，迁移期间不得覆盖/回滚，不计入 4.1 基线）：

- `player-netease/src/main/kotlin/.../NeteaseLyricInfoPayloadEncoder.kt`
- `player-netease/src/main/kotlin/.../NeteaseLyricInfoPublisher.kt`
- `player-netease/src/main/kotlin/.../NeteaseOfficialLyricHooks.kt`
- `player-netease/src/test/kotlin/.../NeteaseLyricInfoPayloadEncoderTest.kt`

## 2. libxposed 参考基线

| 材料 | 位置 | 版本 |
|---|---|---|
| libxposed/api 源码审查基线 | `PlayerSource/LSP_api` | commit `79b75b4`，发布坐标 `io.github.libxposed:api:102.0.0` |
| libxposed/service 源码审查副本 | `PlayerSource/LSP_service` | tag `102.0.0`（commit `3318940`） |

Gradle 依赖使用 Maven Central 的 `io.github.libxposed:api:102.0.0`（compileOnly，
不进 APK）与 `io.github.libxposed:service:102.0.0`（仅模块 App 路径）。两个参考
checkout 只做签名审查，不进入 Provider 源码树。

## 3. 共享层（Phase 1 已落地）

- `provider-hook-api102`：API 102 入口基类 `ProviderModuleEntry`、
  `ProviderApplicationBootstrap`（Application.attach 一次性 bootstrap）、
  `ProviderHookRuntime`/`Api102HookRuntime`（不可变参数链、单次 proceed、skip/after
  语义、稳定 hook id、PROTECTIVE 模式）、`ProviderProcessPolicy` 门禁、
  `FrameworkLogSink`（注入式 framework log）、`RemotePreferencesDebugSource`
  （只读、失败解析为 disabled:*）。JVM fake-chain 测试覆盖 before 读参、参数改写、
  after 读/替换 result、skip、宿主异常原样传播、after 覆盖异常 result、重复 id 防护。
- `provider-settings-api102`：`ProviderModuleApplication` +
  `ProviderServiceState` + Remote Preferences 版 `ProviderDebugSettingsActivity`
  （service 未连接 / API<102 / 无 PROP_CAP_REMOTE / 打开 group 失败时禁用开关并给出
  原因；无 MODE_PRIVATE 回退；commit 失败回滚并提示）。
- `provider-core`：仅新增注入式 framework sink 的 `applyDiagnostics` /
  `configureForRuntime` 重载；legacy 适配器保留到全仓最终清理。
- `gradle/provider-app-convention.gradle.kts` + `gradle/libxposed-api102.pro`：
  集中声明 API/service 依赖、release R8 规则与 `verifyXposedApi102Resources`
  契约校验（module.prop 五字段、java_init.list 唯一入口、scope.list 与矩阵集合相等、
  legacy 资源/Manifest 残留检查），并挂在 `preBuild` 前。

## 4. Salt Hook ledger（Wave A #1，已迁移）

4.0 静态快照 → 4.1 API 102 映射：

| # | 目标 | 4.0 形态 | 4.1 hook id | 语义保持点 |
|---|---|---|---|---|
| 1 | `MediaButtonIntentReceiver#onReceive` | Yuki before + `resultNull()` | `salt:salt.media-button.MediaButtonIntentReceiver#onReceive` | 播放键门禁、启动服务+延迟播放、skip 原方法 |
| 2 | `MediaSession` 全部构造器 | Yuki after | `salt:salt.session.MediaSession#ctor<index>` | 构造登记 + constructorTag(args) |
| 3 | `MediaSession#setMetadata` | Yuki before，改写 args[0] | `salt:salt.session.MediaSession#setMetadata` | 蓝牙 relay、pending 附着、replay 重建，均通过参数副本改写 |
| 4 | `MediaSession#setPlaybackState` | Yuki before，改写 args[0] | `salt:salt.session.MediaSession#setPlaybackState` | 公开翻译 action 注入、状态登记、pending drain |
| 5 | `MediaSession#setActive` | Yuki before | `salt:salt.session.MediaSession#setActive` | active 登记 + pending drain |
| 6 | `MediaSession#release` | Yuki before | `salt:salt.session.MediaSession#release` | 会话释放 + replay 快照清理 |
| 7 | DexKit 发现的 publisher `invokeSuspend` | Yuki after | `salt:salt.publisher.<declaringClass>#<method>` | 最终歌词发布捕获 |

入口 / 生命周期映射：

| 4.0 | 4.1 |
|---|---|
| `@InjectYukiHookWithXposed` KSP 生成入口 | `SaltModuleEntry` + `META-INF/xposed/java_init.list` |
| `loadApp(SALT_PACKAGE)` | `ScopeOnlyProcessPolicy(com.salt.music)` + `onPackageReady` 门禁 |
| `onAppLifecycle { onCreate { ... } }` | `Application.attach(Context)` after，一次性 `ProviderHookContext` bootstrap |
| `RuntimeModeResolver.notifyXposedHookActive()` 于 `onHook` | 移到 `onModuleLoaded` |
| `YukiHookDebugSource`（Yuki prefs） | `RemotePreferencesDebugSource`（只读，失败关闭） |
| `assets/xposed_init` + `META-INF/yukihookapi_init` + 5 项 legacy meta-data | 全部删除；现代 `META-INF/xposed/*` 三件套 |

## 5. 验证层级记录

- **静态确认**：API 102/Remote Preferences 签名逐条对照
  `PlayerSource/LSP_api@79b75b4` 与 `PlayerSource/LSP_service@102.0.0`。
- **本地构建/测试通过**：
  - `:provider-hook-api102:testDebugUnitTest`（14 用例）与
    `:provider-settings-api102:testDebugUnitTest` 通过；
  - `:player-salt:testDebugUnitTest`、`:player-salt:assembleDebug`、
    `:player-salt:verifyXposedApi102Resources` 通过；
  - Salt debug APK 契约：`META-INF/xposed` 三件套内容正确，无
    `assets/xposed_init`/`yukihookapi_init`，merged manifest 无 legacy meta-data、
    application 指向共享 `ProviderModuleApplication` 并包含
    `io.github.libxposed.service.XposedProvider`；dexdump 确认
    `io.github.libxposed.service.*` 已打包、`io.github.libxposed.api.*` 未打包。
- **需要设备验证**：Salt 12.3.0-alpha03 全场景（播放/切歌/暂停/seek/锁屏/AOD/
  蓝牙/翻译按钮/Debug 开关 Remote Preferences 写读、MODULE_LOADED/PROCESS_ACCEPTED/
  HOOK_INSTALL_SUMMARY 事件、Manager 无废弃警告）。
- Bridge 本轮未改动。
