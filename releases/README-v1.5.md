# DIVA-Launcher v1.5 — Save GUI 3 (存档 GUI 修复)

真机日志 `osoaoaos.zip` 定位：存档黑屏不是 AOT 停机（本次 AOT 库为完好版，57133 units / 0 unsupported），而是 v1.4 存档 GUI 的两个 Java 层 bug：

1. **弹窗瞬间自毁**：`showManager()` 在 UI 线程先把 `dismiss()` 投递为异步消息，`dialog.show()` 之后该消息执行，把刚显示的 GUI 立刻关掉。native 端 `GetResult` 一直等不到 response，`bbpresent` 就一直 `DROP`（黑屏）。
2. **独立的全屏 Dialog 窗口**：Dialog 覆盖游戏时会触发底层 `SurfaceView` 的 `surfaceDestroyed` → `BbPresent.surface(null)`，进一步导致 `sc=0x0` 黑屏。

## v1.5 修复
- 存档 GUI 改为**直接 addContentView 到游戏 Activity 自己的窗口**（in-window overlay），不再新建 Dialog 窗口 → 不会销毁游戏 SurfaceView。
- `dismiss` 改为**同步**移除 overlay，杜绝 show 后立刻被异步 dismiss 干掉。
- 保留 v1.4 的 SEQ 令牌 + request 立即删除机制（防止重复弹窗）。
- 新增 `savedlg/bridge.log` 记录 Java 侧流程，便于下次日志定位：`DivaSaveDlg` tag / `bridge.log`。

## 不变项（覆盖安装安全）
- 签名与 v1.1～v1.4 完全一致（debug key，serial c2d1dead5839fd99）。
- AOT 库 sha256 `2a608885...`（完好 57133-unit 版）未变。
- AHB 驱动 sha256 `0fb864d8...` 未变。
- 仅替换 classes.dex；其余 .so / 资源 / manifest 全部沿用 v1.4。

安装：直接覆盖 v1.4 即可，无需卸载、无需清除数据。
