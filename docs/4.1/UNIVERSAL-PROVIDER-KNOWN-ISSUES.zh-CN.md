# Universal Provider 已知问题

## 蓝牙歌词/车载歌词冲突

通用 Provider 依赖 Android 标准 MediaSession metadata 识别歌曲。部分播放器开启蓝牙歌词或车载歌词后，会用当前歌词行覆盖 `MediaMetadata.TITLE`。在播放器没有提供稳定 media ID、queue ID、media URI，且当前歌曲尚无本地缓存时，Provider 无法可靠获得真实歌名。

可能出现：

- 冷启动首曲没有歌词，切歌后才恢复；
- 歌词获取延迟；
- 歌词匹配错误；
- metadata 变化被误认为切歌。

发布和使用说明必须包含以下提示：

> 使用 Universal Lyrics Provider 时，请关闭播放器的蓝牙歌词、车载歌词或类似功能。暂不支持两者同时启用。

该限制是当前版本的已知缺陷，不作为发布阻断项。

## 粗粒度进度与撕裂 metadata（UAPP 类播放器）

部分播放器（例如 USB Audio Player PRO）没有 media ID / queue ID / media URI，以约 1 Hz 重发 PlaybackState，position 取自解码块计数（设备日志观察到 400 ms 量化），并在换曲后异步发布 metadata：TITLE 为换曲时刻的快照，ARTIST/ALBUM/DURATION 取发布时刻的当前曲目。静态证据见 `PlayerSource\UAPP\UAPP-METADATA-TIMING-INVESTIGATION.md`。

通用 Provider 的处理（只读，不按包名分支，不改写 PlaybackState）：

- **身份闸门**（`TrackIdentityResolver`）：换曲后短时间内出现的“旧标题 + 当前曲目 artist/album/duration”被识别为过期标题，保持当前 generation；“当前标题 + 其他锚点变化”的 payload 最多等待 2 s，等到自洽 metadata 时只换代一次，否则由定时复核接受，事件驱动播放器不会卡在旧身份上。
- **时钟质量回退**（`PlaybackClockQuality`）：统计同一曲目连续 PLAYING 锚点的 `position - (上一锚点 + Δt × speed)`；最近 8 个样本中至少 6 个 ≥ 90 ms 判为粗粒度，该播放器改发逐行 `rawLyric`（保留翻译）。当前曲目立即降级，恢复逐字只在下一首生效。设置项“进度粒度粗时自动逐行”默认开启，关闭后始终按“优先使用逐字时间轴”发布。

行号时钟（调查报告 P3）在 Bridge 侧实现，与 Provider 无关：Bridge 只改写 SystemUI 歌词列表控制器与定时方法的 position 参数，使官方行号与 Bridge 逐字填充共用 Bridge 的平滑时钟，并阻止带宽内（600 ms）的行号回跳；媒体卡片进度条和其他消费方不受影响。规则见 Bridge 仓库 `docs/LYRIC_RENDERING_OWNERSHIP.zh-CN.md` 的“进度时钟对齐”。2026-09-25 的设备日志确认控制器已挂上并生效（UAPP，自动逐行开启时）；关闭“进度粒度粗时自动逐行”后的逐字表现尚未测试，通过后 UAPP 类播放器可考虑改用逐字。

仍存在的限制：播放器进度相对实际声音的固定偏差（管线提前量）没有校准，需要按调查报告 §六 第 3 步实测标定后再评估。
