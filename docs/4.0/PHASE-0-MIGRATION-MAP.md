# 4.0 Phase 0 Provider 迁移映射

记录日期：2026-08-24  
来源仓库：`LyricProvider` `master`，commit `292a7da3f88a87e8c6df6b4ae4f56455b6856c72`  
当前工作分支：`4.0`  
状态：Phase 0 盘点表保留为迁移对照；2026-08-28 v5 矩阵和旧仓库清理全部完成。
2026-08-26 起 `kuwo-music` 已在原 module 上落地 4.0
`applicationId`/`namespace` `io.github.andrealtb.coloroslyrics.provider.kuwo`、
`1.0.0 (1)`，并移除词幕 Lyricon 挂载；Gradle 模块名仍为 `:kuwo-music`。同日新建
`:player-lx`（`io.github.andrealtb.coloroslyrics.provider.lx`）；旧 `:lx-music`
词幕模块已删除。2026-08-26 新建 `:player-poweramp`
（`io.github.andrealtb.coloroslyrics.provider.poweramp`）；旧 `:poweramp-music`
词幕模块已删除。2026-08-27 新建 `:player-metrolist`
（`io.github.andrealtb.coloroslyrics.provider.metrolist`）；旧 `:metrolist-music`
词幕模块已删除。2026-08-27 新建 `:player-kugou`
（`io.github.andrealtb.coloroslyrics.provider.kugou`）；旧 `:kugou-music`
词幕模块已删除。2026-08-27 新建 `:player-qq`
（`io.github.andrealtb.coloroslyrics.provider.qq`）；旧 `:qq-music` 与
`:qq-music-hd` 词幕模块已删除。2026-08-27 新建 `:player-netease`
（`io.github.andrealtb.coloroslyrics.provider.netease`）；官方 9.5.70 主进程
追加原生 `lyricInfo`；2026-08-27 同模块扩展 Honor 3.5.20 `:play` 原生追加，并由
同一模块承载 9.0.40 构造路线；旧 `:163-music` 已删除。2026-08-27 新建 `:player-apple`
（`io.github.andrealtb.coloroslyrics.provider.apple`）；旧 `:apple-music`
词幕模块已删除。2026-08-28 PJZ110 真机收口
`lyrics-log-20260828-010237.txt`。2026-08-28 新建 `:player-spotify`
（`io.github.andrealtb.coloroslyrics.provider.spotify`）；旧 `:spotify-music`
词幕模块已删除。QQ HD 不在 4.0 适配范围。

## 1. 新 applicationId / namespace 规则

目标格式统一为：

```text
io.github.andrealtb.coloroslyrics.provider.<player>
```

namespace、applicationId 和 Kotlin/Java 根包在迁移后保持同一 `<player>` 后缀。
QQ HD、MusicFree、Gramophone、Symfonium 不属于最终 v5 发布矩阵。

## 2. 现有 application module 映射

