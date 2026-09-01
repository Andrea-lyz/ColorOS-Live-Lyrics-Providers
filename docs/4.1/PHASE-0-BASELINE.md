# v4.1.0 Phase 0：基线冻结与迁移台账

> 状态：Phase 0/1 与 Wave A–E 已完成；12/12 Provider 已完成 API 102 源码迁移、
> 本地 Debug 全矩阵门禁与用户真机回归。Wave E 的 9.0.40 构造 profile 按特殊安装前提
> 由用户暂时验收通过，具体证据限制见 §15。
> 上游计划：工作区根目录 `todo.md`（ColorOS Live Lyrics Bridge v4.1.0 TODO）。

## 1. 冻结基线（2026-08-31）

| 仓库 | 分支 | HEAD | 说明 |
|---|---|---|---|
| ColorOS-Live-Lyrics-Providers | `4.1`（自 `4.0` 创建） | `950c3b1` Document Provider adaptation workflow | v4.1 迁移工作分支 |
| ColorOS-Live-Lyrics-Providers | `4.0`（= `origin/4.0`） | `950c3b1` | 4.0 基线，不再前进 |
| ColorOS-Live-Lyrics-Bridge | `4.0` | `674d1ec` Document translation action persistence | v4.1 期间 Bridge 业务源码应保持 diff=0 |

4.0 发布基线：Bridge tag `v4.0.0`，Provider source tag `providers-v1.0.0`，
矩阵契约 `release/v5-provider-matrix.json`（schema 1，suiteVersion 4.0.0，12 Provider）。

Wave E 开始前保留的 4 个用户 NetEase WIP 文件已先经定向单测验证，并独立提交为
`be4eea1`，再开始 Hook API 迁移：

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

## 6. Cone Hook ledger（Wave A #2，已迁移）

| # | 目标 | 4.0 形态 | 4.1 hook id | 语义保持点 |
|---|---|---|---|---|
| 1 | `MediaSession` 全部构造器 | Yuki after | `cone:cone.session.MediaSession#ctor<index>` | 构造登记 + constructorTag(args) |
| 2 | `MediaSession#setMetadata` | Yuki before，改写 args[0] | `cone:cone.session.MediaSession#setMetadata` | pending 附着、replay（携带 hostPackage 区分双宿主） |
| 3 | `MediaSession#setPlaybackState` | Yuki before，改写 args[0] | `cone:cone.session.MediaSession#setPlaybackState` | 公开翻译 action 注入、状态登记、pending drain |
| 4 | `MediaSession#setActive` | Yuki before | `cone:cone.session.MediaSession#setActive` | active 登记 + pending drain |
| 5 | `MediaSession#release` | Yuki before | `cone:cone.session.MediaSession#release` | 会话释放 + replay 快照清理 |
| 6 | `MediaPlayerService#onTracksChanged(*, Tracks)` | Yuki after（按参数形状匹配） | `cone:cone.service.MediaPlayerService#onTracksChanged` | 曲目元数据歌词提取入口不变 |

非 Hook 行为：`ACTION_CURRENT_LYRIC_CHANGED` 模块内广播接收器保留（RECEIVER_NOT_EXPORTED
按版本分支）；双宿主 `ink.trantor.coneplayer` / `ink.trantor.coneplayer.gp` 通过
每进程独立 `ProviderHookContext.packageName` 隔离，发布器继续携带 hostPackage。

## 7. KuWo Hook ledger（Wave A #3，已迁移）

