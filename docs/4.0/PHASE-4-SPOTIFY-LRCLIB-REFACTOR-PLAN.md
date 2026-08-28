# Spotify Provider LRCLIB 重构方案

状态：**方案已完成，尚未实现**  
日期：2026-08-28  
目标模块：`:player-spotify`  
应用 ID：`io.github.andrealtb.coloroslyrics.provider.spotify`

## 1. 重构结论

Spotify Provider 推倒现有取词实现，改为：

- 只从 Spotify 标准 `MediaSession#setMetadata` 获取曲目身份和展示元数据。
- 只向 LRCLIB 公共 API 请求歌词。
- 不再读取、调用或嗅探 Spotify 内部歌词、内部认证、Color Lyrics、OkHttp/Cronet 请求。
- 保留现有 generation、防旧结果串歌、主 MediaSession 选择、封面保护、pending/replay 和原生 `lyricInfo` 发布链。
- Bridge 不增加 LRCLIB 专属协议或业务逻辑，Provider 与 Bridge 继续解耦。

目标状态：

```text
Spotify MediaSession metadata
        ↓
spotify:track: ID + title/artist/album/duration
        ↓
SpotifyTrackSignature（本地强身份）
        ↓
LRCLIB /api/get 精确签名查询
        ↓
本地严格复核；必要时 /api/search 唯一候选兜底
        ↓
syncedLyrics → LINE_TIMED RichLyricLine
        ↓
Spotify track ID + generation 二次校验
        ↓
NativeLyricInfoPublisher → Spotify 主 MediaSession
```

## 2. 依据与边界

### 2.1 继续采用的 Spotify 逆向结论

依据：

- `PlayerSource/Spotify/SPOTIFY-CONSTRUCTED-LYRICINFO-INVESTIGATION.md`
- `docs/4.0/PHASE-4-SPOTIFY-MIGRATION-REPORT.md`

继续采用：

1. 宿主仅为 `com.spotify.music` 主进程；跳过 `:push`、`:download` 等辅助进程。
2. 权威曲目身份来自平台 `MediaMetadata.METADATA_KEY_MEDIA_ID`。
3. 音乐曲目 ID 形态为 `spotify:track:<rawId>`；`spotify:episode:`、`spotify:show:`、广告不得拉词。
4. title、artist、album、duration 和 artwork 均来自同一次或同曲后续 `setMetadata`。
5. Spotify 没有可供 ColorOS 使用的官方 `lyricInfo`，仍需 Provider 构造并覆盖到主 `MediaSession`。
6. 不改写宿主 `setPlaybackState`，不恢复 Lyricon/v4 广播，不获取或伪造封面。
7. `spotify:track:` ID 继续作为 generation、缓存、异步结果拒绝和发布绑定的第一身份。

### 2.2 作废的旧结论

以下内容全部作废，不再作为 Spotify Provider 设计的一部分：

- `guc3-spclient.spotify.com/color-lyrics/v2/track/{id}`。
- `authorization`、`client-token`、`x-client-id`、Spotify `user-agent` 的捕获与复用。
- `okhttp3.Headers`、Cronet `UrlRequest.Builder#addHeader` Hook。
- DexKit 搜索 Spotify 网络实现。
- 401/token 刷新、Spotify 内部 403/区域策略。
- Color Lyrics JSON、`transliteratedWords`、`SYLLABLE_SYNCED` 解析。
- 按系统语言缓存 Spotify Color Lyrics 响应。

设备日志 `logs/lyrics-log-20260828-114320.txt` 已证明内部网络依赖不稳定：宿主不存在
`okhttp3.Headers`，首曲仅因旧磁盘缓存可用，后续歌曲一直等待空认证头。新架构从根本上删除该依赖。

## 3. LRCLIB 官方 API 约束

官方文档：<https://lrclib.net/docs>

### 3.1 客户端要求

- 无 API Key、无需注册。
- 必须设置能识别本客户端的 `User-Agent`，包含应用名、版本及项目地址或联系方式。
- 请求必须串行；批量操作建议每次请求间隔 200–500ms。
- HTTP 429 必须读取并遵守 `Retry-After`，不可继续立即请求。

