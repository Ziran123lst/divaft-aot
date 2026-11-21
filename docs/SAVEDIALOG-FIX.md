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

---

## v1.1 真机验证结果(2026-11-21)

v1.1 的 dialog 补丁**成功**:新日志里出现
`SaveDataDialog GetResult -> mode=1 disp=1 slot=SAVEDATA00 (auto)`,
游戏拿着结果去 `save data 'SAVEDATA00' mounted at /savedata0 (created)`。
`bbpresent-diag.log` 从 v1.0 的满屏 `DROP: sc=0x0`(黑屏)变成
`frame 1 SHOW ... frame 1140 SHOW`,**画面正常渲染,黑屏解决**。

但游戏继续往前走后触发了新的底层阻塞:

```
STUB import: j8xKtiFj0SY#T#U (sceSaveDataGetEventResult) arg0=0x0
STOP: AOT: native code is missing an entry at +0xa97d5
```

- 前者:`sceSaveDataGetEventResult` 未实现(STUB 返回 0,不致命)。
- 后者:**AOT 严格模式 miss**——guest 地址 `+0xa97d5` 没有对应的 native 入口,停机。
  这是全 AOT(无解释器/JIT 回退)的固有问题,不是 dialog 修复引入的,
  只是修复后游戏第一次走到这个未编译的分支。

## v1.2 修复(两项)

1. **AOT 补入口**:用 `bb-recomp --extra-entries`(地址 `0xa97d5`)重编 libbbaot.so。
   - 新库 units `57133 → 57134`(正好补了这一个入口),image hash 匹配。
   - 这是 `--extra-entries` 的标准 miss-log 循环:若后续还有新 miss,重复此步。
2. **补 `sceSaveDataGetEventResult`**:返回 `ERR_NOT_FOUND`(0x809F0008,无挂起事件),
   消除存档流程里的这个 STUB。

产物 `DIVA-Launcher-v1.2-SaveAOT.apk`:同时替换了 libbbaot.so 与 libbbprobe.so,
AHB 驱动保持不变,签名一致可覆盖安装。

## 验证方法(v1.2)

覆盖安装后进入存档页,抓新 `diva-boot.log`:
- 不应再有 `native code is missing an entry at +0xa97d5`
- 不应再有 `STUB import: sceSaveDataGetEventResult`
- 存档菜单应能正常交互(选槽/新建/读取)

若出现**新的** `+0x.....` 地址 miss:说明还有未覆盖的间接跳转落点,
把该地址发我,用同样的 `--extra-entries` 再补一次即可(每次真机跑会暴露一个)。
若 miss 地址反复出现在同一处且补后无效,需检查 extra-entries 是否被正确解析。

---

## v1.2 回归与回退(2026-11-21)

v1.2 用 `bb-recomp --extra-entries 0xa97d5` **全量重编**整个 libbbaot.so,引入了回退:
- AOT 头从 v1.1 的 `57133 units, 0 unsupported` 变成 `57134 units, 10 unsupported`;
- 游戏很早停在 `patched/hooked function has no variant at +0xc725530`,连画面都没了。
结论:全量重编这条路线有害,已回退。v1.2.1 把 AOT 库换回 v1.1 完好版(57133 units),恢复画面。

## v1.3 — 真正的存档 GUI(参考 shadPS4)

按用户要求,放弃"自动应答",实现 shadPS4 式的**交互式存档选择界面**。
shadPS4 用 ImGui 画槽位选择器;本移植 native 层无图形前端,改为 **Android 原生界面 + 文件桥接**
(与现有虚拟手柄 BB_PAD_FILE 同一模式):

- native (`runtime_services.c`):`SaveDataDialogOpen` 扫描已有存档槽(`<user>/savedata/*/*/SAVEDATA*`),
  把 `mode/dispType/槽列表` 写到 `$BB_SAVEDLG_DIR/request`,状态置 RUNNING;
  `GetResult` 轮询 `response` 文件(超时 BB_SAVEDLG_TIMEOUT,默认 600s),按玩家选择填
  `OrbisSaveDataDialogResult`;桥接不可用/超时则回退自动应答,保证不卡死。
- Java (`SaveDialogBridge.java` + `DisplayActivity`):后台线程轮询 request,
  用原生 AlertDialog 显示"新建存档 + 已有槽位 + 取消",点选后写 response。
- MainActivity 启动时创建桥接目录、设 `BB_SAVEDLG_DIR`/`BB_SAVEDLG_TIMEOUT` 环境变量。

产物 `DIVA-Launcher-v1.3-SaveGUI.apk`:基于 v1.2.1 干净版,只替换 libbbprobe.so 与 classes.dex;
AHB 驱动与 v1.1 完好 AOT 库均保持不变,签名一致可覆盖安装。

## 仍存在的底层阻塞(需真机确认)

v1.1 日志显示:存档 dialog 自动应答后游戏正常渲染 1140 帧、SAVEDATA00 成功挂载,
但继续往下走会停在 `native code is missing an entry at +0xa97d5`(AOT 严格模式,
无解释器/JIT 回退)。这是全 AOT 的固有问题,**GUI 解决不了它**——玩家在 GUI 里选完存档、
游戏继续加载时仍可能撞到这个或后续的 miss。

严格 AOT 的标准解法是 `--extra-entries`,但 v1.2 证明全量重编本工程会引入回退。
后续方向:
1. 找到当初生成 57133-unit 完好库的**确切工具版本与参数**,复刻构建、只多补一个入口;
2. 或让真机能写出 `logs/aot-misses.log`(需先建 logs 目录/改用环境变量指定路径),
   一次收集完整 miss 列表再决定;
3. 若 miss 无法根除,GUI 版仍能让玩家真正看到并选择存档界面(体验优于黑屏)。
