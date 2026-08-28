# 播放器适配参考：Salt Player (`com.salt.music`)

记录日期：2026-08-25  
适用目标：4.0 独立 Provider、原生 `lyricInfo`、Root/LSPosed 验收  
实现模块：`player-salt`

## 1. 最终状态

| 运行模式 | 结论 | 证据 |
|---|---|---|
| Root / LSPosed | 已通过 | Salt 12.3.0-alpha03 真机播放、锁屏歌词、车载 metadata 与方括号逐字 LRC |
| Bridge legacy hook | 已删除 | Root/LSPosed 等价与冲突评审由用户确认完成 |

Salt 的 Root 实现是后续播放器适配的正向模板。4.0 全系列不适配 NPatch：历史失败链只在
Phase 2 报告中留档，不构成资格评估、实现或交付模板。

## 2. 先确定真正的数据边界

播放器适配的第一步不是找某个 UI 或某行歌词，而是找到播放器已经完成以下工作之后的最终
发布点：

1. 歌词来源选择完成；
2. 异步旧结果已按当前歌曲 ID 拒绝；
3. 行级/逐字模型已经形成；
4. 最终结果即将写入播放器状态流、回调或 MediaSession。

Salt 的正确 Hook 点是最终歌词发布协程，而不是网络请求、文件解析器、歌词列表 UI 或车载
逐行 metadata 更新。这样可以直接继承宿主的 stale-result 拒绝，显著减少 Provider 自建状态机。

通用数据流：

```text
宿主最终歌词发布点
        ↓
播放器专属 decoder
        ↓
RichLyricLine / TrackIdentity
        ↓
TrackGenerationPolicy
        ↓
唯一主 MediaSession + 当前 host metadata
        ↓
NativeLyricInfoPublisher
```

## 3. DexKit 发现：匹配结构，不匹配混淆类名

Salt 的可复用策略：

1. 以稳定枚举字符串定位来源枚举，例如 `EMBEDDED`、`TAG_LYRICS3_V2`。
2. 以 `CAN_SCROLL`、`NOT_SCROLL` 定位滚动能力枚举。
3. 查找同时持有“来源枚举字段 + 滚动枚举字段”的结果类。
4. 查找同时持有“稳定 Song 类 + 结果类字段”的最终 publisher。
5. 每一步都要求候选数严格等于 1；零候选和多候选必须失败并记录完整候选。

Salt 同时搜索历史包根：

```text
androidx.obf
androidx.media3
```

### 禁止直接照抄类名

静态反编译报告、测试 fixture、JADX 显示名和真机 DexKit 结果可能出现不同混淆名称。具体
`ac1/zb1/tv1`、`ob1/pb1/qb1/ev1` 等名称只能作为某次 APK 的证据，不能成为运行时契约。

后续播放器必须记录三层名称：

| 名称层 | 用途 |
|---|---|
| 原始 DEX 名称 | Hook 和 ClassLoader 的真实目标 |
| JADX 显示/重命名 | 人工阅读 |
| 测试 fixture 名称 | 回归测试，不参与运行时选择 |

## 4. 混淆方法解析

Salt 的 coroutine 方法可能保留 `invokeSuspend`，也可能被改为 CJK 或任意名称。解析顺序：

1. 优先查找字面 `invokeSuspend(Object)`；
2. 未命中时查找唯一“单个 `Object` 参数”的声明方法；
3. 多个结构候选时拒绝 Hook并输出签名列表；
4. 不使用 `firstMethod` 或声明顺序兜底。

这套策略可复用于 Kotlin coroutine、匿名 lambda、Flow collector 等被重命名场景，但必须先
确认“唯一单 Object 参数”确实代表目标挂起体。

## 5. Track identity 与展示 metadata 必须分离

Salt 车载/蓝牙歌词和普通播放共用一个主 MediaSession。车载模式会逐行改写：

```text
TITLE  = 当前动态歌词行
ARTIST = 原歌手 + 分隔符 + 原曲名
```

如果直接用 TITLE/ARTIST 推进 generation，每一行都会被误判为换曲。最终边界：

```text
raw MediaMetadata
        ↓
Salt metadata identity resolver
        ↓
stable TrackIdentity
        ↓
TrackGenerationPolicy
```

解析优先级：

1. `DISPLAY_TITLE` / `DISPLAY_SUBTITLE`；
2. 从 Salt 复合 ARTIST 中恢复 `<Artist> -|–|— <Title>`；
3. 普通 TITLE / ARTIST。

通用原则：MediaSession metadata 是展示载体，不天然等于稳定歌曲身份。播放器专属复合字段
解析只能放在该播放器 profile；在第二个真实播放器出现相同行为前，不抽成全局规则。

## 6. MediaSession 所有权与发布事务

Salt 实现采用弱引用 session registry，并记录：

