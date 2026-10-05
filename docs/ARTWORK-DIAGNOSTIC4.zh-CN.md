# diagnostic4：原视频保留、三路读取对照与手机 ZIP 导出

## 目的与输入

diagnostic3 的 `9e70613e` 会话中，4 次完整下载后的首样本时间均为 `-1`，flags 未读取；
同专辑并发请求已出现 DOWNLOAD_JOIN。仍未取得设备下载的原视频，不能判定文件内容与
设备解析兼容性哪个导致没有首样本。用户要求一次收集原文件、三组读取结果和日志。

## 实现边界

- 仅在开启诊断且视频完整下载后验证失败时，保留第一份原始视频；最大 20 MiB。
  通过同应用存储内 rename 从临时缓存保留到私有 inspection 目录，不在解析失败路径复制整份视频。
  保留后当前请求仍按原来的失败/候选回退策略运行，样本永不作为 READY 资产或锁屏播放回退。
- 样本不随视频缓存或 TXT 日志清理；只能用“清除检查样本”删除，或随卸载删除。
  存在样本时不覆盖，避免后续候选替换第一份故障证据；运行中不允许清除或导入覆盖。
- 原文件未重封装/转码。后台保存实际长度、全文件 SHA-256 和有界 MP4 顶层 box 清单；
  这只检查顶层边界，不宣称已经验证所有 sample table、NAL 或关键帧。
- 三个独立后台 MediaExtractor 对同一 `source.part` 做以下对照，不调用解码器或播放器：
  1. `setDataSource(path)`，选视频轨后读取。
  2. `setDataSource(fd, 0, fileLength)`，选轨后读取。
  3. 新建 extractor，使用同样 FD 范围，选轨后 `seekTo(0, SEEK_TO_PREVIOUS_SYNC)`，再读取。
- 路径对照使用保留后的私有文件路径，扩展名仍为 `.part`；原始缓存路径的失败另由现有日志记录。
  三组分别记录阶段、轨道数/MIME/尺寸、时间、轨道索引、大小、flags 和 readSampleData 字节数。
  检查模式允许负时间后独立探测其他 getter，分别捕获异常；正式重封装路径仍保留负时间短路。
- native 调用超过 12 秒时报告 `stalled` 与当前阶段，不从其他线程强行 release extractor。
  ZIP 导出最多等 12 秒，即使 native 调用未结束仍可导出原文件和当前结果；检查线程有固定上限，
  未结束时不再启动另一组。`finished` 表示检查执行结束，不等于视频通过校验。

## ZIP 内容与手机入口

高级 → “导出视频检查包 ZIP”通过系统文件保存器保存，包含固定名称：

- `original.mp4`：逐字节原视频，只有导出名称改为 mp4。
- `capture.txt`：环境、来源、请求编号、候选尺寸、预期长度、原因及资源路径摘要。
- `structure.txt`：实际长度、SHA-256、顶层 box 清单或检查错误。
- `path.txt`、`fd.txt`、`fd-seek.txt`：三组结果/异常/未完成阶段。
- `diagnostics.txt`：现有脱敏日志快照。

日志不记录原始 URL、凭证、曲目文字或私人路径；异常仅输出类/方法/行号。ZIP 明确包含
上游原始视频本身，不对媒体文件中的内嵌元数据作脱敏或改写。不自动上传。

正常设备对照入口：“导入视频做对照”。从上述 ZIP 解出 `original.mp4` 后选中它，
按同一有界流程导入并检查；再导出该设备 ZIP，对比 SHA-256 一致后才比较三路结果。
导入不替换绑定、正常视频缓存或播放来源。

## 手机复现步骤

1. 覆盖上一诊断版，确认底部 `1.0.1-diagnostic4`。Bridge 不用更换，绑定/设置保留。
2. 启用诊断版来源、开启脱敏诊断，Bridge 来源保持“动态封面诊断版”。
3. 保留出错的手动绑定，距上次失败等一分钟后播放同一首歌进入锁屏。
4. 报错后等待约 15 秒，再打开诊断版 → 高级 → “导出视频检查包 ZIP”，把 ZIP 发回。
   如果报告尚未完成，ZIP 仍包含原视频与当前阶段。无需再次清空绑定或反复自动匹配。
5. “导入视频做对照”供另一台正常设备使用；反馈用户此次不需要操作它。

## 构建与验证

```text
scripts/dev.cmd provider :artwork-provider-am:testDebugUnitTest :artwork-provider-am:assembleDiagnostic :artwork-provider-am:lintDiagnostic
```

- 117 项单元测试通过，无失败/跳过；覆盖首份原视频保留、跨实例 ZIP 导出、原字节一致性、
  导入大小/空文件限制、失败后临时文件清理、固定报告名、SHA-256 与 MP4 顶层边界/计数上限。
- Lint 0 errors / 4 warnings；APK 签名、包名、版本和 zipalign 另行核对。
- 仍为独立包名 `io.github.andrealtb.artwork.am.diagnostic` / code 5 / 本地测试签名，非正式候选。
- 三路原生读取、文件保存器、安装与故障复现尚待真机；未代理操作设备，未推送/发布。
- 保留工作区原有修改（包括 `scripts/verify-artwork-contract.ps1`）。