| 旧 module | 旧 applicationId / namespace | 旧版本 | 旧 Xposed scope（原始值） | 4.0 目标 module | 4.0 目标 applicationId / namespace |
| --- | --- | --- | --- | --- | --- |
| `163-music` | `io.github.proify.lyricon.cmprovider` | `17 / 1.1.7` | `com.netease.cloudmusic`, `com.hihonor.cloudmusic`, `$syllable`, `$translation` | `player-netease` | `io.github.andrealtb.coloroslyrics.provider.netease`（旧 module 已删除） |
| `apple-music` | `io.github.proify.lyricon.amprovider` | `12 / 1.1.4` | `com.apple.android.music`, `$syllable`, `$translation` | `player-apple` | `io.github.andrealtb.coloroslyrics.provider.apple`（旧 module 已删除） |
| `qq-music` | `io.github.proify.lyricon.qmprovider` | `11 / 1.1.6` | `com.tencent.qqmusic`, `$syllable`, `$translation` | `player-qq` | `io.github.andrealtb.coloroslyrics.provider.qq`（旧 module 已删除） |
| `qq-music-hd` | `io.github.proify.lyricon.qmhdprovider` | `1 / 1.0.0` | `com.tencent.qqmusicpad` | — | 4.0 不适配，词幕 module 已删除 |
| `kugou-music` | `io.github.proify.lyricon.kgprovider` | `15 / 1.2.0` | `com.kugou.android`, `com.kugou.android.lite`, `$syllable`, `$translation` | `player-kugou` | `io.github.andrealtb.coloroslyrics.provider.kugou`（旧 module 已删除） |
| `kuwo-music` | `io.github.proify.lyricon.kwprovider` | `12 / 2.1.0` | `cn.kuwo.player`, `车载歌词` | `player-kuwo` | `io.github.andrealtb.coloroslyrics.provider.kuwo` |
| `spotify-music` | `io.github.proify.lyricon.spotifyprovider` | `12 / 1.1.6` | `com.spotify.music` | `player-spotify` | `io.github.andrealtb.coloroslyrics.provider.spotify`（旧 module 已删除） |
| `lx-music` | `io.github.proify.lyricon.lxprovider` | `5 / 1.0.3` | `cn.toside.music.mobile`, `com.lxwalnut.music.mobile`, `$translation` | `player-lx` | `io.github.andrealtb.coloroslyrics.provider.lx`（旧 module 已删除） |
| `poweramp-music` | `io.github.proify.lyricon.paprovider` | `11 / 1.0.14` | `com.maxmpz.audioplayer`, `$syllable`, `$translation` | `player-poweramp` | `io.github.andrealtb.coloroslyrics.provider.poweramp`（旧 module 已删除） |
| `salt-player-music` | `io.github.proify.lyricon.saltprovider` | `4 / 1.0.3` | `com.salt.music` | `player-salt` | `io.github.andrealtb.coloroslyrics.provider.salt`（旧 module 已删除） |
| `qishui-music` | `io.github.proify.lyricon.qishuiprovider` | `10 / 1.2.7` | `com.luna.music`, `$syllable`, `$translation` | `player-qishui` | `io.github.andrealtb.coloroslyrics.provider.qishui`（旧 module 已删除） |
| `music-free` | `io.github.proify.lyricon.musicfreeprovider` | `4 / 1.0.0` | `fun.upup.musicfree`, `桌面歌词` | — | 不在 v5 矩阵，旧 module 已删除 |
| `gramophone` | `io.github.proify.lyricon.gramophoneprovider` | `1 / 1.0.0` | `org.akanework.gramophone` | — | 不在 v5 矩阵，旧 module 已删除 |
| `symfonium` | `io.github.proify.lyricon.symfoniumprovider` | `1 / 1.0.0` | `app.symfonik.music.player`, `$syllable`, `$translation` | — | 不在 v5 矩阵，旧 module 已删除 |
| `metrolist-music` | `io.github.proify.lyricon.metrolistprovider` | `1 / 1.0.0` | `com.metrolist.music`, `lyrics`, `metrolist`, `youtube-music` | `player-metrolist` | `io.github.andrealtb.coloroslyrics.provider.metrolist`（旧 module 已删除） |

## 3. 纯词幕交付物

这些模块没有对应的 4.0 v5 player Provider：

| 旧 module | 处理 |
| --- | --- |
| `cloud-provider` | 删除；在线搜索/通用词幕交付不再由 4.0 Provider 套件承载 |
| `meizu-provider` | 删除；用户需要词幕功能时使用词幕官方 Provider |
| `car-provider` | 删除；车载词幕功能不再作为独立词幕 Provider 交付 |
| `share:meizu-provider` | 删除，除非后续证明有 v5 原生 metadata 代码可迁入中立 core |
| `share:car-provider` | 删除，除非后续证明有 v5 原生 metadata 代码可迁入中立 core |

最终清理已完成引用盘点和备份；许可证、`NOTICE` 与历史报告保留。

## 4. 旧 module → 4.0 module 候选映射