Provider 使用：

```text
User-Agent: ColorOS-Live-Lyrics-Spotify/<version> (<project-or-contact-url>)
Accept: application/json
```

不得把 Spotify token、cookie、账户标识或 Spotify track ID 放入请求头。

### 3.2 可用端点

#### `GET /api/get`

按曲目签名取得最佳匹配：

| 参数 | 要求 | Provider 用法 |
|---|---|---|
| `track_name` | 必需 | Spotify title，清理 UI 装饰后发送 |
| `artist_name` | 必需 | Spotify artist，清理 UI 装饰后发送 |
| `album_name` | 推荐 | 有值必须发送 |
| `duration` | 强烈推荐 | Spotify 毫秒时长四舍五入为秒，范围必须为 1–3600 |

官方文档明确：时长应完全匹配，或最多相差 ±2 秒。404 表示当前没有匹配，但后台服务以后可能补齐。

#### `GET /api/get/{id}`

这里的 `id` 是 **LRCLIB 数字记录 ID**，不是 Spotify track ID。只用于已建立本地映射后的缓存刷新。

#### `GET /api/search`

最多返回 20 条且无分页。仅作为 `/api/get` 返回 404或本地复核失败后的严格兜底，不作为宽松模糊匹配入口。

### 3.3 响应字段

首版只使用：

- `id`
- `trackName`
- `artistName`
- `albumName`
- `duration`
- `instrumental`
- `plainLyrics`
- `syncedLyrics`

`lyricsfile` 当前始终存在，但格式仍处于演进阶段。首版不解析它，不借此引入新的逐字格式依赖。

## 4. Spotify ID 与“精确匹配”的关系

LRCLIB 当前没有 `spotify_id`、Spotify URI 或 ISRC 查询参数。因此不得声称“把 Spotify ID 发给
LRCLIB 就能精确命中”。正确设计是：

- Spotify ID：本地权威身份、缓存主键、generation 绑定和防串歌依据。
- title/artist/album/duration：LRCLIB 远端查询签名。
- LRCLIB ID：成功匹配后保存在该 Spotify ID 的本地映射中。

本地映射：

```text
spotify:track:<id>
    → canonical signature hash
    → LRCLIB numeric id
    → validated lyrics response
```

任何异步结果在发布前仍必须同时满足：

1. Spotify ID 与当前曲目相同。
2. generation 仍有效。
3. LRCLIB 返回记录通过本地严格匹配。

## 5. 曲目签名与元数据清洗

新增 `SpotifyTrackSignature`：

```kotlin
data class SpotifyTrackSignature(
    val spotifyUri: String,
    val rawSpotifyId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val durationSeconds: Int,
    val canonicalTitle: String,
    val canonicalArtist: String,
    val canonicalAlbum: String?
)
```

### 5.1 查询前置条件

- 必须是完整 `spotify:track:` ID。
- title 和 artist 必须非空。
- duration 必须位于 1–3600 秒。
- album 可空，但同曲后续 metadata 补齐时要合并。
- 首次 metadata 后做约 200ms 同曲 debounce，等待 album/duration 填充；换曲立即取消。
- 身份不完整时不 latch generation，不请求 LRCLIB。

### 5.2 允许的 canonicalization

仅做不会改变歌曲版本语义的确定性清洗：

- Unicode NFKC。
- trim、连续空白折叠、大小写折叠。
- 统一等价的弯引号、撇号和破折号表示。
- 从 Spotify artist 尾部删除明确的 UI 装饰 allowlist，例如“为你推荐”或
  `Recommended for you`，但只有当它位于独立分隔符后的完整后缀时才删除。

明确禁止：

- 删除 `Live`、`Remix`、`Remaster`、`Acoustic`、`From ...`、版本号或电影/专辑限定词。
- 任意删除括号内容。
- 改写、排序或模糊包含多个艺人名称。
- 只凭标题、第一位艺人或相近时长接受候选。