- constructor tag；
- 当前 host metadata；
- playback state；
- active/release 状态；
- module write reentrancy guard。

只在以下条件同时满足时发布：

1. publication track 与稳定 host track 一致；
2. 当前 generation 有效；
3. 能选出唯一主 session；
4. 已取得该 session 最新 host metadata；
5. payload 归本模块所有或目标字段尚未被外部占用。

发布始终复制当前 metadata，只追加 `lyricInfo`。不得保存旧 metadata 后整包覆盖，否则会破坏
封面、车载动态字段、播放状态和宿主后续更新。

## 7. Pending 与 replay 的最小状态机

最终歌词可能早于 MediaSession metadata 到达。Salt 只缓存一条 pending publication：

- 同曲且 session/metadata 未就绪：暂存；
- 新结果到达：替换旧 pending；
- 换曲或 generation 失效：清除；
- metadata/playback/active 使 session 唯一后：立即 drain。

同曲 metadata 刷新丢失模块 `lyricInfo` 时，可在同一 session 和当前 generation 内有界 replay。
不得跨 session、跨曲或覆盖宿主/其他来源已有 payload。

## 8. 歌词格式兼容经验

Salt/TME 部分 LRC 使用方括号行内逐字格式：

```text
[00:11.367]I [00:11.548]heard [00:11.773]you[00:12.062]
```

正确输出应拆为：

- `lyric`：行级时间 + 纯文本，供原生 SystemUI；
- `rawLyric`：标准化 `<time>` 逐字时间，供 Bridge 逐字渲染。

识别门禁：

- 行首时间兼作首词时间；
- 行内方括号时间只在前面已有可见文本时解释为逐字；
- 末尾无文本时间只关闭上一词；
- `[00:10][00:20]重复歌词` 仍是多行时间标签；
- 普通正文 `[人類]` 不得误判。

当前 Provider 未直接解析 Salt `va1` 私有逐字对象，但原始增强 LRC 可被解析为 WORD；普通
LRC 继续分类为 LINE。

## 9. 日志驱动定位顺序

必须按缺失的第一层定位，不跳层猜测：

```text
RUNTIME_MODE_RESOLVED
        ↓
DEBUG_CONFIG_APPLIED（reason=enabled 才继续查 DEBUG 事件）
        ↓
DEBUG_LOGGING_ENABLED（仅开关打开时出现，level=DEBUG）
        ↓
HOOK_DISCOVERY / SALT_MODEL_DISCOVERED
        ↓
PUBLISHER_HOOK_INSTALLED
        ↓
Track identity / generation
        ↓
Session unique + metadata ready
        ↓
LYRIC_INFO_PUBLISHED
        ↓
SALT_FINAL_PUBLISHED
        ↓
SystemUI / lockscreen visible
```

异常日志必须保留 exception message 与 throwable stack。仅记录
`reason=IllegalStateException` 会把“候选为 0”“APK 不可访问”“方法歧义”等不同问题混为一谈。

## 10. 新播放器推荐实施模板

### Phase A：Root 运行边界确认

1. 固定 APK、SHA-256、包名、版本、进程和 ABI。
2. 确认 LSPosed scope、模块入口、启动与播放。
3. 确认是否需要 DexKit、原 APK、native 或私有 ClassLoader。
4. 只为 Root/LSPosed 路径建立可复现的输入与验证证据。
5. 调试开关走第 13 节：共享 prefs 写入 + Yuki 读取；LSP ZIP 必须出现 `DEBUG_LOGGING_ENABLED`。

### Phase B：Root 最小闭环

1. 找最终歌词发布点。
2. 建立稳定 TrackIdentity。
3. 绑定唯一主 MediaSession。
4. 发布最小合法 `lyricInfo`。
5. 首次播放、切歌、暂停、seek、锁屏通过后再处理逐字/翻译/replay。

### Phase C：特殊 metadata

1. 记录一次完整 metadata timeline。
2. 区分展示字段与身份字段。
3. 用具名 resolver/profile 处理，禁止写入全局主流程。

### Phase D：删除旧 fallback

只有 Root/LSPosed 下的播放场景、AOD、封面和蓝牙 metadata 均真机通过，才删除 Bridge
scope/adapter。构建成功、APK 可安装和单条 Hook 日志均不构成删除依据。

## 11. Salt 专属与可复用边界

| 内容 | 可复用性 |
|---|---|
| 最终发布点优先于解析/网络 Hook | 通用 |
| 字符串 + 字段结构 DexKit | 通用 |
| 唯一单 Object coroutine fallback | 条件通用 |
| 稳定 identity 与展示 metadata 分离 | 通用 |
| 唯一主 Session + generation + transaction | 通用 |
| pending/replay 有界状态机 | 通用 |
| 方括号行内逐字解析 | 通用 LRC |
| LSPosed 调试开关：`MODE_WORLD_READABLE` 写入 + Yuki `Context.prefs()` 读取 | 通用（见第 13 节） |
| Salt 复合 ARTIST 拆分 | Salt 专属 |
| `SaltDebugSettingsActivity` 文案 / `ProviderId.SALT` / prefs 名 `salt_provider_debug_prefs` | Salt 专属标识，实现必须走 `provider-core` |

