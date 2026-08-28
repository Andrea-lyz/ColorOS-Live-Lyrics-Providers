# 4.0 Phase 4 QQ 音乐迁移报告

记录日期：2026-08-27  
仓库：`ColorOS-Live-Lyrics-Providers`（分支 `4.0`）  
模块：`player-qq`  
逆向依据：`PlayerSource/QQMusic/QQ-MUSIC-NATIVE-LYRICINFO-INVESTIGATION.md`  
样本：QQ 音乐 `20.7.5.8`（`com.tencent.qqmusic`，versionCode 7308）  
方法模板：`:player-kugou` 官方 lyricInfo 追加、`:kuwo-music` 官方 encoder  
真机日志：`logs/lyrics-log-20260827-073906.txt`、`081124.txt`、`083716.txt`（PJZ110；用户确认通过）

## 交付边界

- 新建 `player-qq`，`namespace` / `applicationId` 均为
  `io.github.andrealtb.coloroslyrics.provider.qq`，`versionCode=1` /
  `versionName=1.0.0`。
- 只覆盖官方版 `com.tencent.qqmusic`，只 hook `:QQPlayerService`。
  不要 hook 主 UI 进程。不要改写宿主 `setPlaybackState`。
  翻译按钮仍走 Bridge 5 槽收藏覆盖，不注入公开 `ACTION_TOGGLE_TRANSLATION`。
- 官方 `lyricInfo` 已经有 `lyric` / `songName` / `artist` / `songId`，但
  `transLyric` 被硬编码为空、缺少 `rawLyric`。`QqOfficialLyricInfoEncoder`
  就地修补（`source=qqmusic-internal`）。不要改成
  `NativeLyricInfoPublisher` / Bridge envelope，也不要发送 v4 广播或挂载词幕。
- `RemoteLyricController#onLoadSuc` 暂存 `LyricLoadBean`（原文 `c()`、翻译
  `h()`）。`MediaSessionUpdateController#h`（DexKit：`lyricInfo` +
  `transLyric`，参数 Compat.Builder / SongInfo / `com.lyricengine.base.k`）
  afterHook 把修补后的 JSON `putString` 回官方 Builder。歌词晚到时平台
  `MediaSession#setMetadata` 每代最多 replay 一次。
- 罗马音（`LyricLoadBean.e()`）不得进入 `translationLyric` / `transLyric`。
- 空 typed `MediaMetadata.Builder()` 全量拷贝（禁止 `Builder(existing)`）。
  HARDWARE 或边长 >240px 的 bitmap 用 Canvas 重绘为 software ARGB_8888。
  不 HTTP 拉封面、不 snapshot、不发明封面。
- 调试开关复用 `provider-core`：`ProviderId.QQ`、prefs
  `qq_provider_debug_prefs`、`MODE_WORLD_READABLE` 写入 + Yuki
  `Context.prefs()` 读取。
- 已删除旧 `:qq-music` 与 `:qq-music-hd` 词幕模块。QQ HD
  （`com.tencent.qqmusicpad`）不在 4.0 适配范围。Bridge 已去掉 v4
  `lyricprovider/qq-music` 绑定，并从翻译设置 / `BRIDGE_PLAYER_PACKAGES`
  移除 HD。4.0 不适配 NPatch。旧 `LyricProvider` 仓库仍留给 3.x 发布。

## 运行时数据流

```text
TRACK_BOUND (SongInfo.H2 / 平台 metadata)
        ↓ generation++
RemoteLyricController#onLoadSuc
        ↓ LyricLoadBean.c() 原文 k + h() 翻译 k
MediaSessionUpdateController#h afterHook
        ↓ 修补 lyricInfo JSON（rawLyric / translationLyric / transLyric）
平台 MediaSession#setMetadata
        ↓ 空 Builder 拷贝；歌词晚到时每代最多 replay 一次
NATIVE_LYRICINFO_PATCHED
```

## Bridge 登记

- `PlayerTranslationSettings` 指向
  `io.github.andrealtb.coloroslyrics.provider.qq`，`supportsTranslation=true`，
  仅 `com.tencent.qqmusic`。
- `com.tencent.qqmusic` 留在 `BRIDGE_PLAYER_PACKAGES` 和
  `registeredProviderMayOverrideFavoriteActionWithTranslation`。
- 不为 QQ 新增 v4 `EXTERNAL_SOURCES`。不为 HD 保留翻译槽或历史播放器包名。
- Bridge 不再把 `QqMusicAdapter` 登记为 built-in player hook。

## 自动验证

```text
scripts\dev.cmd provider :player-qq:testDebugUnitTest :player-qq:assembleDebug
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
PROCESS_READY（com.tencent.qqmusic:QQPlayerService）
        ↓
TRACK_BOUND
        ↓
ON_LOAD_SUC_HOOKED / SEEDLING_HOOKED
        ↓
NATIVE_LYRICINFO_PATCHED
        ↓
Accepted bridge lyricInfo / NATIVE_LYRIC_RECEIVED
        ↓
锁屏 / AOD 可见逐字；外语歌可切翻译
```

## 真机收口

- `lyrics-log-20260827-073906.txt` Love Story：主词 54 行，翻译 supplemental 只有
  29 行。`mergeTranslation` 只用 `line.begin` 做 500ms 对齐，而 QQ 翻译轴经常落在
  开唱（首词）时间，QRC `t.b` 会早 600ms 或 2s。未匹配的翻译不会写入
  `translationLyric`。已改为行起点+首词双锚点、邻居间距/2（上限 1500ms）的单调 1:1
  对齐，并消费 `//` 占位。翻译时间戳仍写在 `line.begin`，不要改到首词时间
  （Bridge delayed attach 是 120ms）。
- `lyrics-log-20260827-081124.txt`：翻译 intro 抢跑后在主词开唱时回跳；副歌
  `It's a cruel summer` 在唱到之前占空白行、唱到时弹出且无逐字。Bridge
  `shouldHoldWordTimedReveal`（WORD_TIMED `findWordIndex < 0` 进度 0）+
  `findOfficialAliasLine` 先精确时间再文本（duplicateText 不用 radius-2）。
- `lyrics-log-20260827-083716.txt`：用户确认上述两点已好。句末逐字视觉拖慢是
  QRC 句末标签指向下一句首词，不是时间戳错；Bridge
  `lastWordRevealEndMillis` 按句内间隔中位数封顶 `[80, 400]` ms，再 hold 到
  下一句。不改存储时间戳 / `findWordIndex` / `inferWordLineEndMillis`。
- 2026-08-27 用户确认 QQ 官方版可收尾。不改写宿主 `setPlaybackState`，不注入
  公开 `ACTION_TOGGLE_TRANSLATION`。QQ HD 仍不在范围。

## 明确不做

- 不恢复词幕 / Lyricon / v4 直达广播。
- 不适配 QQ 音乐 HD，不新建 `player-qqhd`。`ProviderId.QQHD` 已从
  `provider-core` 删除。
- 不改写宿主 `setPlaybackState`。
- 不注入公开翻译 CustomAction。
- 不把罗马音写入翻译 lane。
- 不在本切片改 Bridge 统一发布 workflow（仍从旧 `LyricProvider` 编
  `:qq-music`）；4.0 正式发布时再切到本仓库 `:player-qq`。
