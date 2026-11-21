# DIVA-Launcher v1.8 — Save GUI 6（存档对话框交互修复）

v1.7 已修好：存档槽位显示（BB_SAVEDATA_DIR）与存档后 AOT 停机（0xa97d5 gap），
日志确认存档本身能创建、AOT 不再停机。本版修复剩下的交互问题。

## 现象
选存档后会退回进入存档界面前的菜单、无法进游戏；且存档界面选一次还会再弹，
一共要点三次才回退。

## 根因（native libbbprobe.so，对照 shadPS4 savedatadialog.cpp）
游戏的加载序列是 `mode=1(LIST 选槽位) -> mode=5(PROGRESS_BAR 进度条) -> mode=4(ERROR_CODE 信息提示)`。
按 PS4 语义，只有 LIST 需要玩家选槽位；5/4 只是显示进度/信息，游戏只轮询 status、从不调用 GetResult。

两个 bug：
1. `save_open` 对所有 mode 都写 request 弹槽位选择框 -> 玩家被迫点三次；第三次(seq=8/9)点到 CANCEL，
   游戏判定“用户取消加载” -> 退回菜单。
2. `savedlg_scan_slots` 用 `strncmp("SAVEDATA",8)` 把元数据目录 `SAVEDATA00.sce_sys` 也当成可选槽位。
   玩家一旦选它，游戏加载无效目录失败 -> 退回菜单（seq=4 实测选到了它）。

## v1.8 修复（只重编 libbbprobe.so）
- `save_open`: 仅 mode=1(LIST) 弹选择框；其它 mode(2/3/4/5) 直接 auto-finish(status=FINISHED, OK)，
  不弹选择器、保留 LIST 阶段选好的槽位，并清掉上一次 dialog 的残留 response。
- `savedlg_scan_slots`: 过滤 `*.sce_sys` 元数据目录，只保留真正的 SAVEDATA00/01... 槽位。

## 不变项（覆盖安装安全）
- 签名与 v1.1～v1.7 完全一致（serial c2d1dead5839fd99）。
- AOT 库（57134 units 修复版）、classes.dex（BB_SAVEDATA_DIR）、AHB 驱动、manifest、资源全部不变。
- 仅替换 `lib/arm64-v8a/libbbprobe.so`（sha256 230a0b8064e4526b...）。

直接覆盖安装，无需卸载/清数据。