## 12. 证据索引

- Root 日志：`logs/salt-phase2-root-01.txt` 至 `salt-phase2-root-03.txt`
- Salt 静态逆向：`PlayerSource/SaltPlayer/12.3.0-decompiled/SALT-12.3.0-REVERSE-REPORT.md`
- Phase 2 报告：`docs/4.0/PHASE-2-SALT-MIGRATION-REPORT.md`
- 当前 Root APK：`build/all-apks/debug/player-salt-<version>-debug.apk`
- 调试开关失败对照：`feelback/LSPosed_20260825_173746.zip`（Cone 旧包 `reason=disabled`）
- 调试开关 Salt 通过：`feelback/LSPosed_20260825_180239.zip`（`com.salt.music`，`reason=enabled` + `DEBUG_LOGGING_ENABLED`）
- 调试开关 Cone 复用通过：`feelback/LSPosed_20260825_180927.zip`（`ink.trantor.coneplayer.gp` 与 `ink.trantor.coneplayer` 均为 `reason=enabled`）

## 13. 调试开关链路（可复用参考案例）

Salt 是这条链路的正向模板；Cone 用同一套 `provider-core` 类、只换 `ProviderId` 与设置页文案即可复用。新播放器不得再写一份模块私有 prefs 读写器。

INFO 启动摘要、发布成功、警告和错误默认双写 logcat 与 LSPosed framework log，**不看**调试开关。开关只打开额外的 `[CLL] level=DEBUG` 事件（以及 `HookEntry.onInit` 里的 Yuki `debugLog`）。因此 LSP ZIP 里出现 `LYRIC_INFO_PUBLISHED` **不能**证明开关生效。

### 13.1 失败形态（必须能从日志直接判定）

2026-08-25 Cone 旧包（`LSPosed_20260825_173746.zip`）设置页已打开、用户认为开关已开，播放器仍打：

```text
event=DEBUG_CONFIG_APPLIED reason=disabled
```

且没有 `event=DEBUG_LOGGING_ENABLED`。根因有两处，必须同时修：

| 侧 | 错误实现 | 结果 |
|---|---|---|
| 模块设置页写入 | `Context.MODE_PRIVATE` | LSPosed 新 XSharedPreferences 不会把该文件导出给宿主。`xposedsharedprefs=true` 只声明能力，**打开 prefs 时必须先用 `MODE_WORLD_READABLE`**。 |
| 播放器进程读取 | 只 `Class.forName("de.robv.android.xposed.XSharedPreferences")`，失败一律当作用户关闭 | 读空、类不可见、未导出都被记成 `reason=disabled`，无法和“用户没开开关”区分。 |

YukiHookAPI 在宿主进程里的受支持读法是 `Context.prefs(prefsName)`（`YukiHookPrefsBridge` / `XSharedPreferencesDelegate`），不是手写 `XSharedPreferences` 作为唯一来源。

### 13.2 正确数据流

```text
模块进程 SaltDebugSettingsActivity
        ↓
ProviderDebugConfig.openModulePrefs()
  先 MODE_WORLD_READABLE，SecurityException 才回退 MODE_PRIVATE
        ↓
prefs 名：${ProviderId.configKey}_provider_debug_prefs
键：provider_debug_logging_enabled
        ↓
LSPosed 导出共享 prefs
        ↓
宿主进程 SaltPlayerHooker.onHook
        ↓
YukiHookDebugSource.create(hostContext)
  → context.prefs(prefsName).getBoolean(KEY, false)
        ↓
ProviderDebugConfig.applyDiagnostics()
  → StructuredDiagnostics.configureForRuntime(mode, enabled)
        ↓
始终 INFO：DEBUG_CONFIG_APPLIED reason=...
开关打开时 DEBUG：DEBUG_LOGGING_ENABLED reason=enabled
```

`HookEntry.onInit` 发生在 Application Context 之前，这里**可以**继续用 `ProviderDebugConfig.readXposedSwitch(modulePackage, provider)` 决定是否打开 Yuki `debugLog { tag = "SaltPlayerProvider" }`。那只是 Yuki 框架日志，不是 `[CLL]` 主链路；主链路必须走 `YukiHookDebugSource`。

### 13.3 新播放器最小接入（照抄 Salt，只换标识）

