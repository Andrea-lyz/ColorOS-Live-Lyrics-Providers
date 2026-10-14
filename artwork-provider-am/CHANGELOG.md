# 1.0.6

随 Bridge v4.5.1 发布（versionCode 10）。Ships with Bridge v4.5.1 (versionCode 10).

## 简体中文

- 修复 US 等市场手动搜索找不到专辑：iTunes 专辑索引可能整张缺失某专辑（Lady Gaga《MAYHEM》），同时仍返回其他艺人的同名专辑，无法从其结果判断缺失。手动搜索改为直接读取所选市场的 Apple Music 网页专辑区；非 CN 市场仅在网页为空或不可读时改用 iTunes，网页不可读且 iTunes 无结果时报告错误，不显示为“无结果”。
- 同名同艺人的多个已核验版本（如《MAYHEM》14 首版与 17 首版）：严格/标准档仍不替用户选择，最近记录新增“多个版本”状态并计入“需处理”；点按该记录直接用专辑名搜索 AM，列出各版本供绑定。
- 宽松档自动选择其中一个：Explicit 优先，其次歌曲多的版本（通常包含另一版全部歌曲，整张专辑保持同一封面），最后目录 ID 较小者。任一候选页面未读完时保持可重试，不做选择；名称或艺人不同仍为歧义。曲目搜索打平时同样适用。
- 宽松档的选择单独缓存，严格/标准档读不到；宽松档查询决策键随之区分，严格/标准档的键不变。匹配严格度说明补充三档区别。
- 新增 US 公开网页专辑区与两张《MAYHEM》专辑页的字段裁剪样本（2026-10-13）。
- 修复符号名称的专辑无法绑定、无法自动匹配或互相串扰：`=`、`÷`、`+`、`×`、`-`、`?` 这类没有字母数字的名称原先归一成空串，被当成“没有填写专辑名”（本地专辑 `=` 绑定 Ed Sheeran《=》时提示需填专辑名）；`÷ (Deluxe)` 与 `- (Deluxe)` 则都折叠成 `deluxe`，绑定其中一张会顶掉另一张，缓存与最近记录也合为一条。现在纯符号名称保留符号本身，以符号开头的名称保留首个符号词（`÷ (Deluxe)` 即 `÷ deluxe`，`= (Tour Edition)` 即 `= tour edition`）；以字母数字开头的名称比较结果完全不变。样例为 2026-10-14 US 的《=》（1581087024，有动态封面）与《= (Tour Edition)》（1625197538，无动态封面）。
- 匹配身份重构：商店标签（艺人原生别名、Explicit/Clean、合作信用、译名括注、发行类型）改为具名折叠，由每个比较按需声明；“字段是否填写”只看原文，不再由折叠结果代理；Explicit/Clean 等价与宽松档同名版本选择合并为同一个取舍入口。新增按性质断言的 `AmNameIdentityTest`。由名称派生的缓存键升版（AM 决策 `match-v9-1080`、专辑快照 `album-v4` / `album-edition-v2`、网易云 `netease-query-v4` / `netease-album-native-v2`），已验证视频与手动绑定不受影响。
- 验证（2026-10-14）：260 项单测通过，debug lint 0 errors（6 条既有 warning 均在 manifest / build 配置），Debug 构建通过。用户已在设备上确认 `artwork-provider-am-1.0.5-debug-identity.apk`（sha256 `3616c2e8…`）：US 手动搜索列出两张《MAYHEM》；本地专辑 `=` 绑定 Ed Sheeran《=》成功；符号专辑各绑各的不互相覆盖；自动匹配正常。
- 新增本地动态封面导入：手动绑定页“本地专辑”卡片的“仅限歌手”下方新增“导入本地视频”，填写专辑名后才可点按，经系统文件选择器选一个视频，导入后直接绑定到该本地专辑名（可限定歌手）。导入前检查：必须是视频、时长 1 秒至 2 分钟（容器时长超出不到 1 秒的按 2 分钟截断）、短边不低于 320。已是方形、不超过 1080、SDR 8 位 H.264 的视频只复制视频轨，保留原画质；其余（横竖屏、HEVC、HDR、旋转、超过 1080、高帧率）居中裁剪为方形，压缩为最高 1080、最高 30 帧的 H.264，码率按 AM 封面密度并受 20 MB 上限约束，超限时降码率重试一次。音轨一律去除。输出经与锁屏相同的文件校验后，按内容 SHA-256 保存在应用私有目录，不随缓存清理或 LRU 淘汰；删除或替换绑定即删除不再被引用的文件。锁屏请求命中本地绑定时不联网，直接返回该文件。
- 本地导入验证（2026-10-14）：269 项单测通过（新增 `AmLocalPolicyTest` 覆盖格式判定、方形边长、码率预算、降帧与四种旋转的裁剪矩阵，绑定测试覆盖本地来源的校验与替换），debug lint 0 errors，Debug 构建通过。编解码与 GLES 裁剪链路只能在设备上验证，尚无设备结果。
- 修复 fragmented MP4 被误判为需压缩：`mvhd` 时长写 0、无 `mehd`、moov 样本表为空的视频（如 `P1506810150_…_1080x1080-.mp4`，样本时长只写在 `tfhd` 默认值里）读不到时长，被判为"未知时长"而走重编码，方形 1080 SDR H.264 的视频也显示"已压缩"。现在时长缺失时读取视频自身的样本时间补齐；时长仍未知时也允许走复制路径，由复制输出经文件校验后决定，过长仍转压缩并截断到 2 分钟。
- 用户已设备验收（2026-10-14，`artwork-provider-am-1.0.5-debug-local-import.apk`，sha256 `63bce4b0…`）：专辑名为空时导入按钮置灰；方形 1080 H.264 视频显示"原始画质"且快速完成；手机竖拍视频裁剪后方向正确；HDR 视频颜色正常；超 2 分钟提示正确；导入可取消；锁屏显示导入的封面，删除绑定后恢复自动匹配。

