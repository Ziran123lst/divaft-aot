# v2.0-Diag — 定位存档退回菜单的真正失败点

真机日志（pppprf.zip）确认：`ERROR_CODE errorCode=0x809f0000` 是**游戏自己**在读取存档失败后填入 ERROR_CODE 对话框的值，
对应 shadPS4 `Error::PARAMETER`（既不是 NOT_FOUND=0x809F0008，也不是 BROKEN=0x809F000F）。
日志里没有任何 `save data ... mounted` 记录 —— 说明游戏的 `sceSaveData*` 调用在挂载成功前就失败了。

由于旧版在所有失败分支都不打日志，无法确定是哪一步（Mount / Mount2 / DirNameSearch / SetupSaveDataMemory 等）返回了 PARAMETER。

本版仅重编 `libbbprobe.so`，为所有 sceSaveData* 调用加入详细 trace：
- `sceSaveData TRACE <API> -> 0x<返回值>`：每个调用的返回值
- `TRACE Mount/Mount2 user=... dir=... mode=...`：挂载参数（user / 目录名 / 模式位）
- `TRACE mount dir=... mode=... exists=... base=...`：宿主路径与目录是否存在
- `TRACE mount REJECT ...`：参数校验失败的细节
- `TRACE DirNameSearch NULL ...`、`TRACE SetupSaveDataMemory size=0 ...`：其它 PARAMETER 来源

请覆盖安装后，重现一次「读取存档 → 退回菜单」，抓 `diva-boot.log` 发回。
关键新增行以 `Runtime: sceSaveData TRACE` 开头，能直接指出失败的 API 与参数，据此精准修复。

## 不变项
签名一致（serial c2d1dead5839fd99，可覆盖安装）；AOT 库、classes.dex、AHB 驱动、manifest、资源全部不变；
仅替换 `lib/arm64-v8a/libbbprobe.so`。

sha256: 55b33856261b0fe9b50464b2e1613fd8c3a06418fbafdf01af85ed37e7a07970
