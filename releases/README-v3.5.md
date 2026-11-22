# DIVA-Launcher v3.5 — 提交锁解锁（解决 GPU 空等）

真机日志（lpqpaps.zip）+ 逐行对照 bb-recomp/shadPS4 源码，定位到"原版 60 帧的游戏只有 20 多帧"的**真正根因**，与算力、温度、设置均无关。

## 根因：guest 线程在 SubmitDone 里空等 GPU 37-40ms

日志铁证：

| 日志行 | 含义 |
|---|---|
| Frame stats: ~28 FPS, GPU thread idle 84% | GPU 闲着，算力严重过剩 |
| EOP QUEUE 1.0 waits 37-40 ms | guest 每帧在 EOP 队列等 37-40ms |
| sceGnmSubmitDone/frame 1.0 | 游戏每帧调一次 SubmitDone |
| EOP fences decoded == written, 0 pending | GPU 早就画完了 |
| Honest labels: 1.0 sent early (GPU idle) | 标签提前发，GPU 确实空闲 |

源码链：`BB_HONEST_LABELS=1` → `SubmitLockOnDecode()` 为真 → `sceGnmSubmitDone()` 走 `WaitPreviousFrame()` → 等 `frames_retired >= frames_submitted` → 而 `frames_retired` 要等 GPU 的 SignalAfterGpu 回调（AHB 呈现路径下要等一整个显示周期 ~33ms）。于是 GPU 空转、guest 空等，帧率被锁死在 20 多。

**这就是"模拟器稳 60、原生只有 20 多"的差距所在**——不是原生更慢，是原生的提交锁在 Android 呈现路径下产生了空等。

## v3.5 修复

- **提交锁模式** `BB_SUBMIT_LOCK=gpu`（新默认）：让 `SubmitLockOnDecode()` 返回 false，`sceGnmSubmitDone()` 走 `WaitGpuIdle()`（等 GPU idle 中断，不再空等 frames_retired）。保留了对 GPU 完成的等待，不会引入堆竞态。若过场动画崩溃/花屏，面板可回退 `decode`。
- **提交提前量** `BB_SUBMIT_AHEAD=8`（新默认）：上游实测 101→154 FPS；作用在 submission 阶段的前 K 次提交。native 有堆增长自动保护（检测到泄漏会降回 0）。
- 保留 v3.4 的 GC 预算自动计算与两阶段绘制管线修复。

## 关于"分辨率没生效"

日志显示 `Scene resolution: raster 960x540`（场景**确实**降到 540p），但 `Output resolution: 1920x1080`（输出仍全屏 1080p）。所以你看到的是 540p 场景升采样铺满 1080p 屏幕，观感变化不大。要真正看到/省更多 GPU，把「输出分辨率」也设成 1280×720 或 960×540。

## 覆盖安装

- 签名与 v1.1～v3.4 完全一致（debug key，SHA-256 `04908028...e5e18`）。
- native 库 / boot-linked / AOT / Turnip 驱动与 v3.4 逐字节一致，仅替换 classes.dex。
- 直接覆盖即可，无需卸载、无需清数据。

## 若仍卡或异常

发新的 `diva-boot.log`，重点看 `EOP QUEUE ... waits` 是否从 37ms 降到个位数。若崩溃，把提交锁模式回退 decode、提交提前量回退 2。
