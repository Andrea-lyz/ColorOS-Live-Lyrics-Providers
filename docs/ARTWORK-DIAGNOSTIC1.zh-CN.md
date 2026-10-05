# 动态封面诊断包 diagnostic1

针对 `LSPosed_20261006_024836.zip`：Bridge 记录了此前的 `missing_initial_keyframe`、
`unsupported_hls_layout`，最新自动匹配窗口记录 `catalog_album_unconfirmed`；包中 logcat
main 最早为 02:48:27.584，未覆盖 02:45–02:47 的复现，不能据此认定用户未开启诊断。

本包仅补充诊断，不宣称解决以上错误。不改变匹配、候选回退、资源检查、缓存、网络或锁屏播放策略。

## 交付与安装

- 构建任务：`:artwork-provider-am:assembleDiagnostic`。
- 版本：`1.0.1-diagnostic1` / code 5；独立包名 `io.github.andrealtb.artwork.am.diagnostic`。
- 中文名称：动态封面诊断版。使用本地测试签名，非正式候选；可与正式包并存，配置、缓存和绑定独立。
- 未推送、未发布、未安装或操作设备。不要作为正式版覆盖更新分发。
- Bridge 按公开 BIND_PROVIDER action 发现来源并记录用户选择的签名；静态确认支持该独立包名。
  已检查 APK 中 service、settings activity 和协议 metadata；真机来源选择仍待确认。

## 手机步骤

1. 安装诊断 APK，打开“动态封面诊断版”，启用来源、设置查询市场；计费网络需要允许计费网络下载。
2. 高级 → 打开“脱敏诊断日志”。开关默认关闭。
3. Bridge → 动态封面 → 来源选择“动态封面诊断版”，保持所需锁屏展示开关及 Bridge 调试开启。
4. 播放问题歌曲并进入锁屏，等待结果。若自动匹配失败，再在诊断版内绑定原来出错的 Apple Music
   专辑并复现视频问题。记录各次歌曲/专辑和报错时间即可，不必反复清空日志。
5. 回诊断版 → 高级 → “导出诊断日志”，选择 Downloads 等保存位置。将导出的 TXT 发回；
   有新的 LSP ZIP 可一并提供，TXT 不依赖 logcat 缓冲区。
6. 完成后关闭诊断开关，并在 Bridge 中切回原正式来源。卸载诊断版会删除它的私有日志。

## 新增证据

- 日志开关同时控制 logcat 与本地异步记录；256 条队列上限，两个各 512 KiB 的轮转文件，
  与视频缓存隔离。日志丢弃计数随导出提供；清除诊断日志独立于视频缓存。
- 请求编号、版本、设备型号、Android SDK、查询路由/市场、身份字段是否存在、请求尺寸与预算。
- 歌曲匹配字段计数、专辑候选数量、标题筛选与最终匹配数量。
- 每次视频候选的顺序、尺寸、码率、帧率、资源路径摘要，以及该候选的失败原因。
- HLS 具体拒绝步骤、行号、分段数、大小和时长预算；不保存播放列表原文或 URL。
- 首样本时间、flags、轨道索引和大小；失败细节区分负时间与同步帧标记。
- 不保存歌词、歌曲文字、原始媒体 ID、凭证、原始 URL 或私人路径。异常只记录类型。

## 本地验证

`scripts/dev.cmd provider :artwork-provider-am:testDebugUnitTest :artwork-provider-am:assembleDiagnostic :artwork-provider-am:lintDiagnostic`

- 104 项单元测试通过，无失败/跳过；新增日志轮转、跨实例保留、UTF-8 字节预算、清除恢复及
  HLS 拒绝原因/敏感内容不泄漏回归。
- Lint 0 errors、4 warnings；APK v2 签名验证和 16 KiB zipalign 检查通过。
- APK 名称、独立包名、版本、权限、非 debuggable 和组件 metadata 已核对。
- 手机安装、来源选择、系统文件保存器导出、实际故障复现仍待设备验证。
- 保留工作区已有 `scripts/verify-artwork-contract.ps1` 修改，未纳入本次实现。
