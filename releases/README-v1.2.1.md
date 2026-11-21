# DIVA Launcher v1.2.1 — 恢复画面（回退 v1.2 的坏 AOT 库）

v1.2 用 `--extra-entries` 全量重编 libbbaot.so 引入了回归：10 个 unit 编译失败、
游戏很早停在 `patched/hooked function has no variant at +0xc725530`，连画面都没了。

本版把 AOT 库**回退到 v1.1 的完好版**（57133 units，画面正常），保留 dialog 存档补丁
与 `sceSaveDataGetEventResult` 修复。直接覆盖安装即可恢复画面。
