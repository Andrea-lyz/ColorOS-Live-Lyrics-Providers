# 4.0 Phase 4 网易云 / 荣耀云音乐 lyricInfo 迁移报告

记录日期：2026-08-27  
仓库：`ColorOS-Live-Lyrics-Providers`（分支 `4.0`）  
模块：`player-netease`  
逆向依据：`PlayerSource/NetEase/NETEASE-NATIVE-LYRICINFO-AND-MODIFIED-V5-INVESTIGATION.md`  
样本 A：网易云音乐官方原版 `9.5.70`（`com.netease.cloudmusic`，versionCode `9005070`，SHA-256 `FA023A65BD02841D33047F4538A417934A961C0D88846C7CD04BF14844AD4C87`）  
样本 B：荣耀云音乐 `3.5.20`（`com.hihonor.cloudmusic`，versionCode `3005020`，SHA-256 `CA75E58F43271A8816A224D421924C377BC501CEE5E6105E0482C277F748980C`）  
样本 C：网易云音乐精简修改版 `9.0.40`（`com.netease.cloudmusic`，`:play` 主播放进程）  
方法模板：`:player-qq` 官方 lyricInfo 追加 + 旧 `:163-music` eAPI 歌词获取  
真机日志：官方版 `lyrics-log-20260827-122718.txt`；荣耀版
`lyrics-log-20260827-130318.txt`；9.0.40 修改版
`lyrics-log-20260827-150005.txt`（三条样本均由用户确认通过）

## 交付边界

- 新建 `player-netease`，`namespace` / `applicationId` 均为
  `io.github.andrealtb.coloroslyrics.provider.netease`，`versionCode=1` /
  `versionName=1.0.0`。
- 同一 APK 覆盖三个经独立样本确认的 host/process profile：
  - 网易云官方 9.5.70：`com.netease.cloudmusic` **主进程**，走官方追加。
  - 荣耀云音乐 3.5.20：静态主链在 `com.hihonor.cloudmusic:play`；运行时同时允许
    `com.hihonor.cloudmusic` 主进程作为兼容回退，由 DexKit/Handler 命中决定实际发布者。
  - 网易云修改版 9.0.40：`com.netease.cloudmusic:play`，走传统构造；该样本无官方
    `lyricInfo` / `what=16`，不运行官方 writer/encoder 发现。
  三者都不改写宿主 `setPlaybackState`。
  翻译按钮仍走 Bridge 5 槽收藏覆盖，不注入公开 `ACTION_TOGGLE_TRANSLATION`。
- `NeteaseRuntimeProfile.resolve` 是唯一闸门：网易云主进程为 `OFFICIAL_APPEND`，
  网易云 `:play` 为 `CONSTRUCTED`；Honor 主进程与 `:play` 永远为
  `OFFICIAL_APPEND`，其余进程返回 null 并跳过。
- 结构收口：`NeteasePlayerHooker` 只负责组合与平台 metadata；
  `NeteaseOfficialLyricHooks` 负责官方 writer/encoder/dispatch；
  `NeteaseConstructedLyricSession` 负责 eAPI 生命周期；
  `NeteaseLyricSessionCoordinator` 负责 generation、去重和 replay。
- 9.0.40 从宿主 metadata 的 `android.media.metadata.MEDIA_ID`、标题、歌手、专辑和时长
  建立稳定身份；`YrcDownloader` 请求网易云 eAPI，最多重试三次。新 generation 会取消旧
  coroutine，阻塞请求晚到后仍按 musicId + generation 丢弃，禁止旧歌词覆盖新歌。
- 构造路径复用 `:share:yrckit` 的 eAPI 客户端与响应 DTO，但不创建 `LyriconFactory`、
  不调用 `player.setSong`，也不发送 v4 广播。YRC/LRC 仍由 4.0 `parser-yrc` / `parser-lrc`
  解码和对齐；`romalrc` 明确不进入翻译 lane。
- 官方 `lyricInfo` 已经有 `lyric` / `songName` / `artist`。`NeteaseLyricInfoPayloadEncoder`
  以显式 `NeteasePayloadMode.OFFICIAL_APPEND` 就地追加 `rawLyric`、
  `translationLyric`、`album`、`songId`、`sessionGeneration`、
  `source=netease-official-append`。不要改成 `NativeLyricInfoPublisher` / Bridge envelope，
  也不要发送 v4 广播或挂载词幕。
- 9.0.40 没有官方 seedling，编码器从同一 `RichLyricLine` 模型完整生成 `lyric`、
  `rawLyric`、`translationLyric`、歌曲身份和 `sessionGeneration`，并写入
  `source=netease-constructed`。宿主 artwork / URI / album 等 metadata 仍由空 typed Builder
  拷贝保留。