| # | 目标 | 4.0 形态 | 4.1 hook id | 语义保持点 |
|---|---|---|---|---|
| 1 | `MediaSession#setMetadata` | KavaRef 定位 + Yuki before/after | `kuwo:kuwo.session.MediaSession#setMetadata` | before 重建宿主 metadata；after 捕获 title/artist/mediaId、generation 推进、pending emit |
| 2 | KuWo 歌词抓取 `cn.kuwo.mod.lyrics.e0#f(Music,boolean,Music)`（DexKit+字面名兜底） | 直接 `XposedBridge.hookMethod` + `XC_MethodHook` | `kuwo:kuwo.lyrics.<declaringClass>#<method>` | after 捕获 result/args 副本、串歌校验、重试调度不变 |
| 3 | `AudioManager#isBluetoothA2dpOn` | `XposedHelpers` + `XC_MethodReplacement.returnConstant(true)` | `kuwo:kuwo.bluetooth.AudioManager#isBluetoothA2dpOn` | 常量 true 替换（before skip + result=true） |
| 4 | `BluetoothAdapter#isEnabled` | 同上 | `kuwo:kuwo.bluetooth.BluetoothAdapter#isEnabled` | 同上 |
| 5 | `Application#onCreate` / `Application#onTerminate` | Yuki `onAppLifecycle` | `kuwo:kuwo.app.Application#onCreate` / `#onTerminate` | onCreate 复查 debug 配置（一次性通告守卫）、onTerminate 关闭重试执行器 |

配套清理：`share:extensions-android/AndroidUtils` 删除 `XposedHelpers`/`XC_MethodReplacement`，
改为 `findBluetoothA2dpOverrides` 受限反射解析器（失败返回 null，KuWo 侧 fail-open 记录
BLUETOOTH_OVERRIDE_SKIPPED）；KuWo 模块删除 KavaRef 依赖。官方 lyricInfo append、writer
result capture、5 槽翻译与封面路径未触碰。

## 8. Wave A 真机回归证据（2026-08-31，用户执行，结论：全部通过）

| Provider | 日志 | 关键证据 |
|---|---|---|
| Salt | `logs/lyrics-log-20260831-110257-salt-player.txt` | 单进程 `hooks=9`；`DEBUG_CONFIG_APPLIED reason=enabled`（Remote Preferences 读端生效）；`SALT_FINAL_PUBLISHED`×7 跨 generation；pending→`SALT_HOST_METADATA_LYRIC_INFO_ATTACHED`→drain 完整；`TRANSLATION_ACTION_INJECTED reason=public`；蓝牙 relay `SALT_RELAY_IDENTITY_RESOLVED`×19；空歌词 `EMPTY_LYRIC_SKIPPED`/`INVALID_INPUT` fail-open；Bridge `NATIVE_LYRIC_RECEIVED`×16 含翻译+封面 |
| Cone / GP | `logs/lyrics-log-20260831-111523-cone.txt` | GP（`hooks=8`）与标准包（`hooks=8`）独立进程各自 bootstrap；广播接收器与 `ON_TRACKS_CHANGED_HOOK_INSTALLED` 双入口安装；`CONE_FINAL_PUBLISHED`×5；GP 侧 `TRANSLATION_ACTION_INJECTED` 且 Bridge actions 含 TOGGLE_TRANSLATION 恰好一次 |
| KuWo | `logs/lyrics-log-20260831-112138-kuwo.txt` | main+`:service` 两轮重启各 `hooks=7`；DexKit `cn.kuwo.mod.lyrics.e0#f` + LRCX `j6.f#a` 解析；`LYRIC_FETCH_HOOKED`（API 102 替代直接 XposedBridge）；`LYRIC_READY`×14 含逐字 TIMING_SAMPLE；`LYRIC_CACHED_PENDING` 串歌防护；`IMMEDIATE_PUBLISH`×4；Bridge 侧翻译+封面 URI 消费；无 `BLUETOOTH_OVERRIDE_SKIPPED` |

三份日志 `level=ERROR` 与 `AndroidRuntime/FATAL` 命中均为 0。

补充说明：

1. Cone 标准包未记录 `TRANSLATION_ACTION_INJECTED` 属预期：宿主启动时恢复了已含
   TOGGLE_TRANSLATION 的 PlaybackState，`prependPublicAction` 检测到已存在返回原实例，
   不重复注入；Bridge 侧 actions count=1 证实无重复。
2. KuWo `PROCESS_SKIPPED`×1 为 `cn.kuwo.player` 进程内
   `com.google.android.webview` 的附加包回调，门禁正确 detach；main+`:service`
   双进程接受与 4.0 scope-only 语义一致。
