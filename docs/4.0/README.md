# ColorOS Live Lyrics Providers 4.0

这是从旧 `LyricProvider` 独立出来的 4.0 Provider 仓库。Phase 0 的初始 source commit 为旧仓库 `master` 的 `292a7da3f88a87e8c6df6b4ae4f56455b6856c72`；当前分支为 `4.0`。

当前只完成基线和迁移映射，旧 namespace、applicationId、词幕依赖与 v4 sender 尚未删除。实施顺序、删除门禁和 v4→v5 边界见 [`PHASE-0-MIGRATION-MAP.md`](PHASE-0-MIGRATION-MAP.md)。

新仓库没有配置旧 LyricProvider remote，避免 4.0 变更误推送到旧发布仓库。许可证、第三方来源和历史署名在迁移中必须继续保留。

