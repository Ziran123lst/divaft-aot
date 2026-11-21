# DIVA-Launcher v1.6 — Save GUI 4（存档状态机死锁修复）

## 现象
v1.5 中存档 GUI 能弹出，点“新建存档”后 GUI 消失，但游戏停在存档页黑屏、不返回主界面；重进启动器后看不到刚创建的存档。

## 根因（native 状态机死锁，对照 shadPS4 源码确认）
shadPS4 `savedatadialog.cpp` 的真实语义：`Open -> RUNNING`；UI 选完后由 `Finish()` 把状态置 `FINISHED`；`GetResult()` 只拷贝结果、从不阻塞。游戏轮询 `GetStatus()/UpdateStatus()`，只有 `status==FINISHED` 时才调 `GetResult()`。

我们之前的 native 实现只在 `GetResult()` 内部把 status 从 RUNNING(2) 置成 FINISHED(3)。于是形成死锁：
- 游戏：`GetStatus()==2` -> 不调 `GetResult()`，继续轮询；
- native：等 `GetResult()` 被调用才处理点击写入的 `response` 并置 3。

结果：点击写入的 response 永远没人消费，游戏永远停在 RUNNING 黑屏；由于 `GetResult` 从未返回，游戏也从未调用 `sceSaveDataMount` 真正创建存档目录，所以重进后看不到存档。

## v1.6 修复（只改 libbbprobe.so 的 native 状态机）
- 新增 `savedlg_pump()`：由 `GetStatus/UpdateStatus/IsReadyToDisplay/GetResult` 共同调用，一旦读到匹配 SEQ 的 response 就缓存结果并把 status 置 FINISHED（对齐 shadPS4 的 UI Finish）。
- `GetResult()` 改为非阻塞，只返回已缓存结果；未就绪返回 NOT_FINISHED。
- `Close/Open/Terminate` 正确重置 ready/cancel 状态。
- 保留 v1.5 的 in-window overlay GUI + 同步 dismiss + SEQ 令牌机制（Java 层 classes.dex 不变）。

## 不变项（覆盖安装安全）
- 签名与 v1.1～v1.5 完全一致。
- AOT 库 sha256 `2a608885...`（完好 57133-unit 版）不变。
- AHB 驱动 sha256 `0fb864d8...` 不变。
- classes.dex 与 v1.5 完全一致（35b28dfcf138...）。
- 仅替换 `lib/arm64-v8a/libbbprobe.so`。

直接覆盖安装即可，无需卸载/清数据。