- DexKit 按结构发现每个样本自己的官方写入链，禁止共用混淆名：
  - 网易云：`jp0.t#o0(LyricInfo, MusicInfo)`、编码器 `I(...)`、当前歌曲访问器 `O()`。
  - Honor：`ce0.p#e0(LyricInfo, MusicInfo)`、编码器 `B(...)`、当前歌曲访问器 `F()`。
  两条链都由 `Handler` 的 `what=16` 携带 `LyricInfo`。实际捕获点是框架
  `Handler#dispatchMessage` 的 **after**，按运行时 handler 类名 + what + payload 类型过滤，
  再调用唯一零参数 `MusicInfo` 访问器；不 hook 宿主 `handleMessage`，不遍历 Handler 字段。
  宿主私有 `o0/e0 -> I/B` Hook 只作同调用栈快速路径，pending 在返回后立即清除。
  Track bind 只按 `void(MusicInfo)` 形状选择，不优先任何混淆名。
  Compat/平台 Builder、Bundle、Compat Session、callback、宿主 `handleMessage` 和 publish
  探针及对应 resolver/测试已经从源码删除；它们曾把上一曲 publication 注入下一曲。
  平台 `MediaSession#setMetadata` 负责 session 注册、最终
  overlay 与 replay；同曲 `onTrackChanged` 保留 publication，标题不符的旧模块 payload 先清空。
- `lrcRomeLyric` / `yrcRomeLyric` 不得进入 `translationLyric`。翻译只来自
  `yrcTranslateLyric`（YRC 头则走 `YrcParser`，否则 LRC）再回退 `lrcTranslateLyric`。
  对齐与 QQ 相同：行起点 + 首词双锚点、邻居间距/2、上限 1500 ms、消费 `//`。
- 身份：`musicInfo.getFilterMusicId() == lyricInfo.getMusicId()`，否则不追加。
- 空 typed `MediaMetadata.Builder()` 全量拷贝（禁止 `Builder(existing)`）。
  HARDWARE 或边长 >240px 的 bitmap 用 Canvas 重绘为 software ARGB_8888。
  不 HTTP 拉封面、不 snapshot、不发明封面。
- 调试开关复用 `provider-core`：`ProviderId.NETEASE`、prefs
  `netease_provider_debug_prefs`、`MODE_WORLD_READABLE` 写入 + Yuki
  `Context.prefs()` 读取。`HookEntry.onInit` 只在开关打开时启用 Yuki tag
  `NeteaseMusicProvider`。结构化日志 `[CLL] ... component=provider/netease`。
- Bridge 不再把 `NeteaseMusicAdapter` 登记为 built-in player hook。Apple 已迁到
  `:player-apple`，同样不再登记 built-in adapter。
  网易云 / Honor 翻译设置共用新 applicationId。v4
  `lyricprovider/netease-cloud-music` 准入仅为旧版本兼容保留；新模块的三条样本路径都不发送 v4。
- 4.0 不适配 NPatch。旧 `LyricProvider` 仓库仍留给 3.x 发布。

## 运行时数据流

```text
平台 metadata → TRACK_BOUND → generation++
        ├─ 官方 9.5.70 / Honor：Handler.dispatchMessage after（what=16）
        │      ↓ 内部 LyricInfo → YrcParser / LrcParser + 双锚点翻译合并
        └─ 修改版 9.0.40 :play：MEDIA_ID → eAPI fetch
               ↓ musicId + generation 复核 → YrcParser / LrcParser + 双锚点翻译合并
平台 MediaSession replay / 后续宿主 setMetadata overlay
        ↓ 空 Builder 拷贝；官方追加或完整构造 lyricInfo
        ↓ 标题不符的旧模块 payload（append / constructed）先清除
        ↓ 同曲 onTrackChanged 保留 publication
NATIVE_LYRICINFO_PATCHED / LYRIC_INFO_PATCHED
```

## Bridge 登记

- `PlayerTranslationSettings` 的网易云行指向
  `io.github.andrealtb.coloroslyrics.provider.netease`，同时列出
  `com.netease.cloudmusic` 与 `com.hihonor.cloudmusic`。
- `com.netease.cloudmusic` 与 `com.hihonor.cloudmusic` 都留在
  `BRIDGE_PLAYER_PACKAGES` 和
  `registeredProviderMayOverrideFavoriteActionWithTranslation`。
- 不为新模块新增 v4 `EXTERNAL_SOURCES`。不删除旧
  `lyricprovider/netease-cloud-music` 准入。
- Bridge 不再把 `NeteaseMusicAdapter` 登记为 built-in player hook。

