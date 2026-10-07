# 1.0.4

随 C17 Artwork Preview2 交付。Ships with C17 Artwork Preview2.

## 简体中文

- 新增默认关闭的网易云官方动态封面来源，使用官方客户端扫码，私有加密保存会话并支持退出。
- AM 先按专辑名搜索，必要时仅补一次曲目搜索；网易云按曲名核对歌手，再用 songId 请求动态封面。
- 两来源并发查找、AM 优先下载；已验证缓存优先复用，同曲查询及同专辑下载合并，只有整条链路完成才更新匹配状态。
- 临时网络故障在原总期限内追加 1/3 秒两轮恢复；取消、限流、身份失败及会话操作不重放。
- AM / 网易云手动搜索框与结果彻底独立；最近记录分别填入专辑名和曲名。网易云结果显示真实静态封面，缓存预览显示来源标签。
- 保留原始网易云方形 MP4，上限 1280；配套更新 Bridge，AM 继续使用原生 1080。
- 发布前补齐 `(中文版)`、`(粵語版)`、`(重錄)`、`(現場)` 的版本保护；歌曲失败不再否决整张网易云专辑，成功视频仍共享。AM 查询缓存 v8、网易云身份缓存 v3，既有视频可复用。
- 233 项 Provider 单测通过，debug/release lint 0 errors，Debug 构建通过。用户已确认 fix9 实机测试通过；本次两项收尾修复与最终签名包未另做设备 RC。

## English

- Add an opt-in official NetEase motion-cover source with QR sign-in, encrypted private sessions and sign-out.
- Search AM by album first with at most one song fallback; search NetEase by title, verify artists and request the selected song ID's cover.
- Discover sources concurrently with AM download priority, verified cache reuse and coalesced queries/downloads. Publish only the final matching outcome.
- Recover transient transport failures after 1/3-second waits within the original deadline; preserve cancellation, rate limits, identity checks and single-attempt authentication.
- Separate source search fields and results, prefill album/song names independently, show real NetEase album thumbnails and label cached videos by source.
- Retain original square NetEase MP4s up to 1280 with the paired Bridge update; AM remains native 1080.
- Preserve short native-language/recording labels when stripping translated aliases. Keep NetEase failures song-specific while sharing verified album videos. Upgrade AM query decisions to v8 and NetEase identity decisions to v3 without deleting valid videos.
- 233 Provider tests, debug/release lint with no errors and Debug build passed. The user confirmed fix9 device testing; the two audit fixes and final signed APKs have not received a separate device RC run.

## 开发过程 / Development history

以下记录保留各测试版本当时的状态；当前结论以上文和 Preview2 核验台账为准。
The entries below retain their original development-stage status; see the current summary and Preview2 verification ledger for final results.

- 网易云 fix9：搜索结果接入真实专辑静态封面，异步加载官方 HTTPS 小图，缺图保留占位与绑定入口；已加载图片可缓存到新绑定列表，AM/网易云缩略图按来源隔离。230 项测试、Lint（0 errors）与 Debug 构建通过，设备显示待验收。
- NetEase fix9: show actual album covers in search results using asynchronous official HTTPS thumbnails. Keep missing-art results selectable and reuse loaded pictures in new bindings with source-isolated thumbnail storage. 230 tests, lint (0 errors) and Debug build passed; device display validation is pending.

- 网易云 fix8：拆分 AM/网易云手动搜索框、按钮和结果，网易云框随来源开关显示；最近记录分别填入专辑/曲目名，修复网易云首项覆盖 AM 查询。缓存预览封面左上角新增来源标签，兼容旧索引补认与缓存清理。225 项测试、Lint（0 errors）和 Debug 构建通过，设备布局待验收。
- NetEase fix8: separate manual AM/NetEase fields, actions and results, show NetEase only when enabled, and prefill album/song names from recent requests. Add source badges to cached cover thumbnails with legacy provenance recovery and cache lifecycle handling. 225 tests, lint (0 errors) and Debug build passed; device layout validation is pending.

- 网易云 fix7：临时传输失败后，在原请求内间隔 1/3 秒最多恢复两轮，绕过对应网络负缓存并保留最终状态统一交付。取消、限流、身份/无封面及会话操作不重试，总期限不延长。219 项测试与 Debug 构建通过，实际网络恢复效果待设备复测。
- NetEase fix7: recover transient transport failures with up to two delayed resolve retries (1/3 seconds), bypassing only transient failure caches and publishing one final outcome. Preserve cancellation, rate limits, identity/no-motion decisions, single-attempt authentication and the original deadline. 219 tests and Debug build passed; device recovery validation is pending.

