# DIVA Launcher v1.3 — 交互式存档 GUI（参考 shadPS4）

## 下载
[DIVA-Launcher-v1.3-SaveGUI.apk](DIVA-Launcher-v1.3-SaveGUI.apk)（sha256 见 `.sha256`）

## 本版内容
- **真正的存档选择界面**：参考 shadPS4 的 SaveDataDialog 交互，用 Android 原生
  AlertDialog 显示"新建存档 + 已有槽位 + 取消"。native 层扫描存档槽并发起请求，
  Java 层弹界面，玩家选择后回写结果；native 按 shadPS4 的 OrbisSaveDataDialogResult
  布局填充后交还游戏。超时（默认 600s）会自动应答，不会卡死。
- 基于 v1.2.1（已回退 v1.2 的坏 AOT 库），画面正常；AHB 驱动与 v1.1 完好 AOT 库不变。
- 签名与旧版一致，直接覆盖安装。

## 重要说明
存档 dialog 本身在 v1.1 就已不再阻塞游戏。真正让游戏在存档流程里停下来的是更深层的
`native code is missing an entry at +0xa97d5`（全 AOT 严格模式，无解释器回退）。
GUI 让存档选择变得真实可见，但**选完继续加载时仍可能撞到这个 AOT miss**。
详见 `docs/SAVEDIALOG-FIX.md` 的"仍存在的底层阻塞"一节。

## 若仍卡住
抓新 `diva-boot.log`：若出现 `SaveDataDialog request posted` + `selection ->`
说明 GUI 桥接工作正常；若随后停在 `+0x.....` 的 AOT miss，把地址发我。
