# 存档界面黑屏修复 (SaveDataDialog auto-answer)

## 现象

`DIVA-Launcher-v1.0-AHBfix.apk` 已能正常进入游戏主界面(AHB 驱动修复成功),
但进入存档页面后黑屏。`bbpresent-diag.log` 显示每一帧都是 `DROP: sc=0x0`,
即游戏主循环卡住、没有新画面提交,所以屏幕停在空白。

## 根因(已由真机日志确认)

新日志 `diva-boot.log` 结尾:

```
Runtime: SaveDataDialog opened; completed immediately (no dialog UI yet)
STUB import: yEiJ-qqr6Cg#S#T (sceSaveDataDialogGetResult) arg0=0x81478b820
Runtime: SaveDataDialog opened; completed immediately (no dialog UI yet)
STUB import: en7gNVnh878#S#T (sceSaveDataDialogIsReadyToDisplay) arg0=0x3e80
```

游戏进入存档界面时调用 PS4 系统的 `libSceSaveDataDialog`:

| 函数 | 运行时状态 |
|---|---|
| `sceSaveDataDialogInitialize/Open/UpdateStatus/Terminate` | 有实现,但 Open 不画 UI、直接标记完成 |
| `sceSaveDataDialogGetResult` | **未实现 (STUB)** |
| `sceSaveDataDialogIsReadyToDisplay` | **未实现 (STUB)** |
| `sceCommonDialogIsUsed` | 未实现 (STUB) |

游戏打开 dialog 后调用 `GetResult` 和 `IsReadyToDisplay` 等待用户选槽位,
但这两个是未解析的 import,在 `BB_IMPORT_STUBS` 模式下返回 0(空结果)。
游戏拿不到合法的存档选择,卡死在等待循环 → present 无新帧 → 黑屏。

这与 shadPS4 的差别:shadPS4 实现了完整的 dialog 状态机
(`savedatadialog.cpp`)并用 ImGui 画出真实的存档选择 UI(`savedatadialog_ui.cpp`),
`GetResult` 会把 `mode/result/buttonId/dirName/param/userData` 全部填回游戏。
本移植是 native AOT + Android launcher,没有 ImGui 前端,所以走"自动应答"路线。

## 修复(路线 A:自动应答)

改动文件(两份保持一致):
- `/workspace/_build/bb-android/src/runtime_services.c` (实际编译进 APK)
- `/workspace/divaft_Android/aot/overlay/runtime_services.c` (工程层副本)

内容:
1. 补全 dialog 函数:`GetResult` / `IsReadyToDisplay` / `GetStatus` / `Close` /
   `ProgressBarSetValue` / `ProgressBarInc` / `CommonDialogIsUsed`。
2. `GetResult` 按 `OrbisSaveDataDialogResult` (72 字节) 布局填充合法结果:
   `result=OK`, `buttonId=OK`, `dirName="SAVEDATA00"`, `mode/userData` 回显 Open 的参数。
   - 存档槽名格式 `SAVEDATA%02u` 来自游戏 eboot 字符串。
   - 实际存档文件仍由已完整实现的 `runtime_savedata.c` (libSceSaveData) 处理。
3. 详细日志:`SaveDataDialog GetResult -> mode=%u disp=%u slot=SAVEDATA00 (auto)`,
   便于下一版日志确认 mode(1=LIST..5=PROGRESS_BAR)和 disp(1=SAVE 2=LOAD 3=DELETE)。

## 产物

- `DIVA-Launcher-v1.1-SaveDialog.apk` (33.5 MB)
  - 仅替换 `lib/arm64-v8a/libbbprobe.so`(重编 runtime_services.c);
  - `assets/driver/libvulkan_freedreno.so` 仍是 AHB 修复版(sha256 一致,未回退);
  - 签名证书与旧版一致 → 可直接覆盖安装,不丢存档/数据。
  - package `com.divaft.launcher`, versionCode 4。

## 验证方法

1. 覆盖安装 v1.1,启动进入游戏,打开存档页面。
2. 抓新的 `diva-boot.log`,确认不再出现:
   - `STUB import: ... (sceSaveDataDialogGetResult)`
   - `STUB import: ... (sceSaveDataDialogIsReadyToDisplay)`
   并出现新日志 `SaveDataDialog GetResult -> mode=... slot=SAVEDATA00 (auto)`。
3. 若存档页面仍黑屏或异常,看新日志里的 `mode` / `disp` 值以及有没有新的
   unsupported import,据此调整为"新建空槽"或更完整的槽位选择逻辑。

## 备注

- 本地无真机,无法在沙箱内验证运行;需真机日志闭环。
- 若此自动应答不能让存档流程正常,下一步(路线 B)是复刻 ImeDialog 的
  `BB_IME_FILE` 文件桥接,由 Android launcher 弹出原生存档选择 UI。