3. 本批捕获仅覆盖 logcat 侧；framework log 双写未单独导出 LSPosed 日志包核对，
   留待发布前真机门禁（Phase 5）统一验证。
4. Wave A 完成仅覆盖 3/12 Provider；Phase 5 发布门禁仍要求全部 12 Provider 真机通过。

## 9. Wave B Hook ledger（LX / Poweramp / Metrolist，已迁移，本地门禁通过）

### LX / Walnut（`player-lx`）

| # | 目标 | 4.0 形态 | 4.1 hook id |
|---|---|---|---|
| 1 | `LyricModule#setLyric(String, String)`（官方/Walnut/lxnetease 候选解析） | Yuki after | `lx:lx.lyric.<LyricModule>#<setLyric>` |
| 2 | `MediaSession` 全部构造器 | Yuki after | `lx:lx.session.MediaSession#ctor<index>` |
| 3 | `MediaSession#setMetadata` | Yuki before，改写 args[0] | `lx:lx.session.MediaSession#setMetadata` |
| 4 | `MediaSession#setPlaybackState` | Yuki before，翻译 action 注入 | `lx:lx.session.MediaSession#setPlaybackState` |
| 5 | `MediaSession#setActive` / `#release` | Yuki before | `lx:lx.session.MediaSession#setActive` / `#release` |

保持点：双宿主 hostPackage 隔离、蓝牙 TITLE/ARTIST projection 忽略策略、
URI/bitmap 封面就绪门禁（PENDING_ARTWORK）、replay 所有权判定。

### Poweramp（`player-poweramp`）

| # | 目标 | 4.0 形态 | 4.1 hook id |
|---|---|---|---|
| 1 | `ContextWrapper#sendStickyBroadcast(Intent)`（TRACK_CHANGED host-send） | Yuki before | `poweramp:poweramp.track.ContextWrapper#sendStickyBroadcast` |
| 2 | TRACK_CHANGED 广播接收器（NOT_EXPORTED） | 非 Hook，保留 | — |
| 3 | `MediaSession` 构造器 / setMetadata(before+after) / setPlaybackState / setActive / release | Yuki | `poweramp:poweramp.session.MediaSession#*` |
| 4 | `Application#onTerminate`（receiver 反注册 + executor 关闭） | Yuki onAppLifecycle | `poweramp:poweramp.app.Application#onTerminate` |

保持点：翻译 poke 一次性守卫（ThreadLocal + generation CAS）、暂停竞态保护
（只改宿主 args，不延迟 setPlaybackState）、cast session 排除、本地歌词
（.lrc/TagLib）与两阶段封面。

### Metrolist（`player-metrolist`）

| # | 目标 | 4.0 形态 | 4.1 hook id |
|---|---|---|---|
| 1 | `MusicService#onCreate()`（按名称+参数数解析） | Yuki method 工厂 after | `metrolist:metrolist.service.MusicService#onCreate` |
| 2 | `MusicService#onEvents(*, *)` | Yuki method 工厂 after | `metrolist:metrolist.service.MusicService#onEvents` |
| 3 | `MediaSession` 构造器 / setMetadata / setPlaybackState / setActive / release | Yuki | `metrolist:metrolist.session.MediaSession#*` |
| 4 | `Application#onTerminate`（fetchScope 取消） | Yuki onAppLifecycle | `metrolist:metrolist.app.Application#onTerminate` |

保持点：host track bind、provider order（BetterLyrics/LrcLib/KuGou）、
pending 附着、定性不支持翻译（不注入 TOGGLE_TRANSLATION）、KSP unit-test
workaround 随 Yuki 入口一并删除。

三个模块均已通过模块单测、`verifyXposedApi102Resources`、debug APK 构建与
`testV5Matrix` 全矩阵回归；真机证据见 §10。

## 10. Wave B 真机回归证据（2026-08-31，用户执行，结论：全部通过）

