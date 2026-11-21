# DIVA-Launcher v1.9 — Save GUI 7（userData 回写修复 + ERROR_CODE 诊断）

v1.8 已修好 dialog 交互（只弹一次、过滤 .sce_sys、mode5/4 自动完成），
日志确认这些全部生效。但游戏在 dialog 后没有 mount、直接退回菜单。

## 根因候选：userData 未回写
shadPS4 `SaveDialogResult::CopyTo` 会把游戏 Open 时传入的 `userData` 原样回写给 GetResult。
游戏用它识别“这是哪个 dialog 请求”。我们之前在 `save_get_result` 里 `memset` 把 userData
清零了 —— 游戏拿到结果后发现 userData 不匹配，判定请求无效 -> 放弃加载、退回菜单。

## v1.9 修复（只重编 libbbprobe.so）
- `save_open`: 保存 param->userData 到全局。
- `save_get_result`: 把 userData 回写到 OrbisSaveDataDialogResult（对齐 shadPS4 CopyTo）。

## 诊断增强
- mode=4 (ERROR_CODE) 打开时打印 errorCode。PS4 语义：
  `0x809F0008`=NOT_FOUND（无存档）、`0x809F000F`=BROKEN（数据损坏）。
  若本版仍退回菜单，日志里的 `ERROR_CODE errorCode=0x...` 能直接告诉我们原因：
  - 若是 0x809F000F/0x809F0008：说明现有 SAVEDATA00 是空壳/损坏存档（很可能是早期 AOT
    停机那次只创建了目录、没写完数据）。请在存档界面选“新建存档”重建，或删除旧存档后再试。
  - 若是其它值/无 ERROR_CODE 行：说明是另一个环节，请带完整 diva-boot.log。

## 不变项（覆盖安装安全）
- 签名与 v1.1～v1.8 完全一致（serial c2d1dead5839fd99）。
- AOT 库（57134 units）、classes.dex、AHB 驱动、manifest、资源全部不变。
- 仅替换 `lib/arm64-v8a/libbbprobe.so`（sha256 b1b5ecf706f36166...）。

直接覆盖安装，无需卸载/清数据。