| 旧 module | 4.0 候选 | 说明 |
| --- | --- | --- |
| `share:extensions-kt` | KuWo/NetEase compatibility | 仍被 v5 helper 引用，保留为非 application 模块 |
| `share:extensions-android` | KuWo compatibility | 仍被 `:kuwo-music` 引用，保留 |
| `share:lrckit` | KuWo/NetEase compatibility | 仍被现有 DTO/下载链引用，保留 |
| `share:qrckit` | `parser-qrc` | v5 无直接引用，旧 share 已删除 |
| `share:yrckit` | NetEase eAPI compatibility | 仍被 `:player-netease` 引用，保留 |
| `share:krckit` | `parser-krc` | v5 无直接引用，旧 share 已删除 |
| `share:cloudlyric` | — | v5 无直接引用，旧 share 已删除 |
| `apple-music` | `player-apple` | 旧词幕 module 已删除；JNI TTML 构造原生 `lyricInfo`；翻译走 5 槽收藏覆盖；真机收口 `lyrics-log-20260828-010237.txt` |
| `163-music` | `player-netease` | 官方 9.5.70、Honor 3.5.20 与 9.0.40 三条路径均由同一 v5 APK 承载；旧 module 已删除 |
| `qq-music` | `player-qq` | 旧词幕 module 已删除；仅官方 `com.tencent.qqmusic`，`:QQPlayerService` 追加原生 `lyricInfo`；QQ HD 不在 4.0 范围；真机收口 `lyrics-log-20260827-083716.txt` |
| `kugou-music` | `player-kugou` | 旧词幕 module 已删除；普通与 Lite 共用 `:player-kugou`，写入原生 `lyricInfo`；两宿主都只 hook `:support` / `.support`；真机收口 `lyrics-log-20260827-063251.txt` |
| `kuwo-music` | `player-kuwo` | 原生 `lyricInfo`、封面和同曲模型边界独立保留 |
| `spotify-music` | `player-spotify` | 旧词幕 module 已删除；嗅探 OkHttp 头请求 Color Lyrics，构造原生 `lyricInfo`；不支持翻译；不发送 v4 广播 |
| `lx-music` | `player-lx` | 旧词幕 module 已删除；LX / Walnut 共用 `:player-lx`，scope 按宿主隔离 |
| `poweramp-music` | `player-poweramp` | 旧词幕 module 已删除；同目录 `.lrc` 优先于内嵌标签，写入原生 `lyricInfo` |
| `salt-player-music` | `player-salt` | Root/LSPosed v5 已替代旧 module；旧 module 已删除 |
| `qishui-music` | `player-qishui` | Root/LSPosed v5 已真机收口：宿主 `TrackLyric` 优先，NetCache/SQLite 回退，标准 `lyricInfo`、VIP 时钟/逐字视觉和翻译按钮均通过；旧 module/source 已删除；不适配 NPatch |
| `music-free` | — | 不属于最终 v5 矩阵，旧 module 已删除 |
| `gramophone` | — | 不属于最终 v5 矩阵，旧 module 已删除 |
| `symfonium` | — | 不属于最终 v5 矩阵，旧 module 已删除 |
| `metrolist-music` | `player-metrolist` | 旧词幕 module 已删除；切歌后主动拉取 BetterLyrics/LrcLib/KuGou，写入原生 `lyricInfo`；不支持翻译 |

## 5. v4 → v5 数据边界映射

| 3.x/v4 入口 | 4.0 目标 | Phase 0 处理 |
| --- | --- | --- |
| `SystemUiBroadcastSender.submit` / `submitWithLyricLineFallback` | `NativeLyricInfoPublisher.publish` | v5 矩阵已完成替换 |
| `EXTERNAL_LYRIC_DIRECT_V4` 私有广播 | 播放器自己的 `MediaMetadata["lyricInfo"]` | Provider 仓库已无 v4 sender |
| `LyriconFactory.createProvider` | `RuntimeModeResolver` + player hook + v5 publisher | 发布 application 已全部移除 |
| `LyriconProvider.player.setSong` | track identity/generation + native metadata publish | 发布 application 已全部移除 |
| `setPlaybackState` / `setPosition` 词幕提交 | 使用宿主 MediaSession 的播放状态和位置 | 不把旧词幕状态调用直接搬进 v5 |
| `source` / `senderKind` / Provider applicationId 白名单 | player profile、宿主 scope 和运行模式诊断 | 不迁入 Bridge；未改旧 Bridge |
| `external-lyric-protocol` fixture/Parcel | provider 内部的有界 payload 与完整 metadata fail-open | 后续 Phase 1/5 再定义和删除 |

v5 具体契约仍以 `todo.md` 第 3 节为准：只写播放器自己的 `lyricInfo`，保留其它 metadata，区分 WORD/LINE/UNTIMED/INVALID，并对 display/raw/translation 做有界单调对齐。

## 6. 4.0 新仓库边界

- 新仓库从旧 `LyricProvider` 的 tracked source 建立，不带旧仓库的 remote；旧仓库不作为新仓库的工作树继续修改。
- 最终仓库只包含 v5 发布 application、中立 core/parser 和仍被 v5 使用的兼容 helper。
- 非矩阵 application、未使用 share、Lyricon Provider runtime 与 v4 sender 已删除。
- 4.0 不适配 NPatch。