| Provider | 日志 | 关键证据 |
|---|---|---|
| LX / Walnut | `logs/lyrics-log-20260831-124343-LX.txt` | 官方 1.8.4 与 Walnut 26.08.19 各自 `hooks=8`；`LYRIC_MODULE_HOOK_INSTALLED` 分别命中 `cn.toside...` 与 `com.lxwalnut...` LyricModule；`LX_FINAL_PUBLISHED`×6、pending→PENDING_ARTWORK→ATTACHED→DRAINED 完整；蓝牙 `LX_BLUETOOTH_PROJECTION_IGNORED`×11 与 `LX_PUBLISHED_LYRIC_TITLE_PROJECTION_IGNORED`×5；`EMPTY_CLEARED`×8；两宿主各一次 `TRANSLATION_ACTION_INJECTED`；Bridge 消费官方×9 + Walnut×7（URI 封面） |
| Poweramp | `logs/lyrics-log-20260831-124907-Poweramp.txt` | `TRACK_CHANGED_SEND_HOOK_INSTALLED` + `TRACK_CHANGED_RECEIVER_INSTALLED`；`TRACK_BOUND`×4（source=host-send，path=true）；`TRANSLATION_ACTION_POKED` 恰好每代一次（generation 1–4 无重复，poke 门禁成立）；`POWERAMP_FINAL_PUBLISHED`×3 + ATTACHED×3；Bridge 消费×8 |
| Metrolist | `logs/lyrics-log-20260831-124722-metrolist.txt` | `MUSIC_SERVICE_CREATE_HOOK_INSTALLED` + `MUSIC_SERVICE_HOOK_INSTALLED`（onCreate/onEvents 按名称+参数数解析）；`TRACK_BOUND`×3 → `LYRIC_PROVIDERS_SELECTED/TRY/HIT` 主动检索；`METROLIST_FINAL_PUBLISHED`×2 + Coil 封面 pending attach；Bridge Rule0 actions 无 TOGGLE_TRANSLATION（无翻译契约成立）；`PROCESS_SKIPPED` 为 webview 附加包，门禁正确 |

三份日志 `level=ERROR` 命中均为 0；Poweramp 日志中 4 行 `AndroidRuntime|FATAL`
模式匹配为误报（Bridge 解析 trace 输出了歌名含 “Fatal” 的 LRC `[ti:]` 头），无真实崩溃。

至此 Wave A + Wave B 共 6/12 Provider 完成迁移与真机回归；Phase 5 发布门禁仍需
剩余 6 个 Provider（Wave C/D/E）真机通过。

## 11. Wave C Hook ledger（KuGou / QQ / QiShui，已迁移，本地门禁通过）

### KuGou（`player-kugou`）

| # | 目标 | 4.0 形态 | 4.1 处理 |
|---|---|---|---|
| 1 | `LyricManager` 加载方法（DexKit/解析） | 直接 `XposedBridge.hookMethod` + `XC_MethodHook` objectExtra | `kugou:kugou.lyric.<class>#<method>`；objectExtra 前后桥替换为 ThreadLocal 单次调用状态 |
| 2 | `MediaSession#setMetadata` before+after | KavaRef + Yuki | `kugou:kugou.session.MediaSession#setMetadata` |
| 3 | `:support` / `.support` 进程门禁 | hooker 内 `KuGouProcessPolicy.shouldHook` | entry 层 `KuGouSupportProcessPolicy` 适配器，拒绝进程在安装前 detach |

### QQ（`player-qq`）

| # | 目标 | 4.0 形态 | 4.1 处理 |
|---|---|---|---|
| 1 | `onLoadSuc` 加载 bean 捕获（DexKit 解析） | 直接 `XposedBridge.hookMethod` | `qq:qq.lyric.<class>#<method>` |
| 2 | seedling writer（`builder` 就地补 lyricInfo） | 直接 `XposedBridge.hookMethod` | `qq:qq.seedling.<class>#<method>` |
| 3 | `MediaSession#setMetadata` before+after | KavaRef + Yuki | `qq:qq.session.MediaSession#setMetadata` |
| 4 | `:QQPlayerService` 进程门禁 | hooker 内 `QqProcessPolicy.shouldHook` | entry 层 `QqServiceProcessPolicy` 适配器 |

