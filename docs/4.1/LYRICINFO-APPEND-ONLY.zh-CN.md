# lyricInfo 只追加机制推广（4.1）

更新时间：2026-09-26。状态：**已实现；本地构建与单测通过；需要设备验证**。KuWo 以外的
Provider 尚无设备证据。

来源：`KUWO-LYRICINFO-APPEND-ONLY.zh-CN.md`（KuWo 设备验证记录，PJZ110 + 酷我 12.2.0.0）。

## 范围

| 状态 | Provider |
| --- | --- |
| 已迁移 | KuWo（改用共享层，行为不变）、LX、Poweramp、Apple Music、Metrolist、酷狗、网易云、QQ 音乐 |
| 未迁移，同一族风险 | Spotify、汽水（空 typed Builder 复制 + `stripModuleLyricInfo`）、Cone、Salt（`MediaMetadata.Builder(existing)`） |

## 为什么不允许重建

ColorOS 的锁屏封面只取宿主发布的 bitmap 通道（`OplusMediaDataManagerExImpl.getArtWorkIconByMediaMetaData`：
`loadBitmapFromUri` → `METADATA_KEY_ART` → `METADATA_KEY_ALBUM_ART`）。`MediaMetadata.Builder(existing)`
与空 typed Builder 逐键复制都会把 bitmap 交给 framework 复制语义；LX 设备日志里出现过
512x512 varied → 1x1 solid。各 Provider 原先的 HARDWARE / >240px Canvas 重绘，只是为了绕开这次复制。
Provider 的职责只有 `lyricInfo`（LX 另有蓝牙歌词身份还原），正确做法是根本不进入这条边界。

## 共享层（`provider-core` `publisher/`）

- `HostMetadataBundle`：反射宿主 `MediaMetadata` 私有 `Bundle`，由 `KuWoMetadataBundle` 迁来。
  优先取 `mBundle`，否则取唯一的非静态 Bundle 字段；有歧义时返回 null。provider-core 不依赖
  reflection-core，因此不使用 `CandidateResolver`。
- `HostMetadataOverlay`：
  - `putLyricInfo`：写入后做 parcel 守卫，超限或无法测量时恢复原值（原先没有该键时删除）。
  - `clearLyricInfo`：非空时写 `""`。
  - `putText`：按 `Builder.putString` 的方式写一个文本键，供 LX 身份还原使用。
  - `hostLyricInfo`：返回宿主自己放在该对象上的值。按对象身份（弱引用）记住最近 8 个对象
    第一次模块写入前的值。
  - 所有写入走进程内同一把锁串行，值不变时不写。
- `NativeLyricInfoPublisher.publishToHostMetadata`：检查项与 `publishToPlatformMetadata` 完全相同
  （输入、宿主包名、generation / 身份、编码、字段长度），然后原地写入。结果映射：
  WRITTEN / UNCHANGED → `PUBLISHED`，UNSUPPORTED → 新增的 `APPEND_UNSUPPORTED`，
  超限 → `PAYLOAD_TOO_LARGE`，无法测量 → `PARCEL_MEASUREMENT_FAILED`。
- `MetadataParcelGuard`：`acceptOrOriginal` 与 `assess` 已无调用方，删除。

## 各 Provider 的变化

| Provider | 宿主 `setMetadata` hook | 异步补发 | 删除 |
| --- | --- | --- | --- |
| LX | 身份还原、pending 附着、replay 都原地写入 LX 自己的对象；不再替换 `args[0]` | 以 `controller.metadata` 为底原地追加后重发（有 module-write 标记，不重入 hook） | binder-safe 重绘、空 Builder 复制、`BINDER_*` 探针 |
| Apple / Poweramp / Metrolist | pending 附着与 replay 原地写入宿主对象 | 同 LX | binder-safe 重绘、空 Builder 复制、`shouldCopyForBinder` / `sampleSize` |
| 酷狗 / QQ | 原地追加；官方字段与 songId 取 `hostLyricInfo` | replay 重发宿主同一对象 | `*MetadataCopy`（空 Builder + 重绘） |
| 网易云 | 继承自上一曲的模块 payload 原地清为 `""`；官方字段取清理后的宿主值 | replay 重发宿主同一对象 | `NeteaseMetadataCopy` |
| KuWo | 改用 `HostMetadataOverlay`；决策表不变 | 不变（重发宿主对象） | `KuWoMetadataBundle` |

每个 Provider 的封面就绪策略（Poweramp 等第二帧 bitmap、Metrolist 等 Coil bitmap、Apple 允许
https URI 首帧等）没有改动。

## 需要设备关注的行为差异

1. 封面不再做 software 重绘：进入 framework 的 Bitmap 对象与模块关闭时相同。
2. hook 不再替换 `args[0]`，宿主对象本身会带上 `lyricInfo`。宿主重发同一对象时歌词自然保留，
   LX 等 replay 策略会把它视为已拥有（`isModuleOwned`）而跳过。
3. QQ / 网易云的 replay 在 hook 已写入相同 payload 时跳过（UNCHANGED），不再多发一次
   `setMetadata`。酷狗保持原行为：每代仍会 replay 一次。
4. bundle 反射失败时不回退到重建：NativeLyricInfoPublisher 家族记 `*_FINAL_APPEND_UNSUPPORTED`，
   酷狗 / QQ 记 `LYRIC_INFO_APPEND_SKIPPED reason=UNSUPPORTED`，网易云记
   `OVERLAY_SKIPPED reason=append-UNSUPPORTED`，KuWo 记 `LYRIC_INFO_APPEND_UNSUPPORTED`。
5. 酷狗 / QQ / 网易云的 replay 在歌词线程写宿主对象。写入受上述锁保护，但宿主若在同一瞬间于
   其他线程重发同一个对象，仍存在理论上的 Bundle 并发读写窗口。设备日志中应留意宿主崩溃。

## 验证

- 本地：`testV5Matrix assembleV5MatrixDebug` 通过（522 项单测，0 失败），全部矩阵 debug APK 可组装。
  新增 `HostMetadataOverlayTest`（10 项）与 `NativeLyricInfoPublisherTest` 的原地发布用例。
- 设备（待做，每个已迁移 Provider）：
  - 模块关闭基线 vs 开启：session metadata 键数只多 `lyricInfo`；封面通道
    （ALBUM_ART / ART 尺寸、config、sample）与基线一致；无 `APPEND_UNSUPPORTED`、无超限跳过；
    SystemUI `NATIVE_LYRIC_RECEIVED` 正常。
  - 同一日志窗口内：切歌（缓存封面与未缓存封面）、暂停 / 继续、拖动、熄屏 / 亮屏，
    用户确认封面与歌词。
  - LX：开启蓝牙歌词，确认 `HOST_IDENTITY` 标题还原且 `HOST_IN` / `HOST_OUT` 为同一 bitmap。
  - Poweramp：占位 URI 首帧不附着，第二帧 bitmap 到达时附着。
  - 网易云：切歌时出现继承 payload 的场景下有 `STALE_LYRICINFO_CLEARED`，新曲歌词正常。
  - 酷狗概念版 / Lite：车载歌词标题不触发换曲。

## 未决

- Spotify、汽水、Cone、Salt 的迁移。
- 测试版本已写入各模块与 `release/v5-provider-matrix.json`：迁移的 7 个 Provider 1.2.0，
  KuWo 1.2.1，其余补丁号 +1，versionCode 均 +1。suite 仍为 4.3.2，发布时再定。
  测试包：`device-testing/append-only-all-providers/`。
