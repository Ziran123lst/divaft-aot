# v2.1 — 修复空 dirName 挂载导致的读档退回菜单（真正根因）

v2.0 诊断版日志精确定位：`sceSaveData TRACE Mount2 user=1 dir='' mode=0x6` →
`mount REJECT ... name='' valid=0` → `Mount2 -> 0x809f0000`。

根因：游戏在读取存档时调用 `sceSaveDataMount2` 传入**空 dirName**（DIVA 用它挂载全局存档根），
对照 shadPS4 `sceSaveDataMount2` 只要求 `dirName != nullptr`、允许内容为空字符串并直接作为子目录名。
而我们的 `mount()` 用 `valid_name()` 拒绝了空字符串 → 返回 `ERR_PARAMETER(0x809F0000)` →
游戏弹 ERROR_CODE 对话框、退回菜单。这解释了为何日志里从未出现 `save data ... mounted`。

修复（仅重编 libbbprobe.so）：
- 新增 `valid_mount_name()`：允许空 dirName（长度 0 合法），仍拒绝 '/' '\\' '..' 等非法字符与未终止串。
- 仅 mount 路径改用该宽松校验；delete/search 继续用严格 `valid_name()`，不影响安全性。
- 空 dirName 时 host = <saveRoot>/<uid>/<title>/，作为该 title 的全局存档目录，与 shadPS4 语义一致。
- 保留 v2.0 的全部 sceSaveData TRACE 日志，便于验证修复生效（应能看到 `mounted at /savedata0`）。

覆盖安装后测试：进入读取存档 → 选 SAVEDATA00 → 应能看到 `save data '' mounted at /savedata0 (existing/created)`
并正常进入游戏，不再退回菜单。

## 不变项
签名一致（serial c2d1dead5839fd99）；AOT/DEX/AHB/manifest/资源不变；仅替换 libbbprobe.so（7bd1b38b...）。
sha256: 1c3273821134911059915b5eb8a26894993fa5fdd4c9716bd64b794bba5ba04e