### QiShui（`player-qishui`）

| # | 目标 | 4.0 形态 | 4.1 处理 |
|---|---|---|---|
| 1 | `CoreRemoteControl#update`（可多重载） | `XposedHelpers.findClass` + `XposedBridge.hookMethod` | `classLoader.loadClass` + `qishui:qishui.internal.CoreRemoteControl#update<index>`；playback refresh target 记录保留 |
| 2 | `MediaSession` 构造器/setMetadata/setPlaybackState(before+after)/setActive/release | Yuki | `qishui:qishui.session.MediaSession#*` |
| 3 | 主进程门禁（`isPlaybackProcess`） | hooker 内判断 | entry 层 `QishuiMainProcessPolicy` 适配器 |

三个模块的翻译策略按 4.0 保留：KuGou/QQ 不注入公开翻译 action（Bridge 5 槽收藏覆盖），
QiShui 按 `QishuiTranslationActionPolicy` 动态注入/移除。三个模块均已通过模块单测、
`verifyXposedApi102Resources`、debug APK 构建、`testV5Matrix` 全矩阵回归，且
`player-kugou`/`player-qq`/`player-qishui`/`share:extensions-android` 源码级
forbidden 扫描（XposedBridge/XC_MethodHook/XposedHelpers/Yuki 等 11 个 token）为 0；
真机回归证据见 §12。

## 12. Wave C 真机回归证据（2026-08-31，用户执行，结论：全部通过）

| Provider | 日志 | 关键证据 |
|---|---|---|
| KuGou / Lite | `logs/lyrics-log-20260831-140051-kugou.txt` | 标准版与 Lite 的 main/message 进程均在业务 Hook 前 `PROCESS_SKIPPED`，两个 support 进程分别 `PROCESS_ACCEPTED`、`hooks=3`；`TRACK_BOUND`×8、`KUGOU_KRC_LOAD_CAPTURED`×8、`KUGOU_LYRIC_PARSED`×8、`NATIVE_LYRICINFO_PATCHED`×8，Bridge 消费 KuGou/Lite payload×20；无崩溃或 Hook 异常 |
| QQ | `logs/lyrics-log-20260831-150221.txt` | main `PROCESS_SKIPPED`，`:QQPlayerService` 以 API 102 `PROCESS_ACCEPTED`；`DEBUG_CONFIG_APPLIED reason=enabled`、`MEDIA_SESSION_HOOKED`、`ON_LOAD_SUC_HOOKED`、`SEEDLING_HOOKED`、`hooks=4`；首曲《海的女儿》generation 2 稳定，48 行中 46 行翻译成功发布，Bridge 消费 QQ payload×10；无崩溃或 Hook 异常 |
| QiShui | `logs/lyrics-log-20260831-151912.txt` | `com.luna.music` 主进程以 API 102 接受，`CoreRemoteControl#update methods=1`、`hooks=8`；Cassandra generation 2 为 49/49 行，切至 The Other Side Of Paradise generation 3 为 55/55 行并 `NATIVE_LYRIC_INFO_COMMITTED`；旧缓存回调被 `STALE_RESOLUTION_DROPPED`，Bridge 分别解析 49/49、55/55 且翻译 action 可点击；无崩溃或 Hook 异常 |

QQ 首曲的 5 条法语逐字行被 Bridge `raw-split` 启发式误拆，以及 02:01.270 零宽尾部
占位被过滤；KuGou《回家的路》的标题行错绑翻译与零宽尾部占位丢失，均已按用户反馈定性为
独立、非阻断的 Bridge 解析问题，不改变 Wave C Provider API 102 迁移验收结果。Bridge
提交 `f374c2b` 已完成本地修复：延迟首词的逐字行按结构保留、标题/制作信息禁止作为翻译
alias、零宽尾部占位保留 official slot；486 个单测与 Debug APK 构建通过。2026-09-01
用户确认 QQ《海的女儿》与 KuGou《回家的路》真机复测通过。

