# 4.0 Phase 4 Spotify 迁移报告

记录日期：2026-08-28；真机收口 2026-08-28  
仓库：`ColorOS-Live-Lyrics-Providers`（分支 `4.0`）  
模块：`player-spotify`  
逆向依据：`PlayerSource/Spotify/SPOTIFY-CONSTRUCTED-LYRICINFO-INVESTIGATION.md`  
样本：`spotify-9-1-78-2208.apk`（SHA-256 `0EA5AFA7E86CF44CBBA397AEDF8DF498C736EE067AA2294866EBC1F420C3F720`）  
方法模板：`:player-apple`（constructed `lyricInfo`）与 `:player-metrolist`（独立拉词 + pending 附着）  
真机日志：`lyrics-log-20260828-063100.txt`、`lyrics-log-20260828-064938.txt`（PJZ110；用户确认重启 SystemUI 后通过）

## 交付边界

- 新建 `player-spotify`，`namespace` / `applicationId` 均为
  `io.github.andrealtb.coloroslyrics.provider.spotify`，`versionCode=1` /
  `versionName=1.0.0`。
- LSPosed scope / 调试页目标播放器仅 `com.spotify.music` 主进程。
- 全 DEX 无 `"lyricInfo"` / `METADATA_KEY_LYRICS`。走
  `NativeLyricInfoPublisher`（`source=com.spotify.music-v5`）。
- Color Lyrics `GET /color-lyrics/v2/track/{id}`，鉴权来自嗅探到的
  OkHttp `authorization` / `client-token` / `user-agent` / `x-client-id`。
  不得把 token 写入日志。冷启动头未齐用有界退避等待；等待耗尽不得把该代记成
  `NO_LYRIC`，等嗅探头变齐后再拉一次。401 丢弃鉴权并重试一次；
  404 记 `NO_LYRIC`，禁止无限重试。
- `transliteratedWords` 是音译/罗马音，禁止写入 `translationLyric`。
  `endTimeMs == 0` 用下一行起点或 +5s。`SYLLABLE_SYNCED` 先做
  `LatinSyllableSpanMerger`。
- 广告与播客不得拉词、不得叠加。广告/换曲时剥离过期模块 `lyricInfo`。
- `lyricInfo` 经空 Builder 全量拷贝后写入。URI-only 封面即可叠加；
  HARDWARE / 超 240px Canvas 重绘。pending 附着到同一次 host `setMetadata`。
  后续 drain 只看 live `controller.metadata`。忽略 Cast session。
- 不支持翻译，不注入 `ACTION_TOGGLE_TRANSLATION`，不改写
  `setPlaybackState`，不发送 v4 广播，不挂载词幕。
- 调试开关复用 `provider-core`：`ProviderId.SPOTIFY`、prefs
  `spotify_provider_debug_prefs`、`MODE_WORLD_READABLE` 写入 + Yuki
  `Context.prefs()` 读取。
- 已删除旧 `:spotify-music` 词幕模块，以及 Bridge v4 `EXTERNAL_SOURCES`
  （`lyricprovider/spotify-music`）。4.0 不适配 NPatch。旧 `LyricProvider`
  仓库的 `:spotify-music` 仍留给 3.x 发布，不是本切片编辑目标。

## 运行时数据流

```text
okhttp3.Headers 构造 → 缓存 authorization / client-token / …
        ↓
MediaSession.setMetadata（spotify:track:id）
        ↓
TrackGenerationPolicy（广告/播客忽略；同曲填空不换代）
        ↓
SpotifyLyricFetchGate（每代最多一次；身份不完整不 latch）
        ↓
DiskCache → Color Lyrics API
        ↓
SpotifyLyricDecoder（LINE_SYNCED / SYLLABLE_SYNCED）
        ↓
pending 附着 incoming host metadata
        ↓
NativeLyricInfoPublisher → session.setMetadata
```

## Bridge 登记

- `PlayerTranslationSettings` 指向
  `io.github.andrealtb.coloroslyrics.provider.spotify`，`supportsTranslation=false`。