## 6. LRCLIB 严格匹配策略

### 6.1 第一阶段：`/api/get`

始终发送可用的四字段签名：title + artist + album + duration。200 响应仍需本地复核，不能直接信任
“best match”。

接受条件：

| 字段 | 条件 |
|---|---|
| title | canonical 后完全相等 |
| artist | canonical 后完全相等 |
| duration | 绝对差 ≤2 秒 |
| album | 双方均非空时必须 canonical 相等；一方为空只降低置信度 |
| lyrics | `instrumental=true`，或存在可解析的 `syncedLyrics` |

title、artist 或 duration 任一不满足即拒绝。不得用分数把硬不匹配“补回来”。

### 6.2 第二阶段：`/api/search`

仅在以下情况触发一次：

- `/api/get` 返回 404。
- `/api/get` 返回的记录未通过本地硬校验。

使用结构化 `track_name`、`artist_name`、`album_name`，不使用宽泛 `q`。对最多 20 条候选应用与上面相同的
硬过滤：

1. title 完全相等。
2. artist 完全相等。
3. duration 差 ≤2 秒。
4. album 相等者优先。
5. 过滤后必须只有一个可接受候选；多个同级候选视为 `AMBIGUOUS`，不发布。

精确优先于覆盖率：宁可没有歌词，也不把同名歌曲、现场版、混音版或其他艺人的歌词写入当前 Spotify ID。

## 7. 网络调度、重试与限流

新增单进程 `SpotifyLrclibRequestCoordinator`：

- 使用 `Mutex` 保证 LRCLIB 请求串行。
- 两次实际 HTTP 请求之间至少间隔 300ms。
- 连接/读取超时各 10 秒。
- 请求被 generation 取消时立即取消 OkHttp call。
- HTTP 404：记录短期负缓存，不做立即重试。
- HTTP 429：解析 `Retry-After` 秒数或 HTTP 日期，设置全局 cooldown；同 generation 最多在 cooldown
  结束后重试一次。
- HTTP 5xx/IO/timeout：属于瞬时失败，不写 `NO_LYRIC`；当前 generation 最多退避重试一次。
- JSON/身份校验失败：不重试，不发布，不污染正缓存。
- `/api/publish` 完全不实现，Provider 不向第三方数据库上传内容。

新的 outcome：

```text
Lyrics
Instrumental
NotFound
Ambiguous
IdentityRejected
RateLimited(retryAt)
TransientFailure
DecodeFailure
```

只有 `Lyrics` 可以进入发布链。其他 outcome 都不得携带上一曲 payload。

## 8. 缓存设计

旧目录 `cacheDir/spotify-lyrics/<language>/<spotifyId>.json` 属于 Color Lyrics 响应，重构后只忽略，
不在升级时递归删除。

新目录：

```text
cacheDir/spotify-lrclib/v1/<rawSpotifyId>.json
```

缓存内容至少包括：

- schemaVersion
- raw Spotify ID
- canonical signature hash
- LRCLIB ID
- LRCLIB track/artist/album/duration
- syncedLyrics
- instrumental
- match route（`get` / `search-unique`）
- fetchedAt
- expiresAt

策略：

- 正命中/纯音乐：30 天。
- 404：6 小时；官方说明缺失曲目以后可能被后台补齐，因此禁止永久负缓存。
- ambiguous/identity-rejected：1 小时。
- 429、5xx、网络失败：不写负缓存。
- Spotify ID 相同但 canonical signature hash 不同：缓存无效，重新查询。
- 写入采用临时文件加原子替换，避免进程终止留下半个 JSON。

缓存命中仍必须重新绑定当前 Spotify ID 和 generation；缓存永远不能绕过防串歌校验。

## 9. 歌词解码与发布

### 9.1 首版支持

- `instrumental=true`：输出 `Instrumental`，不构造假歌词。
- `syncedLyrics`：使用 `EnhanceLrcParser` 解析为 `RichLyricLine`。
- LRCLIB 标准 LRC 只按逐行歌词处理，预期落为 `LINE_TIMED`；不得伪造逐字时间。
- `translationLyric` 保持空；Spotify Provider 仍不支持翻译。

