# 动态封面正式版日志流程与状态码

本文对应动态封面 Provider 1.0.2 与 Bridge C17 Artwork Preview1 的源码。
动态封面来源是独立应用，来源诊断与 Bridge 调试开关分别控制；版本与资产仍以发布契约和发布台账为准。

## 用户反馈流程

1. 打开动态封面来源应用 → 高级 → 开启“脱敏诊断日志”。默认关闭。
2. 记录来源版本、Bridge 版本、系统版本、出错歌曲/专辑及手动绑定情况。
   歌曲信息由用户另行提供，日志不保存歌曲文字、歌词、原始媒体 ID、URL 或凭证。
3. 保持原来的绑定与配置，播放问题歌曲并进入锁屏，记录报错时间和状态码。
   如果只命中了开启诊断前产生的失败缓存，等约一分钟后再复现；不要求先清空全部绑定。
4. 回来源应用 → 高级 → “导出诊断日志”，用系统文件保存器保存 TXT，连同状态截图发送。
5. 若问题涉及锁屏挂载、显示或播放，再开启 Bridge 调试并提供同一窗口的 Bridge 日志。
   LSP ZIP 可以补充宿主日志，但包内 logcat main 可能已经滚动覆盖来源的匹配/下载窗口；
   来源 TXT 是资源处理链路的主要证据。
6. 反馈完成后关闭诊断。日志可单独清除，不要求清除绑定或视频缓存。

## 正式版保留的诊断能力

- `AmDiagnostics` 同时写 logcat（`CLL-Artwork-AM`）和应用私有目录下的文本日志。
- 单写线程、队列最多 256 条、两个各 512 KiB 的轮转文件，合计约 1 MiB。
  队列/写入失败记录丢弃计数；日志失败不得变更资源解析、回退或播放结果。
- 日志带 UTC 时间、进程会话和请求编号；记录路由/市场、字段存在性、展示尺寸/预算、
  匹配候选计数、缓存命中、候选视频顺序/规格/路径摘要、下载、HLS 与样本处理阶段。
- 首样本先记录时间，负时间直接失败且不读取 flags；非负时间才读取 flags。
  异常记录受限的类/方法/行号与 cause，不记录异常消息或文件名。
- 每个视频候选记录自己的结果。最终结果优先保留已下载视频的读取/处理失败，
  不被后续网络异常、HLS 拒绝或超限候选覆盖；并未改变候选尝试和取消门禁。
- 手机提供 TXT 导出和日志清除。日志默认关闭、不自动上传、不需要电脑。

## 正式包排除的专项能力

原视频保留、视频导入、ZIP 样本导出、容器检查、路径/FD/seek 三路探测只在 `diagnostic` 变体编译：

- `src/diagnostic/java/.../AmInspectionFeature.java`：专项入口。
- `src/diagnostic/java/.../AmInspectionStore.java`、`AmMp4Structure.java`、`AmVideoInspection.java`：专项实现。
- `src/diagnostic/res/`：专项按钮和说明。
- `src/standard/java/.../AmInspectionFeature.java`：debug/release 的空实现，不收集文件、不启动探测。
- 专项存储与容器测试移至 `src/testDiagnostic/`，常规日志/状态测试保留在 `src/test/`。

正式版用户反馈不足时，再按具体问题提供独立诊断包，不在正式版默默保留原视频。
此前 diagnostic1–4 的专项记录用于解释历史交付，不代表这些探测入口进入正式版。

## 状态码与用户提示

Binder 的状态枚举和协议保持不变，仅细分 reason。Bridge 兼容旧来源返回的 reason。

| 状态码 | 实际含义 | Bridge 提示 |
| --- | --- | --- |
| `unsupported:source_no_initial_sample` | 选轨后首样本时间无效 | 无法读取动态封面的视频数据 |
| `unsupported:source_sample_read_failed` | 首样本时间/flags API 抛异常 | 无法读取动态封面的视频数据 |
| `unsupported:media_extract_failed` | 其他 extractor API 阶段抛异常 | 无法读取动态封面的视频数据 |
| `unsupported:source_initial_keyframe_missing` | 时间有效，但同步帧标记未满足要求 | 动态封面视频准备失败 |
| `unsupported:media_remux_failed` | muxer 创建/启动/写入/停止等阶段失败 | 动态封面视频准备失败 |
| `unsupported:media_validation_failed` / `media_manifest_mismatch` | 通用视频验证失败 | 动态封面视频准备失败 |
| `unsupported:source_file_too_large` / `remux_size_budget` / `download_budget` | 源文件、输出文件或下载超过大小限制 | 动态封面视频超过大小限制 |
| `unsupported:unsupported_hls_layout` / `invalid_hls_master` | 播放列表不满足来源的解析要求 | 当前专辑的动态封面格式暂不支持 |
| `unsupported:no_1080_avc_variant` | 没有满足当前固定 1080 策略的 AVC 候选 | 当前专辑的动态封面格式暂不支持 |
| `mount_unsupported` | Bridge 无法挂载动态封面 | 当前锁屏布局暂不支持动态封面 |
| `prepare_failed` / `decode_failed` / `first_frame_timeout` | 锁屏消费/播放阶段失败 | 动态封面播放失败 |
| `retry_later:catalog_album_unconfirmed` | 自动匹配没有确认专辑 | 未匹配到当前歌曲 |

来源视频读取/准备失败附带“在来源应用开启诊断、复现并导出日志”的提示，保留完整状态码。
不根据状态码直接断言是固件 BUG、源站坏文件或锁屏布局缺陷。

旧 `missing_initial_keyframe` 同时涵盖负时间和缺同步帧两种情况，因此只映射到“视频准备失败”，
不能倒推出确实缺关键帧。旧 `no_avc_square_variant` 保持格式不支持分类。
未知 `unsupported:*` 降级为“视频准备失败”，不再统一归入锁屏布局。

## 验证

Provider：

```text
scripts/dev.cmd provider :artwork-provider-am:testDebugUnitTest :artwork-provider-am:testDiagnosticUnitTest :artwork-provider-am:assembleDebug :artwork-provider-am:assembleDiagnostic :artwork-provider-am:compileReleaseJavaWithJavac :artwork-provider-am:lintRelease
```

Bridge：

```text
scripts/dev.cmd bridge :app:testDebugUnitTest :app:assembleDebug
```

- Provider 标准分支 113 项通过，诊断分支 119 项通过，无失败/跳过。
- Provider release Java 编译与 lint 通过（0 errors、4 warnings）；debug/diagnostic APK 构建通过。
- 检查标准 debug APK 的 DEX 与 release 编译类目录，专项收集/探测类不存在；诊断 APK 保留它们。
- Bridge 761 项通过、6 项既有 fixture 跳过，Debug APK 构建通过。
- 标准日志导出与新状态提示仍待正式候选的设备验收；编译/测试不代替签名发布或设备确认。
- 保留用户已有 `scripts/verify-artwork-contract.ps1` 修改，未改动发布契约、应用 ID 或正式版本号。
