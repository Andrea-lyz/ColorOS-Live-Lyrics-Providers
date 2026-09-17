# KuWo 只追加 lyricInfo 的封面边界（4.1）

更新时间：2026-09-17。状态：**真机已验证**（PJZ110 + 酷我 12.2.0.0，debug 1.2.0(3) 测试包）。
定版：`kuwo-music` = versionName `1.2.0` / versionCode `3`，已同步
`release/v5-provider-matrix.json`。其他 Provider 本次不动（其封面稳定）。

## 结论

KuWo Provider 不再重建 metadata，也不再补图。封面链路完全交还给酷我原生：
Provider 只在宿主自己的 metadata bundle 里写入唯一一个 `lyricInfo` 键。

## 原生事实（静态确认）

1. 酷我原生发布位图封面：`cn.kuwo.mod.playcontrol.session.media.MediaSessionDelegate.buildMetaData`
   （`PlayerSource\Kuwo\jadx-lyric2\...\MediaSessionDelegate.java:230`，异常分支 :233 写 null），
   新实现见 `MediaSessionDirector.java:199`。字段共 9 个：TITLE / ARTIST / ALBUM / MEDIA_ID /
   DURATION / RATING / USER_RATING / ALBUM_ART_URI(http) / ALBUM_ART(bitmap)。
2. ColorOS 的封面来源顺序（`OplusMediaDataManagerExImpl.getArtWorkIconByMediaMetaData`，
   `PlayerSource\SystemUI\系统界面_16.99.12\java_src\com\oplus\systemui\media\controls\pipeline\OplusMediaDataManagerExImpl.java:396`）：
   `loadBitmapFromUri`（只接受 content / android.resource / file，见 `LegacyMediaDataManagerImpl.java:593`）
   → `METADATA_KEY_ART` → `METADATA_KEY_ALBUM_ART`；三者都拿不到时 artWorkIcon 为 null，媒体卡回落纯色。
   酷我的 http `ALBUM_ART_URI` 第一步必然失败，所以封面只可能来自 `ALBUM_ART` 位图。

## 为什么不允许重建

`MediaMetadata.Builder(existing)`（以及任何"复制全部键"的写法）会把封面位图重新 `putBitmap` 一次，
等于把封面通道交给 framework 的复制语义处理；一旦该边界不是无损的（LX 设备日志里出现过
512x512 varied → 1x1 solid），SystemUI 就拿不到位图，直接回落纯色。既然 Provider 的职责只有
`lyricInfo`，正确做法是**根本不进入这条边界**。

## 实现

- `KuWoMetadataBundle.kt`：反射取宿主 `MediaMetadata` 私有 `Bundle`（AOSP 名 `mBundle`，
  优先按名、否则按唯一非静态 Bundle 字段解析，`CandidateResolver` 保证不盲取），解析一次后缓存；失败即 fail-open。
- `KuWoLyricOverlayPolicy.kt`：纯决策表 `APPEND / CLEAR / NOOP`。
- `KuWoLyricInfoPublisher.kt`：命中当前曲 `putString("lyricInfo", json)`；串歌写 `""` 清 stale；
  值未变化不写。写入后 `MetadataParcelGuard.assess`，超限恢复原值并跳过。artwork、URI、rating、
  未知键一律不触碰，Bitmap 实例保持同一个。
- `KuWo.kt`：删除 artwork 抓取执行器、`scheduleArtworkCompletion` 及全部补图逻辑；
  立即发布改为重发宿主自己的 metadata 对象。
- 删除：`KuWoArtworkFetcher.kt`、`KuWoMetadataArtwork.kt` 及对应单测。合约第 8 节
  "不联网补封面"重新成立。
- 单测 26 项通过，`assembleDebug` 通过，`scripts\validate-v5-release-contract.ps1` 通过。

## 真机验证（2026-09-17，PJZ110，酷我 12.2.0.0）

| 观察点 | 模块关闭基线 | append-only 测试包 |
| --- | --- | --- |
| session metadata | `size=9` | `size=10`（+`lyricInfo`） |
| 封面通道 | 正常封面 | `album=360x360:ARGB_8888:bytes=518400:sample=varied`，`art=null`，`albumUri=http` 原样 |
| 覆盖事件 | — | `LYRIC_INFO_APPENDED chars=10475/2421`；无 `APPEND_UNSUPPORTED`、无 `OVERSIZE_SKIPPED` |
| SystemUI 消费 | — | `NATIVE_LYRIC_RECEIVED provider=cn.kuwo...` + `RECYCLER_ATTACHED` + `SET_CURRENT_LYRIC_GEOMETRY` |
| 封面表现 | 正常 | 正常（用户确认，切歌两首） |

日志：`logs\kuwo-artwork-baseline-off.txt`、`logs\kuwo-append-only-module-on.txt`。
测试包：`device-testing\kuwo-lyricinfo-append-only\`。

## 未决

- `mBundle` 反射已在本机通过（无 `LYRIC_INFO_APPEND_UNSUPPORTED`）；其他 ColorOS 版本仍以该事件为准。
- LX / Poweramp / Apple / 酷狗 / 网易 / QQ / Metrolist 仍有 `Builder(existing)` 重建路径，
  同一族风险面。本次按用户决定不动；如需统一，建议把本机制抽到 `provider-core` 共享。