### 9.2 首版不支持

- 只有 `plainLyrics`、没有有效 `syncedLyrics` 的记录不发布。当前通用 encoder 会把无时轴行编码为全
  `00:00`，会破坏高亮和滚动，因此首版记录 `PLAIN_ONLY` 后结束。
- 不解析 `lyricsfile`。
- 不根据总时长人工均分歌词时间。
- 不从其他歌曲版本借用时间轴。

以后如需支持无时间歌词，必须先给 `provider-core` 增加真正的 UNTIMED 编码契约并单独做 Bridge 验证，
不能在本次 Spotify 重构中偷偷加入。

### 9.3 发布保持不变

继续使用：

- `SpotifyNativePublisher`
- `NativeLyricInfoPublisher`
- 空 typed `MediaMetadata.Builder()`
- artwork 软件化/尺寸保护
- pending 附着 incoming metadata
- live `controller.metadata` drain
- replay snapshot

`songId` 继续为 `spotify:track:<id>`，`source` 继续遵循通用
`com.spotify.music-v5` 契约；LRCLIB 来源通过 Provider diagnostics 和 publication `sourceName` 表达，
不修改 Bridge 通用协议。

## 10. 源码改造清单

### 10.1 删除

| 文件/依赖 | 原因 |
|---|---|
| `SpotifyAuthHeaderPolicy.kt` | 不再捕获 Spotify 凭据 |
| `SpotifyCronetHeaderResolver.kt` | 不再 Hook Spotify 网络栈 |
| `SpotifyColorLyricsApi.kt` | 删除 Color Lyrics API |
| `SpotifyRetryPolicy.kt` | 删除 header wait/401 策略 |
| `SpotifyAuthHeaderPolicyTest.kt`、`SpotifyCronetHeaderResolverTest.kt`、`SpotifyRetryPolicyTest.kt` | 契约已作废 |
| `implementation(project(":reflection-core"))` | Spotify 不再需要运行时反射发现 |
| `implementation(libs.dexkit)` | 删除 DexKit 及四 ABI `libdexkit.so` |

### 10.2 重写

| 文件 | 改造内容 |
|---|---|
| `SpotifyPlayerHooker.kt` | 删除全部 header hook、auth store 和 headers-ready retry；保留 MediaSession、generation、pending/replay；接入 LRCLIB outcome |
| `SpotifyLyricFetcher.kt` | 改为 LRCLIB 精确查询编排，不再接受 auth headers |
| `SpotifyDiskCache.kt` | 改为 versioned Spotify-ID→LRCLIB 映射和 TTL 缓存 |
| `SpotifyLyricDecoder.kt` | 改为 `syncedLyrics` LRC 解码；删除 Color Lyrics/Syllable 模型 |
| `SpotifyLyricFetchGate.kt` | 要求完整 query signature；瞬时失败/429 可按策略 unlatch，404 保持短期负缓存 |
| `SpotifyPlayerConstants.kt` | 删除 Color Lyrics URL，增加 LRCLIB URL、User-Agent 和 throttle 常量 |
| `SpotifyPublication.kt` | 默认 `sourceName="LRCLIB"`，保留 Spotify TrackIdentity 绑定 |

`SpotifyDiskCacheTest.kt` 和 `SpotifyLyricDecoderTest.kt` 不删除，分别重写为 LRCLIB cache/decoder 契约测试。

### 10.3 新增

| 文件 | 职责 |
|---|---|
| `SpotifyTrackSignature.kt` | Spotify metadata 清洗、canonicalization、签名哈希 |
| `SpotifyLrclibModels.kt` | API response/error/cache DTO |
| `SpotifyLrclibApi.kt` | `/api/get`、`/api/get/{id}`、`/api/search` 与 HTTP 分类 |
| `SpotifyLrclibMatchPolicy.kt` | title/artist/duration/album 硬校验和唯一候选决策 |
| `SpotifyLrclibRequestCoordinator.kt` | 串行、300ms throttle、429 cooldown、取消 |