同日新日志 `logs/lyrics-log-20260901-065012.txt` 暴露独立 P0：状态栏媒体卡片重新 bind 时，
OPlus `setSemanticButton` 对 Bridge 写入的 `Icon.TYPE_BITMAP` 调用 `getResPackage()`，导致
SystemUI FATAL/重启。Bridge 提交 `e0b7320` 已把 semantic icon 收口为资源型 Icon，并在写入前
fail-open 校验；487 个单测与 Debug APK 构建通过，真机复测待完成，因此仍阻断发布门禁。

至此 Wave A + Wave B + Wave C 共 9/12 Provider 完成迁移与用户真机回归；Phase 5 发布门禁
仍需完成 Wave E（NetEase）迁移与设备验证。

## 13. Wave D Hook ledger（Apple / Spotify，已迁移，本地门禁通过）

### Apple Music（`player-apple`）

| # | 目标 | 4.0 形态 | 4.1 处理 |
|---|---|---|---|
| 1 | PlaybackItem mapper（多候选） | Yuki method after | `apple:apple.playback.<class>#<method><index>`；保留 DexKit/fallback 解析与 Adam ID 缓存 |
| 2 | `PlayerLyricsViewModel#loadLyrics(PlaybackItem)` | Yuki before | `apple:apple.lyric.<class>#loadLyrics`；请求/重试/poll 生命周期不变 |
| 3 | `buildTimeRangeToLyricsMap(SongInfoPtr)` | Yuki after | `apple:apple.lyric.<class>#buildTimeRangeToLyricsMap`；JNI 解析、翻译/发音 lane 策略不变 |
| 4 | `MediaSession` 构造器/setMetadata/setPlaybackState/setActive/release | Yuki | `apple:apple.session.MediaSession#*`；pending/replay、封面与 cast 门禁不变 |
| 5 | package/process 路由 | Yuki `loadApp` | 唯一 `AppleModuleEntry` + entry 层 scope-only policy，安装前路由 |

### Spotify（`player-spotify`）

| # | 目标 | 4.0 形态 | 4.1 处理 |
|---|---|---|---|
| 1 | shaded/Cronet/OkHttp header 构造器/方法 | Yuki constructor/method Hook | `spotify:spotify.headers.*`；仅保留 header 键名，fetch/cache/retry 行为不变 |
| 2 | `MediaSession` 构造器/setMetadata/setPlaybackState/setActive/release | Yuki | `spotify:spotify.session.MediaSession#*`；广告/episode/cast、generation、pending/replay 不变 |
| 3 | `Application#onTerminate` | Yuki `onAppLifecycle` | `spotify:spotify.app.<application>#onTerminate`；继续取消 `fetchScope` |
| 4 | 主进程门禁 | hooker 内 `isPlaybackProcess` | 唯一 `SpotifyModuleEntry` + entry 层 `SpotifyMainProcessPolicy`；拒绝进程在业务 Hook 前 detach |

两个模块均已通过定向单测、`verifyXposedApi102Resources`、Debug APK 构建、
`testV5Matrix` 全矩阵回归和源码 forbidden 扫描（Yuki/legacy Xposed 等 token 为 0）。
测试包：`device-testing/wave-d/`；Apple APK SHA-256
`73AC328C2516C127AE0335D65FB0D6C87E9C384E383A29A00FA1C5B82933D06D`，Spotify APK
SHA-256 `83ADDDE18B1E9DBA7501C10521722642F1EEBC3522F6750DB4E237BE08630B3C`。
首轮 Apple 真机日志 `logs/lyrics-log-20260831-160438.txt` 暴露 bootstrap 误传
`Application.attach(Context)` 的 base Context，导致 Apple 在安装业务 Hook 前静默返回（`hooks=1`）。
提交 `eb675b1` 改为传递真实 Application 实例并加入显式断言；共享层/Apple 单测、Debug APK、
`testV5Matrix` 已重跑通过；最终真机证据见下文。

