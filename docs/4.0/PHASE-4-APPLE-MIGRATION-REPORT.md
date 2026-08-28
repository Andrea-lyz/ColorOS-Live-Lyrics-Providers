# 4.0 Phase 4 Apple Music 迁移报告

记录日期：2026-08-27；真机收口 2026-08-28  
仓库：`ColorOS-Live-Lyrics-Providers`（分支 `4.0`）  
模块：`player-apple`  
逆向依据：`PlayerSource/Apple Music/APPLE-MUSIC-TRADITIONAL-LYRICINFO-INVESTIGATION.md`  
方法模板：`:player-metrolist`、`:player-poweramp`、`:player-lx`  
真机日志：`lyrics-log-20260828-002416.txt`、`004404.txt`、`010237.txt`（PJZ110；用户确认通过）

## 交付边界

- 新建 `player-apple`，`namespace` / `applicationId` 均为
  `io.github.andrealtb.coloroslyrics.provider.apple`，`versionCode=1` /
  `versionName=1.0.0`。
- LSPosed scope / 调试页目标播放器仅 `com.apple.android.music`（单主进程）。
- 宿主 **没有** 官方 `lyricInfo`。由 `PlayerLyricsViewModel.loadLyrics` 触发
  JNI/`libttml.so` 解析后，经 `NativeLyricInfoPublisher` 构造写入
  （`source=com.apple.android.music-v5`）。
- 切歌身份以 adamId 为准。队列预取的下一首 `PlaybackItem` 不得换代。
  `loadLyrics` 必须等到匹配的 `PlaybackItem`；禁止只靠第一帧
  `setMetadata` 开请求。
- 空 typed Builder 拷贝 metadata；HARDWARE / 超 240px bitmap Canvas 重绘。
  pending `lyricInfo` 附着到同一次 host `setMetadata`。忽略 Cast session。
- 不注入 `ACTION_TOGGLE_TRANSLATION`。翻译按钮走 Bridge 5 槽收藏覆盖
  （AM 评分式爱心保留宿主图标）。
- 调试开关复用 `provider-core`：`ProviderId.APPLE`、prefs
  `apple_provider_debug_prefs`、`MODE_WORLD_READABLE` 写入 + Yuki
  `Context.prefs()` 读取。
- 已删除旧 `:apple-music` 词幕模块，以及 Bridge v4 `EXTERNAL_SOURCES`
  （`lyricprovider/apple-music`）和 `AppleMusicAdapter`。4.0 不适配 NPatch。
  旧 `LyricProvider` 仓库的 `:apple-music` 仍留给 3.x 发布，不是本切片编辑目标。

## 运行时数据流

```text
PlaybackItem mapper（DexKit MEDIA_ID + PLAYBACK_ENDPOINT_TYPE）
        ↓
adamId 缓存 PlaybackItem；与当前 session 身份不一致则不换代。
session 已有歌名但尚无 adamId 时，不得跟随队列下一首。
禁止在专用 PlayerLyricsViewModel 上预热下一首 TTML。
        ↓
MediaSession#setMetadata → TrackIdentity（权威身份；同曲后到的 adamId 合并进当前代）
        ↓
按 adamId 或 title/artist 命中缓存 PlaybackItem
命中磁盘/内存缓存 → RichLyricLine
未命中 → PlayerLyricsViewModel.loadLyrics(PlaybackItem)
仅当 live session 身份与解析结果一致才 overlay lyricInfo
        ↓
buildTimeRangeToLyricsMap(SongInfoPtr)
        ↓
setTranslation(system language) → 遍历 Sections/Lines/Words
        ↓
过滤和声/短拟声；罗马音不得进翻译 lane
相邻无空格拉丁音节合并（Gal+way → Galway），CJK 不合并
        ↓
NativeLyricInfoPublisher → session.setMetadata
（Apple https URI 即可叠加，不等待后续 Glide bitmap）
```

切歌空白、中间曲串 overlay、拉丁音节拆词的根因与修复见下方真机收口。

## Bridge 登记

- `PlayerTranslationSettings` 指向
  `io.github.andrealtb.coloroslyrics.provider.apple`，`supportsTranslation=true`。