### 10.4 原样保留

- `HookEntry.kt`
- `SpotifyAdvertisementPolicy.kt`
- `SpotifyArtworkPolicy.kt`
- `SpotifyHostGenerationController.kt`
- `SpotifyMediaSessionRegistry.kt`
- `SpotifyMetadataArtwork.kt`
- `SpotifyNativePublisher.kt`
- `SpotifyPendingPublicationPolicy.kt`
- `SpotifyReplayPolicy.kt`
- `SpotifyTrackBindPolicy.kt`（只补充 query-ready 判定和 UI suffix sanitizer 接口）
- `SpotifyTrackIdentity.kt`
- `SpotifyDebugSettingsActivity.kt`

## 11. 现有 LRCLIB 代码的复用判断

仓库已有两套 LRCLIB 实现，但均不能直接复用为 Spotify 精确匹配：

1. `player-metrolist/MetrolistLyricsFetcher.kt`
   - 使用 `/api/search` 多轮宽松回退。
   - 时长容差为 5 秒。
   - 可退化到仅标题或宽泛 `q`。
   - 会优先取任意同步候选，不满足本方案的严格身份要求。
2. `share/cloudlyric/.../LrcLibProvider.kt`
   - 主要按字段完整度排序，不校验 Spotify ID/generation。
   - 未实现 `/api/get` 精确签名、±2 秒硬限制、429 cooldown 和负缓存。

因此首版在 `player-spotify` 内实现窄而严格的 LRCLIB client。真机验证稳定后，才考虑抽成通用
`:source-lrclib`；不得先改动 Metrolist 等其他 Provider。

## 12. 诊断事件

保留 `[CLL] component=provider/spotify`，新增：

```text
LRCLIB_SIGNATURE_READY
LRCLIB_CACHE_HIT
LRCLIB_CACHE_STALE
LRCLIB_GET_STARTED
LRCLIB_GET_HIT
LRCLIB_GET_404
LRCLIB_SEARCH_STARTED
LRCLIB_CANDIDATE_REJECTED
LRCLIB_SEARCH_UNIQUE_HIT
LRCLIB_AMBIGUOUS
LRCLIB_RATE_LIMITED
LRCLIB_TRANSIENT_FAILURE
LRCLIB_PLAIN_ONLY
LRCLIB_DECODED
SPOTIFY_FINAL_PUBLISHED
SPOTIFY_FINAL_NO_LYRIC
SPOTIFY_FINAL_STALE
```

日志只记录 Spotify ID、generation、候选数量、LRCLIB ID、时长差、匹配/拒绝原因和歌词字符/行数；
不记录完整歌词、请求响应正文或用户隐私字段。

删除所有 `AUTH_HEADERS_*`、`LYRIC_HEADERS_*`、`OKHTTP_*`、`CRONET_*` 事件。

## 13. 自动测试矩阵

### 13.1 Track signature

- 完整 `spotify:track:` 才能生成 signature。
- title/artist/duration 缺失不 latch。
- 同 Spotify ID 后续 album 补齐不换代。
- NFKC、空白、大小写、弯引号归一。
- 仅删除 allowlist 中的 Spotify 推荐后缀。
- `Live/Remix/Remaster/Acoustic/From ...` 必须保留。

### 13.2 Match policy

- title/artist 完全匹配 + duration 0、1、2 秒差：接受。
- duration 2.001 秒或 3 秒差：拒绝。
- 同名不同艺人：拒绝。
- studio/live、album/remix 不同：拒绝。
- search 过滤后唯一候选：接受。
- 两个同级候选：`AMBIGUOUS`。
- LRCLIB ID 绝不能与 Spotify ID 混用。

### 13.3 API/coordinator

- `/api/get` URL 编码和四字段参数。
- `User-Agent` 与 `Accept`。
- 请求串行及最小 300ms 间隔。
- 404、429 + `Retry-After`、5xx、timeout 分类。
- generation 取消会取消 HTTP call。
- 不调用 `/api/publish`。