Apple 复测日志 `logs/lyrics-log-20260831-161138.txt` 已确认 hooks=10、取词/发布、Bridge
消费与逐字高亮恢复。Spotify 日志 `logs/lyrics-log-20260831-161617.txt` 确认取词/发布正常，
但 Bridge 未注册 Spotify `MediaController.Callback`，自绘时钟保持
`position=0, playing=false`；同时暴露 `Application#onTerminate` 在 attach 阶段取
`applicationContext` 的空指针。Provider 提交 `0ce244b` 改用真实 Application；Bridge 独立
blocker 提交 `afb8a35` 在接受 lyricInfo 后仅对唯一目标包活跃 controller 回补 callback，
不写入或伪造 PlaybackState。Bridge 单测/Debug APK 与 Provider 全矩阵门禁均通过；
Bridge hotfix APK SHA-256
`5F8262617508AFE1AB04AFC3FE73F3BA99385244C1EB5DCAB9567F8021EABEC0`。

最终 Spotify 复测日志 `logs/lyrics-log-20260831-163823.txt` 已确认：主进程
`PROCESS_ACCEPTED`、webview 附加包 `PROCESS_SKIPPED`、`hooks=11`，且不再出现
`APPLICATION_TERMINATE_HOOK_FAILED`；generation 1→2 切歌、缓存/取词、
`NATIVE_LYRIC_INFO_COMMITTED` 与 Bridge payload 解析完整。Bridge 自绘位置从 21.5s 持续推进
至 49s，新曲从 1.2s 推进至 10.1s，`playing=true`、`active=true`、
`scaleActiveIndex` 连续变化，暂停态 PlaybackState 亦正常。至此 Apple 与 Spotify 均完成用户
真机回归，Wave D 结论：全部通过。

## 14. Wave E Hook ledger（NetEase，源码迁移与本地门禁通过）

NetEase 继续由一个 APK 覆盖四个显式 package/process profile：

| package / process | profile | 处理 |
|---|---|---|
| `com.netease.cloudmusic` | `OFFICIAL_APPEND` | 官方 writer/encoder 追加 |
| `com.netease.cloudmusic:play` | `CONSTRUCTED` | 9.0.40 构造链 |
| `com.hihonor.cloudmusic` | `OFFICIAL_APPEND` | 荣耀官方追加 |
| `com.hihonor.cloudmusic:play` | `OFFICIAL_APPEND` | 荣耀播放进程官方追加 |

| # | 目标 | 4.0 形态 | 4.1 处理 |
|---|---|---|---|
| 1 | package/process 路由 | Yuki `loadApp` + hooker 内 profile gate | 唯一 `NeteaseModuleEntry` + `NeteaseProfileProcessPolicy`，拒绝进程在业务 Hook 前 detach |
| 2 | `MediaSession#setMetadata` | KavaRef + Yuki before | `netease:netease.session.MediaSession#setMetadata`；constructed 请求、generation 与 host metadata overlay 不变 |
| 3 | 官方 lyric writer before/after | `XposedBridge.hookMethod` + `XC_MethodHook` | `netease:netease.writer.<class>#<method>`；ThreadLocal 仅覆盖同步 writer→encoder 窗口 |
| 4 | 官方 encoder result after | `XposedBridge.hookMethod` 直接替换 result | `netease:netease.encoder.<class>#<method>`；共享 Chain 先 `proceed()` 一次，再显式返回修补结果 |
| 5 | track-bind methods before | 复用一个 `XC_MethodHook` | `netease:netease.track.<class>#<method>`，逐方法稳定 id |
| 6 | `Handler#dispatchMessage` after | 全局 legacy Hook + handler/what/payload 过滤 | `netease:netease.dispatch.Handler#dispatchMessage`；原有三重过滤和 post-dispatch capture 不变 |

`NeteaseConstructedLyricSession`、`NeteaseLyricSessionCoordinator`、payload mode、异步
generation 防串曲、官方/构造 source 值均未因 Hook API 迁移改变。Wave E 前的官方歌词重复
别名修复保留在独立提交 `be4eea1`；API 102 迁移提交为 `8208945`。