- `com.apple.android.music` 留在 `BRIDGE_PLAYER_PACKAGES` 与
  `registeredProviderMayOverrideFavoriteActionWithTranslation`。
- 不为 Apple Music 新增 v4 `EXTERNAL_SOURCES`。
- 不恢复 Bridge 进程内 `AppleMusicAdapter` / first-batch 播放器 hook。

## 自动验证

```text
scripts\dev.cmd provider :parser-lrc:test :parser-ttml:test :player-apple:testDebugUnitTest :player-apple:assembleDebug
scripts\dev.cmd bridge testDebugUnitTest
```

2026-08-28 `:parser-lrc:test`、`:parser-ttml:test`、`:player-apple:testDebugUnitTest`
与 `:player-apple:assembleDebug` BUILD SUCCESSFUL。卸载旧
`io.github.proify.lyricon.amprovider` 后，LSPosed 启用新包并勾选
`com.apple.android.music`。

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
TRACK_BOUND reason=playback-item|metadata（带 adamId / title）
        ↓
LYRIC_REQUESTED 或 LYRIC_CACHE_HIT
        ↓
APPLE_HOST_METADATA_LYRIC_INFO_ATTACHED / APPLE_FINAL_PUBLISHED
        ↓
Accepted bridge lyricInfo / NATIVE_LYRIC_RECEIVED
        ↓
锁屏 / AOD 可见逐字；翻译开时 translationChars>0；切歌不得串曲
```

## 真机收口

- `lyrics-log-20260828-002416.txt`：《I Knew It, I Knew You》切歌后约 7.4s
  才 `NATIVE_LYRIC_RECEIVED`，同场缓存命中 0.3–0.5s。SystemUI 最终
  `WORD_TIMED`（47 行）。空白来自 Provider 等 Glide bitmap，不是 Bridge
  recycler gate。已改为 https URI 即可叠加 `lyricInfo`，专用
  `PlayerLyricsViewModel`、主线程 `loadLyrics`、PlaybackItem 200ms 轮询、
  400ms/1.2s 重试。
- `lyrics-log-20260828-004404.txt`：中间曲清空后约 20s 无新 payload，随后
  同一 session 收到 `[ti:Look What You Made Me Do]`，锁屏仍是
  《I Knew It, I Knew You》。title-only metadata 把空 adamId 当成可跟随
  任意队列 `PlaybackItem`，且专用 VM 预取下一首取消了当前 `loadLyrics`。
  已改为 `AppleTrackBindPolicy` 按完整身份跟随、禁止专用 VM 预取、
  title/artist 查找缓存项；live session 与解析结果不一致只缓存不 overlay。
- `lyrics-log-20260828-010237.txt`：Red 专辑连续切歌，`[ti:]` 与落地曲
  一致，约 300–400ms。用户确认切歌身份与锁屏歌词正常。
- 2026-08-28 用户确认拉丁音节拆词已好：Apple JNI 的 `Gal`+`way` 经
  `LatinSyllableSpanMerger` 合成 `Galway` 后再写 enhanced LRC；空白
  token / 下一词前导空格为词界；CJK 不合并。不改 Bridge 的 ASCII 插空格。
- 不改写宿主 `setPlaybackState`，不注入公开 `ACTION_TOGGLE_TRANSLATION`
  （AM 评分式爱心保留宿主图标）。NPatch 仍不在范围。

结论：`:player-apple` 构造原生 `lyricInfo` 已完成真机闭环。

## 明确不做

- 不恢复词幕 / Lyricon / v4 直达广播 / ExoPlayer 位置轮询。
- 不注入公开翻译按钮，不覆盖桌面歌词槽。
- 不 HTTP 拉封面，不改写宿主 `setPlaybackState`。
- 不在本切片改 Bridge 统一发布 workflow（仍从旧 `LyricProvider` 编
  `:apple-music`）；4.0 正式发布时再切到本仓库 `:player-apple`。
- 不删除 Bridge 里旧 v4 `AppleMusicExternalLyricAdaptation`：
  `lyricprovider/apple-music` 已不在 `EXTERNAL_SOURCES`，运行期不会进入；
  清扫属于独立 Bridge 切片。
- 不为 Apple Music 恢复 ExoPlayer 500ms 进度轮询或 hitchhike 官方歌词页
  `PlayerLyricsViewModel`。