## English

- Fix manual album search in US and similar storefronts: the iTunes album index can lack an album outright (Lady Gaga's MAYHEM) while still answering with other artists' albums of the same name, so its results cannot reveal the gap. Manual search now reads the selected storefront's Apple Music album shelf; outside CN, iTunes answers only when the shelf is empty or unreadable, and an unreadable shelf without iTunes hits is an error, not an empty search.
- Several verified same-titled editions by one artist (MAYHEM's 14- and 17-song releases): Strict and Standard still leave the choice to the user. Recent records show a new "Several editions" status that counts as needing attention; tapping it searches AM for the album and lists the editions to bind.
- Loose picks one: Explicit first, then the most songs (usually a superset, so the whole album keeps one cover), then the earliest catalog ID. It does not choose while any candidate page is unread; different titles or artists stay ambiguous. A tie in the song search is settled the same way.
- Loose's choice is cached apart, out of Strict and Standard's reach, and its query decisions get their own key; Strict and Standard keys are unchanged. The strictness summary explains the three levels.
- Add field-trimmed public US samples of the album shelf and both MAYHEM album pages (2026-10-13).
- Fix symbol-named albums that could not be bound, could not be matched, or stood in for each other. `=`, `÷`, `+`, `×`, `-` and `?` carry no letter or digit, so they folded to nothing and read as a missing album name (binding local album `=` to Ed Sheeran's "=" asked for an album name); `÷ (Deluxe)` and `- (Deluxe)` both folded to `deluxe`, so binding one replaced the other and the two shared one cache entry and one recent record. A symbol-only name now keeps its symbols and a name that opens with a symbol keeps that leading token (`÷ (Deluxe)` is `÷ deluxe`, `= (Tour Edition)` is `= tour edition`); names that open with a letter or digit fold exactly as before. Sample: US "=" (1581087024, has a motion cover) and "= (Tour Edition)" (1625197538, none) on 2026-10-14.
- Matching identity refactor: store labels (native artist alias, Explicit/Clean, guest credit, translated annotation, release type) are named folds each comparison declares; "is this field filled in" reads the raw text instead of a folded form; the Explicit/Clean equivalence and the Loose same-title choice share one selection entry point. New property-based `AmNameIdentityTest`. Name-derived cache keys are versioned (`match-v9-1080`, `album-v4` / `album-edition-v2`, `netease-query-v4` / `netease-album-native-v2`); verified videos and manual bindings are unaffected.
- Verification (2026-10-14): 260 unit tests, debug lint with 0 errors (6 pre-existing warnings in the manifest and build configuration), Debug build passed. The user confirmed `artwork-provider-am-1.0.5-debug-identity.apk` (sha256 `3616c2e8…`) on the device: US manual search lists both MAYHEM editions, local album `=` binds to Ed Sheeran's "=", symbol-named albums bind separately without replacing each other, and automatic matching works.
- Add local motion-cover import: the manual binding page's Local album card has an "Import local video" button below "Only for this artist", enabled once an album name is entered. A video picked through the system file picker is bound to that local album name (optionally one artist). It must be a video of 1 second to 2 minutes (a container less than a second over is trimmed to 2 minutes) whose shorter side is at least 320. A square SDR 8-bit H.264 video up to 1080 keeps its original picture by copying only its video track; anything else (landscape or portrait, HEVC, HDR, rotated, larger than 1080, high frame rate) is centre-cropped to a square and compressed to H.264 of at most 1080 and 30 fps, at AM's cover density within the 20 MB limit, with one lower-rate retry on overshoot. Audio is always dropped. The result passes the same file check the lock screen runs and is stored privately under its content SHA-256, outside the cache, so clearing or evicting the cache never removes it; deleting or replacing the binding deletes the file once nothing names it. A lock-screen request for a locally bound album answers from that file without going online.
- Local import verification (2026-10-14): 269 unit tests (new `AmLocalPolicyTest` covers format decisions, square side, rate budget, frame dropping and the crop transform under all four rotations; binding tests cover validation and replacement of the local source), debug lint with 0 errors, Debug build passed. The codec and GLES crop path can only be verified on a device and has no device result yet.
- Fix a fragmented MP4 being re-encoded unnecessarily: a video whose `mvhd` duration is zero, with no `mehd` and empty moov sample tables (e.g. `P1506810150_…_1080x1080-.mp4`, whose sample duration lives only in the `tfhd` defaults) read as having no length, so an already square 1080 SDR H.264 clip reported "compressed". The length is now read from the video's own sample times when the container states none; a still-unknown length is allowed to take the copy path, and the copy's own duration decides when it is verified. A clip that is genuinely too long is still re-encoded and trimmed to 2 minutes.
- Device-accepted by the user (2026-10-14, `artwork-provider-am-1.0.5-debug-local-import.apk`, sha256 `63bce4b0…`): the import button is disabled until an album name is entered; a square 1080 H.264 video reports original quality and finishes at once; a portrait phone recording is cropped the right way up; HDR colour is correct; a clip over 2 minutes is refused with the right message; an import can be cancelled; the lock screen shows the imported cover, and deleting the binding returns the album to automatic matching.

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