## 自动验证

```text
scripts\dev.cmd provider :player-netease:testDebugUnitTest :player-netease:assembleDebug
scripts\dev.cmd bridge testDebugUnitTest
```

设备验收梯子：

```text
RUNTIME_MODE_RESOLVED
        ↓
DEBUG_CONFIG_APPLIED（reason=enabled 才继续查 DEBUG 事件）
        ↓
DEBUG_LOGGING_ENABLED
        ↓
PROCESS_READY（网易云主进程 / `:play`；Honor 主进程 / `:play`）
        ↓
TRACK_BOUND
        ↓
官方：LYRIC_WRITE_HOOKED / ENCODER_HOOKED
9.0.40：CONSTRUCTED_PROFILE_READY / CONSTRUCTED_FETCH_REQUESTED / CONSTRUCTED_FETCH_HIT
        ↓
NATIVE_LYRICINFO_PATCHED
        ↓
Accepted bridge lyricInfo / NATIVE_LYRIC_RECEIVED
        ↓
锁屏 / AOD 可见逐字；外语歌可切翻译
```

测试网易云、Honor 或 9.0.40 前卸载旧 `io.github.proify.lyricon.cmprovider`，或至少从对应
host scope 停用旧模块；LSPosed 启用新包并勾选两个 scope。9.0.40 的验收必须出现
`source=netease-constructed`，不能以官方追加事件代替。

## 真机收口：网易云 9.0.40 修改版

`lyrics-log-20260827-150005.txt` 在同一捕获窗口覆盖三次稳定换曲：

| generation | 歌曲 | musicId | Provider 结果 | Bridge 结果 |
|---|---|---:|---|---|
| 1 | The Fate of Ophelia | 2744403174 | 首次 eAPI 命中，78 行 / 72 行翻译 | `rawChars=3144`、`translationChars=1579` |
| 2 | Elizabeth Taylor | 2744399122 | 首次 eAPI 命中，71 行 / 61 行翻译 | `rawChars=2885`、`translationChars=1629` |
| 3 | Opalite | 2744403189 | 构造请求后 Bridge 接收新歌曲 payload | `rawChars=2585`、`translationChars=1356` |

- 实际发布进程为 `com.netease.cloudmusic:play`：
  `CONSTRUCTED_PROFILE_READY → TRACK_BOUND → CONSTRUCTED_FETCH_REQUESTED →
  LYRIC_INFO_PATCHED/NATIVE_LYRICINFO_PATCHED`。前两首均 `attempt=1`、`replay=true`；
  第三首捕获中缺少 Provider 中间 INFO，但 Bridge 在 277 ms 后接收到带原文和翻译的新 payload。
- Bridge 三首分别得到 72 / 62 / 59 个最终槽位，全部
  `duplicateObjects=0`、`officialAliasMismatches=0`；自绘日志持续
  `hasTranslation=true`，翻译按钮点击也被正常接管。
- 两次换曲先出现旧 publication 对新标题的 `identity-mismatch`，随后 generation 从
  1 → 2 → 3 并绑定新 musicId；这是预期的串曲防护命中，不是回退。
- 无 `CONSTRUCTED_FETCH_RETRY`、`CONSTRUCTED_FETCH_FAILED`、
  `CONSTRUCTED_FETCH_STALE`、`CONSTRUCTED_DECODE_EMPTY`、`REPLAY_FAILED` 或
  `OVERLAY_FAILED`。Bridge 无空 `rawLyric` / `translationLyric`、无 INVALID 或官方视觉回退。
- 修改版主进程的 `LYRIC_WRITE_MISSING` 符合静态结论：该进程没有官方 native writer；
  它未发布歌词，实际主 MediaSession 和构造链都在 `:play`，不影响功能。

结论：`NETEASE_9_0_40_READY_FOR_TRADITIONAL_V5_CONSTRUCTION` 已完成真机闭环；
`:player-netease` 的官方 9.5.70、荣耀 3.5.20、修改版 9.0.40 三条内部路径均完成验收。

## 明确不做

- 不恢复词幕 / Lyricon / v4 直达广播（新模块）。
- 不把 9.0.40 构造路线套到网易云 9.5.70 主进程或 Honor；构造 profile 仅为
  `com.netease.cloudmusic:play`，对应已确认的 9.0.40 播放链。
- 不改写宿主 `setPlaybackState`。
- 不注入公开翻译 CustomAction。
- 不把罗马音写入翻译 lane。
- 设备等价验证完成后的仓库清理已删除旧 `:163-music`；9.0.40 构造链由
  `:player-netease` 独立承载。
- 不在本切片改 Bridge 统一发布 workflow。