- `com.spotify.music` 留在 `BRIDGE_PLAYER_PACKAGES`。
- 不加入 `registeredProviderMayOverrideFavoriteActionWithTranslation`。
- 不为 Spotify 新增 v4 `EXTERNAL_SOURCES`。
- 已删除 `SpotifyExternalLyricAdaptation` 及其在 `loadLyricInBg` 的调用。
  4.0 走原生 `lyricInfo`，不要恢复 v4 `lyricReady` 等待。

## 自动验证

2026-08-28 已通过：

```text
scripts\dev.cmd provider :player-spotify:testDebugUnitTest :player-spotify:assembleDebug
scripts\dev.cmd bridge testDebugUnitTest
```

冷启动头未齐时 `LYRIC_HEADERS_MISSING` 会解开该代 latch，等嗅探头变齐（`headers-ready`）后再拉一次；404 仍记 `NO_LYRIC` 且不再重试。

设备验收梯子：

```text
RUNTIME_MODE_RESOLVED
        ↓
DEBUG_CONFIG_APPLIED（reason=enabled 才继续查 DEBUG 事件）
        ↓
DEBUG_LOGGING_ENABLED
        ↓
PROCESS_READY
        ↓
OKHTTP_HEADERS_HOOK_INSTALLED
        ↓
TRACK_BOUND reason=metadata id=spotify:track:…
        ↓
LYRIC_FETCH_STARTED
        ↓
LYRIC_INFO_PUBLISHED / NATIVE_LYRIC_INFO_COMMITTED
        ↓
SystemUI WORD_TIMED 或 LINE_TIMED（translationChars=0）
```

卸载旧 `io.github.proify.lyricon.spotifyprovider` 后再装新包。装模块后必须重启
SystemUI；热替换残留进程不能当验收。

## 真机收口

- `lyrics-log-20260828-063100.txt`：模块热装后未重启 SystemUI。PID `16678`
  日志从中途开始，无 Hook 安装。全程 `playing=false`，进度卡在 `11272`
  再跳到 `0`。当前行看起来像未激活、keep-awake 被
  `Skip screen timeout wake lock … playing=false` 跳过。两处症状同源：
  `lastPlaybackIsPlaying = false`。不要据此改写宿主 `setPlaybackState`，
  也不要从 BUFFERING / `position=-1` 发明 PLAYING。
- `lyrics-log-20260828-064938.txt`：重启 SystemUI 后 PID `14096`。
  `playing=true`，进度约每 3s 前进。当前行 `active=true, focused=true`，
  keep-awake `Pulsed screen timeout user activity`。Color Lyrics
  `LINE_SYNCED` 落地为 SystemUI `LINE_TIMED`（`translationChars=0`）是预期；
  前奏 `active=false, scaleActiveIndex=-1` 直到第一句时间戳，是
  `isBeforeFirstProgressStart`。用户确认高亮与常亮都恢复。
- 不改写宿主 `setPlaybackState`，不注入公开 `ACTION_TOGGLE_TRANSLATION`。
  NPatch 仍不在范围。

结论：`:player-spotify` 构造原生 `lyricInfo` 已完成真机闭环。安装后重启
SystemUI。

## 明确不做

- 不恢复词幕 / Lyricon / v4 直达广播 / `SpotifyExternalLyricAdaptation`
  `lyricReady` 等待。
- 不注入公开翻译按钮，不覆盖收藏槽。
- 不 HTTP 拉封面，不改写宿主 `setPlaybackState`。
- 不在本切片改 Bridge 统一发布 workflow（仍从旧 `LyricProvider` 编
  `:spotify-music`）；4.0 正式发布时再切到本仓库 `:player-spotify`。
- 不把 `SpotifyHostGenerationController` 提升到 `provider-core`；Apple /
  Metrolist 同形副本仍各自保留。
- 冷启动头嗅探、Token 刷新 401 恢复、Spotify Connect、广告与播客忽略
  仍按代码路径覆盖；Connect 与 401 未单独抓日志。
