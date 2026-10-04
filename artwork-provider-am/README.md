# 动态封面 Provider（Dynamic Artwork Provider）

v1.0.0。包名 `io.github.andrealtb.artwork.am`。普通 Android APK（API 30+），不是 Xposed 模块，
不注入 Apple Music 或 SystemUI，不进入任何播放器进程。通过 `artwork-contract` v1 向已授权的
Bridge 返回 `localTestOnly=false` 与完整本地 MP4 的只读 FD。

它是**可选**的独立应用：不安装它时，锁屏仍然是官方静态封面；安装并启用后才会联网匹配动态封面。
界面默认英文，中文系统显示中文，语言跟随系统。

来源：本模块原先在 Bridge 仓库中开发（`artwork-provider-am/`，迁出前为 Bridge 提交 `7adbdb2`）。
逐次真机联调记录（fix1–fix24）保留在 Bridge 仓库的 `docs/DYNAMIC-ARTWORK-*` 文档中，本仓库不再复制。

## 来源链路

iTunes 候选/精确 lookup → 已确认的 Apple Music 专辑公开结构化数据 →
方形 HLS master → AVC SDR variant → 有限、连续、单文件 byterange VOD →
完整下载 → Android MediaExtractor/MediaMuxer 归零重封装 → 文件校验 → 原子缓存/租约。
不抓取 Web token、不需要 Apple 账号，不调用第三方 resolver。

## 匹配

- 保留 Unicode/版本信息，用歌曲/艺人/专辑/毫秒时长核对；精确 URL 与查询冲突拒绝。
- 同名同艺人 Explicit/Clean 发行版按封面等价策略合并，冷查询稳定优先 Explicit，精确 URL 仍优先；其他版本歧义继续拒绝。
- 多个候选返回 AMBIGUOUS；有界搜索空结果、HTTP 与结构变化不当成永久 NO_MATCH。
- 缺艺人或时长拒绝；纯歌曲 URL 在地区 lookup 无结果时暂不可用，带 albumId 的链接可直接核对 Web 曲目表。
- 无 URL 的 CN 不完整搜索不会静默跨到 US；已知专辑时可经严格 album search 与 Web 曲目表 fallback。
- 多碟专辑按碟分区的曲目表合并；专辑页解析分步骤记录失败点，单个异常曲目只跳过。
- 手动绑定：把 Apple Music 专辑绑定到本地专辑名（可限定歌手），命中时跳过曲目核对。绑定只影响展示来源，不修改本地文件或标签。

## 缓存与并发

- 已验证整专辑曲目表与按 albumId/目标尺寸的视频索引；同专辑新歌仍逐首核对身份，缓存不绕过版本匹配。
- 已验证视频清单支持在查询上限内跨尺寸复用；网络恢复失效旧传输错误，429 等上游退避保留。
- 瞬时 IOException 在同一请求内有界重试（每次尝试使用新输出流），HTTP 状态类错误不重试。
- 身份匹配成功后不因客户端取消而中止下载：请求转为 detached，完成后仍写入缓存；工作线程与
  detached 计数是进程级的，服务随解绑销毁时已开始的下载继续完成。
- 按阶段总时长上限（例如 web_album 8 秒）与文本 gzip，使慢抓取快速失败并立即重试；视频档传输失败继续尝试下一个 variant。
- 文件缓存默认 512MB LRU（主页“缓存上限”可改为 64MB–8000MB）、查询索引最多 128；活跃租约 pin 文件，关闭/取消/死亡释放。

## 身份与隐私

- 来源默认关闭、默认仅非计费网络；启用及设置由本应用 Activity 管理。
- 缓存命中可离线使用（来源仍须启用）；成功/确定无动画 TTL 24h，临时故障按短退避。
- 所有事务验证实际 UID；Bridge App 必须先在插件授权当前签名，平台签名 SystemUI 单独校验。
- 来源设置与签名不备份；诊断默认关闭，不记录曲名、歌词、完整 URL、token、原始 media ID。

## 构建与测试

从工作区根目录：

```text
scripts\dev.cmd provider :artwork-provider-am:testDebugUnitTest :artwork-provider-am:lintDebug :artwork-provider-am:assembleDebug
```

或使用本仓库聚合任务：

```text
scripts\dev.cmd provider testArtworkProvider
scripts\dev.cmd provider assembleArtworkProviderDebug
```

正式包使用与歌词 Provider 相同的发布签名（`RELEASE_*` 环境变量），由
`assembleArtworkProviderRelease` 构建；它不属于 v5 播放器矩阵。

`artwork-contract/` 是 Bridge 仓库同名模块的镜像，`src/` 必须逐字节一致；
`scripts/verify-artwork-contract.ps1` 在同时具备 Bridge 检出时校验（Gradle 任务 `verifyArtworkContract`，
设置 `BRIDGE_REPO_ROOT` 即启用必需校验）。

## 测试 fixture

`apple-1989-deluxe-public.html`、`showgirl-*-public.*` 等是公开 Apple 页面中必要字段的精简快照，
无个人数据；`*.m3u8` 为公开清单片段。Android 原生重封装、解析/解码、UI 与真实网络结果必须真机验收，
JVM 测试不代替它们。

