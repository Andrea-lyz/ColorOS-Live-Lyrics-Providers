# diagnostic3：恢复首样本短路检查并保留异常前的时间值

## diagnostic2 设备反馈

输入 `artwork-diagnostics-1791228918490.txt` 混有 diagnostic1 旧日志；本次仅取进程会话
`406b78e0`（2026-10-05 19:32–19:35 UTC），设备 OPPO PKJ110、SDK 37。

- 8 次完成下载的视频均在 `extractor_initial_sample_flags` 抛出异常，栈顶为原生
  `android.media.MediaExtractor.getSampleFlags`。对应当前 `AmMp4Normalizer` 的 flags 读取。
- 已经过文件打开、单轨道检查、AVC/尺寸核对、选轨与 sample time 调用。返回的时间值未知。
- 1080 候选筛选已命中（每组仅 4 个）；超限单列 `source_file_too_large`，未覆盖前序媒体异常。
- 没有 READY/REMUX 成功或并发 JOIN 标记，不能宣称真实播放、缓存共用和下载合并已设备验收。
- 自动匹配仍有专辑未确认，与绑定后的视频样本读取错误分开判断。

参考 AOSP `media/jni/android_media_MediaExtractor.cpp`：`JMediaExtractor::getSampleFlags` 读取
sample meta，JNI 包装将非 OK、非 EOS 的错误映射为 IllegalArgumentException。
这是上游参考，不代表已取得这台 OPPO 的原生实现或具体 native status。
来源：https://android.googlesource.com/platform/frameworks/base/+/master/media/jni/android_media_MediaExtractor.cpp

## 本次纠正

diagnostic1/2 为了统一收集字段，把 `getSampleFlags()` 移到了 `origin < 0` 判断之前，
改变了原代码的短路顺序。这是诊断引入的缺陷；可能改变无效时间情况下的错误表现，
尚不能仅凭已有日志证明它就是本机 flags 异常的原因。

- 提取 `AmInitialSample`，先读时间并立即记录，再判断负时间；负时间不调用 flags，维持原错误码。
- 非负时间才读 flags；若 flags 抛错，异常 detail 保留已经读取的时间、调用阶段和脱敏栈。
- 每个已成功读取的字段单独记录，后一个 getter 抛错不会丢失前一个结果。
- 保留固定 1080、缓存和下载合并；不跳过同步帧验证、不伪造 flags、不将失败视频标记 READY。

## 验证与交付

`scripts/dev.cmd provider :artwork-provider-am:testDebugUnitTest :artwork-provider-am:assembleDiagnostic :artwork-provider-am:lintDiagnostic`

- 111 项测试全部通过，无失败/跳过；新增负时间不读 flags、异常前时间保留、诊断回调失败不影响
  有效样本值、time 抛错后不调用 flags 的回归。
- Lint 0 errors / 4 warnings；APK 签名和 zipalign 检查通过。
- 版本 `1.0.1-diagnostic3`，包名/测试签名沿用 diagnostic2，可覆盖安装，Bridge 不需更换。
- 本包修正诊断逻辑，不宣称解决原视频处理故障。设备样本时间与 flags 读取仍待反馈。
- 未推送、发布或操作设备；保留原有工作区修改。

手机操作：覆盖安装，确认版本和诊断开关，保留出错的手动绑定。等待旧失败缓存的 60 秒
窗口过期后复现一首歌即可，再从诊断版导出 TXT。无需重做自动匹配或清空全部绑定。
