# DIVA-Launcher v1.7 — Save GUI 5（存档槽位显示 + AOT gap 修复）

本版修复 v1.6 之后剩下的两个独立问题。

## 1. 存档槽位不显示（Java 层，零风险）
真机日志里 `SaveDataDialog request ... slots=[]` 一直是空的。根因：native 的槽位扫描
`savedlg_scan_slots()` 扫 `$HOME/user/savedata`（= filesDir），而存档实际写在
`<dataDir>/user/savedata`（外部 Download/diva-data，运行时工作目录）。两者是不同目录，
所以永远扫不到已创建的存档，GUI 只能显示“新建存档”。
两处代码都读 `BB_SAVEDATA_DIR`，本版在启动器里把它固定指向真实存档根
`<dataDir>/user/savedata` —— 扫描与挂载统一，旧存档不丢。

## 2. 存档后黑屏停机（AOT gap）
存档创建成功后日志记录：
`STOP: AOT: native code is missing an entry at +0xa97d5`。
这是 AOT 库的一个确定性 gap（guest 内部跳转目标没有单元入口），严格模式直接停机。
本版用当初编出完好库的**定制 bb-recomp**（含 3 条指令 lowering patch）基于完好增量缓存
补 `0xa97d5` 为单元入口重编：从 57133 → 57134 units，仍为 **0 unsupported**，未复现 v1.2
的全量重编回归。miss-log 格式用工具原生的 `miss +0xa97d5 kind=...`。

## 不变项（覆盖安装安全）
- 签名与 v1.1～v1.6 完全一致（serial c2d1dead5839fd99）。
- classes.dex 基于 v1.6，仅新增 `BB_SAVEDATA_DIR` 一行。
- libbbprobe.so（v1.6 状态机修复）、AHB 驱动、manifest、资源全部不变。
- 仅替换 `assets/payload/libbbaot.so` 与 `classes.dex`。

直接覆盖安装，无需卸载/清数据。