1. `AndroidManifest.xml` 保留 `xposedsharedprefs=true`，并导出一个继承 `ProviderDebugSettingsActivity` 的设置页（Launcher）。
2. 设置页只覆盖 `providerId` / `providerDisplayName` / `targetPackageDescription`。不要再写 SharedPreferences。
3. Hooker 在 `RuntimeModeResolver` 判定支持之后立刻：

```text
val debug = ProviderDebugConfig.applyDiagnostics(
    mode = resolution.mode,
    provider = ProviderId.<NEW>,
    rootSource = YukiHookDebugSource.create(hookContext)
)
```

然后打 `DEBUG_CONFIG_APPLIED`；`debug.enabled` 为真时再打 `DEBUG_LOGGING_ENABLED`。
4. 不要新建 `XxxRootDebugSource`。不要在播放器模块里 `getSharedPreferences(..., MODE_PRIVATE)`。
5. `reason` 必须可区分：`enabled`、`disabled`（用户关闭）、`disabled:source-unavailable`、`disabled:read-<Exception>`、`disabled:mode-...`。禁止把读失败折叠成 `disabled`。
6. 开关打开后必须有一条稳定的 `level=DEBUG` 事件进入 LSP ZIP。仅靠 INFO 发布链无法验收 DEBUG 门。

设置页若未能进入 LSPosed 共享存储，必须显示红色警告：开关只会写在模块私有目录，宿主会一直 `reason=disabled`。

### 13.4 验收顺序

安装或覆盖 debug APK 后，若旧写入曾走 `MODE_PRIVATE`，必须在设置页**重新打开一次开关**，再**完全结束并重启**目标播放器。不要在旧进程里等热更新。

LSP ZIP 的 `log/modules_*.log` 启动摘要应同时满足：

```text
(com.salt.music)[...,XposedBridge,...] [ColorOSLiveLyrics]
  event=DEBUG_CONFIG_APPLIED reason=enabled

(com.salt.music)[...,XposedBridge,...] [ColorOSLiveLyrics]
  level=DEBUG event=DEBUG_LOGGING_ENABLED reason=enabled
```

`I/LSPosedFramework` 外壳 + `[CLL] level=DEBUG` 正文是 `XposedBridge.log` 双写的正常形态，不是级别丢失。

真机已确认：

| 包 | ZIP | 进程 | 结果 |
|---|---|---|---|
| Salt 旧写入 / 未导出 | 17:39 窗口（含于 180239） | `com.salt.music` | `reason=disabled`，无 `DEBUG_LOGGING_ENABLED` |
| Salt 修复包 | `LSPosed_20260825_180239.zip` 18:02:01 | `com.salt.music` | `reason=enabled` + `DEBUG_LOGGING_ENABLED` |
| Cone 修复包 | `LSPosed_20260825_180927.zip` 18:08:06 / 18:08:58 | `ink.trantor.coneplayer.gp`、`ink.trantor.coneplayer` | 两进程均为 `reason=enabled` + `DEBUG_LOGGING_ENABLED` |

Cone 证明：多宿主包名只要共用一个 `ProviderId` 和同一 prefs 名，不需要为每个进程再写一套开关。

### 13.5 禁止项

- 不要把 Provider 调试开关写进 Bridge `LyricUiConfig` 或歌词 UI 1.5s 轮询。Bridge 使用独立 prefs `lockscreen_lyrics_debug` 与独立设置子页。
- 不要用 `adb logcat -s` 代替开关。它只过滤输出，不会让 `StructuredDiagnostics` 的 DEBUG 门打开。
- 不要因为 INFO 双写进了 ZIP 就宣称“调试开关已生效”。
- 不要在新播放器里恢复“反射 `XSharedPreferences` 作为宿主唯一读源”。`readXposedSwitch` 仅用于 `onInit`。

## 14. 翻译按钮（公开 CustomAction）

锁屏媒体卡翻译按钮走
`ColorOS-Live-Lyrics-Bridge/docs/TRANSLATION_TOGGLE_INTEGRATION.md` 的 **public** 路径，
不是收藏槽替换。

Salt Rule0 只有 `com.salt.music.desktop_lyrics`。若播放器进程不注入
`io.github.andrealtb.lockscreenlyrics.action.TOGGLE_TRANSLATION`，SystemUI 会得到
`hasPublicAction=false, canOverride=false`，或退回 `protocol=salt-legacy` 占用桌面歌词槽。

正确实现：在已有 `MediaSession#setPlaybackState` hook 里调用
`PlaybackStateTranslationToggle.prependPublicAction`，把公开动作插到 CustomAction 列表首位。
Cone 复用同一 helper。不要把 Bridge Salt/Cone `PlayerAdapter` 或
`installInjectedTranslationToggleActionHook` 接回去。

验收：Provider `event=TRANSLATION_ACTION_INJECTED reason=public`；Bridge
`hasPublicAction=true` 且 `protocol=public`。
