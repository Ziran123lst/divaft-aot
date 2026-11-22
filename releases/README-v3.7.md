# DIVA-Launcher v3.7 — native 层强制 flip_rate=0（首次直击 30fps 锁）

前几版都在 dex 设置层试错。v3.7 第一次改 **native 核心**：直接把呈现层的 `flip_rate` 强制为 0，绕开全部失效的 AOT hook。这是目前最贴近根因、且刻意避开 v3.5 死锁的一版。

## 为什么这次改 native

真机日志铁证：`VideoOut: vblank 60 Hz`、GPU idle 85%、`Frame pacing median 34.84ms`。逐行追源码确认：

1. 游戏调 `sceVideoOutSetFlipRate(1)` → `flip_rate=1`
2. 呈现循环 `count % (flip_rate+1) == 0` → 60Hz 下每 33ms 才翻一帧 = **锁 30fps**
3. 更糟的是 `driver.cpp:1291`：`flip_rate!=0` 会**跳过 immediate-flip 快速路径**，让 guest 提交也被拖慢（这就是 GPU 闲 85% 的原因）

而 `flip_rate` 只有在 AOT hook 装成功时才会被归零（日志：`0 sites`、`pacer's mode not expected code` → hook 全失效）。dex 环境变量碰不到它——这就是前几版无效的根因。

## v3.7 改动

- **native `video_out.cpp`**：新增 `BB_FORCE_FLIP0`（默认开），在 `sceVideoOutSetFlipRate` 里无条件把 `flip_rate` 归零 = driver 原生默认值 = 每个 vblank 都翻。
  - **不碰 submit/wait 路径**（v3.5 死锁的根源），零死锁风险。
  - 不需要 AOT hook，绕开血源地址写死的死结。
- **判定性日志**：`SetFlipRate: game_rate=1 unlocked=0 force_flip0=1 -> flip_rate=0` 会打进 `diva-boot.log`，一眼确认改动是否生效。
- **回退开关**：面板「强制每帧呈现(解30fps锁★)」，关掉即恢复原行为（用于诊断对照）。
- VBlank 回到标准 60Hz（v3.6 的 120Hz 无效已确认），让变量最纯粹。

## ABI / 覆盖安装安全

- 新 `libbbgpu.so` 动态符号表与旧版**逐项一致（8254=8254，diff 0）**，ABI 完全兼容。
- 逐条目哈希验证：除 `classes.dex`、`libbbgpu.so`、签名文件外，**AOT / boot-linked / Turnip 驱动 / 其他库全部逐字节一致**。
- 签名与 v1.1~v3.6 完全一致（debug key，SHA-256 `04908028...e5e18`）。直接覆盖即可。

## 请重点回传

跑一下，把新的 `diva-boot.log` 发来，我要看三行：
1. `SetFlipRate: ... -> flip_rate=0`（确认改动生效）
2. `VideoOut: vblank 60 Hz, frame limit 60 FPS`
3. `Frame stats:` 的 FPS + `Frame pacing:` 的中位数

**如果 FPS 明显上升（往 50-60 走）** → 呈现锁就是瓶颈，问题解决。
**如果 FPS 仍在 20-30** → 证明游戏逻辑本身锁 30（固定 step），呈现层再快也没用，那才真正需要逆向 AOT hook。这一步能一锤定音，避免继续瞎猜。

**如果出现花屏/崩溃** → 面板关掉「强制每帧呈现」即可回退，并告诉我。