### 13.4 Decoder/cache

- synced LRC → LINE_TIMED。
- plain-only → `PLAIN_ONLY`，不生成全 00:00 payload。
- instrumental → `Instrumental`。
- 无效 JSON/LRC → `DecodeFailure`。
- 正缓存、404 TTL、signature hash 失配、原子写入。
- 旧 Color Lyrics 缓存不会被读取。

### 13.5 现有发布回归

现有以下测试必须继续通过：

- TrackIdentity/generation
- advertisement/podcast
- artwork
- NativePublisher
- PendingPublicationPolicy
- ReplayPolicy
- FetchGate

## 14. 实现顺序

### Slice A：纯策略和 API 契约

新增 LRCLIB DTO、signature、match policy、HTTP 分类和单元测试；不接入 Hooker。

### Slice B：Fetcher 与缓存

实现 `/api/get`、严格 search fallback、coordinator、缓存和 decoder，使用 fixture 测试，不访问真实 API。

### Slice C：替换运行时链

删除 auth/Cronet/DexKit，改写 `SpotifyPlayerHooker` 和 fetch gate，接回 pending/publisher/replay。

### Slice D：清理与构建

删除作废文件/测试/依赖，更新迁移报告、README 和诊断事件表；执行：

```text
:player-spotify:compileDebugUnitTestKotlin
:player-spotify:testDebugUnitTest
:player-spotify:packageDebug
```

若项目全局 JUnit runner 仍出现既有 ClassNotFound，必须至少完成 test source 编译、模块 APK 构建，并明确记录
runner 故障，不能把它写成测试通过。

## 15. 真机验收

设备验收必须覆盖：

1. 清缓存后的首曲在线命中，出现 `LRCLIB_GET_HIT → LRCLIB_DECODED → SPOTIFY_FINAL_PUBLISHED`。
2. 同曲重播命中新缓存，不再访问网络。
3. 快速连续切三首歌，旧 generation 结果全部丢弃，新歌歌词可达。
4. 重启 Spotify 后播放相同歌曲，仍能绑定并发布。
5. Smart Shuffle/推荐歌曲 artist 尾部带 UI 装饰时仍生成正确 signature。
6. 同名不同艺人、live/studio、remix/original 不串歌词。
7. 404 后不立即重复请求，6 小时负缓存不永久化。
8. 人工模拟 429 时遵守 `Retry-After`，无请求风暴。
9. podcast、show、广告完全不请求 LRCLIB。
10. Provider APK 不再包含 `libdexkit.so`，日志不存在 auth/Cronet 事件。
11. 锁屏/AOD/highlight/长歌词滚动继续由现有 Bridge 链工作；Provider 重构不新增 Bridge 特例。

验收成功链：

```text
SESSION_METADATA_OBSERVED
→ TRACK_BOUND
→ LRCLIB_SIGNATURE_READY
→ LRCLIB_GET_STARTED
→ LRCLIB_GET_HIT 或 LRCLIB_SEARCH_UNIQUE_HIT
→ LRCLIB_DECODED
→ NATIVE_LYRIC_INFO_COMMITTED
→ SPOTIFY_FINAL_PUBLISHED
→ SystemUI LINE_TIMED
```

## 16. 完成判定

只有同时满足以下条件，才可关闭 Spotify 重构：

- 源码中无 Color Lyrics URL、Spotify auth header、OkHttp/Cronet 宿主 Hook、DexKit 网络发现。
- LRCLIB 请求完全符合官方 User-Agent、串行、throttle 和 429 要求。
- Spotify ID、generation 和 LRCLIB 本地硬校验三层均存在。
- 快速切歌、重启 Spotify、Smart Shuffle、同名歌曲版本在真机日志中无串歌。
- 新歌非缓存场景可稳定发布，不能只用旧缓存证明成功。
- Bridge 无 LRCLIB 专属协议，Spotify Provider 仍只发布标准 `lyricInfo`。
