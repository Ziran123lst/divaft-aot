# DIVA Launcher v1.1 — 存档界面黑屏修复

## 下载
[DIVA-Launcher-v1.1-SaveDialog.apk](DIVA-Launcher-v1.1-SaveDialog.apk)
（sha256 见 `.sha256` 文件）

## 修复内容
基于 v1.0-AHBfix（AHB 驱动修复版），额外补全了 PS4 `libSceSaveDataDialog`
的缺失函数（`GetResult` / `IsReadyToDisplay` / `CommonDialogIsUsed` 等）。
真机日志确认存档页面黑屏是因为这些函数是未实现的 STUB，游戏拿不到存档选择
结果、卡在等待循环、present 停帧。本版让 dialog 自动应答一个合法结果
（slot=`SAVEDATA00`），游戏自己的存档菜单即可继续。

## 安装
- 直接覆盖安装，签名与旧版一致，不丢存档/数据。
- 详细诊断与验证方法见 `docs/SAVEDIALOG-FIX.md`。