最后一个 Provider 迁移完成后，提交 `37df111` 删除 `provider-core` 中的
`YukiHookDebugSource`、world-readable/New XSharedPreferences 路径、legacy `XposedSink` 与
Yuki/legacy Xposed 依赖；Apple/Spotify 设置页同时改回共享 Remote Preferences Activity，
不改变其播放器 Hook 逻辑。

本地门禁：

- `:player-netease:testDebugUnitTest`、`:player-netease:verifyXposedApi102Resources`、
  `:player-netease:assembleDebug` 通过；
- `testV5Matrix` 与 `assembleV5MatrixDebug` 通过，共产出 12/12 Debug APK；
- 12 个 Debug APK 解包后的 DEX legacy forbidden hits 为 0；
- NetEase APK 的 `module.prop` 为 API 102/protective/static scope/关闭 auto hot reload，
  `java_init.list` 仅含 `NeteaseModuleEntry`，scope 为 NetEase + Honor 两包，legacy entry 为 0；
- NetEase Debug APK SHA-256：
  `A84887181C61CA6F2EE407B7B0A82FD71332AD4DDC06830B6D43EF659397358E`，v2 debug 签名通过。

以上为 Wave E 的源码、本地测试、APK 结构与 Debug 全矩阵门禁；后续设备证据与用户验收
结论见 §15。

## 15. Wave E 真机回归证据（2026-09-01，用户执行，结论：暂时通过）

日志 `logs/lyrics-log-20260901-061521.txt`（SHA-256
`FF5C4BB6D34CF87A2B214455A14ACB2ACECD1CE8E0B7FED868EC0078414DB75C`）覆盖网易云官方版
主进程与荣耀版主进程：两者均以 API 102 `official_append` 路由进入，分别安装 11/12 个
Hook；网易云发布 42/42 与 98/85 行翻译 payload，荣耀 writer→encoder 的
`PENDING_ENCODE_SET/CLEARED` 成对出现并发布完整 raw/translation。Bridge 消费、逐行/逐字
高亮、翻译切换、暂停与切歌正常，Provider/Bridge 无 ERROR、WARN、Hook 失败或 FATAL。
`OVERLAY_SKIPPED reason=identity-mismatch` 是跨曲旧 overlay 的预期 fail-open 丢弃。

日志 `logs/lyrics-log-20260901-062538.txt`（SHA-256
`CF7F5DFE26C509B766C20BA9CDE139991DE988CFABD0E970E38A87DFF0A295EA`）确认修改版 9.0.40 的
`com.netease.cloudmusic:play` 以 API 102 `policy=constructed` 被接受，Remote Preferences
开启态出现 `DEBUG_CONFIG_APPLIED reason=enabled` / `DEBUG_LOGGING_ENABLED`，随后
`PROCESS_READY reason=CONSTRUCTED`、`MEDIA_SESSION_HOOKED`、`CONSTRUCTED_PROFILE_READY` 与
`hooks=2` 均成立。

该修改版直接安装后主进程连续 5 次在宿主 UI 初始化阶段崩溃：主要栈为
`MyMusicFragmentV3#onCreateView` 的 `org.json.JSONException`，另一次为
`AIDJBreathView` inflate 失败；崩溃栈没有 Provider 类。修改版主进程缺少官方 writer，因而
`LYRIC_WRITE_MISSING` 与 4.0 静态结论一致。由于宿主未稳定运行，本窗口没有产生 `:play` 的
`TRACK_BOUND`、`CONSTRUCTED_FETCH_HIT` 或 `source=netease-constructed`，不能把本日志表述为
完整构造发布链复验。

用户确认 9.0.40 需要特殊安装方式，直接安装闪退属于该宿主样本的已知使用前提，并明确要求
本轮先标记通过。结合 4.0 已完成的 9.0.40 构造链真机基线与本次 API 102 entry/profile/Hook
安装证据，Wave E 按“用户临时验收通过”收口；特殊安装方法和完整 constructed 发布链复验
作为非阻断限制保留。`officialLyricRepair=true` 目标歌曲本轮仍未触发，重复别名专项修复保持
独立的非阻断验证项。
