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
