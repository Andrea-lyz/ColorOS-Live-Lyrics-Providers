# 动态封面 Provider（Dynamic Artwork Provider）

v1.0.3（versionCode 7）。包名 `io.github.andrealtb.artwork.am`。普通 Android APK（API 30+），不是 Xposed 模块，
不注入 Apple Music 或 SystemUI，不进入任何播放器进程。通过 `artwork-contract` v1 向已授权的
Bridge 返回 `localTestOnly=false` 与完整本地 MP4 的只读 FD。

它是**可选**的独立应用：不安装它时，锁屏仍然是官方静态封面；安装并启用后才会联网匹配动态封面。
界面默认英文，中文系统显示中文，语言跟随系统。

1.0.3 改善跨平台自动匹配、分档缓存与候选复核，修复 `betty (Explicit)` 的目录候选遗漏和
CN 手动绑定搜索错用 US 结果。更新说明见 [CHANGELOG.md](CHANGELOG.md)。

1.0.2 随 C17 Artwork Preview1 交付：大小卡共用原生 1080×1080 视频和下载，
提供默认关闭的本地 TXT 诊断，并区分没有可读样本、读取异常、超限与重封装失败。
使用步骤和状态码见 [日志流程与状态码](../docs/ARTWORK-LOGGING-AND-STATUS.zh-CN.md)。
原视频留存与三路检查仅属于独立 diagnostic 变体，标准 debug/release 不打包这些能力。
历史 diagnostic1–4 的复现记录保留在 `docs/ARTWORK-DIAGNOSTIC*.zh-CN.md`，不作为本次最终签名包验收结论。

来源：本模块原先在 Bridge 仓库中开发（`artwork-provider-am/`，迁出前为 Bridge 提交 `7adbdb2`）。
逐次真机联调记录（fix1–fix24）保留在 Bridge 仓库的 `docs/DYNAMIC-ARTWORK-*` 文档中，本仓库不再复制。

## 来源链路

iTunes 候选/精确 lookup（专辑搜索为空时回退同市场 Apple Music 网页搜索）→ 已确认的 Apple Music 专辑公开结构化数据 →
方形 HLS master → AVC SDR variant → 有限、连续、单文件 byterange VOD →
完整下载 → Android MediaExtractor/MediaMuxer 归零重封装 → 文件校验 → 原子缓存/租约。
不抓取 Web token、不需要 Apple 账号，不调用第三方 resolver。

## 匹配

严格度、发行类型/版本边界、简繁与合作艺人处理，以及分档缓存和目录回退规则见
[自动匹配规则与验证边界](../docs/ARTWORK-MATCHING.zh-CN.md)。

- 保留 Unicode/版本信息，用歌曲/艺人/专辑/毫秒时长核对；精确 URL 与查询冲突拒绝。
- 同名同艺人 Explicit/Clean 发行版按封面等价策略合并，冷查询稳定优先 Explicit，精确 URL 仍优先；其他版本歧义继续拒绝。
- 合格候选按精确专辑、标题、艺人及当前档位的时长依次排序；仍无法区分的多个发行 ID 返回 AMBIGUOUS，不按动画资源猜选。有界搜索空结果、HTTP 与结构变化不当成永久 NO_MATCH。
- 缺艺人或时长拒绝；纯歌曲 URL 在地区 lookup 无结果时暂不可用，带 albumId 的链接可直接核对 Web 曲目表。
- 手动搜索在 iTunes 无专辑结果时读取同市场 Apple Music 网页专辑区；自动匹配在 iTunes 无精确专辑候选时也使用该回退。CN 目录发现使用 US 目录，专辑页先核对用户市场，仅页面缺失/结构不可读可回退到同一 Adam ID 的 US 页面；曲目不匹配、限流和传输故障不触发市场回退。
- 网页候选缺少发行日期，不能用于 Explicit/Clean 等价合并；多个精确候选仍返回 AMBIGUOUS。网页结构或市场不符报告解析错误，不冒充搜索为空。
- 多碟专辑按碟分区的曲目表合并；专辑页解析分步骤记录失败点，单个异常曲目只跳过。
- 手动绑定：把 Apple Music 专辑绑定到本地专辑名（可限定歌手），命中时跳过曲目核对。绑定只影响展示来源，不修改本地文件或标签。

## 缓存与并发

- 已验证整专辑曲目表与按市场/albumId/资源限制建立的固定 1080 视频索引；展示尺寸不再拆分缓存，同专辑新歌仍逐首核对身份，缓存不绕过版本匹配。
- 大小卡都只选原生 AVC SDR 1080×1080，最低码率优先。没有可用 1080 时保留静态，不下载或复用旧的小尺寸资源；已验证的旧 1080 缓存仍可复用。
- 同专辑资源下载中的新请求等待后复核缓存，各自获取文件租约；取消等待者不取消拥有者，其他专辑可并行。网络恢复失效旧传输错误，429 等上游退避保留。
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

2026-10-05 桌面直连复现：相同 `Taylor Swift` 查询，iTunes 专辑搜索 US/HK 分别返回 24/23 条，CN 为 HTTP 200、0 条；Apple Music CN 公开网页专辑区返回 21 条。市场设置保存及读取链路正确，空结果源于 iTunes 搜索响应。`cn-album-search-public.html` 保存同次公开响应的精简专辑字段；回归覆盖手动回退、市场/ID 核对、错误与真实空结果区分、自动匹配曲目核验和版本歧义。以上是桌面网络与本地测试证据，修复包的设备搜索/绑定效果仍待验收。

`apple-1989-deluxe-public.html`、`showgirl-*-public.*` 等是公开 Apple 页面中必要字段的精简快照，
无个人数据；`*.m3u8` 为公开清单片段。Android 原生重封装、解析/解码、UI 与真实网络结果必须真机验收，
JVM 测试不代替它们。

## 1.0.1 修复与验证

修复 CN 搜索为空及搜索结果中合辑 `subtitleLinks=null` 导致整批解析失败。公开响应回归确认“周杰伦 最伟大的作品”的首项为专辑 `1633408719`、12 首歌曲；用户已确认修复后的开发包实机搜索成功。100 项单测通过。正式签名预览附件由受控 CI 构建，开发包确认不冒充最终签名 RC 验收。
