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