- 网易云 fix6：无缓存时并发查找 AM/网易云，AM 可用立即优先下载；AM 最终失败才下载已准备好的网易云资源，避免串行等待或两路视频同时下载。保留缓存复用、同曲查询合并及原有预算。208 项测试与 Debug 构建通过，设备提速效果待复测。
- NetEase fix6: discover both sources concurrently for cold queries while retaining AM download priority. Download the prepared NetEase resource only after AM fails; keep cache reuse, duplicate-query coalescing and existing deadlines. 208 tests and Debug build passed; device latency validation is pending.

- 网易云 fix5：先复用已验证的 AM/网易云视频缓存，避免每次亮屏都重复等待 AM；相同自动查询共用查找。首次无缓存仍保留 AM 优先和原有超时预算，增加分来源耗时诊断。200 项测试与 Debug 构建通过，提速效果待设备复测。
- NetEase fix5: reuse verified AM/NetEase video caches before network lookup and coalesce duplicate automatic queries. Preserve AM priority and existing deadlines for cold queries, with per-source timing diagnostics. 200 tests and Debug build passed; device latency validation is pending.

- 网易云 fix4：AM 改为专辑搜索优先，必要时仅补一次歌曲搜索；网易云按曲名核对歌手后取 songId，请求动态封面。播放器专辑/时长用于候选排序；只写整条链路最终状态，修复 AM 中间失败造成的状态跳变。194 项测试与 Debug 构建通过，设备效果待复测。
- NetEase fix4: search AM albums first with one bounded song fallback; search NetEase by title, verify artist credits and request the selected song ID's cover. Use player album/duration as ranking evidence and publish only the final source-chain outcome. 194 tests and Debug build passed; device validation is pending.

- 网易云 fix1：只读 API/整文件下载有界重试，断流从零重写；增加脱敏异常阶段/字节诊断，不缓存暂停或换网失败；退出登录改为次要文字操作。182 项测试通过，设备恢复效果待验收。
- NetEase fix1: retry read-only APIs and whole-file transfers within bounded deadlines, truncate partial files, log redacted failure stages/byte counts, avoid caching paused-network failures, and simplify sign-out. 182 tests passed; device recovery remains pending.

- 网易云官方动态封面来源默认关闭，提供二级扫码页、风险声明、私有加密会话、状态校验与退出登录。
- 自动匹配 AM 优先、网易云兜底；统一手动搜索显示来源标签，支持完整网易云单曲分享文本和专辑绑定。
- 配套 Bridge 的封面协议上限放宽至 1280，保留网易云原始 MP4；AM 仍为原生 1080。
- Add an opt-in NetEase motion-cover source with QR sign-in, disclosure, encrypted private sessions, verification and sign-out.
- Prefer AM for automatic matching; include labeled NetEase results in shared manual search and accept complete song share text.
- Raise the paired Bridge artwork contract limit to 1280 for original NetEase MP4 files; keep AM at native 1080.
- 本地检查通过，Android 扫码与播放仍待设备验收。Local checks passed; Android sign-in and playback await device acceptance.

# 1.0.3

## 简体中文

- 改善网易云/QQ 等跨平台元数据匹配：兼容简繁、艺人原生文字别名、括号合作艺人和 Single/EP/Mini Album 发行类型。
- 保留中文版本、现场、Remix、重录和专辑版本标签；完整专辑名不再被猜测为截断前缀。
- Strict/Standard/Loose 贯穿候选过滤、专辑页验证、诊断和缓存。查询缓存升级为 v6，专辑快照保留评级及不完整曲目计数。
- 从同专辑其它歌曲保留评级/专辑 ID 线索，补齐 `betty (Explicit)` 的搜索缺口；仍复核当前歌曲，评级冲突及未知发行保持保守结果。
- CN 手动绑定直接查 CN Apple Music 网页，修复无关 US 结果阻止本地市场搜索的问题。
- 脱敏诊断区分标题主体命中和评级拒绝；匹配严格度显示本地化文字。
- 162 个单元测试、模块 lint 与 Debug 构建通过；用户已确认 QQ 音乐 fix1 真机测试成功。

## English

- Improve cross-platform metadata matching with Chinese script folding, native artist aliases, bracketed guest credits and Single/EP/Mini Album release types.
- Preserve language, live, remix, rerecording and album-edition labels. Complete album names are no longer guessed to be truncated prefixes.
- Apply Strict/Standard/Loose consistently to filtering, page verification, diagnostics and cache decisions. Upgrade query decisions to v6 and retain rating/incomplete-table evidence in shared album snapshots.
- Retain album ID/rating hints from other songs in the same album to recover the `betty (Explicit)` search gap, while verifying the requested track and rejecting contradictory or uncertain evidence.
- Search the CN Apple Music website directly for CN manual binding, preventing unrelated US results from hiding the selected storefront.
- Keep diagnostics anonymous while separating title-body matches from rating rejection; localize strictness labels.
- 162 unit tests, module lint and Debug build passed; the user confirmed the QQ Music fix1 device test succeeded.
