# ColorOS Live Lyrics Providers 4.0

这是从旧 `LyricProvider` 独立出来的 4.0 Provider 仓库。Phase 0 的初始 source commit 为旧仓库 `master` 的 `292a7da3f88a87e8c6df6b4ae4f56455b6856c72`；当前分支为 `4.0`。

Phase 1 的中立 core/parser/diagnostics 与 NPatch 清理已经完成；Phase 2 的 Salt/Cone Root Provider
已完成，并已删除 Bridge 的 Salt/Cone legacy scope 和 adapter。Phase 4 已完成 `player-lx`
（LX Music / Walnut 共用，宿主仅 `cn.toside.music.mobile` 与 `com.lxwalnut.music.mobile`）、
`player-poweramp`（宿主 `com.maxmpz.audioplayer`）、`player-metrolist`
（宿主仅 `com.metrolist.music`）、`player-kugou`
（酷狗标准版 / 概念版共用，真机收口 `lyrics-log-20260827-063251.txt`）、`player-qq`
（仅官方 `com.tencent.qqmusic`，`:QQPlayerService` 追加原生 `lyricInfo`，真机收口
`lyrics-log-20260827-083716.txt`）、`player-netease`
（官方 9.5.70 / Honor 3.5.20 / 9.0.40 真机收口）与 `player-apple`
（宿主 `com.apple.android.music`，JNI TTML 构造原生 `lyricInfo`，真机收口
`lyrics-log-20260828-010237.txt`）与 `player-spotify`
（宿主 `com.spotify.music`，Color Lyrics 构造原生 `lyricInfo`，真机收口
`lyrics-log-20260828-064938.txt`）。`player-qishui`
（宿主 `com.luna.music`，复用内部 `TrackLyric` 构造原生 `lyricInfo`）已完成本地实现，
尚待真机验证。旧
`:lx-music` / `:poweramp-music` / `:metrolist-music` / `:kugou-music` /
`:qq-music` / `:qq-music-hd` / `:apple-music` / `:spotify-music` / `:qishui-music`
词幕模块、非 v5 矩阵 application、旧 `:163-music` / `:salt-player-music` 与对应
Bridge v4 `EXTERNAL_SOURCES` 已删除。仓库现在只保留 12 个设备验证通过的 v5
Provider、它们依赖的中立 core/parser，以及 KuWo/NetEase 尚在使用的兼容 helper。
实施顺序、门禁和 v4→v5 边界见
[`PHASE-0-MIGRATION-MAP.md`](PHASE-0-MIGRATION-MAP.md)。
LX 实施记录见 [`PHASE-4-LX-MIGRATION-REPORT.md`](PHASE-4-LX-MIGRATION-REPORT.md)。
Poweramp 实施记录见 [`PHASE-4-POWERAMP-MIGRATION-REPORT.md`](PHASE-4-POWERAMP-MIGRATION-REPORT.md)。
Metrolist 实施记录见 [`PHASE-4-METROLIST-MIGRATION-REPORT.md`](PHASE-4-METROLIST-MIGRATION-REPORT.md)。
酷狗实施记录见 [`PHASE-4-KUGOU-MIGRATION-REPORT.md`](PHASE-4-KUGOU-MIGRATION-REPORT.md)。
QQ 音乐实施记录见 [`PHASE-4-QQ-MIGRATION-REPORT.md`](PHASE-4-QQ-MIGRATION-REPORT.md)。
网易云官方现行实施记录见 [`PHASE-4-NETEASE-MIGRATION-REPORT.md`](PHASE-4-NETEASE-MIGRATION-REPORT.md)。
Apple Music 实施记录见 [`PHASE-4-APPLE-MIGRATION-REPORT.md`](PHASE-4-APPLE-MIGRATION-REPORT.md)。
Spotify 实施记录见 [`PHASE-4-SPOTIFY-MIGRATION-REPORT.md`](PHASE-4-SPOTIFY-MIGRATION-REPORT.md)。
汽水音乐实施记录见 [`PHASE-4-QISHUI-MIGRATION-REPORT.md`](PHASE-4-QISHUI-MIGRATION-REPORT.md)。
最终仓库清理见 [`REPOSITORY-CLEANUP-REPORT.md`](REPOSITORY-CLEANUP-REPORT.md)。

新仓库没有配置旧 LyricProvider remote，避免 4.0 变更误推送到旧发布仓库。许可证、第三方来源和历史署名继续保留。
